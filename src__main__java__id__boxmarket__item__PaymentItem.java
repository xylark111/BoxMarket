package id.boxmarket.item;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/**
 * Item harga barter.
 * customId tidak kosong: dicocokkan lewat PDC (material + custom-id).
 * customId kosong: dicocokkan lewat prototype (isSimilar: nama, lore, model data, dst.).
 */
public record PaymentItem(String id, Material material, String customId, String displayName, ItemStack prototype) {
}
