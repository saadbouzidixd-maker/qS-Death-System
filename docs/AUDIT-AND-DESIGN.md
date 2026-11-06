# Stage 1 — GravesX repository audit

Audited repository: `https://github.com/Legoman99573/GravesX` (shallow clone at the start of this project).

## 1.1 Project facts

| Item | Finding |
| --- | --- |
| Source size | 319 Java files, 67,594 lines |
| Build system | Maven (`pom.xml`), shade plugin, `finalName = GravesX-<version>` |
| Compiler target | Java 17 (`<java.version>17</java.version>`) |
| Server APIs | `org.spigotmc:spigot-api:26.1.2-R0.1-SNAPSHOT` (provided) and `io.papermc.paper:paper-api:[26.1.2.build,)` (provided) |
| Plugin metadata | `api-version: 1.13`, `folia-supported: true`, `load: POSTWORLD` |
| License | GNU GPL v3 (`LICENSE` = GPL-3.0 text); project is GPL-3.0-only in practice |
| Runtime libraries | Libby downloads dependencies at runtime; `libs/` also ships patched authlib, PlayerNPC, FurnitureEngine, libby, minitranslator |
| Databases | SQLite, H2, MySQL, MariaDB, PostgreSQL, SQL Server through HikariCP |
| Integrations | 40+ soft dependencies (Vault, ProtocolLib, PlaceholderAPI, Oraxen, ItemsAdder, Nexo, CraftEngine, CoreProtect, Skript, SkyllTextureAPI, PlayerNPC, FancyNPCs, Floodgate, LuckPerms, …) |

## 1.2 Architecture

GravesX is a general-purpose "grave" plugin, not a roleplay evidence plugin. Its main components are:

- `com.ranull.graves.Graves` — plugin lifecycle, 1,666 lines, wires ~25 managers.
- `GraveManager` (3,060 lines) — grave creation, placement, provider selection, expiry.
- `DataManager` (4,011 lines) — YAML caches plus multi-database persistence; large SQL surface.
- `EntityManager` (1,648 lines) — grave access, compass, teleport, menu interactions.
- `type/Grave` (826 lines) — mutable grave model, serialisable, holds inventory/equipment/location/killer/experience.
- `BlockManager` — block placement/replacement with origin-block restore support.
- `HologramManager` + `TextDisplayManager` — TextDisplay (1.19+) / ArmorStand holograms.
- `ItemStackManager` — grave head item, obituary book, GUI items.
- `util/InventoryUtil` + `util/Base64Util` — Bukkit-object-stream + Base64 item serialisation.
- `util/ExperienceUtil` — total experience calculation.
- `compatibility/CompatibilityBlockData`, `SkinTextureUtil_post_1_21_9` — skull texture/profile handling.
- `PlayerDeathListener`, `EntityDeathListener` — death capture, corpse/grave creation, compass handling.

## 1.3 Reusable components (used by qS Death System)

| GravesX component | Use in qS Death System |
| --- | --- |
| `util/ExperienceUtil` | Ported to `dev.qsdeath.util.ExperienceUtil` (total XP, levels). |
| `util/Base64Util` + `util/InventoryUtil` serialisation approach | Ported idea to `dev.qsdeath.util.ItemCodec` (explicit null markers per slot, Base64, Bukkit object streams). |
| `SkinTextureUtil` / `SkinTextureUtil_post_1_21_9` | Approach ported to `dev.qsdeath.display.HeadService`: capture the profile `textures` property at death, apply a Paper `PlayerProfile` with signed property, fall back to the skin URL, then offline-player lookup. |
| `TextDisplayManager` hologram technique | Ported to `dev.qsdeath.display.NameplateService` (persistent TextDisplay, PDC tagging, billboard CENTER, no background). |
| Death capture ordering ideas (capture list before items are removed, store owner texture/name/experience on the record) | Reused conceptually in `DeathService` and the `evidence` table. |
| Block replacement caution (never destroy unrelated blocks) | Reused in `EvidenceLocator`: only air-like/small plants/snow are replaced. |

## 1.4 Compatibility findings for 1.21.11 / Java 21

