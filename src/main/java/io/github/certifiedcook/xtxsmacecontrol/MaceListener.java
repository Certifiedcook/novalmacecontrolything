package io.github.certifiedcook.xtxsmacecontrol;

import org.bukkit.Bukkit;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.LootGenerateEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.*;

public final class MaceListener implements Listener {
    private final XtxsMaceControl plugin;
    private final Map<UUID, List<ItemStack>> deathKeep = new HashMap<>();

    public MaceListener(XtxsMaceControl plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        long delay = Math.max(0L, plugin.getConfig().getLong("acquisition.join-grace-seconds", 3L) * 20L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (event.getPlayer().isOnline()) plugin.audit().auditPlayer(event.getPlayer());
        }, delay);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        ItemStack item = event.getItem().getItemStack();
        if (!plugin.items().isMace(item)) return;

        boolean newIssuance = plugin.items().serial(item) == null
                || plugin.registry().get(plugin.items().serial(item)) == null;
        Decision decision = plugin.policy().acquisition(player, item, "PICKUP", newIssuance);
        if (!decision.allowed()) {
            event.setCancelled(true);
            plugin.enforcement().blocked(player, decision.reason());
            return;
        }

        if (newIssuance) plugin.registry().register(item, player, "PICKUP");
        else transferIfNeeded(player, item);
        event.getItem().setItemStack(item);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCreative(InventoryCreativeEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        ItemStack cursor = event.getCursor();
        if (!plugin.items().isMace(cursor) && !plugin.items().containsPortableMace(cursor)) return;

        if (!plugin.items().isMace(cursor)) {
            if (!plugin.getConfig().getBoolean("storage.allow-portable-containers", false)
                    && !player.hasPermission("xtxsmacecontrol.bypass.storage")
                    && !player.hasPermission("xtxsmacecontrol.bypass.all")) {
                event.setCancelled(true);
                plugin.enforcement().blocked(player, "portable containers may not contain maces");
            }
            return;
        }

        boolean newIssuance = plugin.items().serial(cursor) == null
                || plugin.registry().get(plugin.items().serial(cursor)) == null;
        Decision decision = plugin.policy().acquisition(player, cursor, "CREATIVE", newIssuance);
        if (!decision.allowed()) {
            event.setCancelled(true);
            plugin.enforcement().blocked(player, decision.reason());
            return;
        }
        if (newIssuance) plugin.registry().register(cursor, player, "CREATIVE");
        event.setCursor(cursor);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        ItemStack result = event.getRecipe().getResult();
        if (!plugin.items().isMace(result)) return;

        Decision decision = plugin.policy().acquisition(player, result, "CRAFT", true);
        if (!decision.allowed()) {
            event.setCancelled(true);
            plugin.enforcement().blocked(player, decision.reason());
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> plugin.audit().auditPlayer(player));
    }

