package id.boxmarket.gui;

import id.boxmarket.BoxMarketPlugin;
import id.boxmarket.Listing;
import id.boxmarket.item.PaymentItem;
import id.boxmarket.manager.CatalogManager;
import id.boxmarket.manager.ListingManager;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class GuiManager implements Listener {

    public enum MenuType {
        MARKET,
        SELL,
        PRICE,
        AMOUNT,
        MY_LISTINGS,
        CATALOG
    }

    public static final class MenuHolder implements InventoryHolder {
        private final MenuType type;
        private final int page;
        // ID listing per slot: klik selalu merujuk ke listing yang tampil, bukan indeks yang bisa bergeser.
        private final List<String> ids = new ArrayList<>();
        private Inventory inventory;

        public MenuHolder(MenuType type, int page) {
            this.type = type;
            this.page = page;
        }

        public MenuType type() {
            return type;
        }

        public int page() {
            return page;
        }

        public List<String> ids() {
            return ids;
        }

        private String selected;

        public String selected() {
            return selected;
        }

        void setSelected(String selected) {
            this.selected = selected;
        }

        void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    public static final class SellSession {
        public final List<ItemStack> items = new ArrayList<>();
        public PaymentItem price;
        public int amount = 1;
    }

    private static final int PAGE_SIZE = 45;

    private final BoxMarketPlugin plugin;
    private final ListingManager listingManager;
    private final Map<UUID, SellSession> sessions = new HashMap<>();
    // Player yang sedang berpindah menu lewat plugin: InventoryCloseEvent dari perpindahan itu diabaikan.
    private final Set<UUID> switching = new HashSet<>();

    public GuiManager(BoxMarketPlugin plugin, ListingManager listingManager) {
        this.plugin = plugin;
        this.listingManager = listingManager;
    }

    // ---------------------------------------------------------------- menu builders

    public void openMarket(Player player, int requestedPage) {
        List<Listing> listings = listingManager.getMarketListings();

        int pages = Math.max(1, (int) Math.ceil(listings.size() / (double) PAGE_SIZE));
        int page = Math.max(0, Math.min(requestedPage, pages - 1));

        MenuHolder holder = new MenuHolder(MenuType.MARKET, page);
        Inventory inventory = Bukkit.createInventory(holder, 54,
                color("&8BoxMarket &7- Page " + (page + 1) + "/" + pages));
        holder.setInventory(inventory);

        int start = page * PAGE_SIZE;
        for (int slot = 0; slot < PAGE_SIZE; slot++) {
            int index = start + slot;
            if (index >= listings.size()) break;

            Listing listing = listings.get(index);
            inventory.setItem(slot, createListingIcon(listing, false));
            holder.ids().add(listing.getId());
        }

        if (page > 0) {
            inventory.setItem(45, button(Material.ARROW, "&7Previous Page"));
        }
        inventory.setItem(47, button(Material.BOOK, "&eMy Listings"));
        inventory.setItem(49, button(Material.CHEST, "&eSell Item"));
        if (page < pages - 1) {
            inventory.setItem(53, button(Material.ARROW, "&7Next Page"));
        }

        open(player, inventory);
    }

    public void openSellMenu(Player player) {
        UUID uuid = player.getUniqueId();

        if (!player.hasPermission("boxmarket.sell")) {
            releaseSession(player);
            plugin.send(player, "no-permission");
            return;
        }

        SellSession previous = sessions.get(uuid);

        if (previous == null && !listingManager.canCreateListing(player)) {
            plugin.send(player, "limit-reached");
            return;
        }

        sessions.remove(uuid);

        MenuHolder holder = new MenuHolder(MenuType.SELL, 0);
        Inventory inventory = Bukkit.createInventory(holder, 54, color("&8Pilih Item yang Ingin Dijual"));
        holder.setInventory(inventory);

        for (int slot = 0; slot < 54; slot++) {
            if (!isSellSlot(slot)) {
                inventory.setItem(slot, button(Material.GRAY_STAINED_GLASS_PANE, " "));
            }
        }

        inventory.setItem(45, button(Material.BARRIER, "&cCancel"));
        inventory.setItem(49, button(Material.ARROW, "&7Back"));
        inventory.setItem(53, button(Material.LIME_DYE, "&aSave"));

        // Kembali dari menu harga: item yang sudah di-stage ditaruh lagi di slot jual.
        if (previous != null) {
            int slot = 0;
            for (ItemStack item : previous.items) {
                while (slot < 54 && !isSellSlot(slot)) slot++;
                if (slot >= 54) {
                    plugin.giveOrDrop(player, item);
                    continue;
                }
                inventory.setItem(slot++, item);
            }
            previous.items.clear();
        }

        open(player, inventory);
    }

    public void openPriceMenu(Player player) {
        SellSession session = sessions.get(player.getUniqueId());

        if (session == null || session.items.isEmpty()) {
            openMarket(player, 0);
            return;
        }

        MenuHolder holder = new MenuHolder(MenuType.PRICE, 0);
        Inventory inventory = Bukkit.createInventory(holder, 54, color("&8Pilih Item Pembayaran"));
        holder.setInventory(inventory);

        int slot = 0;
        for (PaymentItem payment : plugin.getPaymentItems().values()) {
            if (slot >= PAGE_SIZE) break;

            inventory.setItem(slot++, paymentIcon(payment, null,
                    "&8Klik untuk memilih item ini", "&8sebagai harga jual."));
            holder.ids().add(payment.id());
        }

        inventory.setItem(45, button(Material.BARRIER, "&cCancel"));
        inventory.setItem(49, button(Material.ARROW, "&7Back"));
        inventory.setItem(53, button(Material.LIME_DYE, "&aSave"));

        open(player, inventory);
    }

    public void openAmountMenu(Player player) {
        SellSession session = sessions.get(player.getUniqueId());

        if (session == null || session.items.isEmpty()) {
            openMarket(player, 0);
            return;
        }

        if (session.price == null) {
            openPriceMenu(player);
            return;
        }

        MenuHolder holder = new MenuHolder(MenuType.AMOUNT, 0);
        Inventory inventory = Bukkit.createInventory(holder, 54, color("&8Set Jumlah Item Pembayaran"));
        holder.setInventory(inventory);

        inventory.setItem(10, button(Material.RED_STAINED_GLASS_PANE, "&c-64"));
        inventory.setItem(11, button(Material.RED_STAINED_GLASS_PANE, "&c-10"));
        inventory.setItem(12, button(Material.RED_STAINED_GLASS_PANE, "&c-1"));
        inventory.setItem(13, createAmountIcon(session));
        inventory.setItem(14, button(Material.LIME_STAINED_GLASS_PANE, "&a+1"));
        inventory.setItem(15, button(Material.LIME_STAINED_GLASS_PANE, "&a+10"));
        inventory.setItem(16, button(Material.LIME_STAINED_GLASS_PANE, "&a+64"));

        inventory.setItem(45, button(Material.BARRIER, "&cCancel"));
        inventory.setItem(49, button(Material.ARROW, "&7Back"));
        inventory.setItem(53, button(Material.LIME_DYE, "&aSave"));

        open(player, inventory);
    }

    public void openMyListings(Player player) {
        List<Listing> listings = listingManager.getListingsOf(player.getUniqueId());

        MenuHolder holder = new MenuHolder(MenuType.MY_LISTINGS, 0);
        Inventory inventory = Bukkit.createInventory(holder, 54, color("&8My Listings"));
        holder.setInventory(inventory);

        for (int slot = 0; slot < PAGE_SIZE && slot < listings.size(); slot++) {
            Listing listing = listings.get(slot);
            inventory.setItem(slot, createListingIcon(listing, true));
            holder.ids().add(listing.getId());
        }

        inventory.setItem(49, button(Material.ARROW, "&7Back"));
        inventory.setItem(53, button(Material.ENDER_CHEST, "&aClaim Item",
                "&7Hasil penjualan / pengembalian.",
                "&7Stack menunggu: &f" + listingManager.countEarningStacks(player.getUniqueId())));

        open(player, inventory);
    }

    private void open(Player player, Inventory inventory) {
        UUID uuid = player.getUniqueId();
        switching.add(uuid);

        try {
            player.openInventory(inventory);
        } finally {
            switching.remove(uuid);
        }
    }

    // ---------------------------------------------------------------- events

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof MenuHolder menu)) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;

        int raw = event.getRawSlot();
        boolean inTop = raw >= 0 && raw < top.getSize();

        if (menu.type() == MenuType.SELL) {
            handleSellClick(player, event, top, raw, inTop);
            return;
        }

        // Semua menu lain: tidak ada item yang boleh dipindah.
        event.setCancelled(true);

        if (!inTop) {
            // Di menu catalog, klik item di inventory sendiri = tambah ke catalog.
            if (menu.type() == MenuType.CATALOG) {
                handleCatalogAdd(player, event.getCurrentItem());
            }
            return;
        }

        switch (menu.type()) {
            case MARKET -> handleMarketClick(player, menu, raw);
            case PRICE -> handlePriceClick(player, menu, raw);
            case CATALOG -> handleCatalogClick(player, menu, raw);
            case AMOUNT -> handleAmountClick(player, top, raw);
            case MY_LISTINGS -> handleMyListingsClick(player, menu, raw);
            default -> {
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof MenuHolder menu)) return;

        if (menu.type() != MenuType.SELL) {
            event.setCancelled(true);
            return;
        }

        for (int raw : event.getRawSlots()) {
            if (raw < top.getSize() && !isSellSlot(raw)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        if (switching.contains(player.getUniqueId())) return;

        Inventory top = event.getInventory();
        if (!(top.getHolder() instanceof MenuHolder menu)) return;

        switch (menu.type()) {
            case SELL -> returnSellSlotItems(player, top);
            case PRICE, AMOUNT -> releaseSession(player);
            default -> {
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        cleanup(event.getPlayer());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline() && listingManager.countEarningStacks(player.getUniqueId()) > 0) {
                plugin.send(player, "earnings-waiting");
            }
        }, 40L);
    }

    /** Dipanggil saat plugin dimatikan: kembalikan semua item yang belum menjadi listing. */
    public void shutdown() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            cleanup(player);
        }
        sessions.clear();
    }

    private void cleanup(Player player) {
        Inventory top = player.getOpenInventory().getTopInventory();

        if (top.getHolder() instanceof MenuHolder menu && menu.type() == MenuType.SELL) {
            returnSellSlotItems(player, top);
        }

        releaseSession(player);
    }

    // ---------------------------------------------------------------- click handlers

    private void handleSellClick(Player player, InventoryClickEvent event, Inventory top, int raw, boolean inTop) {
        if (event.getAction() == InventoryAction.COLLECT_TO_CURSOR) {
            event.setCancelled(true);
            return;
        }

        // Klik di inventory player sendiri: normal (shift-click hanya bisa masuk ke slot jual kosong,
        // karena slot lain terisi panel).
        if (!inTop) return;

        if (isSellSlot(raw)) {
            if (event.getClick() == ClickType.NUMBER_KEY || event.getClick() == ClickType.SWAP_OFFHAND) {
                event.setCancelled(true);
            }
            return;
        }

        event.setCancelled(true);

        switch (raw) {
            case 45, 49 -> {
                returnSellSlotItems(player, top);
                openMarket(player, 0);
            }
            case 53 -> handleSave(player, top);
            default -> {
            }
        }
    }

    private void handleSave(Player player, Inventory top) {
        if (!player.hasPermission("boxmarket.sell")) {
            plugin.send(player, "no-permission");
            return;
        }

        if (!listingManager.canCreateListing(player)) {
            plugin.send(player, "limit-reached");
            return;
        }

        // Validasi dulu seluruh isi GUI (dibaca ulang dari inventory), baru pindahkan.
        List<Integer> filledSlots = new ArrayList<>();

        for (int slot = 0; slot < top.getSize(); slot++) {
            if (!isSellSlot(slot)) continue;

            ItemStack item = top.getItem(slot);
            if (item == null || item.getType().isAir()) continue;

            if (listingManager.isBlacklisted(item)) {
                plugin.send(player, "blacklisted-item");
                return;
            }

            filledSlots.add(slot);
        }

        if (filledSlots.isEmpty()) {
            plugin.send(player, "invalid-item");
            return;
        }

        SellSession session = new SellSession();

        for (int slot : filledSlots) {
            session.items.add(top.getItem(slot).clone());
            top.setItem(slot, null);
        }

        sessions.put(player.getUniqueId(), session);
        openPriceMenu(player);
    }

    private void handleMarketClick(Player player, MenuHolder menu, int slot) {
        if (slot == 45) {
            if (menu.page() > 0) openMarket(player, menu.page() - 1);
            return;
        }

        if (slot == 47) {
            openMyListings(player);
            return;
        }

        if (slot == 49) {
            openSellMenu(player);
            return;
        }

        if (slot == 53) {
            openMarket(player, menu.page() + 1);
            return;
        }

        if (slot >= PAGE_SIZE || slot >= menu.ids().size()) return;

        ListingManager.BuyResult result = listingManager.buy(player, menu.ids().get(slot));

        switch (result) {
            case SUCCESS -> plugin.send(player, "purchase-success");
            case NOT_ENOUGH -> plugin.send(player, "not-enough-payment");
            case INVENTORY_FULL -> plugin.send(player, "inventory-full-buy");
            case OWN_LISTING -> plugin.send(player, "own-listing");
            case NOT_FOUND -> plugin.send(player, "listing-not-found");
        }

        openMarket(player, menu.page());
    }

    private void handlePriceClick(Player player, MenuHolder menu, int slot) {
        SellSession session = sessions.get(player.getUniqueId());
        if (session == null) {
            openMarket(player, 0);
            return;
        }

        if (slot == 45) {
            releaseSession(player);
            openMarket(player, 0);
            return;
        }

        if (slot == 49) {
            openSellMenu(player);
            return;
        }

        if (slot == 53) {
            if (session.price == null) {
                plugin.send(player, "choose-price");
            } else {
                openAmountMenu(player);
            }
            return;
        }

        if (slot >= PAGE_SIZE || slot >= menu.ids().size()) return;

        PaymentItem payment = plugin.getPaymentItems().get(menu.ids().get(slot));
        if (payment == null) {
            plugin.send(player, "choose-price");
            openPriceMenu(player);
            return;
        }

        session.price = payment;
        session.amount = 1;

        openAmountMenu(player);
    }

    private void handleAmountClick(Player player, Inventory top, int slot) {
        SellSession session = sessions.get(player.getUniqueId());
        if (session == null) {
            openMarket(player, 0);
            return;
        }

        if (slot == 45) {
            releaseSession(player);
            openMarket(player, 0);
            return;
        }

        if (slot == 49) {
            openPriceMenu(player);
            return;
        }

        if (slot == 53) {
            finishListing(player, session);
            return;
        }

        int max = Math.max(1, plugin.getConfig().getInt("market.max-price-amount", 1000000));

        int delta = switch (slot) {
            case 10 -> -64;
            case 11 -> -10;
            case 12 -> -1;
            case 14 -> 1;
            case 15 -> 10;
            case 16 -> 64;
            default -> 0;
        };

        if (delta != 0) {
            session.amount = Math.max(1, Math.min(max, session.amount + delta));
            top.setItem(13, createAmountIcon(session));
        }
    }

    private void handleMyListingsClick(Player player, MenuHolder menu, int slot) {
        if (slot == 49) {
            openMarket(player, 0);
            return;
        }

        if (slot == 53) {
            switch (listingManager.claimEarnings(player)) {
                case ALL -> plugin.send(player, "earnings-claimed");
                case PARTIAL -> plugin.send(player, "earnings-partial");
                case NOTHING -> plugin.send(player, "earnings-empty");
            }
            openMyListings(player);
            return;
        }

        if (slot >= PAGE_SIZE || slot >= menu.ids().size()) return;

        if (listingManager.reclaim(player, menu.ids().get(slot))) {
            plugin.send(player, "listing-cancelled");
        } else {
            plugin.send(player, "listing-not-found");
        }

        openMyListings(player);
    }

    private void finishListing(Player player, SellSession session) {
        if (session.price == null) {
            plugin.send(player, "choose-price");
            return;
        }

        if (session.items.isEmpty()) {
            openMarket(player, 0);
            return;
        }

        // Item harga bisa saja dihapus dari catalog selagi player memilih.
        if (plugin.getPaymentItems().get(session.price.id()) == null) {
            session.price = null;
            plugin.send(player, "choose-price");
            openPriceMenu(player);
            return;
        }

        if (!player.hasPermission("boxmarket.sell") || !listingManager.canCreateListing(player)) {
            plugin.send(player, player.hasPermission("boxmarket.sell") ? "limit-reached" : "no-permission");
            releaseSession(player);
            openMarket(player, 0);
            return;
        }

        Listing listing = listingManager.createListing(player, session.items, session.price, session.amount);

        // Item sudah dicatat di listing/escrow; sesi dihapus tanpa mengembalikan apa pun.
        sessions.remove(player.getUniqueId());

        plugin.send(player, "listing-created",
                "%item%", listing.getItems().get(0).getType().name(),
                "%amount%", String.valueOf(totalItems(listing.getItems())),
                "%price%", plainName(listing.getPrice().displayName()),
                "%price_amount%", String.valueOf(listing.getPriceAmount()));

        openMarket(player, 0);
    }

    // ---------------------------------------------------------------- catalog (admin)

    public void openCatalog(Player player, String selected) {
        if (!plugin.canManageCatalog(player)) {
            plugin.send(player, "no-permission");
            return;
        }

        MenuHolder holder = new MenuHolder(MenuType.CATALOG, 0);
        holder.setSelected(selected);

        Inventory inventory = Bukkit.createInventory(holder, 54, color("&8Catalog Harga Barter"));
        holder.setInventory(inventory);

        CatalogManager catalog = plugin.getCatalogManager();

        int slot = 0;
        for (PaymentItem payment : plugin.getPaymentItems().values()) {
            if (slot >= PAGE_SIZE) break;

            boolean chosen = payment.id().equals(selected);

            inventory.setItem(slot++, paymentIcon(payment, null,
                    "",
                    "&7ID: &f" + payment.id(),
                    "&7Sumber: &f" + (catalog.contains(payment.id()) ? "catalog" : "config.yml"),
                    chosen ? "&a>> TERPILIH <<" : "&eKlik untuk memilih"));
            holder.ids().add(payment.id());
        }

        inventory.setItem(45, button(Material.BOOK, "&eCara pakai",
                "&7Klik item di INVENTORY kamu (bagian bawah)",
                "&7untuk menambahkannya ke catalog.",
                "&7Klik item di atas untuk memilih,",
                "&7lalu tombol merah untuk menghapus."));

        if (selected != null) {
            inventory.setItem(47, button(Material.RED_CONCRETE, "&cHapus item terpilih", "&7ID: &f" + selected));
        }

        inventory.setItem(49, button(Material.BARRIER, "&cTutup"));

        open(player, inventory);
    }

    private void handleCatalogAdd(Player player, ItemStack clicked) {
        if (!plugin.canManageCatalog(player)) {
            plugin.send(player, "no-permission");
            player.closeInventory();
            return;
        }

        if (clicked == null || clicked.getType().isAir()) return;

        plugin.getCatalogManager().add(player, clicked);
        openCatalog(player, null);
    }

    private void handleCatalogClick(Player player, MenuHolder menu, int slot) {
        if (!plugin.canManageCatalog(player)) {
            plugin.send(player, "no-permission");
            player.closeInventory();
            return;
        }

        if (slot == 49) {
            player.closeInventory();
            return;
        }

        if (slot == 47) {
            String selected = menu.selected();
            if (selected != null) {
                plugin.getCatalogManager().remove(player, selected, listingManager.countListingsUsing(selected));
            }
            openCatalog(player, null);
            return;
        }

        if (slot >= PAGE_SIZE || slot >= menu.ids().size()) return;

        String id = menu.ids().get(slot);
        openCatalog(player, id.equals(menu.selected()) ? null : id);
    }

    /** Ikon item harga: memakai item aslinya (nama, lore, model) agar item custom tampil benar. */
    private ItemStack paymentIcon(PaymentItem payment, String nameOverride, String... extraLore) {
        ItemStack icon = payment.prototype().clone();
        icon.setAmount(1);

        ItemMeta meta = icon.getItemMeta();
        if (meta != null) {
            if (nameOverride != null) {
                meta.displayName(color(nameOverride));
            } else if (!meta.hasDisplayName()) {
                meta.displayName(color(payment.displayName()));
            }

            if (extraLore.length > 0) {
                List<Component> existing = meta.lore();
                List<Component> lore = existing == null ? new ArrayList<>() : new ArrayList<>(existing);
                for (String line : extraLore) {
                    lore.add(color(line));
                }
                meta.lore(lore);
            }

            icon.setItemMeta(meta);
        }

        return icon;
    }

    // ---------------------------------------------------------------- auto refresh

    /** Segarkan sisa waktu dan status listing di menu Market / My Listings yang sedang terbuka. */
    public void startRefreshTask() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::refreshOpenMenus, 400L, 400L);
    }

    private void refreshOpenMenus() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            Inventory top = player.getOpenInventory().getTopInventory();
            if (!(top.getHolder() instanceof MenuHolder menu)) continue;
            if (menu.type() != MenuType.MARKET && menu.type() != MenuType.MY_LISTINGS) continue;

            boolean own = menu.type() == MenuType.MY_LISTINGS;

            for (int slot = 0; slot < menu.ids().size() && slot < PAGE_SIZE; slot++) {
                Listing listing = listingManager.getListing(menu.ids().get(slot));
                top.setItem(slot, listing == null ? null : createListingIcon(listing, own));
            }
        }
    }

    private String formatRemaining(long millis) {
        long minutes = millis / 60_000L;
        long days = minutes / 1440;
        long hours = (minutes % 1440) / 60;
        long mins = minutes % 60;

        if (days > 0) return "&a" + days + " hari " + hours + " jam";
        if (hours > 0) return "&e" + hours + " jam " + mins + " menit";
        if (mins > 0) return "&c" + mins + " menit";
        return "&ckurang dari 1 menit";
    }

    // ---------------------------------------------------------------- helpers

    private void releaseSession(Player player) {
        SellSession session = sessions.remove(player.getUniqueId());
        if (session == null) return;

        for (ItemStack item : session.items) {
            plugin.giveOrDrop(player, item);
        }
        session.items.clear();
    }

    private void returnSellSlotItems(Player player, Inventory top) {
        for (int slot = 0; slot < top.getSize(); slot++) {
            if (!isSellSlot(slot)) continue;

            ItemStack item = top.getItem(slot);
            if (item == null || item.getType().isAir()) continue;

            top.setItem(slot, null);
            plugin.giveOrDrop(player, item);
        }
    }

    private static boolean isSellSlot(int slot) {
        int row = slot / 9;
        int column = slot % 9;
        return slot >= 0 && slot < 54 && row >= 1 && row <= 3 && column >= 1 && column <= 7;
    }

    private int totalItems(List<ItemStack> items) {
        int total = 0;
        for (ItemStack item : items) {
            total += item.getAmount();
        }
        return total;
    }

    private String plainName(String text) {
        return text.replaceAll("&[0-9a-fk-orA-FK-OR]", "");
    }

    private ItemStack createListingIcon(Listing listing, boolean own) {
        ItemStack icon = listing.getItems().get(0).clone();
        ItemMeta meta = icon.getItemMeta();

        if (meta != null) {
            List<Component> existing = meta.lore();
            List<Component> lore = existing == null ? new ArrayList<>() : new ArrayList<>(existing);

            lore.add(Component.empty());
            lore.add(color("&7Seller: &f" + listing.getSellerName()));
            lore.add(color("&7Dibutuhkan: &f" + listing.getPrice().displayName()
                    + " &7x" + listing.getPriceAmount()));

            if (listing.getItems().size() > 1) {
                lore.add(color("&7Bundle: &f" + listing.getItems().size() + " stack ("
                        + totalItems(listing.getItems()) + " item)"));
            }

            long remaining = listingManager.remainingMillis(listing);
            if (remaining == Long.MAX_VALUE) {
                lore.add(color("&7Sisa waktu: &aTanpa batas"));
            } else if (remaining <= 0) {
                lore.add(color("&cKedaluwarsa - hanya bisa diambil pemilik"));
            } else {
                lore.add(color("&7Sisa waktu: " + formatRemaining(remaining)));
            }

            lore.add(color("&8ID: " + listing.getId()));
            lore.add(color(own ? "&eKlik untuk mengambil kembali." : "&eKlik untuk membeli."));

            meta.lore(lore);
            icon.setItemMeta(meta);
        }

        return icon;
    }

    private ItemStack createAmountIcon(SellSession session) {
        PaymentItem payment = session.price;

        return paymentIcon(payment, payment.displayName() + " &7x" + session.amount,
                "&8Klik tombol untuk mengubah jumlah.", "&8Lalu tekan Save.");
    }

    private ItemStack button(Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();

        if (meta != null) {
            meta.displayName(color(name));

            if (lore.length > 0) {
                List<Component> lines = new ArrayList<>();
                for (String line : lore) {
                    lines.add(color(line));
                }
                meta.lore(lines);
            }

            item.setItemMeta(meta);
        }

        return item;
    }

    private static Component color(String text) {
        return BoxMarketPlugin.color(text);
    }
}
