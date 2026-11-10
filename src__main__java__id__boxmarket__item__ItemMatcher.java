package id.boxmarket.item;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

public final class ItemMatcher {

    private ItemMatcher() {
    }

    /**
     * Item cocok jika material sama DAN custom-id (PDC) sama persis.
     * Payment dengan custom-id kosong hanya cocok dengan item yang tidak punya PDC custom.
     */
    public static boolean matches(ItemStack item, PaymentItem payment, NamespacedKey customKey) {
        if (item == null || item.getType() != payment.material()) return false;

        String expected = payment.customId() == null ? "" : payment.customId();
        String actual = getCustomId(item, customKey);

        if (!expected.isEmpty()) {
            return expected.equals(actual);
        }

        // Item dengan PDC custom tidak boleh dianggap item polos/non-PDC.
        if (!actual.isEmpty()) return false;

        return item.isSimilar(payment.prototype());
    }

    public static int count(Player player, PaymentItem payment, NamespacedKey customKey) {
        int total = 0;

        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (matches(item, payment, customKey)) {
                total += item.getAmount();
            }
        }

        return total;
    }

    /**
     * Ambil item pembayaran dari inventory. Mengembalikan item yang diambil (salinan utuh,
     * lengkap dengan meta), atau null jika jumlahnya tidak cukup (inventory tidak diubah).
     */
    public static List<ItemStack> take(Player player, PaymentItem payment, NamespacedKey customKey, int amount) {
        ItemStack[] contents = cloneArray(player.getInventory().getStorageContents());
        List<ItemStack> taken = new ArrayList<>();
        int remaining = amount;

        for (int slot = 0; slot < contents.length && remaining > 0; slot++) {
            ItemStack item = contents[slot];
            if (!matches(item, payment, customKey)) continue;

            int take = Math.min(item.getAmount(), remaining);
            ItemStack part = item.clone();
            part.setAmount(take);
            taken.add(part);
            remaining -= take;

            if (take >= item.getAmount()) {
                contents[slot] = null;
            } else {
                item.setAmount(item.getAmount() - take);
            }
        }

        if (remaining > 0) return null;

        player.getInventory().setStorageContents(contents);
        return taken;
    }

    public static String getCustomId(ItemStack item, NamespacedKey customKey) {
        if (item == null || !item.hasItemMeta()) return "";

        ItemMeta meta = item.getItemMeta();
        if (meta == null) return "";

        String value = meta.getPersistentDataContainer().get(customKey, PersistentDataType.STRING);
        return value == null ? "" : value;
    }

    public static List<ItemStack> cloneItems(List<ItemStack> items) {
        List<ItemStack> clone = new ArrayList<>();
        for (ItemStack item : items) {
            clone.add(item.clone());
        }
        return clone;
    }

    public static ItemStack[] cloneArray(ItemStack[] items) {
        ItemStack[] clone = new ItemStack[items.length];
        for (int i = 0; i < items.length; i++) {
            clone[i] = items[i] == null ? null : items[i].clone();
        }
        return clone;
    }
}
