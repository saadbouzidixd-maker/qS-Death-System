/*
 * qS Death System
 * Copyright (C) qS Death System contributors.
 *
 * This file is part of a derivative work of GravesX
 * (https://github.com/Legoman99573/GravesX), originally created by Ranull.
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation, version 3 only (GPL-3.0-only).
 */
package dev.qsdeath.util;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Serialises inventories to a compact Base64 payload.
 *
 * <p>Based on the Bukkit object-stream approach used by GravesX
 * ({@code Base64Util}/{@code InventoryUtil}). Every slot is written with an
 * explicit null marker so that empty slots and slot indices survive a round
 * trip. Data is never silently replaced: corrupt payloads raise an
 * {@link ItemCodecException} so callers can keep the physical items instead of
 * overwriting them with air.</p>
 */
public final class ItemCodec {

    private ItemCodec() {
    }

    /**
     * Encodes an array of item stacks (null slots allowed).
     *
     * @param items item array, may be {@code null}
     * @return Base64 payload
     * @throws ItemCodecException when the items cannot be serialised
     */
    public static String encode(ItemStack[] items) {
        ItemStack[] safeItems = items == null ? new ItemStack[0] : items;

        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             BukkitObjectOutputStream out = new BukkitObjectOutputStream(bytes)) {

            out.writeInt(safeItems.length);
            for (ItemStack item : safeItems) {
                out.writeBoolean(item != null && item.getType() != Material.AIR);
                if (item != null && item.getType() != Material.AIR) {
                    out.writeObject(item);
                }
            }
            out.flush();

            return Base64.getEncoder().encodeToString(bytes.toByteArray());
        } catch (Exception exception) {
            throw new ItemCodecException("Could not serialise item data", exception);
        }
    }

    /**
     * Decodes a Base64 payload produced by {@link #encode(ItemStack[])}.
     *
     * @param payload encoded payload, may be {@code null} or blank
     * @param logger  logger used for corrupt payload details, may be {@code null}
     * @return decoded array, never {@code null}
     * @throws ItemCodecException when the payload is corrupt
     */
    public static ItemStack[] decode(String payload, Logger logger) {
        if (payload == null || payload.isBlank()) {
            return new ItemStack[0];
        }

        try {
            byte[] data = Base64.getDecoder().decode(payload);

            try (ByteArrayInputStream bytes = new ByteArrayInputStream(data);
                 BukkitObjectInputStream in = new BukkitObjectInputStream(bytes)) {

                int length = in.readInt();
                if (length < 0 || length > 4096) {
                    throw new ItemCodecException("Invalid inventory slot count: " + length);
                }

                ItemStack[] items = new ItemStack[length];
                for (int slot = 0; slot < length; slot++) {
                    if (!in.readBoolean()) {
                        continue;
                    }

                    Object read = in.readObject();
                    if (read instanceof ItemStack stack && stack.getType() != Material.AIR) {
                        items[slot] = stack;
                    }
                }

                return items;
            }
        } catch (ItemCodecException exception) {
            throw exception;
        } catch (Exception exception) {
            if (logger != null) {
                logger.log(Level.WARNING, "Corrupt inventory payload (" + exception.getMessage()
                        + "); the stored inventory could not be decoded.", exception);
            }
            throw new ItemCodecException("Corrupt inventory payload", exception);
        }
    }

    /**
     * Decodes a payload and returns an empty array instead of throwing. Only use
     * this for read-only listings where losing display data is acceptable.
     *
     * @param payload payload
     * @param logger  logger
     * @return decoded items, empty on failure
     */
    public static ItemStack[] decodeLenient(String payload, Logger logger) {
        try {
            return decode(payload, logger);
        } catch (ItemCodecException ignored) {
            return new ItemStack[0];
        }
    }

    /**
     * @param item item stack, may be {@code null}
     * @return true when the stack is null, air or has zero amount
     */
    public static boolean isEmpty(ItemStack item) {
        return item == null || item.getType() == Material.AIR || item.getAmount() <= 0;
    }

    /**
     * Deep copies a stack.
     *
     * @param item item stack
     * @return copy or {@code null}
     */
    public static ItemStack copyOf(ItemStack item) {
        return isEmpty(item) ? null : item.clone();
    }

    /**
     * Deep copies every non-empty stack of the given arrays.
     *
     * @param arrays item arrays
     * @return flattened, copied list
     */
    public static List<ItemStack> flatten(ItemStack[]... arrays) {
        List<ItemStack> items = new ArrayList<>();
        if (arrays == null) {
            return items;
        }

        for (ItemStack[] array : arrays) {
            if (array == null) {
                continue;
            }
            for (ItemStack item : array) {
                if (!isEmpty(item)) {
                    items.add(item.clone());
                }
            }
        }

        return items;
    }

    /**
     * Merges identical stacks (ignoring amount) up to the material stack size.
     * This maximises how many items fit into a single evidence chest.
     *
     * @param input items to merge
     * @return merged copies
     */
    public static List<ItemStack> mergeSimilar(List<ItemStack> input) {
        List<ItemStack> merged = new ArrayList<>();
        if (input == null) {
            return merged;
        }

        for (ItemStack original : input) {
            if (isEmpty(original)) {
                continue;
            }

            ItemStack remaining = original.clone();
            int maxStackSize = Math.max(1, remaining.getMaxStackSize());

            for (ItemStack target : merged) {
                if (remaining.getAmount() <= 0) {
                    break;
                }
                if (!target.isSimilar(remaining)) {
                    continue;
                }

                int space = maxStackSize - target.getAmount();
                if (space <= 0) {
                    continue;
                }

                int moved = Math.min(space, remaining.getAmount());
                target.setAmount(target.getAmount() + moved);
                remaining.setAmount(remaining.getAmount() - moved);
            }

            while (remaining.getAmount() > 0) {
                ItemStack split = remaining.clone();
                int amount = Math.min(maxStackSize, remaining.getAmount());
                split.setAmount(amount);
                remaining.setAmount(remaining.getAmount() - amount);
                merged.add(split);
            }
        }

        return merged;
    }

    /**
     * @param items flattened item list
     * @return array representation
     */
    public static ItemStack[] toArray(List<ItemStack> items) {
        return items == null ? new ItemStack[0] : items.toArray(new ItemStack[0]);
    }

    /**
     * Counts non-empty stacks in the given arrays.
     *
     * @param arrays item arrays
     * @return number of item stacks
     */
    public static int countStacks(ItemStack[]... arrays) {
        return flatten(arrays).size();
    }

    /**
     * Counts individual items (respecting stack amounts).
     *
     * @param arrays item arrays
     * @return total amount
     */
    public static int countItems(ItemStack[]... arrays) {
        int count = 0;
        for (ItemStack item : flatten(arrays)) {
            count += item.getAmount();
        }
        return count;
    }

    /**
     * Checks whether both inventories contain the same stacks and amounts.
     *
     * @param left  first inventory
     * @param right second inventory
     * @return true when both hold the same items
     */
    public static boolean sameContents(ItemStack[] left, ItemStack[] right) {
        List<ItemStack> leftItems = flatten(left);
        List<ItemStack> rightItems = flatten(right);

        if (leftItems.size() != rightItems.size()) {
            return false;
        }

        boolean[] matched = new boolean[rightItems.size()];
        for (ItemStack item : leftItems) {
            boolean found = false;

            for (int index = 0; index < rightItems.size(); index++) {
                if (matched[index]) {
                    continue;
                }

                ItemStack candidate = rightItems.get(index);
                if (candidate.isSimilar(item) && candidate.getAmount() == item.getAmount()) {
                    matched[index] = true;
                    found = true;
                    break;
                }
            }

            if (!found) {
                return false;
            }
        }

        return true;
    }

    /**
     * Thrown when item data cannot be serialised or decoded.
     */
    public static final class ItemCodecException extends RuntimeException {
        public ItemCodecException(String message) {
            super(message);
        }

        public ItemCodecException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
