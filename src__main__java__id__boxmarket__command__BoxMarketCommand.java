package id.boxmarket.command;

import id.boxmarket.BoxMarketPlugin;
import id.boxmarket.gui.GuiManager;
import id.boxmarket.manager.CatalogManager;
import id.boxmarket.manager.ListingManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class BoxMarketCommand implements CommandExecutor, TabCompleter {

    private final BoxMarketPlugin plugin;
    private final ListingManager listingManager;
    private final GuiManager guiManager;

    public BoxMarketCommand(BoxMarketPlugin plugin, ListingManager listingManager, GuiManager guiManager) {
        this.plugin = plugin;
        this.listingManager = listingManager;
        this.guiManager = guiManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "market" : args[0].toLowerCase(Locale.ROOT);

        if (sub.equals("reload")) {
            if (!sender.hasPermission("boxmarket.admin")) {
                plugin.send(sender, "no-permission");
                return true;
            }

            plugin.reloadPlugin();
            plugin.send(sender, "reloaded");
            return true;
        }

        if (sub.equals("cancel")) {
            if (!sender.hasPermission("boxmarket.admin")) {
                plugin.send(sender, "no-permission");
                return true;
            }

            if (args.length < 2) {
                plugin.send(sender, "usage-cancel");
                return true;
            }

            if (listingManager.adminCancel(args[1].toUpperCase(Locale.ROOT))) {
                plugin.send(sender, "listing-cancelled");
            } else {
                plugin.send(sender, "listing-not-found");
            }
            return true;
        }

        if (sub.equals("catalog")) {
            if (!plugin.canManageCatalog(sender)) {
                plugin.send(sender, "no-permission");
                return true;
            }

            String action = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "";
            CatalogManager catalog = plugin.getCatalogManager();

            if (action.equals("remove")) {
                if (args.length < 3) {
                    plugin.send(sender, "usage-catalog");
                } else {
                    String id = args[2].toLowerCase(Locale.ROOT);
                    catalog.remove(sender, id, listingManager.countListingsUsing(id));
                }
                return true;
            }

            if (!(sender instanceof Player admin)) {
                plugin.send(sender, "only-player");
                return true;
            }

            if (action.equals("add")) {
                catalog.add(admin, admin.getInventory().getItemInMainHand());
            } else if (action.equals("check")) {
                catalog.check(admin, admin.getInventory().getItemInMainHand());
            } else {
                guiManager.openCatalog(admin, null);
            }
            return true;
        }

        if (!(sender instanceof Player player)) {
            plugin.send(sender, "only-player");
            return true;
        }

        if (!player.hasPermission("boxmarket.use")) {
            plugin.send(player, "no-permission");
            return true;
        }

        switch (sub) {
            case "sell" -> guiManager.openSellMenu(player);
            case "my" -> guiManager.openMyListings(player);
            default -> guiManager.openMarket(player, 0);
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        boolean admin = sender.hasPermission("boxmarket.admin");

        if (args.length == 1) {
            List<String> options = new ArrayList<>(List.of("market", "sell", "my"));
            if (admin) {
                options.add("reload");
                options.add("cancel");
            }
            if (plugin.canManageCatalog(sender)) {
                options.add("catalog");
            }

            String prefix = args[0].toLowerCase(Locale.ROOT);
            options.removeIf(option -> !option.startsWith(prefix));
            return options;
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("catalog") && plugin.canManageCatalog(sender)) {
            List<String> options = new ArrayList<>(List.of("add", "remove", "check"));
            String prefix = args[1].toLowerCase(Locale.ROOT);
            options.removeIf(option -> !option.startsWith(prefix));
            return options;
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("catalog")
                && args[1].equalsIgnoreCase("remove") && plugin.canManageCatalog(sender)) {
            List<String> ids = new ArrayList<>(plugin.getCatalogManager().entries().keySet());
            String prefix = args[2].toLowerCase(Locale.ROOT);
            ids.removeIf(id -> !id.startsWith(prefix));
            return ids;
        }

        if (args.length == 2 && admin && args[0].equalsIgnoreCase("cancel")) {
            String prefix = args[1].toUpperCase(Locale.ROOT);
            List<String> ids = listingManager.getAllListingIds();
            ids.removeIf(id -> !id.startsWith(prefix));
            return ids;
        }

        return List.of();
    }
}
