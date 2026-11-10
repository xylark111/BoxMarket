package id.boxmarket.item;

import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

public final class ItemSerializer {

    private ItemSerializer() {
    }

    public static String serialize(List<ItemStack> items) {
        try (
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                BukkitObjectOutputStream output = new BukkitObjectOutputStream(bytes)
        ) {
            output.writeObject(new ArrayList<>(items));
            output.flush();
            return Base64.getEncoder().encodeToString(bytes.toByteArray());
        } catch (IOException exception) {
            throw new IllegalStateException("Gagal serialize item", exception);
        }
    }

    @SuppressWarnings("unchecked")
    public static List<ItemStack> deserialize(String base64) {
        try (
                ByteArrayInputStream bytes = new ByteArrayInputStream(Base64.getDecoder().decode(base64));
                BukkitObjectInputStream input = new BukkitObjectInputStream(bytes)
        ) {
            return (List<ItemStack>) input.readObject();
        } catch (IOException | ClassNotFoundException | IllegalArgumentException exception) {
            throw new IllegalStateException("Gagal deserialize item", exception);
        }
    }
}
