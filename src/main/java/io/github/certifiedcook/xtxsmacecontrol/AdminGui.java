package io.github.certifiedcook.xtxsmacecontrol;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

public final class AdminGui implements Listener, InventoryHolder {
    private final XtxsMaceControl plugin;
    private Inventory inventory;

    public AdminGui(XtxsMaceControl plugin) {
        this.plugin = plugin;
    }

    public void open(Player player) {
        if (!plugin.getConfig().getBoolean("gui.enabled", true)) {
            player.sendMessage(plugin.enforcement().prefix() + "GUI is disabled in config.yml.");
            return;
        }
        inventory = Bukkit.createInventory(this, 27, "xtx's MaceControl");
        inventory.setItem(10, item(Material.MACE, "§6Stock status",
                "§7Active registered: §f" + plugin.registry().activeCount(),
                "§7Issued ever: §f" + plugin.registry().issuedEver()));
        inventory.setItem(12, item(Material.REDSTONE_TORCH, "§cEmergency lockdown",
                "§7Current: " + (plugin.isLockdown() ? "§cON" : "§aOFF"),
                "§eClick to toggle"));
        inventory.setItem(14, item(Material.SPYGLASS, "§bAudit yourself",
                "§7Runs the full inventory/ender audit."));
        inventory.setItem(16, item(Material.COMPARATOR, "§eReload configuration",
                "§7Reloads config.yml."));
        player.openInventory(inventory);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof AdminGui)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!player.hasPermission("xtxsmacecontrol.admin")) return;

        switch (event.getRawSlot()) {
            case 12 -> {
                plugin.setLockdown(!plugin.isLockdown());
                player.sendMessage(plugin.enforcement().prefix() + "Emergency lockdown is now "
                        + (plugin.isLockdown() ? "§cON" : "§aOFF") + "§7.");
                open(player);
            }
            case 14 -> {
                int found = plugin.audit().auditPlayer(player);
                player.sendMessage(plugin.enforcement().prefix() + "Audit completed; §f" + found + "§7 violation(s).");
            }
            case 16 -> {
                plugin.reloadEverything();
                player.sendMessage(plugin.enforcement().prefix() + "Configuration reloaded.");
                open(player);
            }
            default -> { }
        }
    }

    private ItemStack item(Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        meta.setLore(List.of(lore));
        item.setItemMeta(meta);
        return item;
    }
}
