/*
 * qS Death System
 * Copyright (C) qS Death System contributors.
 *
 * This file is part of a derivative work of GravesX, GPL-3.0-only.
 */
package dev.qsdeath.evidence;

import dev.qsdeath.util.BlockKey;

/**
 * Chosen chest and head positions for one evidence site.
 *
 * @param chest chest block
 * @param head  head block, may be {@code null} when heads are disabled
 */
public record PlacementPlan(BlockKey chest, BlockKey head) {
}
