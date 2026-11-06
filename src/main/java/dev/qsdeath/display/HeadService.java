/*
 * qS Death System
 * Copyright (C) qS Death System contributors.
 *
 * This file is part of a derivative work of GravesX
 * (https://github.com/Legoman99573/GravesX), originally created by Ranull.
 *
 * The profile/texture handling follows the approach used by GravesX
 * (SkinTextureUtil / SkinTextureUtil_post_1_21_9).
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License, version 3 only.
 */
package dev.qsdeath.display;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import dev.qsdeath.QSDeathSystem;
import dev.qsdeath.evidence.EvidenceRecord;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Skull;
import org.bukkit.profile.PlayerTextures;

import java.net.URI;
import java.util.UUID;

/**
 * Places and configures the victim's real player head.
 *
 * <p>Skin data is captured at death (signed {@code textures} property when
 * available, otherwise the skin URL). Applying the signed property makes the
 * head keep the victim's real skin even when the victim is offline or the
 * server restarts.</p>
 */
public final class HeadService {

    private final QSDeathSystem plugin;

    public HeadService(QSDeathSystem plugin) {
        this.plugin = plugin;
    }

    /**
     * Ensures the player head for an evidence record exists at the given block.
     *
     * @param block  target block
     * @param record evidence record
     * @return true when a head block with a profile was applied
     */
    public boolean place(Block block, EvidenceRecord record) {
        if (block == null) {
            return false;
        }

        if (block.getType() != Material.PLAYER_HEAD) {
            if (!plugin.locator().isReplaceable(block)) {
                return false;
            }
            block.setType(Material.PLAYER_HEAD, false);
        }

        BlockState state = block.getState();
        if (!(state instanceof Skull skull)) {
            plugin.getLogger().warning("Could not read skull state at " + block.getLocation());
            return false;
        }

        boolean applied = applyProfile(skull, record);
        skull.update(true, false);
        return applied;
    }

    /**
     * Applies skin data to a skull block state.
     *
     * @param skull  skull
     * @param record evidence record
     * @return true when some profile data was applied
     */
    private boolean applyProfile(Skull skull, EvidenceRecord record) {
        UUID victimUuid = record.victimUuid != null ? record.victimUuid : UUID.randomUUID();
        String victimName = record.victimName == null || record.victimName.isBlank() ? "Unknown" : record.victimName;

        if (record.skinValue != null && !record.skinValue.isBlank()) {
            try {
                PlayerProfile profile = Bukkit.createProfile(victimUuid, victimName);
                ProfileProperty property = record.skinSignature != null && !record.skinSignature.isBlank()
                        ? new ProfileProperty("textures", record.skinValue, record.skinSignature)
                        : new ProfileProperty("textures", record.skinValue);
                profile.setProperty(property);
                skull.setPlayerProfile(profile);
                return true;
            } catch (Throwable throwable) {
                plugin.getLogger().fine("Signed texture profile failed for " + record.victimName
                        + ": " + throwable.getMessage());
            }
        }

        if (record.skinUrl != null && !record.skinUrl.isBlank()) {
            try {
                org.bukkit.profile.PlayerProfile profile = Bukkit.createPlayerProfile(victimUuid, victimName);
                PlayerTextures textures = profile.getTextures();
                textures.setSkin(URI.create(record.skinUrl).toURL());
                profile.setTextures(textures);
                skull.setOwnerProfile(profile);
                return true;
            } catch (Throwable throwable) {
                plugin.getLogger().fine("Skin URL profile failed for " + record.victimName
                        + ": " + throwable.getMessage());
            }
        }

        // Last resort: let the server resolve the player's cached profile.
        try {
            skull.setOwningPlayer(Bukkit.getOfflinePlayer(victimUuid));
            return true;
        } catch (Throwable throwable) {
            plugin.getLogger().warning("Could not apply any skin profile for " + record.victimName);
            return false;
        }
    }
}
