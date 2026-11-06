/*
 * qS Death System
 * Copyright (C) qS Death System contributors.
 *
 * This file is part of a derivative work of GravesX, GPL-3.0-only.
 */
package dev.qsdeath.evidence;

import dev.qsdeath.QSDeathSystem;
import dev.qsdeath.util.BlockKey;
import dev.qsdeath.util.ItemCodec;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Chest;
import org.bukkit.block.data.BlockData;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Creates and removes the physical evidence: one vanilla chest holding the
 * stored items, the victim's head and the name display.
 *
 * <p>Placement is idempotent. On crash recovery the existing chest contents are
 * compared with the stored payload and only missing stacks are added, so an
 * interrupted placement can never duplicate or delete items.</p>
 */
public final class EvidenceRenderer {

    private final QSDeathSystem plugin;

    public EvidenceRenderer(QSDeathSystem plugin) {
        this.plugin = plugin;
    }

    /**
     * Result of a placement attempt.
     *
     * @param success      whether the site is usable
     * @param retryable    whether a later attempt may succeed
     * @param error        error description
     * @param chest        final chest position
     * @param head         final head position
     * @param nameplateUuid name display UUID
     * @param overflowItems encoded items that did not fit into the chest
     */
    public record Outcome(boolean success, boolean retryable, String error, BlockKey chest,
                          BlockKey head, UUID nameplateUuid, String overflowItems) {

        public static Outcome failure(String error, boolean retryable) {
            return new Outcome(false, retryable, error, null, null, null, "");
        }
    }

    /**
     * Places (or repairs) the evidence for a record.
     *
     * @param record record
     * @return placement outcome
     */
    public Outcome place(EvidenceRecord record) {
        World world = resolveWorld(record);
        if (world == null) {
            return Outcome.failure("world not loaded: " + record.worldName, true);
        }

        PlacementPlan plan = resolvePlan(record, world);
        if (plan == null) {
            return Outcome.failure("no safe placement location near death point", true);
        }

        Block chestBlock = world.getBlockAt(plan.chest().x(), plan.chest().y(), plan.chest().z());

        if (chestBlock.getType() != Material.CHEST) {
            if (!plugin.locator().isReplaceable(chestBlock)) {
                return Outcome.failure("chest position is no longer replaceable", true);
            }

            chestBlock.setType(Material.CHEST, false);
            applyChestFacing(chestBlock);
            if (chestBlock.getType() != Material.CHEST) {
                return Outcome.failure("could not place a chest block", true);
            }
        }

        List<ItemStack> payload;
        try {
            payload = ItemCodec.flatten(ItemCodec.decode(record.chestItems, plugin.getLogger()));
        } catch (ItemCodec.ItemCodecException exception) {
            return Outcome.failure("stored inventory payload is corrupt", false);
        }

        String overflow;
        try {
            if (!(chestBlock.getState() instanceof Chest chest)) {
                return Outcome.failure("chest block state is unavailable", true);
            }

            Inventory inventory = chest.getBlockInventory();
            List<ItemStack> existing = ItemCodec.flatten(inventory.getContents());

            if (!existing.isEmpty() && !isSubset(existing, payload)) {
                return Outcome.failure("chest position already contains foreign items", true);
            }

            List<ItemStack> remaining = subtract(payload, existing);

            for (int slot = 0; slot < inventory.getSize() && !remaining.isEmpty(); slot++) {
                if (!ItemCodec.isEmpty(inventory.getItem(slot))) {
                    continue;
                }

                inventory.setItem(slot, remaining.remove(0));
            }

            overflow = ItemCodec.encode(ItemCodec.toArray(remaining));
        } catch (Throwable throwable) {
            return Outcome.failure("could not fill the evidence chest: " + throwable.getMessage(), true);
        }

        BlockKey headKey = null;
        if (plugin.config().headEnabled() && plan.head() != null) {
            Block headBlock = world.getBlockAt(plan.head().x(), plan.head().y(), plan.head().z());
            if (plugin.headService().place(headBlock, record)) {
                headKey = plan.head();
            } else {
                plugin.getLogger().warning("Could not place the evidence head for evidence #"
                        + record.id + " at " + world.getName() + " " + plan.head().x() + ", "
                        + plan.head().y() + ", " + plan.head().z());
            }
        }

        UUID nameplateUuid = plugin.nameplate().ensure(record, chestBlock.getLocation());

        record.chestWorldUuid = world.getUID();
        record.chestWorldName = world.getName();
        record.chestX = plan.chest().x();
        record.chestY = plan.chest().y();
        record.chestZ = plan.chest().z();
        record.overflowItems = overflow;
        record.nameplateUuid = nameplateUuid;

        if (headKey != null) {
            record.headWorldUuid = headKey.worldId();
            record.headWorldName = world.getName();
            record.headX = headKey.x();
            record.headY = headKey.y();
            record.headZ = headKey.z();
        }

        return new Outcome(true, false, null, plan.chest(), headKey, nameplateUuid, overflow);
    }

