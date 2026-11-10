package id.boxmarket.manager;

import id.boxmarket.BoxMarketPlugin;
import id.boxmarket.item.ItemMatcher;
import id.boxmarket.item.PaymentItem;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Level;

/**
 * Daftar item harga barter yang ditambah admin lewat game (catalog.yml).
 * Item disimpan utuh (bytes NBT) sehingga item ItemEdit/ItemTag/lore custom dikenali persis.
 */
public class CatalogManager {

    public static final int MAX_ENTRIES = 45;

    private final BoxMarketPlugin plugin;
    private final File file;
    private final Map<String, PaymentItem> entries = new LinkedHashMap<>();
    // Item bawaan config.yml yang disembunyikan admin lewat game.
    private final Set<String> disabled = new LinkedHashSet<>();

    public CatalogManager(BoxMarketPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "catalog.yml");
    }

    public Map<String, PaymentItem> entries() {
        return entries;
    }

    public boolean contains(String id) {
        return entries.containsKey(id);
    }

    public boolean isDisabled(String id) {
        return disabled.contains(id);
    }

    public void reload() {
        entries.clear();
        disabled.clear();
        if (!file.exists()) return;

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);

        for (String id : yaml.getStringList("disabled-config")) {
            disabled.add(id.toLowerCase(Locale.ROOT));
        }

        ConfigurationSection section = yaml.getConfigurationSection("items");
        if (section == null) return;

        for (String id : section.getKeys(false)) {
            ItemStack prototype = null;

            String data = section.getString(id + ".data");
            if (data != null) {
                try {
                    prototype = ItemStack.deserializeBytes(Base64.getDecoder().decode(data));
                } catch (RuntimeException exception) {
                    plugin.getLogger().log(Level.WARNING, "Catalog entry '" + id + "' gagal dibaca", exception);
                }
            }

            if (prototype == null) {
                prototype = section.getItemStack(id + ".item"); // format lama
            }

            if (prototype == null || prototype.getType().isAir()) {
                plugin.getLogger().warning("Catalog entry '" + id + "' rusak, dilewati.");
                continue;
            }

            entries.put(id, build(id, prototype));
        }
    }

    private PaymentItem build(String id, ItemStack source) {
        ItemStack prototype = source.clone();
        prototype.setAmount(1);

        String customId = ItemMatcher.getCustomId(prototype, plugin.getCustomItemKey());
        return new PaymentItem(id, prototype.getType(), customId, nameOf(prototype), prototype);
    }

    public void add(CommandSender sender, ItemStack item) {
        if (item == null || item.getType().isAir()) {
            plugin.send(sender, "catalog-no-item");
            return;
        }

        ItemStack probe = item.clone();
        probe.setAmount(1);

        for (PaymentItem existing : plugin.getPaymentItems().values()) {
            if (ItemMatcher.matches(probe, existing, plugin.getCustomItemKey())) {
                plugin.send(sender, "catalog-exists", "%name%", plain(existing.displayName()));
                return;
            }
        }

        if (plugin.getPaymentItems().size() >= MAX_ENTRIES) {
            plugin.send(sender, "catalog-full");
            return;
        }

        String id = uniqueId(probe);
        PaymentItem payment = build(id, probe);

        entries.put(id, payment);
        plugin.getPaymentItems().put(id, payment);
        save();

        plugin.send(sender, "catalog-added", "%name%", plain(payment.displayName()), "%id%", id);
    }

    /** activeUses = jumlah listing aktif yang memakai item ini sebagai harga. */
    public void remove(CommandSender sender, String id, int activeUses) {
        PaymentItem payment = plugin.getPaymentItems().get(id);

        if (payment == null) {
            plugin.send(sender, "catalog-not-found");
            return;
        }

        if (activeUses > 0) {
            plugin.send(sender, "catalog-in-use", "%count%", String.valueOf(activeUses));
            return;
        }

        // Item bawaan config.yml disembunyikan lewat catalog.yml supaya tidak muncul lagi saat restart.
        if (entries.remove(id) == null) {
            disabled.add(id);
        }
        plugin.getPaymentItems().remove(id);
        save();

        plugin.send(sender, "catalog-removed", "%name%", plain(payment.displayName()));
    }

    /** Cek item di tangan: cocok dengan catalog atau tidak, dan kalau tidak, apa bedanya. */
    public void check(CommandSender sender, ItemStack held) {
        if (held == null || held.getType().isAir()) {
            plugin.send(sender, "catalog-no-item");
            return;
        }

        ItemStack probe = held.clone();
        probe.setAmount(1);

        for (PaymentItem payment : plugin.getPaymentItems().values()) {
            if (ItemMatcher.matches(probe, payment, plugin.getCustomItemKey())) {
                plugin.send(sender, "catalog-check-match",
                        "%name%", plain(payment.displayName()), "%id%", payment.id());
                return;
            }
        }

        boolean found = false;
        for (PaymentItem payment : plugin.getPaymentItems().values()) {
            if (payment.material() != probe.getType()) continue;

            found = true;
            plugin.send(sender, "catalog-check-diff",
                    "%id%", payment.id(), "%diff%", String.join(", ", differences(payment.prototype(), probe)));
        }

        if (!found) {
            plugin.send(sender, "catalog-check-none");
        }
    }

    private List<String> differences(ItemStack expected, ItemStack actual) {
        List<String> diffs = new ArrayList<>();

        ItemMeta a = expected.getItemMeta();
        ItemMeta b = actual.getItemMeta();
        if (a == null || b == null) {
            diffs.add("meta item");
            return diffs;
        }

        if (!Objects.equals(a.displayName(), b.displayName())) diffs.add("nama");
        if (!Objects.equals(a.lore(), b.lore())) diffs.add("lore");
        if (!a.getItemFlags().equals(b.getItemFlags())) diffs.add("item flag (hide)");
        if (!a.getEnchants().equals(b.getEnchants())) diffs.add("enchant");
        if (a.hasCustomModelData() != b.hasCustomModelData()
                || (a.hasCustomModelData() && a.getCustomModelData() != b.getCustomModelData())) {
            diffs.add("custom model data");
        }
        if (a.isUnbreakable() != b.isUnbreakable()) diffs.add("unbreakable");
        if (!a.getPersistentDataContainer().getKeys().equals(b.getPersistentDataContainer().getKeys())) {
            diffs.add("tag/PDC (key beda)");
        }
        if (a instanceof Damageable da && b instanceof Damageable db && da.getDamage() != db.getDamage()) {
            diffs.add("durability");
        }

        if (diffs.isEmpty()) {
            diffs.add("data NBT lain (mis. nilai tag atau atribut)");
        }

        return diffs;
    }

    private void save() {
        YamlConfiguration yaml = new YamlConfiguration();

        for (PaymentItem payment : entries.values()) {
            String base = "items." + payment.id();
            yaml.set(base + ".name", payment.displayName());
            yaml.set(base + ".material", payment.material().name());
            yaml.set(base + ".data", Base64.getEncoder().encodeToString(payment.prototype().serializeAsBytes()));
        }

        yaml.set("disabled-config", new ArrayList<>(disabled));

        try {
            if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
                plugin.getLogger().severe("Tidak bisa membuat folder plugin untuk catalog.yml");
                return;
            }
            yaml.save(file);
        } catch (IOException exception) {
            plugin.getLogger().log(Level.SEVERE, "Gagal menyimpan catalog.yml", exception);
        }
    }

    private String uniqueId(ItemStack item) {
        String customId = ItemMatcher.getCustomId(item, plugin.getCustomItemKey());
        String base = customId.isEmpty() ? plain(nameOf(item)) : customId;

        base = base.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (base.isEmpty()) {
            base = item.getType().name().toLowerCase(Locale.ROOT);
        }
        if (base.length() > 32) {
            base = base.substring(0, 32);
        }

        String id = base;
        int counter = 2;
        while (plugin.getPaymentItems().containsKey(id) || disabled.contains(id)) {
            id = base + "_" + counter++;
        }
        return id;
    }

    private String nameOf(ItemStack item) {
        ItemMeta meta = item.getItemMeta();

        if (meta != null && meta.hasDisplayName()) {
            Component name = meta.displayName();
            if (name != null) {
                return LegacyComponentSerializer.legacyAmpersand().serialize(name);
            }
        }

        StringBuilder pretty = new StringBuilder("&f");
        for (String word : item.getType().name().toLowerCase(Locale.ROOT).split("_")) {
            if (word.isEmpty()) continue;
            if (pretty.length() > 2) pretty.append(' ');
            pretty.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return pretty.toString();
    }

    private String plain(String text) {
        return text.replaceAll("&[0-9a-fk-orA-FK-OR]", "");
    }
}
