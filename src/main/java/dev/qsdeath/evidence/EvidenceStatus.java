/*
 * qS Death System
 * Copyright (C) qS Death System contributors.
 *
 * This file is part of a derivative work of GravesX, GPL-3.0-only.
 */
package dev.qsdeath.evidence;

/**
 * Lifecycle of an evidence record.
 *
 * <ul>
 *     <li>{@link #PREPARING} - inventory captured and committed, world
 *     placement pending (also used for crash recovery).</li>
 *     <li>{@link #ACTIVE} - chest, head and name display exist in the world.</li>
 *     <li>{@link #PROCESSING} - inventory archived, world removal pending. This
 *     state makes police processing atomic across crashes.</li>
 *     <li>{@link #PROCESSED} - processed by police; inventory archived, world
 *     evidence removed.</li>
 * </ul>
 */
public enum EvidenceStatus {
    PREPARING,
    ACTIVE,
    PROCESSING,
    PROCESSED;

    public static EvidenceStatus fromDatabase(String raw) {
        if (raw == null) {
            return PREPARING;
        }

        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return PREPARING;
        }
    }
}
