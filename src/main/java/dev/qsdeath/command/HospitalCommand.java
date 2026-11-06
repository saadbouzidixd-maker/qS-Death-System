/*
 * qS Death System
 * Copyright (C) qS Death System contributors.
 *
 * This file is part of a derivative work of GravesX, GPL-3.0-only.
 */
package dev.qsdeath.command;

import dev.qsdeath.QSDeathSystem;
import dev.qsdeath.storage.HospitalRecord;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.logging.Level;

/**
 * {@code /hospital set|teleport|info}. Administrative only.
 */
public final class HospitalCommand implements CommandExecutor, TabCompleter {

    private final QSDeathSystem plugin;

    public HospitalCommand(QSDeathSystem plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String subcommand = args.length == 0 ? "info" : args[0].toLowerCase(Locale.ROOT);

        switch (subcommand) {
            case "set" -> set(sender);
            case "teleport" -> teleport(sender);
            case "info" -> info(sender);
            default -> sender.sendMessage(plugin.config().prefix()
                    + plugin.config().message("usage.hospital"));
        }

        return true;
    }

    private void set(CommandSender sender) {
        if (!require(sender, "qsds.hospital.set")) {
            return;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.config().prefix() + plugin.config().message("player-only"));
            return;
        }

        Location location = player.getLocation();

        plugin.hospital().set(location).whenComplete((ignored, error) -> plugin.scheduler().sync(() -> {
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "Could not save the hospital location", error);
                sender.sendMessage(plugin.config().prefix() + plugin.config().message("error.database"));
                return;
            }

            sender.sendMessage(plugin.config().prefix() + plugin.config().message("hospital.set",
                    "world", location.getWorld() == null ? "?" : location.getWorld().getName(),
                    "x", format(location.getX()),
                    "y", format(location.getY()),
                    "z", format(location.getZ())));
        }));
    }

    private void teleport(CommandSender sender) {
        if (!require(sender, "qsds.hospital.teleport")) {
            return;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.config().prefix() + plugin.config().message("player-only"));
            return;
        }

        Location target = plugin.hospital().location();

        if (target == null) {
            if (plugin.hospital().isConfigured()) {
                sender.sendMessage(plugin.config().prefix() + plugin.config().message("hospital.invalid",
                        "reason", String.valueOf(plugin.hospital().validationError())));
            } else {
                sender.sendMessage(plugin.config().prefix() + plugin.config().message("hospital.not-configured"));
            }
            return;
        }

        sender.sendMessage(plugin.config().prefix() + plugin.config().message("hospital.teleporting"));
        player.teleport(target);
    }

    private void info(CommandSender sender) {
        if (!require(sender, "qsds.hospital.info")) {
            return;
        }

        sender.sendMessage(plugin.config().message("hospital.info-header"));
        HospitalRecord record = plugin.hospital().record();

        if (record == null) {
            sender.sendMessage(plugin.config().prefix() + plugin.config().message("hospital.not-configured"));
            return;
        }

        sender.sendMessage(plugin.config().message("hospital.info-world", "world", record.worldName));
        sender.sendMessage(plugin.config().message("hospital.info-coordinates",
                "x", format(record.x), "y", format(record.y), "z", format(record.z)));
        sender.sendMessage(plugin.config().message("hospital.info-rotation",
                "yaw", format(record.yaw), "pitch", format(record.pitch)));

        String status = plugin.hospital().isUsable()
                ? plugin.config().message("hospital.valid")
                : plugin.config().message("hospital.invalid-status",
                "reason", String.valueOf(plugin.hospital().validationError()));

        sender.sendMessage(plugin.config().message("hospital.info-status", "status", status));
    }

    private boolean require(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) {
            return true;
        }

        sender.sendMessage(plugin.config().prefix() + plugin.config().message("no-permission"));
        return false;
    }

    private String format(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("set", "teleport", "info").stream()
                    .filter(option -> option.startsWith(args[0].toLowerCase(Locale.ROOT)))
                    .toList();
        }

        return List.of();
    }
}
