/*
 * qS Death System
 * Copyright (C) qS Death System contributors.
 *
 * This file is part of a derivative work of GravesX, GPL-3.0-only.
 */
package dev.qsdeath.command;

import dev.qsdeath.QSDeathSystem;
import dev.qsdeath.evidence.EvidenceRecord;
import dev.qsdeath.evidence.EvidenceStatus;
import dev.qsdeath.storage.ArchiveRecord;
import dev.qsdeath.storage.StorageStats;
import dev.qsdeath.util.ItemCodec;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;

/**
 * {@code /qsds} administration: police tool, evidence inspection, archive
 * recovery, reload, status and diagnostics.
 */
public final class QSDSCommand implements CommandExecutor, TabCompleter {

    private final QSDeathSystem plugin;

    public QSDSCommand(QSDeathSystem plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            help(sender, label);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "help" -> help(sender, label);
            case "tool" -> tool(sender, label, args);
            case "evidence" -> evidence(sender, label, args);
            case "reload" -> reload(sender);
            case "status" -> status(sender);
            case "debug" -> debug(sender, args);
            default -> sender.sendMessage(plugin.config().prefix()
                    + plugin.config().message("unknown-command", "command", label));
        }

        return true;
    }

    private void help(CommandSender sender, String label) {
        sender.sendMessage(plugin.config().message("status.header"));
        sender.sendMessage(plugin.config().color("&7/" + label + " tool give [player] &8- &fgive the police shovel"));
        sender.sendMessage(plugin.config().color("&7/" + label + " evidence list [active|archived|all] [page]"));
        sender.sendMessage(plugin.config().color("&7/" + label + " evidence info <id>"));
        sender.sendMessage(plugin.config().color("&7/" + label + " evidence recover <id> [player]"));
        sender.sendMessage(plugin.config().color("&7/" + label + " status &8- &fplugin and database status"));
        sender.sendMessage(plugin.config().color("&7/" + label + " reload"));
        sender.sendMessage(plugin.config().color("&7/hospital set|teleport|info"));
    }

    private void tool(CommandSender sender, String label, String[] args) {
        if (!require(sender, "qscrimescenes.police.give")) {
            return;
        }

        if (args.length < 2 || !args[1].equalsIgnoreCase("give")) {
            sender.sendMessage(plugin.config().prefix() + plugin.config().message("usage.tool"));
            return;
        }

        Player target;

        if (args.length >= 3) {
            target = Bukkit.getPlayerExact(args[2]);
        } else if (sender instanceof Player player) {
            target = player;
        } else {
            sender.sendMessage(plugin.config().prefix() + plugin.config().message("player-only"));
            return;
        }

        if (target == null) {
            sender.sendMessage(plugin.config().prefix() + plugin.config().message("evidence.not-found",
                    "id", args.length >= 3 ? args[2] : "?"));
            return;
        }

        ItemStack tool = plugin.policeTool().create();
        Map<Integer, ItemStack> leftovers = target.getInventory().addItem(tool);
        for (ItemStack leftover : leftovers.values()) {
            target.getWorld().dropItemNaturally(target.getLocation(), leftover);
        }

        target.sendMessage(plugin.config().prefix() + plugin.config().message("police.tool-received"));
        sender.sendMessage(plugin.config().prefix() + plugin.config().message("police.tool-given",
                "player", target.getName()));
    }

    private void evidence(CommandSender sender, String label, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(plugin.config().prefix() + plugin.config().message("usage.evidence"));
            return;
        }

        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "list" -> list(sender, args);
            case "info" -> info(sender, args);
            case "recover" -> recover(sender, args);
            default -> sender.sendMessage(plugin.config().prefix() + plugin.config().message("usage.evidence"));
        }
    }

    private void list(CommandSender sender, String[] args) {
        if (!require(sender, "qsds.evidence.list")) {
            return;
        }

        String filter = args.length >= 3 ? args[2].toLowerCase(Locale.ROOT) : "active";
        int page = args.length >= 4 ? parsePage(args[3]) : 1;
        int pageSize = plugin.config().listPageSize();
        int offset = (page - 1) * pageSize;

        sender.sendMessage(plugin.config().prefix() + plugin.config().message("evidence.processing"));

        if ("archived".equals(filter)) {
            plugin.storage().countArchives(null).thenAccept(total ->
                    plugin.storage().listArchives(pageSize, offset).thenAccept(archives ->
                            plugin.scheduler().sync(() -> printArchives(sender, archives, filter, page, total))));
            return;
        }

        EvidenceStatus status = switch (filter) {
            case "all" -> null;
            case "preparing" -> EvidenceStatus.PREPARING;
            case "processing" -> EvidenceStatus.PROCESSING;
            case "processed" -> EvidenceStatus.PROCESSED;
            default -> EvidenceStatus.ACTIVE;
        };

        plugin.storage().countEvidence(status).thenAccept(total ->
                plugin.storage().listEvidence(status, pageSize, offset).thenAccept(records ->
                        plugin.scheduler().sync(() -> printEvidence(sender, records, filter, page, total))));
    }

    private void printEvidence(CommandSender sender, List<EvidenceRecord> records, String filter,
                               int page, long total) {
        sender.sendMessage(plugin.config().message("evidence.list-header",
                "status", filter.toUpperCase(Locale.ROOT),
                "page", page + "/" + Math.max(1, (total + plugin.config().listPageSize() - 1)
                        / plugin.config().listPageSize())));

        if (records.isEmpty()) {
            sender.sendMessage(plugin.config().message("evidence.list-empty"));
            return;
        }

        for (EvidenceRecord record : records) {
            sender.sendMessage(plugin.config().message("evidence.list-line",
                    "id", Long.toString(record.id),
                    "victim", record.victimName,
                    "status", record.status,
                    "world", record.worldName,
                    "x", Integer.toString(coordinate(record.deathX)),
                    "y", Integer.toString(coordinate(record.deathY)),
                    "z", Integer.toString(coordinate(record.deathZ)),
                    "date", plugin.config().time().date(record.deathTime),
                    "time", plugin.config().time().time(record.deathTime)));
        }

        sender.sendMessage(plugin.config().message("evidence.list-footer"));
    }

    private void printArchives(CommandSender sender, List<ArchiveRecord> archives, String filter,
                               int page, long total) {
        sender.sendMessage(plugin.config().message("evidence.list-header",
                "status", filter.toUpperCase(Locale.ROOT),
                "page", page + "/" + Math.max(1, (total + plugin.config().listPageSize() - 1)
                        / plugin.config().listPageSize())));

        if (archives.isEmpty()) {
            sender.sendMessage(plugin.config().message("evidence.list-empty"));
            return;
        }

        for (ArchiveRecord archive : archives) {
            sender.sendMessage(plugin.config().color("&7#" + archive.evidenceId
                    + " &8- &f" + archive.itemCount + " stacks"
                    + " &8- &7" + (archive.recovered ? "recovered" : "stored")
                    + " &8- &7" + plugin.config().time().dateTime(archive.archivedAt)
                    + " &8- &7by " + (archive.archivedByName == null ? "?" : archive.archivedByName)));
        }

        sender.sendMessage(plugin.config().message("evidence.list-footer"));
    }

    private void info(CommandSender sender, String[] args) {
        if (!require(sender, "qsds.evidence.info")) {
            return;
        }

        if (args.length < 3) {
            sender.sendMessage(plugin.config().prefix() + plugin.config().message("usage.evidence"));
            return;
        }

        long id = parseId(args[2]);
        if (id < 0) {
            sender.sendMessage(plugin.config().prefix() + plugin.config().message("evidence.not-found",
                    "id", args[2]));
            return;
        }

        plugin.storage().findEvidence(id).thenCompose(record -> {
            if (record == null) {
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            }
            return plugin.storage().findArchive(id).thenApply(archive -> new Object[]{record, archive});
        }).whenComplete((result, error) -> plugin.scheduler().sync(() -> {
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "Could not read evidence #" + id, error);
                sender.sendMessage(plugin.config().prefix() + plugin.config().message("error.database"));
                return;
            }

            if (result == null) {
                sender.sendMessage(plugin.config().prefix() + plugin.config().message("evidence.not-found",
                        "id", Long.toString(id)));
                return;
            }

            printInfo(sender, (EvidenceRecord) result[0], (ArchiveRecord) result[1]);
        }));
    }

    private void printInfo(CommandSender sender, EvidenceRecord record, ArchiveRecord archive) {
        sender.sendMessage(plugin.config().message("evidence.info-header", "id", Long.toString(record.id)));

        line(sender, "Status", record.status);
        line(sender, "Victim", record.victimName);
        line(sender, "Victim UUID", String.valueOf(record.victimUuid));
        line(sender, "Death cause", record.deathCause);
        line(sender, "Killer", record.isKillerKnown() ? record.killerName : "unknown");
        line(sender, "World", record.worldName);
        line(sender, "Death location", coordinate(record.deathX) + ", "
                + coordinate(record.deathY) + ", " + coordinate(record.deathZ));
        line(sender, "Chest location", record.hasChestLocation()
                ? record.chestX + ", " + record.chestY + ", " + record.chestZ
                : "not placed");
        line(sender, "Head location", record.hasHeadLocation()
                ? record.headX + ", " + record.headY + ", " + record.headZ
                : "not placed");
        line(sender, "Stored stacks", Integer.toString(
                ItemCodec.decodeLenient(record.chestItems, plugin.getLogger()).length));
        line(sender, "Experience", record.expTotal + " (" + record.expLevel + " levels)");
        line(sender, "Created", plugin.config().time().dateTime(record.createdAt));
        line(sender, "Processed by", record.processedByName == null ? "-" : record.processedByName);
        line(sender, "Archive", archive == null ? "none"
                : (archive.recovered ? "recovered" : "stored, " + archive.itemCount + " stacks"));
        if (record.lastError != null && !record.lastError.isBlank()) {
            line(sender, "Last error", record.lastError);
        }
    }

    private void recover(CommandSender sender, String[] args) {
        if (!require(sender, "qsds.evidence.recover")) {
            return;
        }

        if (args.length < 3) {
            sender.sendMessage(plugin.config().prefix() + plugin.config().message("usage.evidence"));
            return;
        }

        long id = parseId(args[2]);
        if (id < 0) {
            sender.sendMessage(plugin.config().prefix() + plugin.config().message("evidence.not-found",
                    "id", args[2]));
            return;
        }

        Player target;

        if (args.length >= 4) {
            target = Bukkit.getPlayerExact(args[3]);
        } else if (sender instanceof Player player) {
            target = player;
        } else {
            sender.sendMessage(plugin.config().prefix() + plugin.config().message("player-only"));
            return;
        }

        if (target == null) {
            sender.sendMessage(plugin.config().prefix() + plugin.config().message("evidence.not-found",
                    "id", args.length >= 4 ? args[3] : "?"));
            return;
        }

        plugin.recovery().recover(id, target, sender);
    }

    private void reload(CommandSender sender) {
        if (!require(sender, "qsds.reload")) {
            return;
        }

        plugin.config().reload();
        sender.sendMessage(plugin.config().prefix() + plugin.config().message("reload.done"));
        sender.sendMessage(plugin.config().prefix() + plugin.config().message("storage-note"));
    }

    private void status(CommandSender sender) {
        if (!require(sender, "qsds.status")) {
            return;
        }

        plugin.storage().stats().whenComplete((stats, error) -> plugin.scheduler().sync(() -> {
            if (error != null || stats == null) {
                if (error != null) {
                    plugin.getLogger().log(Level.SEVERE, "Could not read storage statistics", error);
                }
                sender.sendMessage(plugin.config().prefix() + plugin.config().message("error.database"));
                return;
            }

            sender.sendMessage(plugin.config().message("status.header"));
            line(sender, "Version", plugin.getDescription().getVersion());
            line(sender, "Server", Bukkit.getVersion());
            line(sender, "Java", System.getProperty("java.version"));
            line(sender, "Active evidence", Long.toString(stats.active()));
            line(sender, "Pending placement", Long.toString(stats.preparing()));
            line(sender, "Processing", Long.toString(stats.processing()));
            line(sender, "Processed", Long.toString(stats.processed()));
            line(sender, "Archives", stats.archives() + " (" + stats.pendingArchives() + " awaiting recovery)");
            line(sender, "Database size", humanBytes(stats.databaseBytes()));
            line(sender, "Journal entries", Integer.toString(plugin.journal().pendingCount()));
            line(sender, "Indexed chests", Integer.toString(plugin.evidenceIndex().all().size()));
            line(sender, "Hospital", plugin.hospital().isUsable()
                    ? "valid: " + plugin.hospital().record().worldName
                    : "invalid: " + plugin.hospital().validationError());
        }));
    }

    private void debug(CommandSender sender, String[] args) {
        if (!require(sender, "qsds.debug")) {
            return;
        }

        if (args.length < 2) {
            sender.sendMessage(plugin.config().prefix() + plugin.config().color(
                    "&7Usage: /qsds debug <simulate|reconcile>"));
            return;
        }

        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "simulate" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(plugin.config().prefix() + plugin.config().message("player-only"));
                    return;
                }

                plugin.deathService().createDiagnosticEvidence(player).whenComplete((id, error) ->
                        plugin.scheduler().sync(() -> {
                            if (error != null) {
                                plugin.getLogger().log(Level.SEVERE,
                                        "Could not create diagnostic evidence", error);
                                sender.sendMessage(plugin.config().prefix()
                                        + plugin.config().message("error.database"));
                                return;
                            }

                            sender.sendMessage(plugin.config().prefix()
                                    + plugin.config().message("debug.evidence-created", "id", Long.toString(id)));
                        }));
            }
            case "reconcile" -> plugin.deathService().reconcilePending().whenComplete((count, error) ->
                    plugin.scheduler().sync(() -> {
                        if (error != null) {
                            plugin.getLogger().log(Level.SEVERE, "Could not reconcile evidence", error);
                            sender.sendMessage(plugin.config().prefix()
                                    + plugin.config().message("error.database"));
                            return;
                        }
                        sender.sendMessage(plugin.config().prefix() + plugin.config().message("debug.reconcile",
                                "count", String.valueOf(count)));
                    }));
            default -> sender.sendMessage(plugin.config().prefix() + plugin.config().color(
                    "&7Usage: /qsds debug <simulate|reconcile>"));
        }
    }

    private void line(CommandSender sender, String key, String value) {
        sender.sendMessage(plugin.config().message("evidence.info-line", "key", key, "value", value));
    }

    private boolean require(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) {
            return true;
        }

        sender.sendMessage(plugin.config().prefix() + plugin.config().message("no-permission"));
        return false;
    }

    private int parsePage(String raw) {
        try {
            return Math.max(1, Integer.parseInt(raw));
        } catch (NumberFormatException ignored) {
            return 1;
        }
    }

    private long parseId(String raw) {
        try {
            return Long.parseLong(raw.startsWith("#") ? raw.substring(1) : raw);
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private int coordinate(double value) {
        return (int) Math.floor(value);
    }

    private String humanBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024.0));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(List.of("help", "tool", "evidence", "status", "reload", "debug"), args[0]);
        }

        if (args.length == 2) {
            return switch (args[0].toLowerCase(Locale.ROOT)) {
                case "tool" -> filter(List.of("give"), args[1]);
                case "evidence" -> filter(List.of("list", "info", "recover"), args[1]);
                case "debug" -> filter(List.of("simulate", "reconcile"), args[1]);
                default -> List.of();
            };
        }

        if (args.length == 3) {
            String subcommand = args[0].toLowerCase(Locale.ROOT);

            if (subcommand.equals("evidence") && args[1].equalsIgnoreCase("list")) {
                return filter(List.of("active", "preparing", "processing", "processed", "archived", "all"), args[2]);
            }

            if (subcommand.equals("evidence") && args[1].equalsIgnoreCase("recover")) {
                return filter(playerNames(), args[2]);
            }

            if (subcommand.equals("tool") && args[1].equalsIgnoreCase("give")) {
                return filter(playerNames(), args[2]);
            }
        }

        if (args.length == 4 && args[0].equalsIgnoreCase("evidence")
                && args[1].equalsIgnoreCase("recover")) {
            return filter(playerNames(), args[3]);
        }

        return List.of();
    }

    private List<String> playerNames() {
        List<String> names = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            names.add(player.getName());
        }
        return names;
    }

    private List<String> filter(List<String> options, String prefix) {
        String normalized = prefix.toLowerCase(Locale.ROOT);
        return options.stream()
                .filter(option -> option.toLowerCase(Locale.ROOT).startsWith(normalized))
                .toList();
    }
}
