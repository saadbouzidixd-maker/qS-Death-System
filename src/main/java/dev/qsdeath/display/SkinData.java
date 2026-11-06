/*
 * qS Death System
 * Copyright (C) qS Death System contributors.
 *
 * This file is part of a derivative work of GravesX
 * (https://github.com/Legoman99573/GravesX), originally created by Ranull.
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License, version 3 only.
 */
package dev.qsdeath.display;

import com.destroystokyo.paper.profile.ProfileProperty;
import org.bukkit.entity.Player;

/**
 * Skin data captured from a player's profile.
 *
 * @param value     signed textures property value, if available
 * @param signature signed textures property signature, if available
 * @param url       skin URL, if available
 */
public record SkinData(String value, String signature, String url) {

    /**
     * Captures the victim's current skin. Never throws: a missing skin simply
     * falls back to the offline-player lookup when the head is created.
     *
     * @param player player
     * @return captured skin data
     */
    public static SkinData capture(Player player) {
        String value = null;
        String signature = null;
        String url = null;

        try {
            var profile = player.getPlayerProfile();
            if (profile != null) {
                for (ProfileProperty property : profile.getProperties()) {
                    if ("textures".equals(property.getName())) {
                        value = property.getValue();
                        signature = property.getSignature();
                        break;
                    }
                }

                try {
                    var textures = profile.getTextures();
                    if (textures != null && textures.getSkin() != null) {
                        url = textures.getSkin().toString();
                    }
                } catch (Throwable ignored) {
                    // Signed property is enough when the URL cannot be read.
                }
            }
        } catch (Throwable ignored) {
            // Skin capture must never break death handling.
        }

        return new SkinData(value, signature, url);
    }
}
