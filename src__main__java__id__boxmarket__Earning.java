package id.boxmarket;

import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.UUID;

/** Item yang menunggu diambil pemiliknya: hasil penjualan atau pengembalian listing oleh admin. */
public record Earning(String id, UUID owner, List<ItemStack> items) {
}
