package id.boxmarket.db;

import id.boxmarket.BoxMarketPlugin;
import id.boxmarket.Earning;
import id.boxmarket.Listing;
import id.boxmarket.item.ItemSerializer;
import id.boxmarket.item.PaymentItem;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public class Database {

    @FunctionalInterface
    private interface SqlTask {
        void run(Connection connection) throws SQLException;
    }

    private final BoxMarketPlugin plugin;
    private final Object lock = new Object();
    // Satu thread penulis: urutan tulis terjamin (insert selalu sebelum update status).
    private final ExecutorService writer =
            Executors.newSingleThreadExecutor(runnable -> new Thread(runnable, "BoxMarket-DB"));
    private Connection connection;

    public Database(BoxMarketPlugin plugin) {
        this.plugin = plugin;
    }

    private Connection openConnection() throws SQLException {
        String type = plugin.getConfig().getString("storage.type", "sqlite");

        try {
            if ("mysql".equalsIgnoreCase(type)) {
                Class.forName("com.mysql.cj.jdbc.Driver");

                String host = plugin.getConfig().getString("storage.mysql.host", "localhost");
                int port = plugin.getConfig().getInt("storage.mysql.port", 3306);
                String database = plugin.getConfig().getString("storage.mysql.database", "boxmarket");
                String user = plugin.getConfig().getString("storage.mysql.username", "root");
                String password = plugin.getConfig().getString("storage.mysql.password", "");
                boolean ssl = plugin.getConfig().getBoolean("storage.mysql.use-ssl", false);

                String url = "jdbc:mysql://" + host + ":" + port + "/" + database
                        + "?useSSL=" + ssl + "&allowPublicKeyRetrieval=true&characterEncoding=utf8";

                return DriverManager.getConnection(url, user, password);
            }

            Class.forName("org.sqlite.JDBC");
            File folder = plugin.getDataFolder();
            if (!folder.exists() && !folder.mkdirs()) {
                throw new SQLException("Tidak bisa membuat folder plugin");
            }
            return DriverManager.getConnection("jdbc:sqlite:" + new File(folder, "market.db").getAbsolutePath());
        } catch (ClassNotFoundException exception) {
            throw new SQLException("Driver JDBC tidak ditemukan", exception);
        }
    }

    // Harus dipanggil di dalam synchronized (lock).
    private Connection connection() throws SQLException {
        if (connection == null || connection.isClosed() || !connection.isValid(2)) {
            connection = openConnection();
        }
        return connection;
    }

    public void init() {
        synchronized (lock) {
            try (Statement statement = connection().createStatement()) {
                statement.executeUpdate(
                        "CREATE TABLE IF NOT EXISTS boxmarket_listings ("
                                + "id VARCHAR(16) PRIMARY KEY, "
                                + "seller VARCHAR(36) NOT NULL, "
                                + "seller_name VARCHAR(64) NOT NULL, "
                                + "items LONGTEXT NOT NULL, "
                                + "price_id VARCHAR(64) NOT NULL, "
                                + "price_amount INT NOT NULL, "
                                + "status VARCHAR(16) NOT NULL, "
                                + "created_at BIGINT NOT NULL)"
                );
                statement.executeUpdate(
                        "CREATE TABLE IF NOT EXISTS boxmarket_earnings ("
                                + "id VARCHAR(16) PRIMARY KEY, "
                                + "owner_uuid VARCHAR(36) NOT NULL, "
                                + "items LONGTEXT NOT NULL, "
                                + "created_at BIGINT NOT NULL)"
                );
            } catch (SQLException exception) {
                throw new IllegalStateException("Gagal inisialisasi database BoxMarket", exception);
            }
        }
    }

    /** Dipanggil saat startup, sebelum ada interaksi player. */
    public List<Listing> loadActive(Map<String, PaymentItem> paymentItems) {
        List<Listing> listings = new ArrayList<>();

        synchronized (lock) {
            String sql = "SELECT id, seller, seller_name, items, price_id, price_amount, created_at "
                    + "FROM boxmarket_listings WHERE status = 'ACTIVE'";

            try (
                    PreparedStatement statement = connection().prepareStatement(sql);
                    ResultSet result = statement.executeQuery()
            ) {
                while (result.next()) {
                    String id = result.getString("id");

                    try {
                        PaymentItem payment = paymentItems.get(result.getString("price_id").toLowerCase(Locale.ROOT));
                        if (payment == null) {
                            plugin.getLogger().warning("Listing " + id + " dilewati: payment item "
                                    + result.getString("price_id") + " tidak ada di config.");
                            continue;
                        }

                        listings.add(Listing.deserialize(
                                id,
                                UUID.fromString(result.getString("seller")),
                                result.getString("seller_name"),
                                result.getString("items"),
                                payment,
                                result.getInt("price_amount"),
                                Listing.Status.ACTIVE,
                                result.getLong("created_at")
                        ));
                    } catch (RuntimeException exception) {
                        plugin.getLogger().log(Level.SEVERE, "Listing " + id + " rusak, dilewati", exception);
                    }
                }
            } catch (SQLException exception) {
                plugin.getLogger().log(Level.SEVERE, "Gagal memuat listing", exception);
            }
        }

        return listings;
    }

    public List<Earning> loadEarnings() {
        List<Earning> earnings = new ArrayList<>();

        synchronized (lock) {
            String sql = "SELECT id, owner_uuid, items FROM boxmarket_earnings";

            try (
                    PreparedStatement statement = connection().prepareStatement(sql);
                    ResultSet result = statement.executeQuery()
            ) {
                while (result.next()) {
                    String id = result.getString("id");

                    try {
                        earnings.add(new Earning(
                                id,
                                UUID.fromString(result.getString("owner_uuid")),
                                ItemSerializer.deserialize(result.getString("items"))
                        ));
                    } catch (RuntimeException exception) {
                        plugin.getLogger().log(Level.SEVERE, "Earning " + id + " rusak, dilewati", exception);
                    }
                }
            } catch (SQLException exception) {
                plugin.getLogger().log(Level.SEVERE, "Gagal memuat earnings", exception);
            }
        }

        return earnings;
    }

    private void execute(String description, SqlTask task) {
        try {
            writer.execute(() -> {
                synchronized (lock) {
                    try {
                        task.run(connection());
                    } catch (SQLException exception) {
                        plugin.getLogger().log(Level.SEVERE, "Gagal " + description, exception);
                    }
                }
            });
        } catch (RejectedExecutionException exception) {
            plugin.getLogger().severe("Gagal " + description + ": database sudah ditutup");
        }
    }

    public void insertListing(Listing listing) {
        final String items = listing.serializedItems();
        final String id = listing.getId();
        final String seller = listing.getSeller().toString();
        final String sellerName = listing.getSellerName();
        final String priceId = listing.getPrice().id();
        final int priceAmount = listing.getPriceAmount();
        final String status = listing.getStatus().name();
        final long createdAt = listing.getCreatedAt();

        execute("menyimpan listing " + id, connection -> {
            String sql = "REPLACE INTO boxmarket_listings "
                    + "(id, seller, seller_name, items, price_id, price_amount, status, created_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, id);
                statement.setString(2, seller);
                statement.setString(3, sellerName);
                statement.setString(4, items);
                statement.setString(5, priceId);
                statement.setInt(6, priceAmount);
                statement.setString(7, status);
                statement.setLong(8, createdAt);
                statement.executeUpdate();
            }
        });
    }

    public void updateStatus(String id, Listing.Status status) {
        finishListing(id, status, null);
    }

    /** Ubah status listing dan (opsional) simpan earning dalam satu transaksi. */
    public void finishListing(String id, Listing.Status status, Earning earning) {
        final String serialized = earning == null ? null : ItemSerializer.serialize(earning.items());
        final String owner = earning == null ? null : earning.owner().toString();
        final String statusName = status.name();

        execute("menyelesaikan listing " + id, connection -> {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);

            try {
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE boxmarket_listings SET status = ? WHERE id = ?")) {
                    statement.setString(1, statusName);
                    statement.setString(2, id);
                    statement.executeUpdate();
                }

                if (serialized != null) {
                    replaceEarning(connection, id, owner, serialized);
                }

                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        });
    }

    public void saveEarning(Earning earning) {
        final String serialized = ItemSerializer.serialize(earning.items());
        final String owner = earning.owner().toString();
        final String id = earning.id();

        execute("menyimpan earning " + id, connection -> replaceEarning(connection, id, owner, serialized));
    }

    public void deleteEarning(String id) {
        execute("menghapus earning " + id, connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM boxmarket_earnings WHERE id = ?")) {
                statement.setString(1, id);
                statement.executeUpdate();
            }
        });
    }

    private void replaceEarning(Connection connection, String id, String owner, String items) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "REPLACE INTO boxmarket_earnings (id, owner_uuid, items, created_at) VALUES (?, ?, ?, ?)")) {
            statement.setString(1, id);
            statement.setString(2, owner);
            statement.setString(3, items);
            statement.setLong(4, System.currentTimeMillis());
            statement.executeUpdate();
        }
    }

    /** Tunggu semua tulisan yang antre selesai, lalu tutup koneksi. */
    public void close() {
        writer.shutdown();

        try {
            if (!writer.awaitTermination(20, TimeUnit.SECONDS)) {
                plugin.getLogger().severe("Sebagian penulisan database belum selesai saat shutdown!");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }

        synchronized (lock) {
            try {
                if (connection != null && !connection.isClosed()) {
                    connection.close();
                }
            } catch (SQLException exception) {
                plugin.getLogger().log(Level.SEVERE, "Gagal menutup database", exception);
            }
        }
    }
}
