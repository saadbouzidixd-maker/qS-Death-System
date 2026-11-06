# Commands

## `/hospital set|teleport|info`

Administrative management of the single hospital operating-room spawn. No player-facing variant exists.

| Command | Permission | Description |
| --- | --- | --- |
| `/hospital set` | `qsds.hospital.set` | Stores the administrator's exact location (world, x/y/z, yaw, pitch) in SQLite. |
| `/hospital teleport` | `qsds.hospital.teleport` | Teleports the administrator to the hospital. Testing/management only. |
| `/hospital info` | `qsds.hospital.info` | Shows the stored world, coordinates, rotation and validity. |

`/hospital` with no arguments shows `info`. Tab completion: `set`, `teleport`, `info`.

If the hospital is not configured or invalid, `/hospital teleport` refuses and `/hospital info` shows the reason. Deaths still work and use the configured fallback.

## `/qsds`

Aliases: `/qsdeath`, `/deathsystem`.

| Command | Permission | Description |
| --- | --- | --- |
| `/qsds help` | — | Command overview. |
| `/qsds tool give [player]` | `qscrimescenes.police.give` | Gives a genuine police evidence shovel. Console must name a player. |
| `/qsds evidence list [filter] [page]` | `qsds.evidence.list` | Lists evidence. Filters: `active` (default), `preparing`, `processing`, `processed`, `archived`, `all`. |
| `/qsds evidence info <id>` | `qsds.evidence.info` | Full record: victim, killer, cause, coordinates, chest/head, stored stacks, XP, archive state. |
| `/qsds evidence recover <id> [player]` | `qsds.evidence.recover` | Returns an archived inventory (or a record that could not be placed) to the player. One-time only. |
| `/qsds status` | `qsds.status` | Plugin, server, Java, evidence counters, archive counters, database size, journal entries, hospital state. |
| `/qsds reload` | `qsds.reload` | Reloads `config.yml` and messages. Storage settings need a restart. |
| `/qsds debug simulate` | `qsds.debug` | Creates a diagnostic evidence site at the player's location with sample items (does not touch the player's inventory). |
| `/qsds debug reconcile` | `qsds.debug` | Retries placing all pending (`PREPARING`) evidence records. |

IDs accept `12` or `#12`. Tab completion covers subcommands, filters and online player names.

## Police interaction

1. Hold the police evidence shovel (wooden shovel with the plugin's PDC marker).
2. Right-click an evidence chest.
3. The officer receives the crime report privately.
4. The inventory is archived in SQLite and the chest, head and name display are removed immediately in the same interaction.

Ordinary players see a "sealed" message and cannot open or break the evidence. Using a normal wooden shovel does nothing.

## Console examples

```
qsds status
hospital info
qsds evidence list all 1
qsds evidence info 3
qsds evidence recover 3 Steve
qsds tool give OfficerJane
qsds debug reconcile
```
