/*
 * qS Death System
 * Copyright (C) qS Death System contributors.
 *
 * This file is part of a derivative work of GravesX, GPL-3.0-only.
 */
package dev.qsdeath.storage;

import dev.qsdeath.evidence.EvidenceRecord;
import dev.qsdeath.evidence.EvidenceStatus;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * SQLite persistence for evidence, archives and the hospital location.
 *
 * <p>All database work runs on a single dedicated thread. No JDBC call is ever
 * made on the server thread after startup, which keeps the main tick loop free
 * of disk I/O. Every mutation that spans multiple statements is wrapped in a
 * transaction, and processing/archival plus recovery claims use conditional
 * updates so the same record can never be consumed twice.</p>
 */
public final class Storage {

    private static final int SCHEMA_VERSION = 1;

    private static final String EVIDENCE_COLUMNS = String.join(", ",
            "id", "evidence_uuid", "victim_uuid", "victim_name", "death_time",
            "world_uuid", "world_name", "death_x", "death_y", "death_z", "death_yaw", "death_pitch",
            "death_cause", "killer_uuid", "killer_name", "status",
            "chest_world_uuid", "chest_world", "chest_x", "chest_y", "chest_z",
            "head_world_uuid", "head_world", "head_x", "head_y", "head_z", "nameplate_uuid",
            "chest_items", "overflow_items", "armor_items", "offhand_item",
            "exp_total", "exp_level", "skin_value", "skin_signature", "skin_url",
            "created_at", "updated_at", "last_error",
            "processed_by_uuid", "processed_by_name", "processed_at");

    private static final String INSERT_EVIDENCE_SQL = "INSERT INTO evidence ("
            + "evidence_uuid, victim_uuid, victim_name, death_time, world_uuid, world_name, "
            + "death_x, death_y, death_z, death_yaw, death_pitch, death_cause, killer_uuid, killer_name, status, "
            + "chest_world_uuid, chest_world, chest_x, chest_y, chest_z, head_world_uuid, head_world, "
            + "head_x, head_y, head_z, chest_items, overflow_items, armor_items, offhand_item, "
            + "exp_total, exp_level, skin_value, skin_signature, skin_url, created_at, updated_at, last_error"
            + ") VALUES (" + placeholders(37) + ") ON CONFLICT(evidence_uuid) DO NOTHING";

    private static final String ARCHIVE_COLUMNS = String.join(", ",
            "evidence_id", "archived_at", "archived_by_uuid", "archived_by_name",
            "chest_items", "overflow_items", "armor_items", "offhand_item",
            "exp_total", "exp_level", "item_count", "recovered", "recovered_at",
            "recovered_by_uuid", "recovered_by_name");

