package io.github.certifiedcook.xtxsmacecontrol;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.*;

public final class AdminCommand implements CommandExecutor, TabCompleter {
    private final XtxsMaceControl plugin;

    public AdminCommand(XtxsMaceControl plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("xtxsmacecontrol.admin")) {
            sender.sendMessage("You do not have permission.");
            return true;
        }

        if (args.length == 0) {
            if (sender instanceof Player player) plugin.gui().open(player);
            else status(sender);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "status", "stats" -> status(sender);
            case "reload" -> {
                plugin.reloadEverything();
                sender.sendMessage(plugin.enforcement().prefix() + "Configuration and registry reloaded.");
            }
            case "lockdown" -> lockdown(sender, args);
            case "audit" -> audit(sender, args);
            case "inspect" -> inspect(sender);
            case "issue", "give" -> issue(sender, args);
            case "confiscate", "purge" -> confiscate(sender, args);
            case "retire" -> retire(sender, args);
            case "violations" -> violations(sender, args);
            case "help" -> help(sender);
            default -> help(sender);
        }
        return true;
    }

    private void status(CommandSender sender) {
        sender.sendMessage(plugin.enforcement().prefix() + "§6Status");
        sender.sendMessage("§7Lockdown: " + (plugin.isLockdown() ? "§cON" : "§aOFF"));
        sender.sendMessage("§7Active registered maces: §f" + plugin.registry().activeCount());
        sender.sendMessage("§7Issued ever: §f" + plugin.registry().issuedEver());
        sender.sendMessage("§7Online players: §f" + Bukkit.getOnlinePlayers().size());
        sender.sendMessage("§7Violation reasons tracked this runtime: §f" + plugin.enforcement().reasons().size());
    }

    private void lockdown(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(plugin.enforcement().prefix() + "Lockdown is "
                    + (plugin.isLockdown() ? "§cON" : "§aOFF") + "§7. Use on/off/toggle.");
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "on" -> plugin.setLockdown(true);
            case "off" -> plugin.setLockdown(false);
            case "toggle" -> plugin.setLockdown(!plugin.isLockdown());
            default -> {
                sender.sendMessage("Use /macecontrol lockdown <on|off|toggle>");
                return;
            }
        }
        sender.sendMessage(plugin.enforcement().prefix() + "Emergency lockdown set to "
                + (plugin.isLockdown() ? "§cON" : "§aOFF") + "§7.");
    }

    private void audit(CommandSender sender, String[] args) {
        if (args.length >= 2 && args[1].equalsIgnoreCase("all")) {
            int total = 0;
            for (Player player : Bukkit.getOnlinePlayers()) total += plugin.audit().auditPlayer(player);
            plugin.audit().auditLoadedItemEntities();
            sender.sendMessage(plugin.enforcement().prefix() + "Audited all online players; §f" + total + "§7 violation(s).");
            return;
        }

        Player target = args.length >= 2 ? Bukkit.getPlayerExact(args[1]) : (sender instanceof Player p ? p : null);
        if (target == null) {
            sender.sendMessage("Specify an online player or use /macecontrol audit all");
            return;
        }
        int total = plugin.audit().auditPlayer(target);
        sender.sendMessage(plugin.enforcement().prefix() + "Audited " + target.getName() + "; §f" + total + "§7 violation(s).");
    }

    private void inspect(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only.");
            return;
        }
        ItemStack item = player.getInventory().getItemInMainHand();
        if (!plugin.items().isMace(item)) {
            sender.sendMessage(plugin.enforcement().prefix() + "Hold a mace in your main hand.");
            return;
        }
        String serial = plugin.items().serial(item);
        MaceRecord record = plugin.registry().get(serial);
        sender.sendMessage(plugin.enforcement().prefix() + "§6Mace inspection");
        sender.sendMessage("§7Serial: §f" + (serial == null ? "<unregistered>" : serial));
        if (record != null) {
            sender.sendMessage("§7Owner: §f" + record.owner());
            sender.sendMessage("§7Source: §f" + record.source());
            sender.sendMessage("§7Status: §f" + record.status());
            sender.sendMessage("§7Transfers: §f" + record.transfers());
            sender.sendMessage("§7Issued: §f" + new Date(record.issuedAt()));
        }
        Decision legal = plugin.policy().enchantments(player, item);
        sender.sendMessage("§7Enchant policy: " + (legal.allowed() ? "§aLEGAL" : "§c" + legal.reason()));
    }

    private void issue(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("Usage: /macecontrol issue <player> [amount]");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage("Player not found.");
            return;
        }

        int amount = 1;
        if (args.length >= 3) {
            try {
                amount = Math.max(1, Math.min(64, Integer.parseInt(args[2])));
            } catch (NumberFormatException ignored) {
                sender.sendMessage("Amount must be a number.");
                return;
            }
        }

        int issued = 0;
        for (int i = 0; i < amount; i++) {
            ItemStack mace = new ItemStack(Material.MACE);
            Decision decision = plugin.policy().acquisition(target, mace, "ADMIN", true);
            if (!decision.allowed()) {
                sender.sendMessage(plugin.enforcement().prefix() + "Stopped after " + issued + ": " + decision.reason());
                break;
            }
            plugin.registry().register(mace, target, "ADMIN");
            Map<Integer, ItemStack> overflow = target.getInventory().addItem(mace);
            overflow.values().forEach(item -> target.getWorld().dropItemNaturally(target.getLocation(), item));
            issued++;
        }
        sender.sendMessage(plugin.enforcement().prefix() + "Issued §f" + issued + "§7 mace(s) to " + target.getName() + ".");
    }

    private void confiscate(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("Usage: /macecontrol confiscate <player>");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage("Player not found.");
            return;
        }
        int removed = plugin.audit().confiscateAll(target, "admin confiscation by " + sender.getName());
        sender.sendMessage(plugin.enforcement().prefix() + "Confiscated §f" + removed + "§7 mace(s) from " + target.getName() + ".");
    }

    private void retire(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("Usage: /macecontrol retire <serial>");
            return;
        }
        MaceRecord record = plugin.registry().get(args[1]);
        if (record == null) {
            sender.sendMessage("Unknown serial.");
            return;
        }
        plugin.registry().setStatus(record.serial(), "RETIRED");
        sender.sendMessage(plugin.enforcement().prefix() + "Retired serial §f" + record.serial());
    }

    private void violations(CommandSender sender, String[] args) {
        if (args.length >= 2) {
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage("Player not found.");
                return;
            }
            sender.sendMessage(plugin.enforcement().prefix() + target.getName() + " has §f"
                    + plugin.enforcement().violationCount(target.getUniqueId()) + "§7 runtime violation(s).");
            return;
        }

        sender.sendMessage(plugin.enforcement().prefix() + "§6Runtime violation reasons:");
        plugin.enforcement().reasons().entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(10)
                .forEach(e -> sender.sendMessage("§7- §f" + e.getValue() + "§7 × " + e.getKey()));
    }

    private void help(CommandSender sender) {
        sender.sendMessage(plugin.enforcement().prefix() + "§6Commands");
        sender.sendMessage("§e/macecontrol §7- GUI");
        sender.sendMessage("§e/macecontrol status");
        sender.sendMessage("§e/macecontrol reload");
        sender.sendMessage("§e/macecontrol lockdown <on|off|toggle>");
        sender.sendMessage("§e/macecontrol audit [player|all]");
        sender.sendMessage("§e/macecontrol inspect");
        sender.sendMessage("§e/macecontrol issue <player> [amount]");
        sender.sendMessage("§e/macecontrol confiscate <player>");
        sender.sendMessage("§e/macecontrol retire <serial>");
        sender.sendMessage("§e/macecontrol violations [player]");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return partial(args[0], List.of("status", "reload", "lockdown", "audit", "inspect", "issue",
                    "confiscate", "retire", "violations", "help"));
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("lockdown")) {
            return partial(args[1], List.of("on", "off", "toggle"));
        }
        if (args.length == 2 && List.of("audit", "issue", "confiscate", "violations").contains(args[0].toLowerCase(Locale.ROOT))) {
            List<String> names = new ArrayList<>();
            if (args[0].equalsIgnoreCase("audit")) names.add("all");
            Bukkit.getOnlinePlayers().forEach(p -> names.add(p.getName()));
            return partial(args[1], names);
        }
        return List.of();
    }

    private List<String> partial(String token, Collection<String> values) {
        String lower = token.toLowerCase(Locale.ROOT);
        return values.stream().filter(v -> v.toLowerCase(Locale.ROOT).startsWith(lower)).sorted().toList();
    }
}
