package io.github.certifiedcook.xtxsmacecontrol;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.ShulkerBox;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;

public final class MaceItems {
    private final XtxsMaceControl plugin;
    private final NamespacedKey serialKey;

    public MaceItems(XtxsMaceControl plugin) {
        this.plugin = plugin;
        this.serialKey = new NamespacedKey(plugin, "serial");
    }

    public boolean isMace(ItemStack item) {
        return item != null && item.getType() == Material.MACE && item.getAmount() > 0;
    }

    public String serial(ItemStack item) {
        if (!isMace(item) || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(serialKey, PersistentDataType.STRING);
    }

    public void setSerial(ItemStack item, String serial) {
        if (!isMace(item)) return;
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(serialKey, PersistentDataType.STRING, serial);
        if (plugin.getConfig().getBoolean("general.stamp-serial-in-lore", false)) {
            List<String> lore = meta.hasLore() && meta.getLore() != null
                    ? new ArrayList<>(meta.getLore())
                    : new ArrayList<>();
            lore.removeIf(line -> line.startsWith("§8Serial: "));
            lore.add("§8Serial: " + serial.substring(0, Math.min(12, serial.length())));
            meta.setLore(lore);
        }
        item.setItemMeta(meta);
    }

    public int countDirect(Inventory inventory) {
        int count = 0;
        for (ItemStack item : inventory.getContents()) {
            if (isMace(item)) count += item.getAmount();
        }
        return count;
    }

    public int countRecursive(ItemStack item) {
        return countRecursive(item, 0, new HashSet<>());
    }

    private int countRecursive(ItemStack item, int depth, Set<ItemStack> visited) {
        if (item == null || depth > 4) return 0;
        if (isMace(item)) return item.getAmount();
        if (!item.hasItemMeta() || visited.contains(item)) return 0;
        visited.add(item);

        int count = 0;
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof BundleMeta bundleMeta) {
            for (ItemStack nested : bundleMeta.getItems()) {
                count += countRecursive(nested, depth + 1, visited);
            }
        }
        if (meta instanceof BlockStateMeta stateMeta && stateMeta.getBlockState() instanceof ShulkerBox box) {
            for (ItemStack nested : box.getInventory().getContents()) {
                count += countRecursive(nested, depth + 1, visited);
            }
        }
        return count;
    }

    public int countRecursive(Inventory inventory) {
        int total = 0;
        for (ItemStack item : inventory.getContents()) total += countRecursive(item);
        return total;
    }

    public boolean containsPortableMace(ItemStack item) {
        return !isMace(item) && countRecursive(item) > 0;
    }
}
