package dev.qsdeath.storage;

import dev.qsdeath.evidence.EvidenceRecord;
import dev.qsdeath.evidence.EvidenceStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the SQLite state machine: durable evidence, single-winner archival,
 * single-winner recovery and hospital persistence.
 */
class StorageTest {

    @TempDir
    Path temporaryDirectory;

    private Storage storage;

    @BeforeEach
    void setUp() throws Exception {
        storage = new Storage(new File(temporaryDirectory.toFile(), "test.db"),
                5000, Logger.getLogger("StorageTest"), false);
        storage.initialize();
    }

    @AfterEach
    void tearDown() {
        storage.close();
    }

    @Test
    void evidenceRoundTripAndIdempotentInsert() throws Exception {
        EvidenceRecord record = sampleRecord();

        long id = storage.insertEvidence(record).get(10, TimeUnit.SECONDS);
        long secondId = storage.insertEvidence(record).get(10, TimeUnit.SECONDS);

        assertEquals(id, secondId, "replaying the same evidence UUID must not create a second record");

        EvidenceRecord loaded = storage.findEvidence(id).get(10, TimeUnit.SECONDS);
        assertNotNull(loaded);
        assertEquals(record.victimUuid, loaded.victimUuid);
        assertEquals(record.victimName, loaded.victimName);
        assertEquals("PLAYER", loaded.deathCause);
        assertEquals("SuspectedPlayer", loaded.killerName);
        assertEquals(EvidenceStatus.PREPARING.name(), loaded.status);
        assertEquals(record.chestItems, loaded.chestItems);
        assertEquals(record.expTotal, loaded.expTotal);
        assertEquals(42, loaded.chestX);
        assertEquals(64, loaded.chestY);
    }

    @Test
    void activeEvidenceIsListedAndCounted() throws Exception {
        EvidenceRecord record = sampleRecord();
        long id = storage.insertEvidence(record).get(10, TimeUnit.SECONDS);
        record.id = id;
        record.status = EvidenceStatus.ACTIVE.name();
        record.nameplateUuid = UUID.randomUUID();

        storage.markActive(record).get(10, TimeUnit.SECONDS);

        assertEquals(1L, storage.countEvidence(EvidenceStatus.ACTIVE).get(10, TimeUnit.SECONDS));
        assertEquals(0L, storage.countEvidence(EvidenceStatus.PREPARING).get(10, TimeUnit.SECONDS));
        assertEquals(1, storage.findByStatus(EvidenceStatus.ACTIVE).get(10, TimeUnit.SECONDS).size());
    }

    @Test
    void processingCanOnlyBeClaimedOnce() throws Exception {
        EvidenceRecord record = sampleRecord();
        long id = storage.insertEvidence(record).get(10, TimeUnit.SECONDS);
        record.id = id;
        record.status = EvidenceStatus.ACTIVE.name();
        storage.markActive(record).get(10, TimeUnit.SECONDS);

        ArchiveRecord archive = archiveFor(id);

        boolean first = storage.beginProcessing(id, archive, UUID.randomUUID(), "Officer", 1000L)
                .get(10, TimeUnit.SECONDS);
        boolean second = storage.beginProcessing(id, archive, UUID.randomUUID(), "Officer2", 2000L)
                .get(10, TimeUnit.SECONDS);

        assertTrue(first);
        assertFalse(second, "a second police interaction must not process the same evidence");

        EvidenceRecord processing = storage.findEvidence(id).get(10, TimeUnit.SECONDS);
        assertEquals(EvidenceStatus.PROCESSING.name(), processing.status);

        ArchiveRecord stored = storage.findArchive(id).get(10, TimeUnit.SECONDS);
        assertNotNull(stored);
        assertEquals(archive.chestItems, stored.chestItems);
        assertEquals(4, stored.itemCount);

        assertTrue(storage.finishProcessing(id, 3000L).get(10, TimeUnit.SECONDS));
        assertEquals(EvidenceStatus.PROCESSED.name(),
                storage.findEvidence(id).get(10, TimeUnit.SECONDS).status);
        assertFalse(storage.finishProcessing(id, 4000L).get(10, TimeUnit.SECONDS));
    }

    @Test
    void recoveryCanOnlyBeClaimedOnce() throws Exception {
        EvidenceRecord record = sampleRecord();
        long id = storage.insertEvidence(record).get(10, TimeUnit.SECONDS);
        record.id = id;
        record.status = EvidenceStatus.ACTIVE.name();
        storage.markActive(record).get(10, TimeUnit.SECONDS);
        storage.beginProcessing(id, archiveFor(id), UUID.randomUUID(), "Officer", 1000L)
                .get(10, TimeUnit.SECONDS);
        storage.finishProcessing(id, 2000L).get(10, TimeUnit.SECONDS);

        assertTrue(storage.claimRecovery(id, UUID.randomUUID(), "Admin", 3000L).get(10, TimeUnit.SECONDS));
        assertFalse(storage.claimRecovery(id, UUID.randomUUID(), "Admin2", 4000L).get(10, TimeUnit.SECONDS));

        ArchiveRecord archive = storage.findArchive(id).get(10, TimeUnit.SECONDS);
        assertTrue(archive.recovered);
        assertEquals("Admin", archive.recoveredByName);
    }

