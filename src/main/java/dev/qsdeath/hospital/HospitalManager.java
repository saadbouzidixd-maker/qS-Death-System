/*
 * qS Death System
 * Copyright (C) qS Death System contributors.
 *
 * This file is part of a derivative work of GravesX, GPL-3.0-only.
 */
package dev.qsdeath.hospital;

import dev.qsdeath.QSDeathSystem;
import dev.qsdeath.storage.HospitalRecord;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerRespawnEvent;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * The single hospital operating-room spawn.
 *
 * <p>Deceased players are transferred here through {@link PlayerRespawnEvent};
 * there is deliberately no player-facing teleport command.</p>
 */
public final class HospitalManager {

    private final QSDeathSystem plugin;
    private final AtomicBoolean missingWarned = new AtomicBoolean();
    private volatile HospitalRecord hospital;

    public HospitalManager(QSDeathSystem plugin) {
        this.plugin = plugin;
    }

    /**
     * Loads the hospital location during startup.
     */
    public void load() {
        try {
            hospital = plugin.storage().loadHospital().get(15, TimeUnit.SECONDS);
        } catch (Exception exception) {
            plugin.getLogger().log(Level.SEVERE, "Could not load the hospital location", exception);
        }
    }

    /**
     * @return configured hospital record or {@code null}
     */
    public HospitalRecord record() {
        return hospital;
    }

    /**
     * @return true when a hospital location is stored
     */
    public boolean isConfigured() {
        return hospital != null;
    }

    /**
     * @return true when the hospital can currently be used
     */
    public boolean isUsable() {
        return validationError() == null;
    }

    /**
     * @return reason the hospital cannot be used, or {@code null}
     */
    public String validationError() {
        HospitalRecord current = hospital;

        if (current == null) {
            return "not configured";
        }

        World world = current.worldUuid != null
                ? Bukkit.getWorld(current.worldUuid)
                : Bukkit.getWorld(current.worldName);

        if (world == null) {
            return "world '" + current.worldName + "' is not loaded";
        }

        if (current.y <= world.getMinHeight() || current.y >= world.getMaxHeight()) {
            return "y coordinate is outside the world build limits";
        }

        return null;
    }

    /**
     * @return usable hospital location or {@code null}
     */
    public Location location() {
        if (validationError() != null) {
            return null;
        }

        HospitalRecord current = hospital;
        World world = current.worldUuid != null
                ? Bukkit.getWorld(current.worldUuid)
                : Bukkit.getWorld(current.worldName);

        if (world == null) {
            return null;
        }

        return new Location(world, current.x, current.y, current.z, current.yaw, current.pitch);
    }

    /**
     * Stores a new hospital location.
     *
     * @param location exact administrator location
     * @return future completed after the database commit
     */
    public CompletableFuture<Void> set(Location location) {
        HospitalRecord record = new HospitalRecord();
        record.worldUuid = location.getWorld().getUID();
        record.worldName = location.getWorld().getName();
        record.x = location.getX();
        record.y = location.getY();
        record.z = location.getZ();
        record.yaw = location.getYaw();
        record.pitch = location.getPitch();
        record.updatedAt = System.currentTimeMillis();

        CompletableFuture<Void> future = plugin.storage().saveHospital(record);
        future.thenRun(() -> {
            hospital = record;
            missingWarned.set(false);
        });
        return future;
    }

    /**
     * Applies the automatic hospital transfer on respawn.
     *
     * @param event respawn event
     */
    public void applyRespawn(PlayerRespawnEvent event) {
        if (!plugin.config().hospitalEnabled()) {
            return;
        }

        Player player = event.getPlayer();
        Location target = location();

        if (target != null) {
            event.setRespawnLocation(target);
            plugin.scheduler().later(() -> player.sendMessage(plugin.config().prefix()
                    + plugin.config().message("death.hospital")), 2L);
            return;
        }

        warnIfUnconfigured();

        if ("world-spawn".equals(plugin.config().hospitalFallback())) {
            World fallback = fallbackWorld();
            if (fallback != null) {
                event.setRespawnLocation(fallback.getSpawnLocation());
                plugin.scheduler().later(() -> player.sendMessage(plugin.config().prefix()
                        + plugin.config().message("death.no-hospital")), 2L);
            }
        }
    }

    /**
     * Logs one clear console warning when players die without a usable hospital.
     */
    public void warnIfUnconfigured() {
        if (!plugin.config().warnMissingHospital() || isUsable()) {
            return;
        }

        if (missingWarned.compareAndSet(false, true)) {
            plugin.getLogger().warning("A player died without a usable hospital location ("
                    + validationError() + "). Configure it with /hospital set.");
        }
    }

    private World fallbackWorld() {
        String configured = plugin.config().hospitalFallbackWorld();

        if (configured != null && !configured.isBlank()) {
            World world = Bukkit.getWorld(configured);
            if (world != null) {
                return world;
            }
        }

        return Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
    }
}
