package io.github.certifiedcook.xtxsmacecontrol;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;

public final class XtxsMaceControl extends JavaPlugin {
    private MaceItems items;
    private MaceRegistry registry;
    private PolicyEngine policy;
    private EnforcementService enforcement;
    private AuditService audit;
    private AdminGui gui;
    private boolean lockdown;
    private BukkitTask periodicTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
            getLogger().warning("Could not create plugin data directory.");
        }

        loadRuntimeState();
        items = new MaceItems(this);
        registry = new MaceRegistry(this);
        enforcement = new EnforcementService(this);
        audit = new AuditService(this);
        policy = new PolicyEngine(this);
        gui = new AdminGui(this);

        MaceListener listener = new MaceListener(this);
        Bukkit.getPluginManager().registerEvents(listener, this);
        Bukkit.getPluginManager().registerEvents(gui, this);

        AdminCommand adminCommand = new AdminCommand(this);
        if (getCommand("macecontrol") == null) {
            throw new IllegalStateException("macecontrol command missing from plugin.yml");
        }
        getCommand("macecontrol").setExecutor(adminCommand);
        getCommand("macecontrol").setTabCompleter(adminCommand);

        schedulePeriodicAudit();
        Bukkit.getScheduler().runTask(this, () -> Bukkit.getOnlinePlayers().forEach(audit::auditPlayer));

        getLogger().info("xtx's MaceControl enabled. Active registered maces: " + registry.activeCount()
                + ", lockdown=" + lockdown);
    }

    @Override
    public void onDisable() {
        if (periodicTask != null) periodicTask.cancel();
        if (registry != null) registry.save();
        saveRuntimeState();
    }

    public void reloadEverything() {
        reloadConfig();
        registry.load();
        schedulePeriodicAudit();
    }

    private void schedulePeriodicAudit() {
        if (periodicTask != null) periodicTask.cancel();
        if (!getConfig().getBoolean("general.periodic-audit.enabled", true)) return;

        long seconds = Math.max(1L, getConfig().getLong("general.periodic-audit.interval-seconds", 15L));
        periodicTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (getConfig().getBoolean("general.periodic-audit.audit-online-inventories", true)) {
                Bukkit.getOnlinePlayers().forEach(audit::auditPlayer);
            }
            audit.auditLoadedItemEntities();
        }, seconds * 20L, seconds * 20L);
    }

    private void loadRuntimeState() {
        File file = new File(getDataFolder(), "runtime.yml");
        if (!file.exists()) {
            lockdown = false;
            return;
        }
        lockdown = YamlConfiguration.loadConfiguration(file).getBoolean("lockdown", false);
    }

    private void saveRuntimeState() {
        File file = new File(getDataFolder(), "runtime.yml");
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("lockdown", lockdown);
        try {
            yaml.save(file);
        } catch (IOException ex) {
            getLogger().warning("Could not save runtime.yml: " + ex.getMessage());
        }
    }

    public void setLockdown(boolean lockdown) {
        this.lockdown = lockdown;
        saveRuntimeState();
    }

    public boolean isLockdown() {
        return lockdown;
    }

    public MaceItems items() { return items; }
    public MaceRegistry registry() { return registry; }
    public PolicyEngine policy() { return policy; }
    public EnforcementService enforcement() { return enforcement; }
    public AuditService audit() { return audit; }
    public AdminGui gui() { return gui; }
}
