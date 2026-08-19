# xtx's MaceControl

A deliberately overengineered Paper plugin for **controlling the existence, acquisition, storage, transfer, enchantments, repair, damage and use of Minecraft maces**.

The GitHub-safe repository/plugin slug is `xtxsMaceControl` / `xtxs-mace-control`; the human-facing name remains **xtx's MaceControl**.

## What it controls

### Stock and scarcity
- Global active registered-mace cap.
- Optional lifetime issuance cap.
- Rolling issuance-window quota (for example, only six new maces per 24 hours).
- Per-player lifetime issuance quota.
- Per-player active possession cap.
- Per-scoreboard-team possession cap.
- Per-world online possession cap.
- Administrative issuance that can either obey or bypass stock limits.

### Provenance and anti-duplication
Every legitimate mace can be stamped with a persistent UUID serial using Bukkit `PersistentDataContainer`.

The registry stores serial, owner UUID, original recipient, issue timestamp/source, lifecycle state, transfer count, last transfer and last-seen timestamps. Duplicate serials are detected during audits and loaded-item sweeps. Unregistered legacy maces can be automatically registered or rejected by policy.

### Acquisition control
Independently allow/deny crafting, loot-generated maces, player pickup, creative acquisition and admin issuance. Acquisition can additionally be gated by permission, world, gamemode, global stock, player stock, team stock and ownership rules.

### Ownership and transfers
Optional first-owner binding, transfers, transfer cooldowns, maximum transfers per serial, same-scoreboard-team-only transfers and requiring the registered owner to be online.

### Storage control
Optional restrictions for normal containers, ender chests, hoppers, shulker boxes/bundles containing maces and maximum maces per container. Chunk-load audits can remove maces from disallowed loaded containers.

### Use control
Mace attacks can be controlled by use permission, emergency lockdown, world/gamemode, creative/spectator, flying/gliding/vehicle state, minimum/maximum fall distance, per-player cooldown, per-serial cooldown, per-target cooldown and rolling hits-per-minute rate limits.

### Damage control
Independent damage scaling, absolute maximum damage, PvP maximum, PvE maximum and maximum percentage of target max health. This lets you keep the mace mechanic without permitting routine one-shot PvP.

### Enchantments and repair
- Explicit enchantment allowlist and maximum level per enchantment.
- Optional blocking of every unlisted enchantment.
- Illegal enchantments can be sanitized instead of confiscating the mace.
- Mending can be disabled.
- Anvil repair/combine/renaming can be disabled.

### Death, auditing and enforcement
- Normal mace drops, keep-on-death, or confiscate-on-death.
- Broken/despawned serial lifecycle updates.
- Player inventory and ender-chest audits.
- Loaded dropped-item duplicate scans.
- Container audits on chunk load.
- Join audit after a configurable grace period.
- Periodic audit scheduler.
- Violation log file and runtime counters.
- Optional operator notifications.

### Emergency controls
`/macecontrol lockdown on` immediately blocks mace acquisition/use and persists across restart.

## Commands
- `/macecontrol` — GUI.
- `/macecontrol status`
- `/macecontrol reload`
- `/macecontrol lockdown <on|off|toggle>`
- `/macecontrol audit [player|all]`
- `/macecontrol inspect`
- `/macecontrol issue <player> [amount]`
- `/macecontrol confiscate <player>`
- `/macecontrol retire <serial>`
- `/macecontrol violations [player]`
- `/macecontrol help`

Aliases: `/mc`, `/macectl`, `/xtxmc`.

## Build
Requires **JDK 21** and Maven.

```bash
mvn clean package
```

Output: `target/xtxsMaceControl.jar`.

The project targets Paper API `1.21.4-R0.1-SNAPSHOT`.

## Install
1. Build or download `xtxsMaceControl.jar`.
2. Put it in the server's `plugins/` directory.
3. Start/restart the server.
4. Edit `plugins/xtxsMaceControl/config.yml`.
5. Run `/macecontrol reload`.

## Permissions
- `xtxsmacecontrol.admin`
- `xtxsmacecontrol.acquire`
- `xtxsmacecontrol.use`
- `xtxsmacecontrol.bypass.all`
- `xtxsmacecontrol.bypass.acquire`
- `xtxsmacecontrol.bypass.possession`
- `xtxsmacecontrol.bypass.use`
- `xtxsmacecontrol.bypass.cooldown`
- `xtxsmacecontrol.bypass.damage`
- `xtxsmacecontrol.bypass.enchantments`
- `xtxsmacecontrol.bypass.storage`
- `xtxsmacecontrol.bypass.ownership`
- `xtxsmacecontrol.bypass.world`

## Performance note
A server cannot perfectly count items inside **unloaded** chunks without either loading those chunks or maintaining persistent provenance. This plugin deliberately avoids force-loading the entire world. Instead it combines a persistent serial registry with audits of online inventories, loaded item entities and containers as chunks load. That is safer for server performance than brute-force world scans.