    private final File databaseFile;
    private final int busyTimeoutMs;
    private final Logger logger;
    private final boolean debug;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "qSDeathSystem-SQLite");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private Connection connection;

    public Storage(File databaseFile, int busyTimeoutMs, Logger logger, boolean debug) {
        this.databaseFile = databaseFile;
        this.busyTimeoutMs = busyTimeoutMs;
        this.logger = logger;
        this.debug = debug;
    }

    /**
     * Opens the database and creates the schema. Called once during plugin
     * startup; never during gameplay.
     *
     * @throws SQLException when the database cannot be opened
     */
    public void initialize() throws SQLException {
        File parent = databaseFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new SQLException("Could not create directory " + parent.getAbsolutePath());
        }

        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException exception) {
            throw new SQLException("SQLite JDBC driver is missing from the plugin jar", exception);
        }

        connection = DriverManager.getConnection(
                "jdbc:sqlite:" + databaseFile.getAbsolutePath() + "?busy_timeout=" + busyTimeoutMs);

        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=FULL");
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA busy_timeout=" + busyTimeoutMs);
        }

        createSchema();
        logger.info("SQLite storage ready: " + databaseFile.getAbsolutePath());
    }

    private void createSchema() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS schema_info (
                        key TEXT PRIMARY KEY,
                        value TEXT NOT NULL
                    )""");

            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS evidence (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        evidence_uuid TEXT NOT NULL UNIQUE,
                        victim_uuid TEXT NOT NULL,
                        victim_name TEXT NOT NULL,
                        death_time INTEGER NOT NULL,
                        world_uuid TEXT,
                        world_name TEXT NOT NULL,
                        death_x REAL NOT NULL,
                        death_y REAL NOT NULL,
                        death_z REAL NOT NULL,
                        death_yaw REAL NOT NULL DEFAULT 0,
                        death_pitch REAL NOT NULL DEFAULT 0,
                        death_cause TEXT NOT NULL,
                        killer_uuid TEXT,
                        killer_name TEXT,
                        status TEXT NOT NULL,
                        chest_world_uuid TEXT,
                        chest_world TEXT,
                        chest_x INTEGER,
                        chest_y INTEGER,
                        chest_z INTEGER,
                        head_world_uuid TEXT,
                        head_world TEXT,
                        head_x INTEGER,
                        head_y INTEGER,
                        head_z INTEGER,
                        nameplate_uuid TEXT,
                        chest_items TEXT NOT NULL DEFAULT '',
                        overflow_items TEXT NOT NULL DEFAULT '',
                        armor_items TEXT NOT NULL DEFAULT '',
                        offhand_item TEXT NOT NULL DEFAULT '',
                        exp_total INTEGER NOT NULL DEFAULT 0,
                        exp_level INTEGER NOT NULL DEFAULT 0,
                        skin_value TEXT,
                        skin_signature TEXT,
                        skin_url TEXT,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL,
                        last_error TEXT,
                        processed_by_uuid TEXT,
                        processed_by_name TEXT,
                        processed_at INTEGER
                    )""");

            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_evidence_status ON evidence(status)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_evidence_victim ON evidence(victim_uuid)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_evidence_chest ON evidence(chest_world_uuid, chest_x, chest_y, chest_z)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_evidence_death_time ON evidence(death_time)");

            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS evidence_archive (
                        evidence_id INTEGER PRIMARY KEY,
                        archived_at INTEGER NOT NULL,
                        archived_by_uuid TEXT,
                        archived_by_name TEXT,
                        chest_items TEXT NOT NULL DEFAULT '',
                        overflow_items TEXT NOT NULL DEFAULT '',
                        armor_items TEXT NOT NULL DEFAULT '',
                        offhand_item TEXT NOT NULL DEFAULT '',
                        exp_total INTEGER NOT NULL DEFAULT 0,
                        exp_level INTEGER NOT NULL DEFAULT 0,
                        item_count INTEGER NOT NULL DEFAULT 0,
                        recovered INTEGER NOT NULL DEFAULT 0,
                        recovered_at INTEGER,
                        recovered_by_uuid TEXT,
                        recovered_by_name TEXT,
                        FOREIGN KEY (evidence_id) REFERENCES evidence(id)
                    )""");

            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_archive_recovered ON evidence_archive(recovered)");

            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS hospital (
                        id INTEGER PRIMARY KEY CHECK (id = 1),
                        world_uuid TEXT NOT NULL,
                        world_name TEXT NOT NULL,
                        x REAL NOT NULL,
                        y REAL NOT NULL,
                        z REAL NOT NULL,
                        yaw REAL NOT NULL,
                        pitch REAL NOT NULL,
                        updated_at INTEGER NOT NULL
                    )""");
        }

        try (PreparedStatement select = connection.prepareStatement(
                "SELECT value FROM schema_info WHERE key = 'schema_version'");
             ResultSet result = select.executeQuery()) {

            if (result.next()) {
                int found = Integer.parseInt(result.getString(1));
                if (found != SCHEMA_VERSION) {
                    logger.warning("Database schema version " + found + " differs from expected "
                            + SCHEMA_VERSION + "; the plugin will only use known columns.");
                }
                return;
            }
        }

        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO schema_info (key, value) VALUES ('schema_version', ?)")) {
            insert.setString(1, String.valueOf(SCHEMA_VERSION));
            insert.executeUpdate();
        }
    }

    /**
     * Inserts a new evidence record, or returns the existing id when the
     * evidence UUID was already committed (crash recovery replay).
     *
     * @param record record
     * @return future with the database id
     */
    public CompletableFuture<Long> insertEvidence(EvidenceRecord record) {
        return submitTransaction("insert evidence " + record.evidenceUuid, database -> {
            try (PreparedStatement statement = database.prepareStatement(INSERT_EVIDENCE_SQL)) {
                int index = 1;
                statement.setString(index++, record.evidenceUuid);
                statement.setString(index++, record.victimUuid == null ? "" : record.victimUuid.toString());
                statement.setString(index++, record.victimName);
                statement.setLong(index++, record.deathTime);
                statement.setString(index++, uuid(record.worldUuid));
                statement.setString(index++, record.worldName);
                statement.setDouble(index++, record.deathX);
                statement.setDouble(index++, record.deathY);
                statement.setDouble(index++, record.deathZ);
                statement.setDouble(index++, record.deathYaw);
                statement.setDouble(index++, record.deathPitch);
                statement.setString(index++, record.deathCause);
                statement.setString(index++, uuid(record.killerUuid));
                statement.setString(index++, record.killerName);
                statement.setString(index++, record.status);
                statement.setString(index++, uuid(record.chestWorldUuid));
                statement.setString(index++, record.chestWorldName);
                setNullableInt(statement, index++, record.chestX);
                setNullableInt(statement, index++, record.chestY);
                setNullableInt(statement, index++, record.chestZ);
                statement.setString(index++, uuid(record.headWorldUuid));
                statement.setString(index++, record.headWorldName);
                setNullableInt(statement, index++, record.headX);
                setNullableInt(statement, index++, record.headY);
                setNullableInt(statement, index++, record.headZ);
                statement.setString(index++, record.chestItems);
                statement.setString(index++, record.overflowItems);
                statement.setString(index++, record.armorItems);
                statement.setString(index++, record.offhandItem);
                statement.setInt(index++, record.expTotal);
                statement.setInt(index++, record.expLevel);
                statement.setString(index++, record.skinValue);
                statement.setString(index++, record.skinSignature);
                statement.setString(index++, record.skinUrl);
                statement.setLong(index++, record.createdAt);
                statement.setLong(index++, record.updatedAt);
                statement.setString(index++, record.lastError);
                statement.executeUpdate();
            }

            try (PreparedStatement select = database.prepareStatement(
                    "SELECT id FROM evidence WHERE evidence_uuid = ?")) {
                select.setString(1, record.evidenceUuid);
                try (ResultSet result = select.executeQuery()) {
                    if (!result.next()) {
                        throw new SQLException("Evidence record was not stored: " + record.evidenceUuid);
                    }
                    return result.getLong(1);
                }
            }
        });
    }

    /**
     * Marks a record as placed in the world.
     *
     * @param record record containing chest, head, nameplate and overflow data
     * @return completion future
     */
    public CompletableFuture<Void> markActive(EvidenceRecord record) {
        return submit("mark evidence active " + record.id, database -> {
            String sql = """
                    UPDATE evidence SET
                        status = 'ACTIVE',
                        chest_world_uuid = ?, chest_world = ?, chest_x = ?, chest_y = ?, chest_z = ?,
                        head_world_uuid = ?, head_world = ?, head_x = ?, head_y = ?, head_z = ?,
                        nameplate_uuid = ?, chest_items = ?, overflow_items = ?, updated_at = ?,
                        last_error = NULL
                    WHERE id = ?""";

            try (PreparedStatement statement = database.prepareStatement(sql)) {
                int index = 1;
                statement.setString(index++, uuid(record.chestWorldUuid));
                statement.setString(index++, record.chestWorldName);
                setNullableInt(statement, index++, record.chestX);
                setNullableInt(statement, index++, record.chestY);
                setNullableInt(statement, index++, record.chestZ);
                statement.setString(index++, uuid(record.headWorldUuid));
                statement.setString(index++, record.headWorldName);
                setNullableInt(statement, index++, record.headX);
                setNullableInt(statement, index++, record.headY);
                setNullableInt(statement, index++, record.headZ);
                statement.setString(index++, uuid(record.nameplateUuid));
                statement.setString(index++, record.chestItems);
                statement.setString(index++, record.overflowItems);
                statement.setLong(index++, System.currentTimeMillis());
                statement.setLong(index, record.id);
                statement.executeUpdate();
            }
            return null;
        });
    }

    /**
     * Updates the planned chest/head coordinates during a placement retry.
     *
     * @param id             evidence id
     * @param chestWorldUuid chest world
     * @param chestWorldName chest world name
     * @param chestX         chest x
     * @param chestY         chest y
     * @param chestZ         chest z
     * @param headWorldUuid  head world
     * @param headWorldName  head world name
     * @param headX          head x
     * @param headY          head y
     * @param headZ          head z
     * @return completion future
     */
    public CompletableFuture<Void> updatePlan(long id, UUID chestWorldUuid, String chestWorldName,
                                              int chestX, int chestY, int chestZ,
                                              UUID headWorldUuid, String headWorldName,
                                              int headX, int headY, int headZ) {
        return submit("update evidence plan " + id, database -> {
            String sql = """
                    UPDATE evidence SET
                        chest_world_uuid = ?, chest_world = ?, chest_x = ?, chest_y = ?, chest_z = ?,
                        head_world_uuid = ?, head_world = ?, head_x = ?, head_y = ?, head_z = ?,
                        updated_at = ?
                    WHERE id = ?""";

            try (PreparedStatement statement = database.prepareStatement(sql)) {
                int index = 1;
                statement.setString(index++, uuid(chestWorldUuid));
                statement.setString(index++, chestWorldName);
                statement.setInt(index++, chestX);
                statement.setInt(index++, chestY);
                statement.setInt(index++, chestZ);
                statement.setString(index++, uuid(headWorldUuid));
                statement.setString(index++, headWorldName);
                statement.setInt(index++, headX);
                statement.setInt(index++, headY);
                statement.setInt(index++, headZ);
                statement.setLong(index++, System.currentTimeMillis());
                statement.setLong(index, id);
                statement.executeUpdate();
            }
            return null;
        });
    }

    /**
     * Records the most recent placement error.
     *
     * @param id    evidence id
     * @param error error description
     * @return completion future
     */
    public CompletableFuture<Void> updateError(long id, String error) {
        return submit("update evidence error " + id, database -> {
            try (PreparedStatement statement = database.prepareStatement(
                    "UPDATE evidence SET last_error = ?, updated_at = ? WHERE id = ?")) {
                statement.setString(1, error);
                statement.setLong(2, System.currentTimeMillis());
                statement.setLong(3, id);
                statement.executeUpdate();
            }
            return null;
        });
    }

    /**
     * @param id evidence id
     * @return future with the record or {@code null}
     */
    public CompletableFuture<EvidenceRecord> findEvidence(long id) {
        return submit("find evidence " + id, database -> {
            try (PreparedStatement statement = database.prepareStatement(
                    "SELECT " + EVIDENCE_COLUMNS + " FROM evidence WHERE id = ?")) {
                statement.setLong(1, id);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? readEvidence(result) : null;
                }
            }
        });
    }

    /**
     * @param uuid evidence UUID
     * @return future with the record or {@code null}
     */
    public CompletableFuture<EvidenceRecord> findEvidenceByUuid(String uuid) {
        return submit("find evidence uuid " + uuid, database -> {
            try (PreparedStatement statement = database.prepareStatement(
                    "SELECT " + EVIDENCE_COLUMNS + " FROM evidence WHERE evidence_uuid = ?")) {
                statement.setString(1, uuid);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? readEvidence(result) : null;
                }
            }
        });
    }

    /**
     * @param status status to load
     * @return all records in the given state, oldest first
     */
    public CompletableFuture<List<EvidenceRecord>> findByStatus(EvidenceStatus status) {
        return submit("find evidence status " + status, database -> {
            String sql = "SELECT " + EVIDENCE_COLUMNS + " FROM evidence WHERE status = ? ORDER BY death_time ASC";
            List<EvidenceRecord> records = new ArrayList<>();

            try (PreparedStatement statement = database.prepareStatement(sql)) {
                statement.setString(1, status.name());
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        records.add(readEvidence(result));
                    }
                }
            }

            return records;
        });
    }

    /**
     * @param status optional status filter
     * @param limit  page size
     * @param offset row offset
     * @return records, newest first
     */
    public CompletableFuture<List<EvidenceRecord>> listEvidence(EvidenceStatus status, int limit, int offset) {
        return submit("list evidence", database -> {
            String sql = status == null
                    ? "SELECT " + EVIDENCE_COLUMNS + " FROM evidence ORDER BY death_time DESC LIMIT ? OFFSET ?"
                    : "SELECT " + EVIDENCE_COLUMNS + " FROM evidence WHERE status = ? ORDER BY death_time DESC LIMIT ? OFFSET ?";

            List<EvidenceRecord> records = new ArrayList<>();

            try (PreparedStatement statement = database.prepareStatement(sql)) {
                int index = 1;
                if (status != null) {
                    statement.setString(index++, status.name());
                }
                statement.setInt(index++, Math.max(1, limit));
                statement.setInt(index, Math.max(0, offset));

                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        records.add(readEvidence(result));
                    }
                }
            }

            return records;
        });
    }

    /**
     * @param status optional filter
     * @return record count
     */
    public CompletableFuture<Long> countEvidence(EvidenceStatus status) {
        return submit("count evidence", database -> {
            String sql = status == null ? "SELECT COUNT(*) FROM evidence" : "SELECT COUNT(*) FROM evidence WHERE status = ?";

            try (PreparedStatement statement = database.prepareStatement(sql)) {
                if (status != null) {
                    statement.setString(1, status.name());
                }
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? result.getLong(1) : 0L;
                }
            }
        });
    }

    /**
     * Atomically archives an active inventory and moves the evidence into the
     * {@code PROCESSING} state.
     *
     * <p>The conditional {@code status = 'ACTIVE'} update is the duplicate
     * processing guard: only one caller can win, even across restarts. The
     * world evidence is removed after this commit and {@link #finishProcessing}
     * completes the transition.</p>
     *
     * @param evidenceId evidence id
     * @param archive    archive payload
     * @param actorUuid  processing officer UUID
     * @param actorName  processing officer name
     * @param now        timestamp
     * @return future with {@code true} when this call won the claim
     */
    public CompletableFuture<Boolean> beginProcessing(long evidenceId, ArchiveRecord archive,
                                                      UUID actorUuid, String actorName, long now) {
        return archiveInternal(evidenceId, archive, actorUuid, actorName, now, "ACTIVE");
    }

    /**
     * Archives a record that never became active (for example a placement that
     * cannot be completed). Used by explicit administrator recovery so items
     * can always be returned.
     *
     * @param evidenceId evidence id
     * @param archive    archive payload
     * @param actorUuid  actor UUID
     * @param actorName  actor name
     * @param now        timestamp
     * @return future with {@code true} when this call won the claim
     */
    public CompletableFuture<Boolean> archivePreparing(long evidenceId, ArchiveRecord archive,
                                                       UUID actorUuid, String actorName, long now) {
        return archiveInternal(evidenceId, archive, actorUuid, actorName, now, "PREPARING");
    }

    /**
     * Completes a processing claim after the world evidence was removed.
     *
     * @param evidenceId evidence id
     * @param now        timestamp
     * @return future with {@code true} when the record was in the processing state
     */
    public CompletableFuture<Boolean> finishProcessing(long evidenceId, long now) {
        return submit("finish processing " + evidenceId, database -> {
            try (PreparedStatement statement = database.prepareStatement(
                    "UPDATE evidence SET status = 'PROCESSED', updated_at = ? "
                            + "WHERE id = ? AND status = 'PROCESSING'")) {
                statement.setLong(1, now);
                statement.setLong(2, evidenceId);
                return statement.executeUpdate() == 1;
            }
        });
    }

    private CompletableFuture<Boolean> archiveInternal(long evidenceId, ArchiveRecord archive, UUID actorUuid,
                                                       String actorName, long now, String requiredStatus) {
        return submitTransaction("archive evidence " + evidenceId, database -> {
            String statusClause = "ACTIVE".equals(requiredStatus)
                    ? "AND status = 'ACTIVE'"
                    : "AND status = 'PREPARING'";

            String updateSql = "UPDATE evidence SET status = 'PROCESSING', processed_by_uuid = ?, "
                    + "processed_by_name = ?, processed_at = ?, updated_at = ?, last_error = NULL "
                    + "WHERE id = ? " + statusClause;

            int updated;
            try (PreparedStatement update = database.prepareStatement(updateSql)) {
                update.setString(1, uuid(actorUuid));
                update.setString(2, actorName);
                update.setLong(3, now);
                update.setLong(4, now);
                update.setLong(5, evidenceId);
                updated = update.executeUpdate();
            }

            if (updated != 1) {
                database.rollback();
                return false;
            }

            String insertSql = """
                    INSERT INTO evidence_archive (
                        evidence_id, archived_at, archived_by_uuid, archived_by_name,
                        chest_items, overflow_items, armor_items, offhand_item,
                        exp_total, exp_level, item_count, recovered
                    ) VALUES (?,?,?,?,?,?,?,?,?,?,?,0)""";

            try (PreparedStatement insert = database.prepareStatement(insertSql)) {
                int index = 1;
                insert.setLong(index++, evidenceId);
                insert.setLong(index++, now);
                insert.setString(index++, uuid(actorUuid));
                insert.setString(index++, actorName);
                insert.setString(index++, archive.chestItems);
                insert.setString(index++, archive.overflowItems);
                insert.setString(index++, archive.armorItems);
                insert.setString(index++, archive.offhandItem);
                insert.setInt(index++, archive.expTotal);
                insert.setInt(index++, archive.expLevel);
                insert.setInt(index, archive.itemCount);
                insert.executeUpdate();
            }

            return true;
        });
    }

    /**
     * @param evidenceId evidence id
     * @return future with the archive or {@code null}
     */
    public CompletableFuture<ArchiveRecord> findArchive(long evidenceId) {
        return submit("find archive " + evidenceId, database -> {
            try (PreparedStatement statement = database.prepareStatement(
                    "SELECT " + ARCHIVE_COLUMNS + " FROM evidence_archive WHERE evidence_id = ?")) {
                statement.setLong(1, evidenceId);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? readArchive(result) : null;
                }
            }
        });
    }

    /**
     * Claims an archive for restoration. Only one claim can succeed.
     *
     * @param evidenceId evidence id
     * @param actorUuid  actor UUID
     * @param actorName  actor name
     * @param now        timestamp
     * @return future with {@code true} when the claim succeeded
     */
    public CompletableFuture<Boolean> claimRecovery(long evidenceId, UUID actorUuid, String actorName, long now) {
        return submit("claim recovery " + evidenceId, database -> {
            try (PreparedStatement statement = database.prepareStatement(
                    "UPDATE evidence_archive SET recovered = 1, recovered_at = ?, recovered_by_uuid = ?, "
                            + "recovered_by_name = ? WHERE evidence_id = ? AND recovered = 0")) {
                statement.setLong(1, now);
                statement.setString(2, uuid(actorUuid));
                statement.setString(3, actorName);
                statement.setLong(4, evidenceId);
                return statement.executeUpdate() == 1;
            }
        });
    }

    /**
     * @param limit  page size
     * @param offset row offset
     * @return archives, newest first
     */
    public CompletableFuture<List<ArchiveRecord>> listArchives(int limit, int offset) {
        return submit("list archives", database -> {
            String sql = "SELECT " + ARCHIVE_COLUMNS + " FROM evidence_archive ORDER BY archived_at DESC LIMIT ? OFFSET ?";
            List<ArchiveRecord> archives = new ArrayList<>();

            try (PreparedStatement statement = database.prepareStatement(sql)) {
                statement.setInt(1, Math.max(1, limit));
                statement.setInt(2, Math.max(0, offset));

                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        archives.add(readArchive(result));
                    }
                }
            }

            return archives;
        });
    }

    /**
     * @param recovered optional recovered filter
     * @return archive count
     */
    public CompletableFuture<Long> countArchives(Boolean recovered) {
        return submit("count archives", database -> {
            String sql = recovered == null
                    ? "SELECT COUNT(*) FROM evidence_archive"
                    : "SELECT COUNT(*) FROM evidence_archive WHERE recovered = ?";

            try (PreparedStatement statement = database.prepareStatement(sql)) {
                if (recovered != null) {
                    statement.setInt(1, recovered ? 1 : 0);
                }
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? result.getLong(1) : 0L;
                }
            }
        });
    }

    /**
     * Saves (or replaces) the single hospital location.
     *
     * @param hospital hospital data
     * @return completion future
     */
    public CompletableFuture<Void> saveHospital(HospitalRecord hospital) {
        return submit("save hospital", database -> {
            String sql = """
                    INSERT INTO hospital (id, world_uuid, world_name, x, y, z, yaw, pitch, updated_at)
                    VALUES (1, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(id) DO UPDATE SET
                        world_uuid = excluded.world_uuid,
                        world_name = excluded.world_name,
                        x = excluded.x,
                        y = excluded.y,
                        z = excluded.z,
                        yaw = excluded.yaw,
                        pitch = excluded.pitch,
                        updated_at = excluded.updated_at""";

            try (PreparedStatement statement = database.prepareStatement(sql)) {
                int index = 1;
                statement.setString(index++, uuid(hospital.worldUuid));
                statement.setString(index++, hospital.worldName);
                statement.setDouble(index++, hospital.x);
                statement.setDouble(index++, hospital.y);
                statement.setDouble(index++, hospital.z);
                statement.setDouble(index++, hospital.yaw);
                statement.setDouble(index++, hospital.pitch);
                statement.setLong(index, hospital.updatedAt);
                statement.executeUpdate();
            }
            return null;
        });
    }

    /**
     * @return future with the hospital location or {@code null}
     */
    public CompletableFuture<HospitalRecord> loadHospital() {
        return submit("load hospital", database -> {
            try (PreparedStatement statement = database.prepareStatement(
                    "SELECT world_uuid, world_name, x, y, z, yaw, pitch, updated_at FROM hospital WHERE id = 1");
                 ResultSet result = statement.executeQuery()) {

                if (!result.next()) {
                    return null;
                }

                HospitalRecord hospital = new HospitalRecord();
                hospital.worldUuid = parseUuid(result.getString("world_uuid"));
                hospital.worldName = result.getString("world_name");
                hospital.x = result.getDouble("x");
                hospital.y = result.getDouble("y");
                hospital.z = result.getDouble("z");
                hospital.yaw = result.getFloat("yaw");
                hospital.pitch = result.getFloat("pitch");
                hospital.updatedAt = result.getLong("updated_at");
                return hospital;
            }
        });
    }

    /**
     * Collects database counters for {@code /qsds status}.
     *
     * @return future with statistics
     */
    public CompletableFuture<StorageStats> stats() {
        return submit("storage statistics", database -> {
            long active = count(database, "SELECT COUNT(*) FROM evidence WHERE status = 'ACTIVE'");
            long preparing = count(database, "SELECT COUNT(*) FROM evidence WHERE status = 'PREPARING'");
            long processing = count(database, "SELECT COUNT(*) FROM evidence WHERE status = 'PROCESSING'");
            long processed = count(database, "SELECT COUNT(*) FROM evidence WHERE status = 'PROCESSED'");
            long archives = count(database, "SELECT COUNT(*) FROM evidence_archive");
            long pending = count(database, "SELECT COUNT(*) FROM evidence_archive WHERE recovered = 0");

            long bytes = databaseFile.length();
            File wal = new File(databaseFile.getAbsolutePath() + "-wal");
            if (wal.exists()) {
                bytes += wal.length();
            }

            return new StorageStats(active, preparing, processing, processed, archives, pending, bytes);
        });
    }

    /**
     * Waits for all queued database work to complete.
     */
    public void flush() {
        if (closed.get()) {
            return;
        }

        try {
            submit("flush", database -> null).get(15, TimeUnit.SECONDS);
        } catch (Exception exception) {
            logger.log(Level.WARNING, "Could not flush pending database work", exception);
        }
    }

    /**
     * Flushes and closes the database.
     */
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        CompletableFuture<Void> barrier = new CompletableFuture<>();
        try {
            executor.execute(() -> barrier.complete(null));
            barrier.get(10, TimeUnit.SECONDS);
        } catch (RejectedExecutionException ignored) {
            // executor already stopped
        } catch (Exception exception) {
            logger.log(Level.WARNING, "Database did not flush in time", exception);
        }

        executor.shutdown();

        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }

        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException exception) {
                logger.log(Level.WARNING, "Could not close SQLite connection", exception);
            }
        }
    }

    private long count(Connection database, String sql) throws SQLException {
        try (PreparedStatement statement = database.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            return result.next() ? result.getLong(1) : 0L;
        }
    }

    private EvidenceRecord readEvidence(ResultSet result) throws SQLException {
        EvidenceRecord record = new EvidenceRecord();
        record.id = result.getLong("id");
        record.evidenceUuid = result.getString("evidence_uuid");
        record.victimUuid = parseUuid(result.getString("victim_uuid"));
        record.victimName = result.getString("victim_name");
        record.deathTime = result.getLong("death_time");
        record.worldUuid = parseUuid(result.getString("world_uuid"));
        record.worldName = result.getString("world_name");
        record.deathX = result.getDouble("death_x");
        record.deathY = result.getDouble("death_y");
        record.deathZ = result.getDouble("death_z");
        record.deathYaw = result.getFloat("death_yaw");
        record.deathPitch = result.getFloat("death_pitch");
        record.deathCause = result.getString("death_cause");
        record.killerUuid = parseUuid(result.getString("killer_uuid"));
        record.killerName = result.getString("killer_name");
        record.status = result.getString("status");
        record.chestWorldUuid = parseUuid(result.getString("chest_world_uuid"));
        record.chestWorldName = result.getString("chest_world");
        record.chestX = nullableInt(result, "chest_x");
        record.chestY = nullableInt(result, "chest_y");
        record.chestZ = nullableInt(result, "chest_z");
        record.headWorldUuid = parseUuid(result.getString("head_world_uuid"));
        record.headWorldName = result.getString("head_world");
        record.headX = nullableInt(result, "head_x");
        record.headY = nullableInt(result, "head_y");
        record.headZ = nullableInt(result, "head_z");
        record.nameplateUuid = parseUuid(result.getString("nameplate_uuid"));
        record.chestItems = result.getString("chest_items");
        record.overflowItems = result.getString("overflow_items");
        record.armorItems = result.getString("armor_items");
        record.offhandItem = result.getString("offhand_item");
        record.expTotal = result.getInt("exp_total");
        record.expLevel = result.getInt("exp_level");
        record.skinValue = result.getString("skin_value");
        record.skinSignature = result.getString("skin_signature");
        record.skinUrl = result.getString("skin_url");
        record.createdAt = result.getLong("created_at");
        record.updatedAt = result.getLong("updated_at");
        record.lastError = result.getString("last_error");
        record.processedByUuid = parseUuid(result.getString("processed_by_uuid"));
        record.processedByName = result.getString("processed_by_name");
        long processedAt = result.getLong("processed_at");
        record.processedAt = result.wasNull() ? null : processedAt;
        return record;
    }

    private ArchiveRecord readArchive(ResultSet result) throws SQLException {
        ArchiveRecord archive = new ArchiveRecord();
        archive.evidenceId = result.getLong("evidence_id");
        archive.archivedAt = result.getLong("archived_at");
        archive.archivedByUuid = parseUuid(result.getString("archived_by_uuid"));
        archive.archivedByName = result.getString("archived_by_name");
        archive.chestItems = result.getString("chest_items");
        archive.overflowItems = result.getString("overflow_items");
        archive.armorItems = result.getString("armor_items");
        archive.offhandItem = result.getString("offhand_item");
        archive.expTotal = result.getInt("exp_total");
        archive.expLevel = result.getInt("exp_level");
        archive.itemCount = result.getInt("item_count");
        archive.recovered = result.getInt("recovered") == 1;
        long recoveredAt = result.getLong("recovered_at");
        archive.recoveredAt = result.wasNull() ? null : recoveredAt;
        archive.recoveredByUuid = parseUuid(result.getString("recovered_by_uuid"));
        archive.recoveredByName = result.getString("recovered_by_name");
        return archive;
    }

    private <T> CompletableFuture<T> submit(String description, SqlCallable<T> callable) {
        CompletableFuture<T> future = new CompletableFuture<>();

        if (closed.get()) {
            future.completeExceptionally(new IllegalStateException("Storage is closed"));
            return future;
        }

        try {
            executor.execute(() -> {
                try {
                    if (debug) {
                        logger.fine("[SQL] " + description);
                    }
                    future.complete(callable.call(connection));
                } catch (Throwable throwable) {
                    logger.log(Level.SEVERE, "Database operation failed: " + description, throwable);
                    future.completeExceptionally(throwable);
                }
            });
        } catch (RejectedExecutionException exception) {
            future.completeExceptionally(exception);
        }

        return future;
    }

    private <T> CompletableFuture<T> submitTransaction(String description, SqlCallable<T> callable) {
        return submit(description, database -> {
            boolean previousAutoCommit = database.getAutoCommit();
            database.setAutoCommit(false);

            try {
                T result = callable.call(database);
                database.commit();
                return result;
            } catch (Throwable throwable) {
                try {
                    database.rollback();
                } catch (SQLException rollbackFailure) {
                    throwable.addSuppressed(rollbackFailure);
                }
                throw throwable;
            } finally {
                database.setAutoCommit(previousAutoCommit);
            }
        });
    }

    private static String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }

    private static void setNullableInt(PreparedStatement statement, int index, Integer value) throws SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.INTEGER);
        } else {
            statement.setInt(index, value);
        }
    }

    private static Integer nullableInt(ResultSet result, String column) throws SQLException {
        int value = result.getInt(column);
        return result.wasNull() ? null : value;
    }

    private static String uuid(UUID value) {
        return value == null ? null : value.toString();
    }

    private static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    @FunctionalInterface
    private interface SqlCallable<T> {
        T call(Connection connection) throws Exception;
    }
}
