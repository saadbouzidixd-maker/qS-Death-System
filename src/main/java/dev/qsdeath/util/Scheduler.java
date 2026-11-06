/*
 * qS Death System
 * Copyright (C) qS Death System contributors.
 *
 * This file is part of a derivative work of GravesX, GPL-3.0-only.
 */
package dev.qsdeath.util;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/**
 * Small scheduler facade. All world/entity mutations must run through
 * {@link #sync(Runnable)} so they execute on the server thread.
 */
public final class Scheduler {

    private final Plugin plugin;

    public Scheduler(Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Runs a task on the server thread, immediately when already on it.
     *
     * @param task task
     */
    public void sync(Runnable task) {
        if (!plugin.isEnabled() && !Bukkit.isPrimaryThread()) {
            return;
        }

        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    /**
     * Runs a task on the server thread after the given delay.
     *
     * @param task  task
     * @param ticks delay in ticks
     */
    public void later(Runnable task, long ticks) {
        Bukkit.getScheduler().runTaskLater(plugin, task, Math.max(1L, ticks));
    }

    /**
     * Runs a task asynchronously.
     *
     * @param task task
     */
    public void async(Runnable task) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
    }
}
