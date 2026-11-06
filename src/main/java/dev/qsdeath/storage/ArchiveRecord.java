/*
 * qS Death System
 * Copyright (C) qS Death System contributors.
 *
 * This file is part of a derivative work of GravesX, GPL-3.0-only.
 */
package dev.qsdeath.storage;

import java.util.UUID;

/**
 * Archived inventory of a processed evidence record. Also free of Bukkit types.
 */
public final class ArchiveRecord {

    public long evidenceId;
    public long archivedAt;
    public UUID archivedByUuid;
    public String archivedByName;

    /** Authoritative archived inventory payload (chest contents plus overflow). */
    public String chestItems = "";
    /** Items that did not fit into the chest at the time of archival. */
    public String overflowItems = "";
    /** Audit copy of armour contents. */
    public String armorItems = "";
    /** Audit copy of the offhand item. */
    public String offhandItem = "";

    public int expTotal;
    public int expLevel;
    public int itemCount;

    public boolean recovered;
    public Long recoveredAt;
    public UUID recoveredByUuid;
    public String recoveredByName;
}
