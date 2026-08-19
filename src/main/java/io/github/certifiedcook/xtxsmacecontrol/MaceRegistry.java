package io.github.certifiedcook.xtxsmacecontrol;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.logging.Level;

public final class MaceRegistry {
    private final XtxsMaceControl plugin;
    private final File file;
    private final Map<String, MaceRecord> records = new HashMap<>();
    private final Map<UUID, Long> lastPlayerUse = new HashMap<>();
    private final Map<String, Long> lastMaceUse = new HashMap<>();
    private final Map<String, Long> lastTargetUse = new HashMap<>();

    public MaceRegistry(XtxsMaceControl plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "registry.yml");
        load();
    }

    public synchronized void load() {
        records.clear();
        if (!file.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("maces");
        if (root == null) return;
        for (String serial : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(serial);
            if (s == null) continue;
            UUID owner = parseUuid(s.getString("owner"));
            UUID issuedTo = parseUuid(s.getString("issued-to"));
            if (issuedTo == null) issuedTo = owner;
            records.put(serial, new MaceRecord(
                    serial,
                    owner,
                    issuedTo,
                    s.getLong("issued-at"),
                    s.getString("issued-to-name", ""),
                    s.getString("source", "UNKNOWN"),
                    s.getString("status", "ACTIVE"),
                    s.getInt("transfers"),
                    s.getLong("last-transfer-at"),
                    s.getLong("last-seen-at")
            ));
        }
    }

    public synchronized void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (MaceRecord record : records.values()) {
            String path = "maces." + record.serial();
            yaml.set(path + ".owner", record.owner() == null ? null : record.owner().toString());
            yaml.set(path + ".issued-to", record.issuedTo() == null ? null : record.issuedTo().toString());
            yaml.set(path + ".issued-at", record.issuedAt());
            yaml.set(path + ".issued-to-name", record.issuedToName());
            yaml.set(path + ".source", record.source());
            yaml.set(path + ".status", record.status());
            yaml.set(path + ".transfers", record.transfers());
            yaml.set(path + ".last-transfer-at", record.lastTransferAt());
            yaml.set(path + ".last-seen-at", record.lastSeenAt());
        }
        try {
            yaml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "Could not save registry.yml", ex);
        }
    }

    public synchronized MaceRecord get(String serial) {
        return serial == null ? null : records.get(serial);
    }

    public synchronized MaceRecord register(ItemStack mace, Player owner, String source) {
        String serial = plugin.items().serial(mace);
        if (serial == null) {
            serial = UUID.randomUUID().toString();
            plugin.items().setSerial(mace, serial);
        }

        long now = System.currentTimeMillis();
        MaceRecord existing = records.get(serial);
        if (existing != null) {
            records.put(serial, existing.seen(now));
            return records.get(serial);
        }

        UUID ownerUuid = owner == null ? null : owner.getUniqueId();
        MaceRecord record = new MaceRecord(
                serial,
                ownerUuid,
                ownerUuid,
                now,
                owner == null ? "" : owner.getName(),
                source,
                "ACTIVE",
                0,
                now,
                now
        );
        records.put(serial, record);
        save();
        return record;
    }

    public synchronized void transfer(String serial, UUID newOwner) {
        MaceRecord record = records.get(serial);
        if (record == null) return;
        long now = System.currentTimeMillis();
        records.put(serial, record.withOwner(newOwner, record.transfers() + 1, now));
        save();
    }

    public synchronized void setStatus(String serial, String status) {
        MaceRecord record = records.get(serial);
        if (record == null) return;
        records.put(serial, record.withStatus(status, System.currentTimeMillis()));
        save();
    }

    public synchronized int activeCount() {
        return (int) records.values().stream().filter(r -> "ACTIVE".equalsIgnoreCase(r.status())).count();
    }

    public synchronized int issuedEver() {
        return records.size();
    }

    public synchronized int issuedSince(long cutoff) {
        return (int) records.values().stream().filter(r -> r.issuedAt() >= cutoff).count();
    }

    public synchronized int issuedTo(UUID uuid) {
        return (int) records.values().stream().filter(r -> uuid.equals(r.issuedTo())).count();
    }

    public synchronized Collection<MaceRecord> all() {
        return List.copyOf(records.values());
    }

    public long playerUse(UUID player) {
        return lastPlayerUse.getOrDefault(player, 0L);
    }

    public long maceUse(String serial) {
        return lastMaceUse.getOrDefault(serial, 0L);
    }

    public long targetUse(UUID attacker, UUID target) {
        return lastTargetUse.getOrDefault(attacker + ":" + target, 0L);
    }

    public void markUse(UUID player, String serial, UUID target) {
        long now = System.currentTimeMillis();
        lastPlayerUse.put(player, now);
        if (serial != null) lastMaceUse.put(serial, now);
        if (target != null) lastTargetUse.put(player + ":" + target, now);
    }

    private static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
