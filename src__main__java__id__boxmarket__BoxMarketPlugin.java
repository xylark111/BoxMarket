package id.boxmarket;

import id.boxmarket.command.BoxMarketCommand;
import id.boxmarket.db.Database;
import id.boxmarket.gui.GuiManager;
import id.boxmarket.item.PaymentItem;
import id.boxmarket.manager.CatalogManager;
import id.boxmarket.manager.ListingManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;

public final class BoxMarketPlugin extends JavaPlugin {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private Database database;
    private ListingManager listingManager;
    private GuiManager guiManager;
    private CatalogManager catalogManager;
    private NamespacedKey customItemKey;
    private final Map<String, PaymentItem> paymentItems = new LinkedHashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.customItemKey = readCustomKey();
        this.catalogManager = new CatalogManager(this);
        loadPaymentItems();

        this.database = new Database(this);
        try {
            this.database.init();
        } catch (RuntimeException exception) {
            getLogger().log(Level.SEVERE, "Database BoxMarket gagal dibuka, plugin dimatikan.", exception);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.listingManager = new ListingManager(this, database);
        this.listingManager.loadAll();

        this.guiManager = new GuiManager(this, listingManager);
        getServer().getPluginManager().registerEvents(guiManager, this);
        guiManager.startRefreshTask();

        BoxMarketCommand command = new BoxMarketCommand(this, listingManager, guiManager);
        Objects.requireNonNull(getCommand("boxmarket")).setExecutor(command);
        Objects.requireNonNull(getCommand("boxmarket")).setTabCompleter(command);

        getLogger().info("BoxMarket aktif. Listing aktif: " + listingManager.getAllListingIds().size());
    }

    @Override
    public void onDisable() {
        if (guiManager != null) {
            guiManager.shutdown();
        }
        if (database != null) {
            database.close();
        }
    }

    private NamespacedKey readCustomKey() {
        String raw = getConfig().getString("custom-item-key", "boxpvp:custom_id");
        NamespacedKey key = NamespacedKey.fromString(raw == null ? "" : raw.toLowerCase(Locale.ROOT));
        if (key == null) {
            getLogger().warning("custom-item-key tidak valid ('" + raw + "'), memakai boxpvp:custom_id");
            key = NamespacedKey.fromString("boxpvp:custom_id");
        }
        return key;
    }

    private void loadPaymentItems() {
        paymentItems.clear();
        catalogManager.reload();

        ConfigurationSection section = getConfig().getConfigurationSection("payment-items");
        if (section != null) {
            for (String id : section.getKeys(false)) {
                String key = id.toLowerCase(Locale.ROOT);
                if (catalogManager.isDisabled(key)) continue;

                String materialName = section.getString(id + ".material", "EMERALD");
                Material material = Material.matchMaterial(materialName);

                if (material == null || material.isAir()) {
                    getLogger().warning("Material tidak valid untuk payment item " + id + ": " + materialName);
                    continue;
                }

                String customId = section.getString(id + ".custom-id", "");
                if (customId == null) customId = "";
                String name = section.getString(id + ".name", id);

                ItemStack prototype = new ItemStack(material);
                if (!customId.isEmpty()) {
                    ItemMeta meta = prototype.getItemMeta();
                    if (meta != null) {
                        meta.displayName(color(name));
                        meta.getPersistentDataContainer().set(customItemKey, PersistentDataType.STRING, customId);
                        prototype.setItemMeta(meta);
                    }
                }

                paymentItems.put(key, new PaymentItem(key, material, customId, name, prototype));
            }
        }

        // Item yang ditambah admin lewat /bm catalog.
        for (PaymentItem payment : catalogManager.entries().values()) {
            paymentItems.putIfAbsent(payment.id(), payment);
        }
    }

    public void reloadPlugin() {
        reloadConfig();
        this.customItemKey = readCustomKey();
        loadPaymentItems();
    }

    public CatalogManager getCatalogManager() {
        return catalogManager;
    }

    public boolean canManageCatalog(CommandSender sender) {
        return sender.hasPermission("boxmarket.catalog") || sender.hasPermission("boxmarket.admin");
    }

    public NamespacedKey getCustomItemKey() {
        return customItemKey;
    }

    public Map<String, PaymentItem> getPaymentItems() {
        return paymentItems;
    }

    public String message(String key) {
        return getConfig().getString("messages." + key, key);
    }

    public static Component color(String text) {
        return LEGACY.deserialize(text).decoration(TextDecoration.ITALIC, false);
    }

    /** Kirim pesan dari config. replacements berpasangan: placeholder, nilai. */
    public void send(CommandSender to, String key, String... replacements) {
        String text = message("prefix") + message(key);
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            text = text.replace(replacements[i], replacements[i + 1]);
        }
        to.sendMessage(color(text));
    }

    /** Beri item ke player; yang tidak muat di-drop agar tidak hilang. */
    public void giveOrDrop(Player player, ItemStack item) {
        Map<Integer, ItemStack> left = player.getInventory().addItem(item);
        if (left.isEmpty()) return;

        for (ItemStack rest : left.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), rest);
        }
        send(player, "inventory-full");
    }
}
