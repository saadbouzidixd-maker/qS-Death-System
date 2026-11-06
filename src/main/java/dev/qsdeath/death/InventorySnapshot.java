/*
 * qS Death System
 * Copyright (C) qS Death System contributors.
 *
 * This file is part of a derivative work of GravesX, GPL-3.0-only.
 */
package dev.qsdeath.death;

import dev.qsdeath.util.ExperienceUtil;
import dev.qsdeath.util.ItemCodec;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.List;

/**
 * Immutable death-time copy of a player's inventory and experience.
 */
public final class InventorySnapshot {

    private final ItemStack[] storage;
    private final ItemStack[] armor;
    private final ItemStack[] offhand;
    private final int totalExperience;
    private final int level;

    private InventorySnapshot(ItemStack[] storage, ItemStack[] armor, ItemStack[] offhand,
                              int totalExperience, int level) {
        this.storage = storage;
        this.armor = armor;
        this.offhand = offhand;
        this.totalExperience = totalExperience;
        this.level = level;
    }

    /**
     * Captures the player's main inventory, armour, offhand and experience.
     *
     * @param player             player
     * @param captureExperience  whether experience should be captured
     * @return snapshot with deep copies
     */
    public static InventorySnapshot capture(Player player, boolean captureExperience) {
        PlayerInventory inventory = player.getInventory();

        ItemStack[] storage = copy(inventory.getStorageContents());
        ItemStack[] armor = copy(inventory.getArmorContents());
        ItemStack[] offhand = new ItemStack[]{ItemCodec.copyOf(inventory.getItemInOffHand())};

        int experience = captureExperience
                ? ExperienceUtil.getTotalExperience(player.getLevel(), player.getExp())
                : 0;
        int level = captureExperience ? player.getLevel() : 0;

        return new InventorySnapshot(storage, armor, offhand, experience, level);
    }

    private static ItemStack[] copy(ItemStack[] items) {
        if (items == null) {
            return new ItemStack[0];
        }

        ItemStack[] copy = new ItemStack[items.length];
        for (int index = 0; index < items.length; index++) {
            copy[index] = ItemCodec.copyOf(items[index]);
        }
        return copy;
    }

    /**
     * @return main inventory slots
     */
    public ItemStack[] storage() {
        return storage;
    }

    /**
     * @return armour slots
     */
    public ItemStack[] armor() {
        return armor;
    }

    /**
     * @return offhand slots
     */
    public ItemStack[] offhand() {
        return offhand;
    }

    /**
     * @return total experience points at death
     */
    public int totalExperience() {
        return totalExperience;
    }

    /**
     * @return level at death
     */
    public int level() {
        return level;
    }

    /**
     * @return every captured item as deep copies
     */
    public List<ItemStack> allItems() {
        return ItemCodec.flatten(storage, armor, offhand);
    }
}
