package io.github.certifiedcook.xtxsmacecontrol;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class EnforcementService {
    private final XtxsMaceControl plugin;
    private final Map<UUID, Integer> violationsByPlayer = new ConcurrentHashMap<>();
    private final Map<String, Integer> violationsByReason = new ConcurrentHashMap<>();

    public EnforcementService(XtxsMaceControl plugin) {
        this.plugin = plugin;
    }

    public void blocked(Player player, String reason) {
        violationsByPlayer.merge(player.getUniqueId(), 1, Integer::sum);
        violationsByReason.merge(reason, 1, Integer::sum);

        if (plugin.getConfig().getBoolean("general.notify-player-on-block", true)) {
            player.sendMessage(message("messages.blocked", "&cMace action blocked: &f{reason}")
                    .replace("{reason}", reason));
        }

        if (plugin.getConfig().getBoolean("general.notify-ops-on-block", true)) {
            String alert = prefix() + ChatColor.YELLOW + player.getName() + ChatColor.GRAY + " blocked: "
                    + ChatColor.WHITE + reason;
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.isOp() || online.hasPermission("xtxsmacecontrol.admin")) online.sendMessage(alert);
            }
        }

        if (plugin.getConfig().getBoolean("general.log-violations-to-file", true)) {
            appendLog(player, reason);
        }
        plugin.getLogger().info(player.getName() + " mace violation: " + reason);
    }

    public void confiscated(Player player, ItemStack mace, String reason) {
        String serial = plugin.items().serial(mace);
        if (serial != null) plugin.registry().setStatus(serial, "CONFISCATED");
        violationsByPlayer.merge(player.getUniqueId(), 1, Integer::sum);
        violationsByReason.merge("confiscated:" + reason, 1, Integer::sum);
        player.sendMessage(message("messages.confiscated", "&cA mace was confiscated: &f{reason}")
                .replace("{reason}", reason));
        appendLog(player, "CONFISCATED: " + reason + (serial == null ? "" : " serial=" + serial));
    }

    public int violationCount(UUID player) {
        return violationsByPlayer.getOrDefault(player, 0);
    }

    public Map<String, Integer> reasons() {
        return Map.copyOf(violationsByReason);
    }

    public String prefix() {
        return color(plugin.getConfig().getString("messages.prefix", "&8[&6xtx's MaceControl&8] &7"));
    }

    public String message(String path, String fallback) {
        return prefix() + color(plugin.getConfig().getString(path, fallback));
    }

    public String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }

    private void appendLog(Player player, String reason) {
        File file = new File(plugin.getDataFolder(), "violations.log");
        try (FileWriter writer = new FileWriter(file, true)) {
            writer.write(Instant.now() + "\t" + player.getUniqueId() + "\t" + player.getName() + "\t" + reason + "\n");
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not append violations.log: " + ex.getMessage());
        }
    }
}
