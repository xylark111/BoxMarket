package id.boxmarket.manager;

import id.boxmarket.BoxMarketPlugin;
import id.boxmarket.Earning;
import id.boxmarket.Listing;
import id.boxmarket.db.Database;
import id.boxmarket.item.ItemMatcher;
import id.boxmarket.item.PaymentItem;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class ListingManager {

    public enum BuyResult {
        SUCCESS,
        NOT_FOUND,
        OWN_LISTING,
        NOT_ENOUGH,
        INVENTORY_FULL
    }

    public enum ClaimResult {
        NOTHING,
        ALL,
        PARTIAL
    }

    private final BoxMarketPlugin plugin;
    private final Database database;
    private final Map<String, Listing> listings = new ConcurrentHashMap<>();
    private final Map<UUID, List<Earning>> earnings = new ConcurrentHashMap<>();
    private final Object tradeLock = new Object();

    public ListingManager(BoxMarketPlugin plugin, Database database) {
        this.plugin = plugin;
        this.database = database;
    }

    public void loadAll() {
        listings.clear();
        earnings.clear();

        for (Listing listing : database.loadActive(plugin.getPaymentItems())) {
            listings.put(listing.getId(), listing);
        }

        for (Earning earning : database.loadEarnings()) {
            addEarning(earning);
        }
    }

    private void addEarning(Earning earning) {
        earnings.computeIfAbsent(earning.owner(), key -> new CopyOnWriteArrayList<>()).add(earning);
    }

    public int getListingLimit(Player player) {
        int limit = plugin.getConfig().getInt("default-listing-limit", 1);

        for (int value = 1; value <= 100; value++) {
            if (player.hasPermission("boxmarket.sell.limit." + value)) {
                limit = Math.max(limit, value);
            }
        }

        return limit;
    }

    public boolean canCreateListing(Player player) {
        return countActiveListings(player.getUniqueId()) < getListingLimit(player);
    }

    public int countActiveListings(UUID seller) {
        int count = 0;

        for (Listing listing : listings.values()) {
            if (listing.getStatus() == Listing.Status.ACTIVE && listing.getSeller().equals(seller)) {
                count++;
            }
        }

        return count;
    }

    public boolean isBlacklisted(ItemStack item) {
        if (item == null) return true;

        return plugin.getConfig().getStringList("market.blacklisted-materials")
                .stream()
                .anyMatch(name -> name.equalsIgnoreCase(item.getType().name()));
    }

    /** Sisa waktu listing dalam ms; Long.MAX_VALUE jika tanpa batas; <= 0 jika sudah kedaluwarsa. */
    public long remainingMillis(Listing listing) {
        long hours = plugin.getConfig().getLong("market.listing-expire-hours", 72);
        if (hours <= 0) return Long.MAX_VALUE;

        return listing.getCreatedAt() + hours * 3_600_000L - System.currentTimeMillis();
    }

    public Listing getListing(String id) {
        Listing listing = listings.get(id);
        return listing != null && listing.getStatus() == Listing.Status.ACTIVE ? listing : null;
    }

    public boolean isExpired(Listing listing) {
        long hours = plugin.getConfig().getLong("market.listing-expire-hours", 72);
        if (hours <= 0) return false;

        return System.currentTimeMillis() - listing.getCreatedAt() > hours * 3_600_000L;
    }

    public Listing createListing(Player seller, List<ItemStack> items, PaymentItem payment, int priceAmount) {
        String id = UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        Listing listing = new Listing(
                id,
                seller.getUniqueId(),
                seller.getName(),
                ItemMatcher.cloneItems(items),
                payment,
                priceAmount,
                Listing.Status.ACTIVE,
                System.currentTimeMillis()
        );

        listings.put(id, listing);
        database.insertListing(listing);

        return listing;
    }

    public BuyResult buy(Player buyer, String listingId) {
        synchronized (tradeLock) {
            Listing listing = listings.get(listingId);

            if (listing == null || listing.getStatus() != Listing.Status.ACTIVE || isExpired(listing)) {
                return BuyResult.NOT_FOUND;
            }

            if (listing.getSeller().equals(buyer.getUniqueId())) {
                return BuyResult.OWN_LISTING;
            }

            PaymentItem payment = listing.getPrice();
            int required = listing.getPriceAmount();
            PlayerInventory inventory = buyer.getInventory();

            if (ItemMatcher.count(buyer, payment, plugin.getCustomItemKey()) < required) {
                return BuyResult.NOT_ENOUGH;
            }

            // Snapshot untuk rollback penuh jika inventory tidak muat.
            ItemStack[] snapshot = ItemMatcher.cloneArray(inventory.getStorageContents());

            List<ItemStack> paid = ItemMatcher.take(buyer, payment, plugin.getCustomItemKey(), required);
            if (paid == null) {
                return BuyResult.NOT_ENOUGH;
            }

            for (ItemStack item : listing.getItems()) {
                if (!inventory.addItem(item.clone()).isEmpty()) {
                    inventory.setStorageContents(snapshot);
                    return BuyResult.INVENTORY_FULL;
                }
            }

            listing.setStatus(Listing.Status.SOLD);
            listings.remove(listingId);

            // Item pembayaran disimpan untuk seller; status + earning ditulis dalam satu transaksi.
            Earning earning = new Earning(listingId, listing.getSeller(), paid);
            addEarning(earning);
            database.finishListing(listingId, Listing.Status.SOLD, earning);

            Player seller = Bukkit.getPlayer(listing.getSeller());
            if (seller != null) {
                plugin.send(seller, "listing-bought", "%buyer%", buyer.getName());
            }

            return BuyResult.SUCCESS;
        }
    }

    public boolean reclaim(Player seller, String listingId) {
        synchronized (tradeLock) {
            Listing listing = listings.get(listingId);

            if (listing == null || listing.getStatus() != Listing.Status.ACTIVE) {
                return false;
            }

            if (!listing.getSeller().equals(seller.getUniqueId())) {
                return false;
            }

            listing.setStatus(Listing.Status.CANCELLED);
            listings.remove(listingId);
            database.updateStatus(listingId, Listing.Status.CANCELLED);

            for (ItemStack item : listing.getItems()) {
                plugin.giveOrDrop(seller, item.clone());
            }

            return true;
        }
    }

    /** Batalkan listing oleh admin. Seller offline atau inventory penuh: item masuk kotak Claim. */
    public boolean adminCancel(String listingId) {
        synchronized (tradeLock) {
            Listing listing = listings.get(listingId);

            if (listing == null || listing.getStatus() != Listing.Status.ACTIVE) {
                return false;
            }

            listing.setStatus(Listing.Status.CANCELLED);
            listings.remove(listingId);

            Player seller = Bukkit.getPlayer(listing.getSeller());
            List<ItemStack> toClaim = new ArrayList<>();

            if (seller == null) {
                toClaim.addAll(ItemMatcher.cloneItems(listing.getItems()));
            } else {
                for (ItemStack item : listing.getItems()) {
                    toClaim.addAll(seller.getInventory().addItem(item.clone()).values());
                }
            }

            Earning earning = null;
            if (!toClaim.isEmpty()) {
                earning = new Earning(listingId, listing.getSeller(), toClaim);
                addEarning(earning);
            }

            database.finishListing(listingId, Listing.Status.CANCELLED, earning);

            if (seller != null) {
                plugin.send(seller, "listing-cancelled");
                if (earning != null) {
                    plugin.send(seller, "earnings-waiting");
                }
            }

            return true;
        }
    }

    public int countEarningStacks(UUID owner) {
        List<Earning> list = earnings.get(owner);
        if (list == null) return 0;

        int total = 0;
        for (Earning earning : list) {
            total += earning.items().size();
        }
        return total;
    }

    public ClaimResult claimEarnings(Player player) {
        synchronized (tradeLock) {
            List<Earning> list = earnings.get(player.getUniqueId());
            if (list == null || list.isEmpty()) {
                return ClaimResult.NOTHING;
            }

            for (Earning earning : new ArrayList<>(list)) {
                List<ItemStack> leftover = new ArrayList<>();

                for (ItemStack item : earning.items()) {
                    leftover.addAll(player.getInventory().addItem(item.clone()).values());
                }

                list.remove(earning);

                if (leftover.isEmpty()) {
                    database.deleteEarning(earning.id());
                    continue;
                }

                Earning remaining = new Earning(earning.id(), earning.owner(), leftover);
                list.add(remaining);
                database.saveEarning(remaining);
                return ClaimResult.PARTIAL;
            }

            return ClaimResult.ALL;
        }
    }

    public int countListingsUsing(String paymentId) {
        int count = 0;

        for (Listing listing : listings.values()) {
            if (listing.getStatus() == Listing.Status.ACTIVE && listing.getPrice().id().equals(paymentId)) {
                count++;
            }
        }

        return count;
    }

    public List<String> getAllListingIds() {
        return new ArrayList<>(listings.keySet());
    }

    /** Listing yang tampil di marketplace: aktif dan belum kedaluwarsa. */
    public List<Listing> getMarketListings() {
        List<Listing> active = new ArrayList<>();

        for (Listing listing : listings.values()) {
            if (listing.getStatus() == Listing.Status.ACTIVE && !isExpired(listing)) {
                active.add(listing);
            }
        }

        active.sort(Comparator.comparingLong(Listing::getCreatedAt).reversed());
        return active;
    }

    /** Semua listing aktif milik seller, termasuk yang sudah kedaluwarsa. */
    public List<Listing> getListingsOf(UUID seller) {
        List<Listing> result = new ArrayList<>();

        for (Listing listing : listings.values()) {
            if (listing.getStatus() == Listing.Status.ACTIVE && listing.getSeller().equals(seller)) {
                result.add(listing);
            }
        }

        result.sort(Comparator.comparingLong(Listing::getCreatedAt).reversed());
        return result;
    }
}
