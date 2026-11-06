/*
 * qS Death System
 * Copyright (C) qS Death System contributors.
 *
 * This file is part of a derivative work of GravesX, GPL-3.0-only.
 */
package dev.qsdeath.death;

import dev.qsdeath.QSDeathSystem;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerRespawnEvent;

/**
 * Applies the single automatic hospital transfer. No player-facing teleport
 * command exists.
 */
public final class RespawnListener implements Listener {

    private final QSDeathSystem plugin;

    public RespawnListener(QSDeathSystem plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent event) {
        plugin.hospital().applyRespawn(event);
    }
}
