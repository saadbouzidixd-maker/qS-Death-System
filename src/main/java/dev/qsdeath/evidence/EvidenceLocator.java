/*
 * qS Death System
 * Copyright (C) qS Death System contributors.
 *
 * This file is part of a derivative work of GravesX, GPL-3.0-only.
 */
package dev.qsdeath.evidence;

import dev.qsdeath.QSDeathSystem;
import dev.qsdeath.util.BlockKey;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

import java.util.EnumSet;
import java.util.Set;

/**
 * Finds a safe, replaceable block for the evidence chest and its head.
 *
 * <p>Candidates start at the exact death block and move upwards first, then
 * sideways, then downwards. Air-like blocks and small plants/snow may be
 * replaced; other blocks are never destroyed. A position adjacent to an
 * existing chest is rejected so a vanilla double chest can never merge with
 * evidence storage.</p>
 */
public final class EvidenceLocator {

    private static final Set<Material> REPLACEABLE = EnumSet.of(
            Material.AIR,
            Material.CAVE_AIR,
            Material.VOID_AIR,
            Material.SHORT_GRASS,
            Material.TALL_GRASS,
            Material.FERN,
            Material.LARGE_FERN,
            Material.SNOW,
            Material.DEAD_BUSH,
            Material.VINE,
            Material.GLOW_LICHEN,
            Material.SEAGRASS,
            Material.TALL_SEAGRASS);

    private static final int[][] CANDIDATES = {
            {0, 0, 0}, {0, 1, 0}, {0, 2, 0},
            {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1},
            {1, 1, 0}, {-1, 1, 0}, {0, 1, 1}, {0, 1, -1},
            {2, 0, 0}, {-2, 0, 0}, {0, 0, 2}, {0, 0, -2},
            {0, -1, 0}, {0, 3, 0}
    };

    private final QSDeathSystem plugin;

    public EvidenceLocator(QSDeathSystem plugin) {
        this.plugin = plugin;
    }

    /**
     * Finds a placement plan close to the death location.
     *
     * @param death death location
     * @return plan, or {@code null} when no safe spot exists
     */
    public PlacementPlan findPlan(Location death) {
        if (death == null || death.getWorld() == null) {
            return null;
        }

        World world = death.getWorld();
        int baseX = death.getBlockX();
        int baseY = death.getBlockY();
        int baseZ = death.getBlockZ();

        for (int[] offset : CANDIDATES) {
            int x = baseX + offset[0];
            int y = baseY + offset[1];
            int z = baseZ + offset[2];

            if (!isWithinBuildHeight(world, y)) {
                continue;
            }

            Block chestBlock = world.getBlockAt(x, y, z);
            if (!isReplaceable(chestBlock) || isAdjacentToChest(chestBlock)) {
                continue;
            }

            BlockKey headKey = null;
            if (plugin.config().headEnabled()) {
                Block headBlock = findHeadBlock(chestBlock);
                if (headBlock == null) {
                    continue;
                }
                headKey = BlockKey.of(headBlock);
            }

            return new PlacementPlan(BlockKey.of(chestBlock), headKey);
        }

        return null;
    }

    /**
     * @param block block
     * @return true when the block may be replaced by evidence
     */
    public boolean isReplaceable(Block block) {
        return block != null && REPLACEABLE.contains(block.getType());
    }

    /**
     * @param world world
     * @param y     block y
     * @return true when y is inside the world build limits
     */
    public boolean isWithinBuildHeight(World world, int y) {
        return y > world.getMinHeight() + 1 && y < world.getMaxHeight() - 2;
    }

    /**
     * Prevents a double chest from forming next to evidence storage.
     *
     * @param block chest block candidate
     * @return true when a neighbouring block is a chest
     */
    public boolean isAdjacentToChest(Block block) {
        for (BlockFace face : new BlockFace[]{
                BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST,
                BlockFace.UP, BlockFace.DOWN}) {
            Material neighbour = block.getRelative(face).getType();
            if (neighbour == Material.CHEST || neighbour == Material.TRAPPED_CHEST) {
                return true;
            }
        }

        return false;
    }

    /**
     * Finds a spot for the head, preferring directly above the chest.
     *
     * @param chestBlock chest block
     * @return head block or {@code null}
     */
    private Block findHeadBlock(Block chestBlock) {
        Block above = chestBlock.getRelative(BlockFace.UP);
        if (isReplaceable(above)) {
            return above;
        }

        for (BlockFace face : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            Block candidate = chestBlock.getRelative(face);
            if (isReplaceable(candidate) && !isAdjacentToChest(candidate)) {
                return candidate;
            }
        }

        return null;
    }
}
