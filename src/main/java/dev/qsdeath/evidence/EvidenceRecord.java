/*
 * qS Death System
 * Copyright (C) qS Death System contributors.
 *
 * This file is part of a derivative work of GravesX, GPL-3.0-only.
 */
package dev.qsdeath.evidence;

import java.util.UUID;

/**
 * Internal transfer object for a death evidence record.
 *
 * <p>Deliberately free of Bukkit types so it can be read and written on the
 * database thread. Items are stored as encoded payloads; see
 * {@link dev.qsdeath.util.ItemCodec}.</p>
 *
 * <p>{@link #chestItems} is the authoritative inventory captured at death. It
 * contains the merged main inventory, armour and offhand so that a single
 * payload can always be recovered. {@link #overflowItems} is only the subset
 * that did not physically fit into the 27-slot chest. The {@code armorItems}
 * and {@code offhandItem} fields are audit copies and are never used for
 * recovery, which keeps restoration duplicate-free.</p>
 */
public final class EvidenceRecord {

    public long id;
    public String evidenceUuid = UUID.randomUUID().toString();

    public UUID victimUuid;
    public String victimName = "";
    public long deathTime;

    public UUID worldUuid;
    public String worldName = "";
    public double deathX;
    public double deathY;
    public double deathZ;
    public float deathYaw;
    public float deathPitch;
    public String deathCause = "UNKNOWN";

    public UUID killerUuid;
    public String killerName;

    public String status = EvidenceStatus.PREPARING.name();

    public UUID chestWorldUuid;
    public String chestWorldName;
    public Integer chestX;
    public Integer chestY;
    public Integer chestZ;

    public UUID headWorldUuid;
    public String headWorldName;
    public Integer headX;
    public Integer headY;
    public Integer headZ;

    public UUID nameplateUuid;

    /** Authoritative merged inventory payload. */
    public String chestItems = "";
    /** Items that did not fit into the chest. */
    public String overflowItems = "";
    /** Audit copy of armour contents. */
    public String armorItems = "";
    /** Audit copy of the offhand item. */
    public String offhandItem = "";

    public int expTotal;
    public int expLevel;

    public String skinValue;
    public String skinSignature;
    public String skinUrl;

    public long createdAt;
    public long updatedAt;
    public String lastError;

    public UUID processedByUuid;
    public String processedByName;
    public Long processedAt;

    public boolean isKillerKnown() {
        return killerName != null && !killerName.isBlank();
    }

    public boolean isActive() {
        return EvidenceStatus.ACTIVE.name().equals(status);
    }

    public boolean isProcessed() {
        return EvidenceStatus.PROCESSED.name().equals(status);
    }

    public boolean hasChestLocation() {
        return chestWorldUuid != null && chestX != null && chestY != null && chestZ != null;
    }

    public boolean hasHeadLocation() {
        return headWorldUuid != null && headX != null && headY != null && headZ != null;
    }

    /**
     * @return evidence id formatted for player-facing messages
     */
    public String displayId() {
        return "#" + id;
    }
}
