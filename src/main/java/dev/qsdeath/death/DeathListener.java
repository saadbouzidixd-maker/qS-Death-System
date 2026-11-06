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
import org.bukkit.event.entity.PlayerDeathEvent;

/**
 * Routes player deaths into {@link DeathService} after other plugins had a
 * chance to run, so nothing is captured twice and the final drop list can be
 * cleared reliably.
 */
public final class DeathListener implements Listener {

    private final QSDeathSystem plugin;

    public DeathListener(QSDeathSystem plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        plugin.deathService().handleDeath(event);
    }
}