    @Test
    void hospitalLocationSurvivesRestart() throws Exception {
        HospitalRecord hospital = new HospitalRecord();
        hospital.worldUuid = UUID.randomUUID();
        hospital.worldName = "world";
        hospital.x = 120.5;
        hospital.y = 64;
        hospital.z = -250.5;
        hospital.yaw = 90;
        hospital.pitch = 0;
        hospital.updatedAt = 1234L;

        storage.saveHospital(hospital).get(10, TimeUnit.SECONDS);
        HospitalRecord loaded = storage.loadHospital().get(10, TimeUnit.SECONDS);

        assertNotNull(loaded);
        assertEquals(hospital.worldUuid, loaded.worldUuid);
        assertEquals(hospital.worldName, loaded.worldName);
        assertEquals(hospital.x, loaded.x, 0.0001);
        assertEquals(hospital.y, loaded.y, 0.0001);
        assertEquals(hospital.z, loaded.z, 0.0001);
        assertEquals(hospital.yaw, loaded.yaw, 0.0001);
        assertEquals(hospital.pitch, loaded.pitch, 0.0001);
    }

    @Test
    void missingRecordsAreHandledSafely() throws Exception {
        assertNull(storage.findEvidence(999L).get(10, TimeUnit.SECONDS));
        assertNull(storage.findArchive(999L).get(10, TimeUnit.SECONDS));
        assertNull(storage.loadHospital().get(10, TimeUnit.SECONDS));
        assertEquals(0L, storage.countEvidence(null).get(10, TimeUnit.SECONDS));
    }

    @Test
    void statisticsReflectStoredState() throws Exception {
        EvidenceRecord record = sampleRecord();
        long id = storage.insertEvidence(record).get(10, TimeUnit.SECONDS);
        record.id = id;
        record.status = EvidenceStatus.ACTIVE.name();
        storage.markActive(record).get(10, TimeUnit.SECONDS);
        storage.beginProcessing(id, archiveFor(id), UUID.randomUUID(), "Officer", 1000L)
                .get(10, TimeUnit.SECONDS);

        StorageStats stats = storage.stats().get(10, TimeUnit.SECONDS);
        assertEquals(0, stats.active());
        assertEquals(1, stats.processing());
        assertEquals(1, stats.archives());
        assertEquals(1, stats.pendingArchives());
        assertTrue(stats.databaseBytes() > 0);
    }

    private EvidenceRecord sampleRecord() {
        EvidenceRecord record = new EvidenceRecord();
        record.victimUuid = UUID.randomUUID();
        record.victimName = "Victim";
        record.deathTime = 1_700_000_000_000L;
        record.worldUuid = UUID.randomUUID();
        record.worldName = "world";
        record.deathX = 42.5;
        record.deathY = 64;
        record.deathZ = -7.5;
        record.deathYaw = 90;
        record.deathPitch = 0;
        record.deathCause = "PLAYER";
        record.killerUuid = UUID.randomUUID();
        record.killerName = "SuspectedPlayer";
        record.status = EvidenceStatus.PREPARING.name();
        record.chestWorldUuid = record.worldUuid;
        record.chestWorldName = "world";
        record.chestX = 42;
        record.chestY = 64;
        record.chestZ = -8;
        record.headWorldUuid = record.worldUuid;
        record.headWorldName = "world";
        record.headX = 42;
        record.headY = 65;
        record.headZ = -8;
        record.chestItems = "payload";
        record.armorItems = "armor";
        record.offhandItem = "offhand";
        record.expTotal = 120;
        record.expLevel = 7;
        record.skinUrl = "https://textures.minecraft.net/texture/example";
        record.createdAt = System.currentTimeMillis();
        record.updatedAt = record.createdAt;
        return record;
    }

    private ArchiveRecord archiveFor(long evidenceId) {
        ArchiveRecord archive = new ArchiveRecord();
        archive.evidenceId = evidenceId;
        archive.chestItems = "archived-payload";
        archive.overflowItems = "";
        archive.armorItems = "armor";
        archive.offhandItem = "offhand";
        archive.expTotal = 120;
        archive.expLevel = 7;
        archive.itemCount = 4;
        return archive;
    }
}