    @EventHandler
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        if (event.getRecipe() == null) return;
        ItemStack result = event.getRecipe().getResult();
        if (!plugin.items().isMace(result)) return;
        if (!plugin.getConfig().getBoolean("acquisition.allow-crafting", true) || plugin.isLockdown()) {
            event.getInventory().setResult(null);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLoot(LootGenerateEvent event) {
        if (plugin.getConfig().getBoolean("acquisition.allow-loot", true) && !plugin.isLockdown()) return;
        event.getLoot().removeIf(plugin.items()::isMace);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) return;
        if (!(event.getEntity() instanceof LivingEntity target)) return;
        ItemStack mace = player.getInventory().getItemInMainHand();
        if (!plugin.items().isMace(mace)) return;

        boolean newIssuance = plugin.items().serial(mace) == null
                || plugin.registry().get(plugin.items().serial(mace)) == null;
        if (newIssuance) {
            if (!plugin.getConfig().getBoolean("general.auto-register-existing-maces", true)) {
                event.setCancelled(true);
                plugin.enforcement().blocked(player, "unregistered mace");
                return;
            }
            Decision acquire = plugin.policy().acquisition(player, mace, "USE_DISCOVERY", true);
            if (!acquire.allowed()) {
                event.setCancelled(true);
                plugin.enforcement().blocked(player, acquire.reason());
                if (plugin.getConfig().getBoolean("use.consume-cooldown-on-cancelled-hit", false)) {
                    plugin.policy().consumeUse(player, mace, target);
                }
                return;
            }
            plugin.registry().register(mace, player, "USE_DISCOVERY");
        }

        Decision decision = plugin.policy().use(player, mace, target);
        if (!decision.allowed()) {
            event.setCancelled(true);
            plugin.enforcement().blocked(player, decision.reason());
            if (plugin.getConfig().getBoolean("use.consume-cooldown-on-cancelled-hit", false)) {
                plugin.policy().consumeUse(player, mace, target);
            }
            return;
        }

        transferIfNeeded(player, mace);
        event.setDamage(plugin.policy().cappedDamage(player, target, event.getDamage()));
        plugin.policy().consumeUse(player, mace, target);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMend(PlayerItemMendEvent event) {
        if (!plugin.items().isMace(event.getItem())) return;
        if (!plugin.getConfig().getBoolean("repair.allow-mending", true)
                && !event.getPlayer().hasPermission("xtxsmacecontrol.bypass.all")) {
            event.setCancelled(true);
            plugin.enforcement().blocked(event.getPlayer(), "mending is disabled for maces");
        }
    }

    @EventHandler
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        ItemStack result = event.getResult();
        if (!plugin.items().isMace(result)) return;

        if (!plugin.getConfig().getBoolean("repair.allow-anvil-repair", true)
                || !plugin.getConfig().getBoolean("repair.allow-anvil-combine", true)) {
            event.setResult(null);
            return;
        }

        String renameText = event.getView().getRenameText();
        if (!plugin.getConfig().getBoolean("repair.allow-renaming", true)
                && renameText != null && !renameText.isBlank()) {
            event.setResult(null);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryMove(InventoryMoveItemEvent event) {
        ItemStack item = event.getItem();
        if (!plugin.items().isMace(item) && !plugin.items().containsPortableMace(item)) return;
        if (!plugin.getConfig().getBoolean("storage.allow-hoppers", false)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        ItemStack cursor = event.getCursor();
        ItemStack current = event.getCurrentItem();
        boolean involvesMace = plugin.items().isMace(cursor) || plugin.items().isMace(current)
                || plugin.items().containsPortableMace(cursor) || plugin.items().containsPortableMace(current);
        if (!involvesMace) return;

        boolean storageBypass = player.hasPermission("xtxsmacecontrol.bypass.all")
                || player.hasPermission("xtxsmacecontrol.bypass.storage");
        Inventory clicked = event.getClickedInventory();
        if (clicked != null && clicked != player.getInventory() && !storageBypass) {
            if (isExplicitlyBlocked(clicked.getType())) {
                event.setCancelled(true);
                plugin.enforcement().blocked(player, "maces are blocked in " + clicked.getType().name().toLowerCase(Locale.ROOT));
                return;
            }

            boolean ender = clicked.getType() == InventoryType.ENDER_CHEST;
            boolean allowed = ender
                    ? plugin.getConfig().getBoolean("storage.allow-ender-chest", false)
                    : plugin.getConfig().getBoolean("storage.allow-containers", false);
            if (!allowed) {
                event.setCancelled(true);
                plugin.enforcement().blocked(player, "mace storage is blocked here");
                return;
            }

            int max = plugin.getConfig().getInt("storage.max-per-container", 1);
            if (max >= 0 && plugin.items().countRecursive(clicked) >= max
                    && (plugin.items().isMace(cursor) || plugin.items().containsPortableMace(cursor))) {
                event.setCancelled(true);
                plugin.enforcement().blocked(player, "container mace cap reached");
                return;
            }
        }

        Bukkit.getScheduler().runTask(plugin, () -> plugin.audit().auditPlayer(player));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        ItemStack old = event.getOldCursor();
        if (!plugin.items().isMace(old) && !plugin.items().containsPortableMace(old)) return;
        Bukkit.getScheduler().runTask(plugin, () -> plugin.audit().auditPlayer(player));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        ItemStack item = event.getItemDrop().getItemStack();
        if (!plugin.items().isMace(item)) return;
        String serial = plugin.items().serial(item);
        if (serial != null) plugin.registry().setStatus(serial, "ACTIVE");
    }

    @EventHandler
    public void onBreak(PlayerItemBreakEvent event) {
        ItemStack item = event.getBrokenItem();
        if (!plugin.items().isMace(item)) return;
        String serial = plugin.items().serial(item);
        if (serial != null) plugin.registry().setStatus(serial, "DESTROYED");
    }

    @EventHandler
    public void onDespawn(ItemDespawnEvent event) {
        ItemStack item = event.getEntity().getItemStack();
        if (!plugin.items().isMace(item)) return;
        String serial = plugin.items().serial(item);
        if (serial != null) plugin.registry().setStatus(serial, "DESPAWNED");
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        List<ItemStack> maces = event.getDrops().stream()
                .filter(plugin.items()::isMace)
                .map(ItemStack::clone)
                .toList();

        if (plugin.getConfig().getBoolean("death.confiscate-maces-on-death", false)) {
            event.getDrops().removeIf(plugin.items()::isMace);
            for (ItemStack mace : maces) {
                String serial = plugin.items().serial(mace);
                if (serial != null) plugin.registry().setStatus(serial, "CONFISCATED_ON_DEATH");
            }
            return;
        }

        if (plugin.getConfig().getBoolean("death.keep-maces", false) && !maces.isEmpty()) {
            event.getDrops().removeIf(plugin.items()::isMace);
            deathKeep.put(event.getEntity().getUniqueId(), new ArrayList<>(maces));
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        List<ItemStack> kept = deathKeep.remove(event.getPlayer().getUniqueId());
        if (kept == null || kept.isEmpty()) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (ItemStack mace : kept) {
                Map<Integer, ItemStack> overflow = event.getPlayer().getInventory().addItem(mace);
                overflow.values().forEach(item -> event.getPlayer().getWorld()
                        .dropItemNaturally(event.getPlayer().getLocation(), item));
            }
        });
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        if (!plugin.getConfig().getBoolean("general.chunk-container-audit.enabled", true)
                || !plugin.getConfig().getBoolean("general.chunk-container-audit.on-chunk-load", true)) return;
        int limit = plugin.getConfig().getInt("general.chunk-container-audit.max-containers-per-chunk", 64);
        int seen = 0;
        for (BlockState state : event.getChunk().getTileEntities()) {
            if (!(state instanceof Container container)) continue;
            plugin.audit().auditContainer(container);
            if (++seen >= limit) break;
        }
    }

    @EventHandler
    public void onItemSpawn(ItemSpawnEvent event) {
        ItemStack item = event.getEntity().getItemStack();
        if (!plugin.items().isMace(item)) return;
        if (plugin.isLockdown()) {
            event.setCancelled(true);
            String serial = plugin.items().serial(item);
            if (serial != null) plugin.registry().setStatus(serial, "LOCKDOWN_REMOVED");
        }
    }

    @EventHandler
    public void onBlockDrop(BlockDropItemEvent event) {
        if (!plugin.isLockdown()) return;
        event.getItems().removeIf(item -> plugin.items().isMace(item.getItemStack()));
    }

    private boolean isExplicitlyBlocked(InventoryType type) {
        return plugin.getConfig().getStringList("storage.blocked-inventory-types").stream()
                .anyMatch(name -> name.equalsIgnoreCase(type.name()));
    }

    private void transferIfNeeded(Player player, ItemStack item) {
        String serial = plugin.items().serial(item);
        MaceRecord record = plugin.registry().get(serial);
        if (record != null && record.owner() != null && !record.owner().equals(player.getUniqueId())) {
            plugin.registry().transfer(serial, player.getUniqueId());
        }
    }
}
