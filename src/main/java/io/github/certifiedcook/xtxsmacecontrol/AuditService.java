package io.github.certifiedcook.xtxsmacecontrol;

import org.bukkit.World;
import org.bukkit.block.Container;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scoreboard.Team;

import java.util.*;

public final class AuditService {
    private final XtxsMaceControl plugin;

    public AuditService(XtxsMaceControl plugin) {
        this.plugin = plugin;
    }

    public int countPlayerMaces(Player player) {
        int count = plugin.items().countRecursive(player.getInventory());
        if (plugin.getConfig().getBoolean("general.periodic-audit.audit-ender-chests", true)) {
            count += plugin.items().countRecursive(player.getEnderChest());
        }
        return count;
    }

    public int countOnlineWorldMaces(World world) {
        int total = 0;
        for (Player player : world.getPlayers()) total += countPlayerMaces(player);
        return total;
    }

    public int countOnlineTeamMaces(Team team) {
        int total = 0;
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            Team playerTeam = player.getScoreboard().getEntryTeam(player.getName());
            if (playerTeam != null && playerTeam.getName().equals(team.getName())) total += countPlayerMaces(player);
        }
        return total;
    }

    public int auditPlayer(Player player) {
        int violations = 0;
        violations += auditInventory(player, player.getInventory(), "PLAYER_INVENTORY");
        if (plugin.getConfig().getBoolean("general.periodic-audit.audit-ender-chests", true)) {
            violations += auditInventory(player, player.getEnderChest(), "ENDER_CHEST");
        }
        violations += removeDuplicateSerials(player);
        return violations;
    }

    public int auditInventory(Player player, Inventory inventory, String location) {
        int violations = 0;
        ItemStack[] contents = inventory.getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            if (plugin.items().isMace(item)) {
                String serial = plugin.items().serial(item);
                boolean newIssuance = serial == null || plugin.registry().get(serial) == null;

                if (newIssuance && plugin.getConfig().getBoolean("general.auto-register-existing-maces", true)) {
                    Decision decision = plugin.policy().acquisition(player, item, "AUDIT", true);
                    if (decision.allowed()) {
                        plugin.registry().register(item, player, "AUDIT");
                        inventory.setItem(slot, item);
                    } else {
                        inventory.setItem(slot, null);
                        plugin.enforcement().confiscated(player, item, decision.reason());
                        violations++;
                    }
                } else {
                    Decision decision = plugin.policy().acquisition(player, item, "AUDIT", false);
                    if (!decision.allowed()) {
                        if (trySanitizeEnchantments(player, item)) {
                            inventory.setItem(slot, item);
                        } else {
                            inventory.setItem(slot, null);
                            plugin.enforcement().confiscated(player, item, decision.reason());
                            violations++;
                        }
                    } else {
                        MaceRecord record = plugin.registry().get(plugin.items().serial(item));
                        if (record != null && record.owner() != null && !record.owner().equals(player.getUniqueId())) {
                            plugin.registry().transfer(record.serial(), player.getUniqueId());
                        }
                    }
                }
            } else if (plugin.items().containsPortableMace(item)
                    && !plugin.getConfig().getBoolean("storage.allow-portable-containers", false)
                    && !player.hasPermission("xtxsmacecontrol.bypass.storage")) {
                inventory.setItem(slot, null);
                plugin.enforcement().blocked(player, "portable containers may not contain maces");
                violations++;
            }
        }
        return violations;
    }

    public int auditContainer(Container container) {
        if (plugin.getConfig().getBoolean("storage.allow-containers", false)) return 0;
        int removed = 0;
        Inventory inv = container.getInventory();
        for (int slot = 0; slot < inv.getSize(); slot++) {
            ItemStack item = inv.getItem(slot);
            if (plugin.items().isMace(item) || plugin.items().containsPortableMace(item)) {
                inv.setItem(slot, null);
                String serial = plugin.items().serial(item);
                if (serial != null) plugin.registry().setStatus(serial, "CONFISCATED");
                removed++;
            }
        }
        return removed;
    }

    public void auditLoadedItemEntities() {
        if (!plugin.getConfig().getBoolean("general.periodic-audit.audit-loaded-item-entities", true)) return;
        Map<String, Item> firstBySerial = new HashMap<>();
        for (World world : plugin.getServer().getWorlds()) {
            for (Item entity : world.getEntitiesByClass(Item.class)) {
                ItemStack item = entity.getItemStack();
                if (!plugin.items().isMace(item)) continue;
                String serial = plugin.items().serial(item);
                if (serial == null) continue;
                Item first = firstBySerial.putIfAbsent(serial, entity);
                if (first != null && plugin.getConfig().getBoolean("anti-duplication.confiscate-duplicate-serials", true)) {
                    entity.remove();
                    plugin.registry().setStatus(serial, "QUARANTINED_DUPLICATE");
                    plugin.getLogger().warning("Removed duplicate loaded mace entity with serial " + serial);
                }
            }
        }
    }

    public int confiscateAll(Player player, String reason) {
        int removed = 0;
        removed += confiscateInventory(player, player.getInventory(), reason);
        removed += confiscateInventory(player, player.getEnderChest(), reason);
        return removed;
    }

    private int confiscateInventory(Player player, Inventory inventory, String reason) {
        int removed = 0;
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            if (plugin.items().isMace(item)) {
                inventory.setItem(slot, null);
                plugin.enforcement().confiscated(player, item, reason);
                removed++;
            }
        }
        return removed;
    }

    private int removeDuplicateSerials(Player player) {
        if (!plugin.getConfig().getBoolean("anti-duplication.enabled", true)) return 0;
        Set<String> seen = new HashSet<>();
        int removed = 0;
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            if (!plugin.items().isMace(item)) continue;
            String serial = plugin.items().serial(item);
            if (serial == null) continue;
            if (!seen.add(serial)) {
                inventory.setItem(slot, null);
                plugin.registry().setStatus(serial, "QUARANTINED_DUPLICATE");
                plugin.enforcement().confiscated(player, item, "duplicate mace serial");
                removed++;
            }
        }
        return removed;
    }

    private boolean trySanitizeEnchantments(Player player, ItemStack item) {
        if (!plugin.getConfig().getBoolean("enchantments.remove-illegal-instead-of-confiscating", true)) return false;
        var meta = item.getItemMeta();
        boolean changed = false;
        for (var entry : new HashMap<>(meta.getEnchants()).entrySet()) {
            String key = entry.getKey().getKey().getKey().toUpperCase(Locale.ROOT);
            String path = "enchantments.allowed." + key;
            if (!plugin.getConfig().contains(path)) {
                if (plugin.getConfig().getBoolean("enchantments.block-unlisted", true)) {
                    meta.removeEnchant(entry.getKey());
                    changed = true;
                }
            } else {
                int max = plugin.getConfig().getInt(path, -1);
                if (max >= 0 && entry.getValue() > max) {
                    meta.removeEnchant(entry.getKey());
                    meta.addEnchant(entry.getKey(), max, true);
                    changed = true;
                }
            }
        }
        if (changed) {
            item.setItemMeta(meta);
            plugin.enforcement().blocked(player, "illegal mace enchantments were sanitized");
            return true;
        }
        return false;
    }
}
