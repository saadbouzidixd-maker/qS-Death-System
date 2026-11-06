/*
 * qS Death System
 * Copyright (C) qS Death System contributors.
 *
 * This file is part of a derivative work of GravesX, GPL-3.0-only.
 */
package dev.qsdeath.storage;

/**
 * Database counters used by {@code /qsds status}.
 *
 * @param active          active evidence in the world
 * @param preparing       records awaiting world placement
 * @param processing      records whose inventory is archived but removal is unfinished
 * @param processed       processed evidence records
 * @param archives        archived inventories
 * @param pendingArchives archives that have not been recovered yet
 * @param databaseBytes   SQLite file size
 */
public record StorageStats(long active, long preparing, long processing, long processed,
                           long archives, long pendingArchives, long databaseBytes) {
}
