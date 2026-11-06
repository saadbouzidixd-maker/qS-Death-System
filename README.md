# qS Death System

A production-oriented death and crime-scene evidence plugin for **Minecraft 1.21.11** on **Leaf** (Paper-compatible) servers, built for roleplay.

Derived from [GravesX](https://github.com/Legoman99573/GravesX) (by Ranull, JaySmethers and Legoman99573) and distributed under **GPL-3.0-only**. See `NOTICE` and `LICENSE`.

---

## What it does

When a player dies:

1. Their complete inventory, armour, offhand and experience are captured.
2. The inventory is written to a crash journal, then committed to SQLite.
3. Vanilla item drops are suppressed and the player inventory is cleared — after the capture is durable.
4. A vanilla **chest** with the stored items, the victim's **real player head**, and the victim's **name** above it are placed at the death location.
5. The victim respawns at the configured **hospital operating room**.

A police officer holding the custom **wooden shovel** right-clicks the evidence chest and immediately receives a private crime report (victim, killer only when known, cause, world, coordinates, date/time). In the same interaction the inventory is archived in SQLite and the chest, head and name display are removed.

There is **no public teleportation, no vehicle/transport system and no player hospital command**. Transportation is intentionally left to a separate future plugin.

---

## Requirements

| Item | Requirement |
| --- | --- |
| Server | Leaf 1.21.11 (Paper-compatible), or Paper 1.21.11 |
| Java | 21+ |
| Database | SQLite (bundled driver, no setup) |
| Other plugins | None |
| Resource pack | Not used, not required |

---

## Quick start

1. Drop `qSDeathSystem-1.0.0.jar` into `plugins/`.
2. Start the server once to generate `plugins/qSDeathSystem/config.yml`.
3. Stand in the hospital operating room and run `/hospital set`.
4. Give a police officer the tool: `/qsds tool give <player>`.
5. Grant `qscrimescenes.police` through LuckPerms (or any Bukkit permission plugin).
6. Check `/qsds status` and `/hospital info`.

Full instructions: [docs/INSTALLATION.md](docs/INSTALLATION.md)
Commands: [docs/COMMANDS.md](docs/COMMANDS.md)
Permissions: [docs/PERMISSIONS.md](docs/PERMISSIONS.md)
Audit and technical design: [docs/AUDIT-AND-DESIGN.md](docs/AUDIT-AND-DESIGN.md)
Testing results: [docs/TESTING.md](docs/TESTING.md)

---

## Evidence layout

- One vanilla **chest** at a safe block at/near the death location.
- One **player head** with the victim's captured skin, normally on top of the chest.
- A **TextDisplay** nameplate showing only the victim's name above the evidence.

A single chest holds 27 stacks. When the merged inventory needs more space, the remaining stacks are stored in SQLite as `overflow_items`. Police processing archives the chest contents **plus** the overflow, and recovery returns everything. Armour and offhand are merged into the same authoritative payload so a recovery cannot duplicate or drop them.

Evidence is locked: ordinary players cannot open, break, burn, explode, piston-move or hopper-extract it. Only the police tool removes it, and only after the archive commit succeeds.

---

## Safety properties

- Inventory is journalled to disk **before** it is removed from the player.
- SQLite writes run on a dedicated database thread; the server thread never blocks on JDBC.
- SQLite uses WAL with `synchronous=FULL`; processing and recovery use conditional, transactional updates so they can succeed only once.
- Every placement is idempotent: a crash between chest creation and the database update is repaired on restart without duplicating items.
- If the database cannot store the record, the items are returned at the death location instead of being lost.
- The police tool is validated by `PersistentDataContainer` plus a per-installation secret in `keys.yml`; display name and lore are never trusted.

---

## Build

```bash
mvn clean package
```

Output: `target/qSDeathSystem-1.0.0.jar` (shaded, self-contained SQLite driver).

Requirements: JDK 21+ and Maven 3.9+. The build targets `paper-api` 1.21.11 with `--release 21`.

```bash
mvn test        # SQLite state-machine tests
```

---

## License and attribution

qS Death System is **GPL-3.0-only**, not MIT.

It is a derivative work of GravesX (`https://github.com/Legoman99573/GravesX`), originally created by Ranull, with contributions by JaySmethers and Legoman99573. Original copyright notices are preserved. Derived source files carry an attribution header; see `NOTICE` for the list of ported components.

The bundled SQLite JDBC driver (`org.xerial:sqlite-jdbc`) is licensed under Apache-2.0.
