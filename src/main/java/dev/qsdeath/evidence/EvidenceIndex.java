/*
 * qS Death System
 * Copyright (C) qS Death System contributors.
 *
 * This file is part of a derivative work of GravesX, GPL-3.0-only.
 */
package dev.qsdeath.evidence;

import dev.qsdeath.util.BlockKey;
import org.bukkit.block.Block;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fast, server-thread friendly lookup of active evidence by chest or head
 * position. Populated from SQLite at startup and kept in sync by the evidence
 * services.
 */
public final class EvidenceIndex {

    private final Map<BlockKey, EvidenceRecord> byChest = new ConcurrentHashMap<>();
    private final Map<BlockKey, EvidenceRecord> byHead = new ConcurrentHashMap<>();

    /**
     * Adds or replaces a record.
     *
     * @param record evidence record
     */
    public void add(EvidenceRecord record) {
        if (record == null) {
            return;
        }

        if (record.hasChestLocation()) {
            byChest.put(new BlockKey(record.chestWorldUuid, record.chestX, record.chestY, record.chestZ), record);
        }

        if (record.hasHeadLocation()) {
            byHead.put(new BlockKey(record.headWorldUuid, record.headX, record.headY, record.headZ), record);
        }
    }

    /**
     * Removes a record from the index.
     *
     * @param record evidence record
     */
    public void remove(EvidenceRecord record) {
        if (record == null) {
            return;
        }

        if (record.hasChestLocation()) {
            byChest.remove(new BlockKey(record.chestWorldUuid, record.chestX, record.chestY, record.chestZ));
        }

        if (record.hasHeadLocation()) {
            byHead.remove(new BlockKey(record.headWorldUuid, record.headX, record.headY, record.headZ));
        }
    }

    /**
     * @param block block
     * @return evidence whose chest is at the block, or {@code null}
     */
    public EvidenceRecord byChest(Block block) {
        return block == null ? null : byChest.get(BlockKey.of(block));
    }

    /**
     * @param block block
     * @return evidence whose head is at the block, or {@code null}
     */
    public EvidenceRecord byHead(Block block) {
        return block == null ? null : byHead.get(BlockKey.of(block));
    }

    /**
     * @param key block key
     * @return evidence whose chest is at the key, or {@code null}
     */
    public EvidenceRecord byChest(BlockKey key) {
        return key == null ? null : byChest.get(key);
    }

    /**
     * @return snapshot of all indexed records
     */
    public Collection<EvidenceRecord> all() {
        return byChest.values();
    }

    public void clear() {
        byChest.clear();
        byHead.clear();
    }
}