1. **Java level.** GravesX targets Java 17. It can run on Java 21, but a fork would need a compiler upgrade to 21 for this delivery. qS Death System targets release 21.
2. **API drift.** GravesX HEAD depends on `spigot-api 26.1.2-R0.1-SNAPSHOT` and `paper-api [26.1.2.build,)`. Those were not resolvable/verifiable during this audit; the repository is tracking a newer API line than the stable `1.21.11-R0.1-SNAPSHOT` used here. Building the entire GravesX tree against 1.21.11 was therefore not attempted; the reused components were re-implemented against the stable 1.21.11 Paper API and compiled/tested directly.
3. **Runtime downloads.** GravesX's Libby dependency downloads libraries when the server starts. That conflicts with "works independently on a clean Leaf server with no external plugins" and complicates reproducible production deployment.
4. **Feature conflict.** GravesX includes grave compasses, grave teleportation/`/graves tp`, grave GUIs, grave looting menus, expiry/abandonment, corpse providers, NPC/Furniture providers and multi-database support. The specification explicitly forbids public teleportation and requires police-only evidence removal with SQLite-only archival. Carrying these features would either violate the spec or leave disabled attack surface.
5. **No police/evidence concepts.** GravesX has no player-name display-only requirement, no police tool, no crime report, no archive/recovery workflow, no hospital transfer.
6. **Folia claim.** GravesX declares `folia-supported: true`. qS Death System does **not** claim Folia support; only Paper/Leaf 1.21.11 behaviour is implemented and tested.
7. **Skull API.** On 1.21.9+ Paper, `Skull#setProfile(ResolvableProfile)` exists alongside `setPlayerProfile` and `setOwnerProfile`. The implementation uses the Paper `PlayerProfile` + `ProfileProperty("textures", value, signature)` path (signed skin), with URL/offline fallbacks.

## 1.5 Decision

A wholesale fork would import a 67k-line multi-feature plugin whose public teleport/GUI/database behaviour contradicts the specification, plus runtime downloads and unsupported version drift. The delivery is therefore a **focused derivative** that preserves the GPL-3.0-only license and attribution, reuses the proven GravesX techniques listed above, and implements the roleplay workflow (evidence, police, archive, hospital) from scratch on the stable 1.21.11 API.

---

# Stage 2 — Technical design

## 2.1 Module layout

```
dev.qsdeath
├── QSDeathSystem            plugin lifecycle, service wiring, startup recovery
├── config/PluginConfig      typed config + messages
├── death/
│   ├── DeathListener        PlayerDeathEvent @ HIGHEST
│   ├── DeathService         capture → journal → SQLite → clear → place
│   ├── InventorySnapshot    inventory/armour/offhand/XP copy
│   └── RespawnListener      automatic hospital respawn
├── evidence/
│   ├── EvidenceRecord       Bukkit-free record DTO
│   ├── EvidenceStatus       PREPARING → ACTIVE → PROCESSING → PROCESSED
│   ├── EvidenceLocator      safe chest/head placement search
│   ├── EvidenceRenderer     chest + head + nameplate creation/removal
│   ├── EvidenceIndex        chest/head → record map
│   ├── EvidenceProtectionListener  locks, explosions, pistons, hoppers
│   └── RecoveryService      archive recovery with one-shot claim
├── display/
│   ├── SkinData             death-time skin capture
│   ├── HeadService          real player head with captured skin
│   └── NameplateService     persistent TextDisplay name
├── hospital/HospitalManager hospital storage, validation, respawn transfer
├── police/
│   ├── PoliceTool           PDC + per-install secret wooden shovel
│   ├── PoliceService        archive → report → remove
│   └── CrimeReport          configurable private report
├── storage/
│   ├── Storage              SQLite schema/DAO, single DB thread, transactions
│   ├── EvidenceJournal      write-ahead crash journal (Properties, atomic move)
│   ├── ArchiveRecord        archive DTO
│   ├── HospitalRecord       hospital DTO
│   └── StorageStats         status counters
├── util/
│   ├── ItemCodec            Base64 Bukkit item serialisation
│   ├── ExperienceUtil       GravesX-derived XP maths
│   ├── BlockKey             world-scoped block coordinates
│   ├── Scheduler            sync/later/async facade
│   └── TimeUtil             report date/time formatting
└── command/
    ├── HospitalCommand      /hospital set|teleport|info
    └── QSDSCommand          /qsds tool|evidence|status|reload|debug
```

## 2.2 Database schema (SQLite)

`evidence`

| Column group | Columns |
| --- | --- |
| Identity | `id` PK autoincrement, `evidence_uuid` UNIQUE |
| Victim | `victim_uuid`, `victim_name` |
| Death | `death_time`, `world_uuid`, `world_name`, `death_x/y/z`, `death_yaw/pitch`, `death_cause`, `killer_uuid`, `killer_name` |
| Lifecycle | `status` (`PREPARING`/`ACTIVE`/`PROCESSING`/`PROCESSED`), `created_at`, `updated_at`, `last_error` |
| World evidence | `chest_world_uuid`, `chest_world`, `chest_x/y/z`, `head_world_uuid`, `head_world`, `head_x/y/z`, `nameplate_uuid` |
| Inventory | `chest_items` (authoritative merged Base64 payload), `overflow_items` (not in chest), `armor_items`, `offhand_item` (audit copies) |
| Experience | `exp_total`, `exp_level` |
| Skin | `skin_value`, `skin_signature`, `skin_url` |
| Processing | `processed_by_uuid`, `processed_by_name`, `processed_at` |

