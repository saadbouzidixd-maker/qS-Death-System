# Testing results

## Environment

| Item | Value |
| --- | --- |
| Server | Paper 1.21.11 build 132 (`1.21.11-132-c5eb079`), Leaf-compatible API |
| Runtime | Java 25.0.3 (plugin compiled with `--release 21`) |
| Client | Mineflayer 4.39.0, protocol 774 (1.21.11), offline mode |
| Database | SQLite via bundled `sqlite-jdbc` 3.54.1.0 |
| OS | Windows 11 |
| Plugins | qS Death System only |

Leaf itself was not installed; Paper 1.21.11 was used because Leaf is Paper-compatible and the plugin only uses the Paper/Bukkit API. Folia was not tested and is not claimed.

## Automated tests (`mvn test`)

7 tests, 0 failures, 0 errors in `dev.qsdeath.storage.StorageTest`:

- evidence insert is idempotent by evidence UUID
- full record round trip (victim, coordinates, killer, payload, experience)
- `ACTIVE` counting and listing
- `beginProcessing` succeeds exactly once; second claim fails; `finishProcessing` transitions to `PROCESSED` once
- archive recovery claim succeeds exactly once
- hospital location round trip
- missing records return safely
- statistics reflect active/processing/archive counts

The tests caught one real defect during development: the evidence `INSERT` had 36 placeholders for 37 columns. It was fixed by generating the placeholder list from the column count, and the suite then passed.

## Live end-to-end tests

Two operator bots (`TestVictim`, `TestKiller`) and one unprivileged bot (`TestCivilian`) played on the real server.

| # | Specification test | Result | Evidence |
| --- | --- | --- | --- |
| 1 | Player death detected | PASS | Two real deaths (environmental `/kill` and player damage) produced evidence |
| 2 | Complete inventory preserved | PASS | 4-stack inventory fully recovered; 36-stack test preserved all 36 stacks |
| 3 | Armour and offhand preserved | PASS | Diamond chestplate and shield returned by recovery |
| 4 | Experience preserved | PASS | 30 levels granted; recovery restored level 30 |
| 5 | Chest creation | PASS | Chest block at the death block; coordinates in record |
| 6 | Real player skin | PARTIAL | Player head block created with the captured-profile code path; the offline-mode bots have no Mojang textures, so an actual skin texture was not verified. Online-mode capture is implemented but untested |
| 7 | Victim name display | PASS | `TextDisplay` metadata contained exactly `TestVictim`; no other text |
| 8 | Automatic hospital transfer | PASS | Respawned at the configured (100.5, −60, 100.5) |
| 9 | Hospital commands | PASS | `/hospital set`, `info`, `teleport` worked; location persisted across restart with yaw 90.0 / pitch 29.3 |
| 10 | Police permission checks | PASS | Civilian got "no permission" for hospital teleport, status and evidence list |
| 11 | Police shovel identification | PASS | Ordinary wooden shovel only produced "sealed"; genuine PDC tool processed the scene |
| 12 | Crime report output | PASS | Victim, cause, world, coordinates, date, time and evidence ID |
| 13 | Private killer identification | PASS | `Killer: TestKiller` appeared only in the officer's chat; the killer bot received no report |
| 14 | Evidence removal | PASS | Chest, head and name display removed in the same interaction; chest block became air |
| 15 | SQLite archival | PASS | Archive stored before removal; 4-stack and 36-stack archives; status `PROCESSED` |
| 16 | Inventory recovery | PASS | Items, armour, offhand and XP returned; second recovery rejected as already recovered |
| 17 | Server restart persistence | PASS | Active chest, head and nameplate present after restart; hospital still valid |
| 18 | Crash recovery | PASS | Journal-only record replayed; pending record placed; interrupted `PROCESSING` record removed and marked processed |
| 19 | Duplicate processing prevention | PASS | Conditional SQL claim unit-tested; duplicate recovery rejected; in-memory click guard |
| 20 | Missing hospital configuration | PASS | Console warning logged; player respawned at the world-spawn fallback; evidence still created |

Additional checks:

- **Overflow:** a 36-stack inventory was archived and recovered completely (27 in the chest, 9 in SQLite). One stack was dropped at the player's feet because the police tool occupied an inventory slot; this is the configured safe behaviour for leftovers, and no item was lost.
- **Evidence lock:** civilians receive "sealed" and the chest remains; chest opening is cancelled.
- **Restart recovery blocks:** the pending chest was present at (60, −60, 60), the interrupted processing chest was absent at (70, −60, 70), and the journal record's chest was present at (40, −60, 40).
- **Nameplate persistence:** entity metadata showed `{ type: 'string', value: 'TestVictim' }` after restart.
- **No exceptions:** the server log contained no plugin exceptions during the test run. One test death was caused by a world-generated slime before difficulty was set to peaceful; that is unrelated to the plugin.

## Not tested / limitations

1. **Real Mojang skin.** The test clients were offline-mode and therefore had no skin textures. The head block, profile capture, signed-property application and offline/URL fallbacks are implemented, but an online-mode skin was not observed in the world.
2. **Folia.** Not supported and not tested (`folia-supported: false`).
3. **Leaf.** Paper 1.21.11 was used; Leaf was not installed.
4. **LuckPerms.** Permissions use the standard Bukkit API; LuckPerms was not installed for the test.
5. **MySQL/PostgreSQL.** Not implemented; SQLite only, as specified.
6. **Resource packs.** None used.

## Reproducing

```bash
mvn test        # 7 SQLite state-machine tests
mvn package     # builds target/qSDeathSystem-1.0.0.jar
```

The live test harness (two bots, SQL state injection) lives in the ignored `.tooling/` directory and is not part of the delivered plugin.
