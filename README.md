# MaceControl
plugin


## Build

Requires JDK 21 and Maven.

```bash
mvn clean package
```

The compiled jar will be at `target/macecontrol.jar`.

## Install

1. Copy `target/macecontrol.jar` into your server's `plugins/` folder.
2. Restart (or `/reload confirm` if you must, though a restart is safer).
3. Edit `plugins/MaceControl/config.yml` if you want different defaults,
   then `/macecontrol reload`, or just use the GUI.

## Usage

- `/macecontrol` (aliases `/mc`, `/macectl`) — opens the control panel GUI.
- `/macecontrol stats` — prints allowed/blocked counts to chat.
- `/macecontrol reload` — reloads `config.yml` from disk.

All three require the player to be an operator (or explicitly granted the
`macecontrol.admin` permission node by an admin who is themselves op).

## Config reference (`config.yml`)

```yaml
settings:
  strip-illegal-on-join: true
  periodic-sweep: true
  periodic-sweep-interval-seconds: 15
  notify-ops-on-block: true

enchantments:
  DENSITY:
    enabled: true
    max-level: 5
  BREACH:
    enabled: true
    max-level: 4
  # ... etc, see the shipped config.yml for the full default list
```

Add any other enchantment by its vanilla registry key (e.g. `SHARPNESS`,
`MENDING`, `UNBREAKING`) under `enchantments:` to bring it under management;
anything you don't list is blocked automatically on maces.
