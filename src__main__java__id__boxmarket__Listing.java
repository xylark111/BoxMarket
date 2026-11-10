package id.boxmarket;

import id.boxmarket.item.ItemSerializer;
import id.boxmarket.item.PaymentItem;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.UUID;

public class Listing {

    public enum Status {
        ACTIVE,
        SOLD,
        CANCELLED
    }

    private final String id;
    private final UUID seller;
    private final String sellerName;
    private final List<ItemStack> items;
    private final PaymentItem price;
    private final int priceAmount;
    private final long createdAt;
    private volatile Status status;

    public Listing(
            String id,
            UUID seller,
            String sellerName,
            List<ItemStack> items,
            PaymentItem price,
            int priceAmount,
            Status status,
            long createdAt
    ) {
        this.id = id;
        this.seller = seller;
        this.sellerName = sellerName;
        this.items = items;
        this.price = price;
        this.priceAmount = priceAmount;
        this.status = status;
        this.createdAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public UUID getSeller() {
        return seller;
    }

    public String getSellerName() {
        return sellerName;
    }

    public List<ItemStack> getItems() {
        return items;
    }

    public PaymentItem getPrice() {
        return price;
    }

    public int getPriceAmount() {
        return priceAmount;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public String serializedItems() {
        return ItemSerializer.serialize(items);
    }

    public static Listing deserialize(
            String id,
            UUID seller,
            String sellerName,
            String serializedItems,
            PaymentItem price,
            int priceAmount,
            Status status,
            long createdAt
    ) {
        return new Listing(id, seller, sellerName, ItemSerializer.deserialize(serializedItems),
                price, priceAmount, status, createdAt);
    }
}
