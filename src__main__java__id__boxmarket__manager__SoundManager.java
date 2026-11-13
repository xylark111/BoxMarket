package id.boxmarket.manager;

import id.boxmarket.BoxMarketPlugin;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Suara GUI. Tiap suara punya nama (cue) dan bisa diubah di config.yml bagian "sounds".
 * Suara hasil (purchase, sell, claim, error, dst.) otomatis mengikuti pesan yang dikirim ke player.
 */
public class SoundManager {

    private record Cue(String key, float volume, float pitch) {
    }

    private static final Map<String, String> DEFAULTS = new LinkedHashMap<>();
    private static final Map<String, String> MESSAGE_CUES = new HashMap<>();

    static {
        DEFAULTS.put("click", "ui.button.click, 0.6, 1.3");
        DEFAULTS.put("page", "item.book.page_turn, 0.8, 1.0");
        DEFAULTS.put("open", "block.ender_chest.open, 0.4, 1.5");
        DEFAULTS.put("select", "entity.experience_orb.pickup, 0.5, 1.6");
        DEFAULTS.put("amount-up", "block.note_block.hat, 0.7, 1.6");
        DEFAULTS.put("amount-down", "block.note_block.hat, 0.7, 0.9");
        DEFAULTS.put("confirm", "block.amethyst_block.chime, 0.9, 1.2");
        DEFAULTS.put("purchase", "entity.player.levelup, 0.7, 1.3");
        DEFAULTS.put("sell", "entity.villager.yes, 0.8, 1.1");
        DEFAULTS.put("sold", "entity.experience_orb.pickup, 0.8, 1.0");
        DEFAULTS.put("claim", "entity.item.pickup, 0.8, 1.0");
        DEFAULTS.put("error", "entity.villager.no, 0.7, 1.0");
        DEFAULTS.put("catalog-add", "block.note_block.pling, 0.8, 1.6");
        DEFAULTS.put("catalog-remove", "block.note_block.bass, 0.8, 0.8");
        DEFAULTS.put("notify", "block.note_block.chime, 0.8, 1.4");

        MESSAGE_CUES.put("purchase-success", "purchase");
        MESSAGE_CUES.put("listing-created", "sell");
        MESSAGE_CUES.put("listing-cancelled", "claim");
        MESSAGE_CUES.put("earnings-claimed", "claim");
        MESSAGE_CUES.put("earnings-partial", "claim");
        MESSAGE_CUES.put("listing-bought", "sold");
        MESSAGE_CUES.put("earnings-waiting", "notify");
        MESSAGE_CUES.put("catalog-added", "catalog-add");
        MESSAGE_CUES.put("catalog-removed", "catalog-remove");
        MESSAGE_CUES.put("reloaded", "select");

        for (String key : new String[]{
                "no-permission", "only-player", "not-enough-payment", "limit-reached", "inventory-full",
                "inventory-full-buy", "own-listing", "listing-not-found", "blacklisted-item", "invalid-item",
                "choose-price", "earnings-empty", "catalog-exists", "catalog-in-use", "catalog-config",
                "catalog-full", "catalog-no-item", "catalog-not-found", "catalog-check-none",
                "catalog-check-diff", "usage-catalog", "usage-cancel"}) {
            MESSAGE_CUES.put(key, "error");
        }
    }

    private final BoxMarketPlugin plugin;
    private final Map<String, Cue> cues = new HashMap<>();
    private boolean enabled = true;

    public SoundManager(BoxMarketPlugin plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        cues.clear();
        enabled = plugin.getConfig().getBoolean("sounds.enabled", true);

        for (Map.Entry<String, String> entry : DEFAULTS.entrySet()) {
            String raw = plugin.getConfig().getString("sounds." + entry.getKey(), entry.getValue());
            Cue cue = parse(entry.getKey(), raw);
            if (cue != null) {
                cues.put(entry.getKey(), cue);
            }
        }
    }

    private Cue parse(String name, String raw) {
        if (raw == null || raw.isBlank()) return null;

        String[] parts = raw.split(",");
        String key = parts[0].trim().toLowerCase(Locale.ROOT);
        if (key.isEmpty() || key.equals("none")) return null;

        float volume = 1f;
        float pitch = 1f;

        try {
            if (parts.length > 1) volume = Float.parseFloat(parts[1].trim());
            if (parts.length > 2) pitch = Float.parseFloat(parts[2].trim());
        } catch (NumberFormatException exception) {
            plugin.getLogger().warning("Format sound '" + name + "' tidak valid: " + raw);
            return null;
        }

        return new Cue(key, Math.max(0f, volume), Math.max(0.5f, Math.min(2f, pitch)));
    }

    public void play(Player player, String name) {
        if (!enabled) return;

        Cue cue = cues.get(name);
        if (cue == null) return;

        player.playSound(player.getLocation(), cue.key(), cue.volume(), cue.pitch());
    }

    public void playForMessage(Player player, String messageKey) {
        String cue = MESSAGE_CUES.get(messageKey);
        if (cue != null) {
            play(player, cue);
        }
    }
}
