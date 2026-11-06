/*
 * qS Death System
 * Copyright (C) qS Death System contributors.
 *
 * This file is part of a derivative work of GravesX, GPL-3.0-only.
 */
package dev.qsdeath.util;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable block position keyed by world UUID.
 *
 * @param worldId world UUID
 * @param x       block x
 * @param y       block y
 * @param z       block z
 */
public record BlockKey(UUID worldId, int x, int y, int z) {

    public BlockKey {
        Objects.requireNonNull(worldId, "worldId");
    }

    public static BlockKey of(Block block) {
        return new BlockKey(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
    }

    public static BlockKey of(Location location) {
        return new BlockKey(location.getWorld().getUID(),
                location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    public static BlockKey of(World world, int x, int y, int z) {
        return new BlockKey(world.getUID(), x, y, z);
    }

    /**
     * @return database friendly representation
     */
    public String asStorageKey() {
        return worldId + ":" + x + ":" + y + ":" + z;
    }
}