`evidence_archive` — `evidence_id` PK/FK, `archived_at`, `archived_by_uuid/name`, `chest_items`, `overflow_items`, `armor_items`, `offhand_item`, `exp_total`, `exp_level`, `item_count`, `recovered`, `recovered_at`, `recovered_by_uuid/name`.

`hospital` — single row (`id = 1`) with `world_uuid`, `world_name`, `x/y/z`, `yaw`, `pitch`, `updated_at`.

`schema_info` — schema version.

Indexes: evidence status, victim UUID, chest coordinates, death time, archive recovered flag.

All JDBC work runs on one dedicated thread with a single connection. Mutations spanning statements use transactions. SQLite runs in WAL mode with `synchronous=FULL`, `foreign_keys=ON` and a configurable busy timeout.

## 2.3 Death lifecycle

1. `PlayerDeathEvent` at `HIGHEST` (ignored if cancelled by another plugin).
2. Copy storage (36), armour (4), offhand, total XP and level into memory.
3. Capture victim profile `textures` property and skin URL.
4. Resolve cause/killer: `player.getKiller()`, then last `EntityDamageByEntityEvent` (direct player or player projectile). Cause is `PLAYER` only when a player killer is known; otherwise the Bukkit damage cause. No killer is invented.
5. Choose placement (exact death block first, then up/sideways/down). Adjacent chests are rejected to prevent double-chest merging.
6. Build the record (merged inventory payload) and write `journal/<uuid>.properties` synchronously with atomic move.
7. Clear vanilla drops, set dropped XP to 0.
8. Clear player inventory/armour/offhand/XP (the snapshot is durable in the journal).
9. Commit the record to SQLite asynchronously.
10. On commit: delete the journal entry, then place chest/head/nameplate on the server thread and mark `ACTIVE`.
11. On database failure: delete the journal copy and drop the captured items at the death location. Nothing is lost.
12. On respawn: `PlayerRespawnEvent` sets the hospital location (or the configured safe fallback).

## 2.4 Evidence creation lifecycle (crash-safe)

- Record is committed as `PREPARING` with the intended chest/head coordinates.
- Placement fills the chest only with missing stacks (payload minus existing), so a partial or repeated placement is idempotent.
- On success the record becomes `ACTIVE` with real coordinates and overflow.
- Startup: replay journal → insert (idempotent by `evidence_uuid`) → index `ACTIVE` → re-place `PREPARING` → complete `PROCESSING` removals.

## 2.5 Police removal lifecycle

1. Interaction validated server-side: right-click block, genuine tool (PDC token + version), `qscrimescenes.police`, record `ACTIVE`, in-memory claim acquired.
2. Archive payload built from the live chest plus overflow; if the chest is missing, the stored payload is archived.
3. Transaction: conditional `status ACTIVE → PROCESSING` + archive insert. Failure or zero rows means another process already claimed it; no report, no removal.
4. Report built privately and sent only to that officer; a line containing `%killer%` is omitted when no killer is known.
5. Chest inventory cleared, chest/head blocks removed, nameplate entity removed; index updated.
6. `PROCESSING → PROCESSED`. If removal throws, retries occur; otherwise the next start completes the removal because the archive is already durable.
7. Recovery is staff-only and claims with `UPDATE ... WHERE recovered = 0`, so an archive can be returned exactly once.

## 2.6 Hospital lifecycle

- `/hospital set` stores the administrator's exact position (world UUID + name, x/y/z, yaw, pitch) in SQLite, transactionally.
- `/hospital teleport` and `/hospital info` are administrative only. There is no player command.
- On death the player respawns at the hospital. If missing/invalid, the console warns and the configured fallback is used (`world-spawn` by default, or vanilla respawn). The player and their stored inventory are never lost.

## 2.7 Security mechanisms

| Threat | Control |
| --- | --- |
| Fake wooden shovel | `PersistentDataContainer` token from `keys.yml` + version marker; material check; permission check on every use |
| Unauthorised removal | Server-side permission; evidence index lookup; conditional SQL claim |
| Unauthorised recovery | `qsds.evidence.recover`; one-shot archive claim |
| Duplicate processing | In-memory in-flight set + `status='ACTIVE'` conditional update |
| Duplicate recovery | `recovered = 0` conditional update |
| Chest theft/opening | Interaction denied, `InventoryOpenEvent` cancelled, breaks/explosions/pistons/hoppers blocked, adjacent chest placement blocked |
| Killer leak | Killer only in the private report and admin inspection; never broadcast |
| Fabricated records | No client input is trusted; records are created from server death events only |
| Item loss on crash | Journal before removal, WAL + FULL sync, idempotent insert, startup replay |
| Console tampering | All DB access uses bound parameters; no SQL string is built from player input |

## 2.8 Threading model

- Server thread: events, block/entity changes, messages, inventory mutations.
- Single SQLite thread: all JDBC.
- Startup recovery thread: journal replay and initial queries; world work is scheduled back to the server thread.
- No repeating tasks. Only one-shot retries for placement/removal.