    /**
     * Removes the chest, head and name display of an evidence record. The chest
     * inventory is cleared first; the caller must archive it beforehand.
     *
     * @param record record
     */
    public void remove(EvidenceRecord record) {
        World world = resolveWorld(record);
        if (world == null) {
            plugin.getLogger().warning("Could not remove evidence #" + record.id
                    + ": world " + record.chestWorldName + " is not loaded.");
            return;
        }

        if (record.hasChestLocation()) {
            Block chestBlock = world.getBlockAt(record.chestX, record.chestY, record.chestZ);

            if (chestBlock.getType() == Material.CHEST) {
                try {
                    if (chestBlock.getState() instanceof Chest chest) {
                        chest.getBlockInventory().clear();
                    }
                } catch (Throwable throwable) {
                    plugin.getLogger().warning("Could not clear evidence chest #" + record.id
                            + ": " + throwable.getMessage());
                }

                chestBlock.setType(Material.AIR, false);
            }
        }

        if (record.hasHeadLocation()) {
            Block headBlock = world.getBlockAt(record.headX, record.headY, record.headZ);
            if (headBlock.getType() == Material.PLAYER_HEAD) {
                headBlock.setType(Material.AIR, false);
            }
        }

        plugin.nameplate().remove(record);
    }

    /**
     * Resolves the world of a record without loading chunks.
     *
     * @param record record
     * @return world or {@code null}
     */
    public World resolveWorld(EvidenceRecord record) {
        if (record == null) {
            return null;
        }

        if (record.chestWorldUuid != null) {
            World world = Bukkit.getWorld(record.chestWorldUuid);
            if (world != null) {
                return world;
            }
        }

        if (record.worldUuid != null) {
            World world = Bukkit.getWorld(record.worldUuid);
            if (world != null) {
                return world;
            }
        }

        if (record.chestWorldName != null) {
            World world = Bukkit.getWorld(record.chestWorldName);
            if (world != null) {
                return world;
            }
        }

        return record.worldName == null ? null : Bukkit.getWorld(record.worldName);
    }

    private PlacementPlan resolvePlan(EvidenceRecord record, World world) {
        if (record.hasChestLocation() && record.chestWorldUuid.equals(world.getUID())) {
            Block chestBlock = world.getBlockAt(record.chestX, record.chestY, record.chestZ);

            if (chestBlock.getType() == Material.CHEST || plugin.locator().isReplaceable(chestBlock)) {
                BlockKey chestKey = new BlockKey(world.getUID(),
                        record.chestX, record.chestY, record.chestZ);
                BlockKey headKey = null;

                if (plugin.config().headEnabled()) {
                    if (record.hasHeadLocation() && record.headWorldUuid.equals(world.getUID())) {
                        Block headBlock = world.getBlockAt(record.headX, record.headY, record.headZ);
                        if (headBlock.getType() == Material.PLAYER_HEAD
                                || plugin.locator().isReplaceable(headBlock)) {
                            headKey = new BlockKey(world.getUID(),
                                    record.headX, record.headY, record.headZ);
                        }
                    }

                    if (headKey == null) {
                        PlacementPlan fresh = plugin.locator().findPlan(deathLocation(record, world));
                        if (fresh != null) {
                            headKey = fresh.head();
                        }
                    }
                }

                return new PlacementPlan(chestKey, headKey);
            }
        }

        return plugin.locator().findPlan(deathLocation(record, world));
    }

    private Location deathLocation(EvidenceRecord record, World world) {
        return new Location(world, record.deathX, record.deathY, record.deathZ);
    }

    private void applyChestFacing(Block block) {
        try {
            BlockData data = block.getBlockData();
            if (data instanceof org.bukkit.block.data.type.Chest chestData) {
                chestData.setFacing(BlockFace.NORTH);
                block.setBlockData(chestData, false);
            }
        } catch (Throwable ignored) {
            // Facing is cosmetic; the chest remains valid.
        }
    }

    /**
     * @param existing stacks found in the chest
     * @param payload  full stored payload
     * @return true when every stack in {@code existing} is part of the payload
     */
    private boolean isSubset(List<ItemStack> existing, List<ItemStack> payload) {
        List<ItemStack> available = copy(payload);

        for (ItemStack item : existing) {
            if (!consume(available, item)) {
                return false;
            }
        }

        return true;
    }

    private List<ItemStack> subtract(List<ItemStack> payload, List<ItemStack> existing) {
        List<ItemStack> remaining = copy(payload);

        for (ItemStack item : existing) {
            ItemStack leftover = consumePartially(remaining, item);
            if (leftover != null && leftover.getAmount() > 0) {
                remaining.add(leftover);
            }
        }

        return remaining;
    }

    private boolean consume(List<ItemStack> available, ItemStack item) {
        ItemStack leftover = consumePartially(available, item);
        return leftover == null || leftover.getAmount() <= 0;
    }

    private ItemStack consumePartially(List<ItemStack> available, ItemStack item) {
        int remaining = item.getAmount();

        for (ItemStack candidate : available) {
            if (remaining <= 0) {
                break;
            }
            if (!candidate.isSimilar(item)) {
                continue;
            }

            int taken = Math.min(candidate.getAmount(), remaining);
            candidate.setAmount(candidate.getAmount() - taken);
            remaining -= taken;
        }

        available.removeIf(candidate -> candidate.getAmount() <= 0);

        if (remaining <= 0) {
            return null;
        }

        ItemStack missing = item.clone();
        missing.setAmount(remaining);
        return missing;
    }

    private List<ItemStack> copy(List<ItemStack> items) {
        List<ItemStack> copy = new ArrayList<>();
        for (ItemStack item : items) {
            if (!ItemCodec.isEmpty(item)) {
                copy.add(item.clone());
            }
        }
        return copy;
    }
}
