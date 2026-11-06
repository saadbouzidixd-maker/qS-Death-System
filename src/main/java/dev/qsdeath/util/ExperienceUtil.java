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
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for details.
 */
package dev.qsdeath.util;

/**
 * Experience helpers.
 *
 * <p>Ported from GravesX ({@code com.ranull.graves.util.ExperienceUtil}) so the
 * amount stored in an evidence record matches the original project's behaviour.
 * The Bukkit-serialisation approach of GravesX ({@code InventoryUtil},
 * {@code Base64Util}) is likewise the basis of {@link ItemCodec}.</p>
 */
public final class ExperienceUtil {

    private ExperienceUtil() {
    }

    /**
     * Calculates the total amount of experience a player currently holds,
     * including progress towards the next level.
     *
     * @param level          current level
     * @param progressToNext progress towards the next level (0.0 - 1.0)
     * @return total experience points, or -1 for invalid input
     */
    public static int getTotalExperience(int level, float progressToNext) {
        if (level < 0) {
            return -1;
        }

        double progress = Math.max(0.0F, Math.min(1.0F, progressToNext));
        int experience = (int) Math.round(getExperienceAtLevel(level) * progress);

        int remaining = level;
        while (remaining > 0) {
            remaining--;
            experience += getExperienceAtLevel(remaining);
        }

        return Math.max(experience, 0);
    }

    /**
     * Experience required to advance from {@code level} to {@code level + 1}.
     *
     * @param level the level
     * @return the experience required for the next level
     */
    public static int getExperienceAtLevel(int level) {
        if (level <= 15) {
            return 2 * level + 7;
        }
        if (level <= 30) {
            return 5 * level - 38;
        }
        return 9 * level - 158;
    }

    /**
     * Converts a total experience amount to the level it represents.
     *
     * @param experience total experience, must be non-negative
     * @return the level for the given experience
     */
    public static int getLevelFromExperience(long experience) {
        if (experience <= 0) {
            return 0;
        }

        double result;
        if (experience > 1395) {
            result = (Math.sqrt(72 * experience - 54215) + 325) / 18;
        } else if (experience > 315) {
            result = Math.sqrt(40 * experience - 7839) / 10 + 8.1;
        } else {
            result = Math.sqrt(experience + 9) - 3;
        }

        return (int) Math.max(0, Math.floor(result));
    }
}
