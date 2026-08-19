package io.github.certifiedcook.xtxsmacecontrol;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scoreboard.Team;

import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class PolicyEngine {
    private final XtxsMaceControl plugin;
    private final RollingRateLimiter rateLimiter = new RollingRateLimiter();

    public PolicyEngine(XtxsMaceControl plugin) {
        this.plugin = plugin;
    }

    public Decision acquisition(Player player, ItemStack mace, String source, boolean newIssuance) {
        if (bypass(player, "acquire")) return Decision.allow();
        if (!plugin.getConfig().getBoolean("general.enabled", true)) return Decision.allow();
        if (plugin.isLockdown()) return Decision.deny("emergency lockdown");
        if (!plugin.getConfig().getBoolean("acquisition.enabled", true)) return Decision.deny("acquisition is disabled");

        if (plugin.getConfig().getBoolean("acquisition.require-permission", false)
                && !player.hasPermission("xtxsmacecontrol.acquire")) {
            return Decision.deny("missing acquisition permission");
        }

        Decision world = worldAndGamemode(player);
        if (!world.allowed()) return world;

        if ("CRAFT".equals(source) && !plugin.getConfig().getBoolean("acquisition.allow-crafting", true)) {
            return Decision.deny("mace crafting is disabled");
        }
        if ("LOOT".equals(source) && !plugin.getConfig().getBoolean("acquisition.allow-loot", true)) {
            return Decision.deny("maces from loot are disabled");
        }
        if ("PICKUP".equals(source) && !plugin.getConfig().getBoolean("acquisition.allow-pickup", true)) {
            return Decision.deny("mace pickup is disabled");
        }
        if ("CREATIVE".equals(source) && !plugin.getConfig().getBoolean("acquisition.allow-creative", false)) {
            return Decision.deny("creative mace acquisition is disabled");
        }
        if ("ADMIN".equals(source) && !plugin.getConfig().getBoolean("acquisition.allow-admin-issue", true)) {
            return Decision.deny("admin mace issuance is disabled");
        }

        Decision enchants = enchantments(player, mace);
        if (!enchants.allowed()) return enchants;

        if (!player.hasPermission("xtxsmacecontrol.bypass.possession")) {
            boolean itemAlreadyPossessed = source.equals("AUDIT") || source.equals("USE_DISCOVERY");
            int adjustment = itemAlreadyPossessed ? 1 : 0;

            int maxPossession = plugin.getConfig().getInt("stock.per-player-active-possession", 1);
            int current = Math.max(0, plugin.audit().countPlayerMaces(player) - adjustment);
            if (maxPossession >= 0 && current >= maxPossession && !alreadyHasThisSerial(player, mace)) {
                return Decision.deny("per-player mace possession cap reached");
            }

            int worldCap = plugin.getConfig().getInt("stock.per-world-online-possession", -1);
            int worldCurrent = Math.max(0, plugin.audit().countOnlineWorldMaces(player.getWorld()) - adjustment);
            if (worldCap >= 0 && worldCurrent >= worldCap) {
                return Decision.deny("world mace possession cap reached");
            }

            int teamCap = plugin.getConfig().getInt("stock.per-scoreboard-team-active-possession", -1);
            if (teamCap >= 0) {
                Team team = player.getScoreboard().getEntryTeam(player.getName());
                int teamCurrent = team == null ? 0 : Math.max(0, plugin.audit().countOnlineTeamMaces(team) - adjustment);
                if (team != null && teamCurrent >= teamCap) {
                    return Decision.deny("scoreboard-team mace possession cap reached");
                }
            }
        }

        if (newIssuance) {
            Decision stock = newIssuance(player, source);
            if (!stock.allowed()) return stock;
        } else {
            Decision ownership = ownership(player, mace);
            if (!ownership.allowed()) return ownership;
        }

        return Decision.allow();
    }

    public Decision newIssuance(Player player, String source) {
        boolean exemptAdmin = "ADMIN".equals(source)
                && plugin.getConfig().getBoolean("enforcement.exempt-admin-issued-from-stock-cap", false);
        if (exemptAdmin || player.hasPermission("xtxsmacecontrol.bypass.possession")) return Decision.allow();

        int globalActive = plugin.getConfig().getInt("stock.global-active-registered-cap", -1);
        if (globalActive >= 0 && plugin.registry().activeCount() >= globalActive) {
            return Decision.deny("global active mace cap reached");
        }

        int maxEver = plugin.getConfig().getInt("stock.max-issued-ever", -1);
        if (maxEver >= 0 && plugin.registry().issuedEver() >= maxEver) {
            return Decision.deny("maximum total mace issuance reached");
        }

        int playerEver = plugin.getConfig().getInt("stock.per-player-issued-ever", -1);
        if (playerEver >= 0 && plugin.registry().issuedTo(player.getUniqueId()) >= playerEver) {
            return Decision.deny("your lifetime mace issuance quota is exhausted");
        }

        if (plugin.getConfig().getBoolean("stock.issuance-window.enabled", true)) {
            long seconds = plugin.getConfig().getLong("stock.issuance-window.seconds", 86400L);
            int max = plugin.getConfig().getInt("stock.issuance-window.max-new-maces", -1);
            if (max >= 0 && plugin.registry().issuedSince(System.currentTimeMillis() - seconds * 1000L) >= max) {
                return Decision.deny("server mace issuance window quota reached");
            }
        }
        return Decision.allow();
    }

    public Decision ownership(Player player, ItemStack mace) {
        if (bypass(player, "ownership")) return Decision.allow();
        if (!plugin.getConfig().getBoolean("ownership.enabled", true)) return Decision.allow();

        String serial = plugin.items().serial(mace);
        MaceRecord record = plugin.registry().get(serial);
        if (record == null || record.owner() == null || record.owner().equals(player.getUniqueId())) return Decision.allow();

        if (plugin.getConfig().getBoolean("ownership.bind-on-first-acquisition", false)) {
            return Decision.deny("this mace is bound to another owner");
        }
        if (!plugin.getConfig().getBoolean("ownership.allow-transfers", true)) {
            return Decision.deny("mace transfers are disabled");
        }

        int maxTransfers = plugin.getConfig().getInt("ownership.max-transfers-per-mace", -1);
        if (maxTransfers >= 0 && record.transfers() >= maxTransfers) {
            return Decision.deny("this mace has reached its transfer limit");
        }

        long cooldown = plugin.getConfig().getLong("ownership.transfer-cooldown-seconds", 300L) * 1000L;
        if (cooldown > 0 && System.currentTimeMillis() - record.lastTransferAt() < cooldown) {
            return Decision.deny("this mace is transfer-locked");
        }

        if (plugin.getConfig().getBoolean("ownership.owner-must-be-online-for-transfer", false)
                && Bukkit.getPlayer(record.owner()) == null) {
            return Decision.deny("the registered owner must be online to transfer this mace");
        }

        if (plugin.getConfig().getBoolean("ownership.same-scoreboard-team-only", false)) {
            Player oldOwner = Bukkit.getPlayer(record.owner());
            if (oldOwner == null) return Decision.deny("cannot verify the previous owner's team");
            Team a = oldOwner.getScoreboard().getEntryTeam(oldOwner.getName());
            Team b = player.getScoreboard().getEntryTeam(player.getName());
            if (a == null || b == null || !a.getName().equals(b.getName())) {
                return Decision.deny("mace transfers are restricted to the same scoreboard team");
            }
        }
        return Decision.allow();
    }

    public Decision use(Player player, ItemStack mace, LivingEntity target) {
        if (bypass(player, "use")) return Decision.allow();
        if (!plugin.getConfig().getBoolean("general.enabled", true)) return Decision.allow();
        if (!plugin.getConfig().getBoolean("use.enabled", true)) return Decision.deny("mace use is disabled");
        if (plugin.isLockdown()) return Decision.deny("emergency lockdown");

        if (plugin.getConfig().getBoolean("use.require-permission", false)
                && !player.hasPermission("xtxsmacecontrol.use")) {
            return Decision.deny("missing mace-use permission");
        }

        Decision world = worldAndGamemode(player);
        if (!world.allowed()) return world;

        if (player.getGameMode() == GameMode.CREATIVE && plugin.getConfig().getBoolean("use.block-in-creative", true)) {
            return Decision.deny("mace use is disabled in creative");
        }
        if (player.getGameMode() == GameMode.SPECTATOR && plugin.getConfig().getBoolean("use.block-in-spectator", true)) {
            return Decision.deny("mace use is disabled in spectator");
        }
        if (player.isFlying() && plugin.getConfig().getBoolean("use.block-while-flying", true)) {
            return Decision.deny("mace use while flying is disabled");
        }
        if (player.isGliding() && plugin.getConfig().getBoolean("use.block-while-gliding", true)) {
            return Decision.deny("mace use while gliding is disabled");
        }
        if (player.isInsideVehicle() && plugin.getConfig().getBoolean("use.block-in-vehicle", false)) {
            return Decision.deny("mace use in vehicles is disabled");
        }

        double minFall = plugin.getConfig().getDouble("use.minimum-fall-distance", 0.0);
        double maxFall = plugin.getConfig().getDouble("use.maximum-fall-distance", -1.0);
        if (player.getFallDistance() < minFall) return Decision.deny("minimum fall distance not met");
        if (maxFall >= 0 && player.getFallDistance() > maxFall) return Decision.deny("maximum permitted fall distance exceeded");

        Decision enchants = enchantments(player, mace);
        if (!enchants.allowed()) return enchants;
        Decision owner = ownership(player, mace);
        if (!owner.allowed()) return owner;

        if (!bypass(player, "cooldown")) {
            long now = System.currentTimeMillis();
            long playerCooldown = millis(plugin.getConfig().getDouble("use.player-cooldown-seconds", 8.0));
            if (now - plugin.registry().playerUse(player.getUniqueId()) < playerCooldown) {
                return Decision.deny("player mace cooldown");
            }

            String serial = plugin.items().serial(mace);
            long maceCooldown = millis(plugin.getConfig().getDouble("use.mace-cooldown-seconds", 8.0));
            if (serial != null && now - plugin.registry().maceUse(serial) < maceCooldown) {
                return Decision.deny("this mace is on cooldown");
            }

            if (target != null) {
                long targetCooldown = millis(plugin.getConfig().getDouble("use.target-cooldown-seconds", 3.0));
                if (now - plugin.registry().targetUse(player.getUniqueId(), target.getUniqueId()) < targetCooldown) {
                    return Decision.deny("target-specific mace cooldown");
                }
            }

            int maxPerMinute = plugin.getConfig().getInt("use.max-hits-per-minute", -1);
            if (!rateLimiter.allow(player.getUniqueId(), maxPerMinute)) {
                return Decision.deny("mace hit rate limit reached");
            }
        }
        return Decision.allow();
    }

    public Decision enchantments(Player player, ItemStack mace) {
        if (bypass(player, "enchantments")) return Decision.allow();
        Map<Enchantment, Integer> enchants = mace.getEnchantments();
        boolean blockUnlisted = plugin.getConfig().getBoolean("enchantments.block-unlisted", true);

        for (Map.Entry<Enchantment, Integer> entry : enchants.entrySet()) {
            String key = entry.getKey().getKey().getKey().toUpperCase(Locale.ROOT);
            String path = "enchantments.allowed." + key;
            if (!plugin.getConfig().contains(path)) {
                if (blockUnlisted) return Decision.deny("unlisted enchantment: " + key);
                continue;
            }
            int max = plugin.getConfig().getInt(path, -1);
            if (max >= 0 && entry.getValue() > max) {
                return Decision.deny(key + " exceeds level " + max);
            }
        }
        return Decision.allow();
    }

    public double cappedDamage(Player attacker, LivingEntity target, double proposed) {
        if (bypass(attacker, "damage") || !plugin.getConfig().getBoolean("damage.enabled", true)) return proposed;
        double result = proposed * plugin.getConfig().getDouble("damage.scale", 1.0);

        double globalMax = plugin.getConfig().getDouble("damage.global-max", -1.0);
        double pvpMax = plugin.getConfig().getDouble("damage.pvp-max", -1.0);
        double pveMax = plugin.getConfig().getDouble("damage.pve-max", -1.0);

        if (globalMax >= 0) result = Math.min(result, globalMax);
        if (target instanceof Player && pvpMax >= 0) result = Math.min(result, pvpMax);
        if (!(target instanceof Player) && pveMax >= 0) result = Math.min(result, pveMax);

        double percent = plugin.getConfig().getDouble("damage.max-percent-of-target-max-health", -1.0);
        if (percent >= 0) {
            double maxHealth = target.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH) == null
                    ? target.getHealth()
                    : target.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue();
            result = Math.min(result, maxHealth * percent);
        }
        return Math.max(0.0, result);
    }

    public void consumeUse(Player player, ItemStack mace, LivingEntity target) {
        plugin.registry().markUse(player.getUniqueId(), plugin.items().serial(mace),
                target == null ? null : target.getUniqueId());
    }

    private Decision worldAndGamemode(Player player) {
        if (!bypass(player, "world")) {
            List<String> allowed = plugin.getConfig().getStringList("acquisition.allowed-worlds");
            List<String> blocked = plugin.getConfig().getStringList("acquisition.blocked-worlds");
            String world = player.getWorld().getName();
            if (!allowed.isEmpty() && allowed.stream().noneMatch(v -> v.equalsIgnoreCase(world))) {
                return Decision.deny("maces are not allowed in this world");
            }
            if (blocked.stream().anyMatch(v -> v.equalsIgnoreCase(world))) {
                return Decision.deny("maces are blocked in this world");
            }
        }

        List<String> blockedModes = plugin.getConfig().getStringList("acquisition.blocked-gamemodes");
        if (blockedModes.stream().anyMatch(v -> v.equalsIgnoreCase(player.getGameMode().name()))) {
            return Decision.deny("maces are blocked in your gamemode");
        }
        return Decision.allow();
    }

    private boolean alreadyHasThisSerial(Player player, ItemStack mace) {
        String serial = plugin.items().serial(mace);
        if (serial == null) return false;
        for (ItemStack item : player.getInventory().getContents()) {
            if (serial.equals(plugin.items().serial(item))) return true;
        }
        return false;
    }

    private boolean bypass(Player player, String node) {
        return player.hasPermission("xtxsmacecontrol.bypass.all")
                || player.hasPermission("xtxsmacecontrol.bypass." + node);
    }

    private long millis(double seconds) {
        return Math.max(0L, Math.round(seconds * 1000.0));
    }
}
