/*
 * qS Death System
 * Copyright (C) qS Death System contributors.
 *
 * This file is part of a derivative work of GravesX, GPL-3.0-only.
 */
package dev.qsdeath.storage;

import dev.qsdeath.evidence.EvidenceRecord;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Small on-disk write-ahead journal for death evidence.
 *
 * <p>The inventory is captured and written here synchronously before the
 * player's inventory is cleared and before the asynchronous SQLite insert
 * starts. If the server crashes in that window, the record is replayed into
 * SQLite on the next start, so no item can be lost between the death event and
 * the database commit.</p>
 */
public final class EvidenceJournal {

    private final Path directory;
    private final Logger logger;

    public EvidenceJournal(Path directory, Logger logger) {
        this.directory = directory;
        this.logger = logger;
    }

    /**
     * Creates the journal directory.
     *
     * @throws IOException when the directory cannot be created
     */
    public void initialize() throws IOException {
        Files.createDirectories(directory);
    }

    /**
     * Writes a pending record, atomically replacing any previous copy.
     *
     * @param record record
     * @throws IOException when the journal cannot be written
     */
    public void write(EvidenceRecord record) throws IOException {
        Properties properties = new Properties();

        properties.setProperty("evidence_uuid", record.evidenceUuid);
        set(properties, "victim_uuid", uuid(record.victimUuid));
        set(properties, "victim_name", record.victimName);
        set(properties, "death_time", Long.toString(record.deathTime));
        set(properties, "world_uuid", uuid(record.worldUuid));
        set(properties, "world_name", record.worldName);
        set(properties, "death_x", Double.toString(record.deathX));
        set(properties, "death_y", Double.toString(record.deathY));
        set(properties, "death_z", Double.toString(record.deathZ));
        set(properties, "death_yaw", Float.toString(record.deathYaw));
        set(properties, "death_pitch", Float.toString(record.deathPitch));
        set(properties, "death_cause", record.deathCause);
        set(properties, "killer_uuid", uuid(record.killerUuid));
        set(properties, "killer_name", record.killerName);
        set(properties, "status", record.status);
        set(properties, "chest_world_uuid", uuid(record.chestWorldUuid));
        set(properties, "chest_world", record.chestWorldName);
        set(properties, "chest_x", record.chestX == null ? null : record.chestX.toString());
        set(properties, "chest_y", record.chestY == null ? null : record.chestY.toString());
        set(properties, "chest_z", record.chestZ == null ? null : record.chestZ.toString());
        set(properties, "head_world_uuid", uuid(record.headWorldUuid));
        set(properties, "head_world", record.headWorldName);
        set(properties, "head_x", record.headX == null ? null : record.headX.toString());
        set(properties, "head_y", record.headY == null ? null : record.headY.toString());
        set(properties, "head_z", record.headZ == null ? null : record.headZ.toString());
        set(properties, "nameplate_uuid", uuid(record.nameplateUuid));
        set(properties, "chest_items", record.chestItems);
        set(properties, "overflow_items", record.overflowItems);
        set(properties, "armor_items", record.armorItems);
        set(properties, "offhand_item", record.offhandItem);
        set(properties, "exp_total", Integer.toString(record.expTotal));
        set(properties, "exp_level", Integer.toString(record.expLevel));
        set(properties, "skin_value", record.skinValue);
        set(properties, "skin_signature", record.skinSignature);
        set(properties, "skin_url", record.skinUrl);
        set(properties, "created_at", Long.toString(record.createdAt));
        set(properties, "updated_at", Long.toString(record.updatedAt));
        set(properties, "last_error", record.lastError);

        Path target = fileFor(record.evidenceUuid);
        Path temporary = directory.resolve(record.evidenceUuid + ".tmp");

        try (OutputStream output = Files.newOutputStream(temporary)) {
            properties.store(output, "qS Death System pending evidence");
        }

        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Removes the journal entry once the record is durable in SQLite.
     *
     * @param evidenceUuid evidence UUID
     */
    public void delete(String evidenceUuid) {
        try {
            Files.deleteIfExists(fileFor(evidenceUuid));
        } catch (IOException exception) {
            logger.log(Level.WARNING, "Could not remove journal entry " + evidenceUuid, exception);
        }
    }

    /**
     * Reads and validates all pending journal entries.
     *
     * @return pending records
     */
    public List<EvidenceRecord> readAll() {
        List<EvidenceRecord> records = new ArrayList<>();

        if (!Files.isDirectory(directory)) {
            return records;
        }

        try (var stream = Files.list(directory)) {
            stream.filter(path -> path.getFileName().toString().endsWith(".properties"))
                    .forEach(path -> {
                        try {
                            EvidenceRecord record = read(path);
                            if (record != null) {
                                records.add(record);
                            }
                        } catch (Exception exception) {
                            logger.log(Level.SEVERE, "Corrupt evidence journal entry: " + path, exception);
                        }
                    });
        } catch (IOException exception) {
            logger.log(Level.SEVERE, "Could not read the evidence journal", exception);
        }

        return records;
    }

    /**
     * @return number of journal files awaiting replay
     */
    public int pendingCount() {
        if (!Files.isDirectory(directory)) {
            return 0;
        }

        try (var stream = Files.list(directory)) {
            return (int) stream.filter(path -> path.getFileName().toString().endsWith(".properties")).count();
        } catch (IOException exception) {
            return 0;
        }
    }

    private EvidenceRecord read(Path path) throws IOException {
        Properties properties = new Properties();

        try (InputStream input = Files.newInputStream(path)) {
            properties.load(input);
        }

        String evidenceUuid = properties.getProperty("evidence_uuid");
        if (evidenceUuid == null || evidenceUuid.isBlank()) {
            return null;
        }

        EvidenceRecord record = new EvidenceRecord();
        record.evidenceUuid = evidenceUuid;
        record.victimUuid = parseUuid(properties.getProperty("victim_uuid"));
        record.victimName = properties.getProperty("victim_name", "");
        record.deathTime = parseLong(properties.getProperty("death_time"), System.currentTimeMillis());
        record.worldUuid = parseUuid(properties.getProperty("world_uuid"));
        record.worldName = properties.getProperty("world_name", "");
        record.deathX = parseDouble(properties.getProperty("death_x"));
        record.deathY = parseDouble(properties.getProperty("death_y"));
        record.deathZ = parseDouble(properties.getProperty("death_z"));
        record.deathYaw = (float) parseDouble(properties.getProperty("death_yaw"));
        record.deathPitch = (float) parseDouble(properties.getProperty("death_pitch"));
        record.deathCause = properties.getProperty("death_cause", "UNKNOWN");
        record.killerUuid = parseUuid(properties.getProperty("killer_uuid"));
        record.killerName = properties.getProperty("killer_name");
        record.status = properties.getProperty("status", "PREPARING");
        record.chestWorldUuid = parseUuid(properties.getProperty("chest_world_uuid"));
        record.chestWorldName = properties.getProperty("chest_world");
        record.chestX = parseInteger(properties.getProperty("chest_x"));
        record.chestY = parseInteger(properties.getProperty("chest_y"));
        record.chestZ = parseInteger(properties.getProperty("chest_z"));
        record.headWorldUuid = parseUuid(properties.getProperty("head_world_uuid"));
        record.headWorldName = properties.getProperty("head_world");
        record.headX = parseInteger(properties.getProperty("head_x"));
        record.headY = parseInteger(properties.getProperty("head_y"));
        record.headZ = parseInteger(properties.getProperty("head_z"));
        record.nameplateUuid = parseUuid(properties.getProperty("nameplate_uuid"));
        record.chestItems = properties.getProperty("chest_items", "");
        record.overflowItems = properties.getProperty("overflow_items", "");
        record.armorItems = properties.getProperty("armor_items", "");
        record.offhandItem = properties.getProperty("offhand_item", "");
        record.expTotal = (int) parseLong(properties.getProperty("exp_total"), 0);
        record.expLevel = (int) parseLong(properties.getProperty("exp_level"), 0);
        record.skinValue = properties.getProperty("skin_value");
        record.skinSignature = properties.getProperty("skin_signature");
        record.skinUrl = properties.getProperty("skin_url");
        record.createdAt = parseLong(properties.getProperty("created_at"), System.currentTimeMillis());
        record.updatedAt = parseLong(properties.getProperty("updated_at"), record.createdAt);
        record.lastError = properties.getProperty("last_error");
        return record;
    }

    private Path fileFor(String evidenceUuid) {
        return directory.resolve(evidenceUuid + ".properties");
    }

    private static void set(Properties properties, String key, String value) {
        if (value != null) {
            properties.setProperty(key, value);
        }
    }

    private static String uuid(UUID uuid) {
        return uuid == null ? null : uuid.toString();
    }

    private static UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static long parseLong(String raw, long fallback) {
        try {
            return Long.parseLong(raw);
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static double parseDouble(String raw) {
        try {
            return Double.parseDouble(raw);
        } catch (RuntimeException ignored) {
            return 0.0D;
        }
    }

    private static Integer parseInteger(String raw) {
        try {
            return raw == null ? null : Integer.parseInt(raw);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
