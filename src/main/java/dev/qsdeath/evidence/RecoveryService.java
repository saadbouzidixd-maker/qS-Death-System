/*
 * qS Death System
 * Copyright (C) qS Death System contributors.
 *
 * This file is part of a derivative work of GravesX, GPL-3.0-only.
 */
package dev.qsdeath.evidence;

import dev.qsdeath.QSDeathSystem;
import dev.qsdeath.storage.ArchiveRecord;
import dev.qsdeath.util.ItemCodec;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * Restores archived inventories. Recovery is staff-only and can only succeed
 * once: the database claim update is conditional on {@code recovered = 0}.
 */
public final class RecoveryService {

    private final QSDeathSystem plugin;

    public RecoveryService(QSDeathSystem plugin) {
        this.plugin = plugin;
    }

    /**
     * Recovers an evidence record into the target player's inventory.
     *
     * <p>Works for archived records and for records that never became active
     * (for example a death in an unplaceable location). Active records are
     * archived and removed first so recovery can never duplicate the world
     * contents.</p>
     *
     * @param evidenceId evidence id
     * @param target     receiving player
     * @param requester  command sender
     */
    public void recover(long evidenceId, Player target, CommandSender requester) {
        plugin.storage().findEvidence(evidenceId).thenCompose(record -> {
            if (record == null) {
                return CompletableFuture.completedFuture(null);
            }
            return plugin.storage().findArchive(evidenceId)
                    .thenApply(archive -> new Context(record, archive));
        }).whenComplete((context, error) -> {
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "Could not load evidence #" + evidenceId, error);
                plugin.scheduler().sync(() -> requester.sendMessage(plugin.config().prefix()
                        + plugin.config().message("error.database")));
                return;
            }

            plugin.scheduler().sync(() -> {
                if (context == null) {
                    requester.sendMessage(plugin.config().prefix()
                            + plugin.config().message("evidence.not-found", "id", Long.toString(evidenceId)));
                    return;
                }

                handle(context, target, requester);
            });
        });
    }

    private void handle(Context context, Player target, CommandSender requester) {
        EvidenceRecord record = context.record;

        if (record.isProcessed()) {
            if (context.archive == null) {
                requester.sendMessage(plugin.config().prefix() + plugin.config().message("evidence.archive-none",
                        "id", Long.toString(record.id)));
                return;
            }
            if (context.archive.recovered) {
                requester.sendMessage(plugin.config().prefix()
                        + plugin.config().message("evidence.already-recovered"));
                return;
            }
            giveArchived(context.archive, target, requester);
            return;
        }

        if (EvidenceStatus.PROCESSING.name().equals(record.status)) {
            requester.sendMessage(plugin.config().prefix() + plugin.config().message("evidence.recovery-failed",
                    "reason", "evidence processing is not complete yet"));
            return;
        }

        ArchiveRecord archive;
        try {
            archive = buildFromRecord(record);
        } catch (Throwable throwable) {
            plugin.getLogger().log(Level.SEVERE, "Could not read evidence #" + record.id, throwable);
            requester.sendMessage(plugin.config().prefix() + plugin.config().message("error.internal"));
            return;
        }

        boolean active = record.isActive();
        CompletableFuture<Boolean> claim = active
                ? plugin.storage().beginProcessing(record.id, archive, actorUuid(requester),
                requester.getName(), System.currentTimeMillis())
                : plugin.storage().archivePreparing(record.id, archive, actorUuid(requester),
                requester.getName(), System.currentTimeMillis());

        claim.whenComplete((won, error) -> {
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "Could not archive evidence #" + record.id, error);
                plugin.scheduler().sync(() -> requester.sendMessage(plugin.config().prefix()
                        + plugin.config().message("error.database")));
                return;
            }

            if (!Boolean.TRUE.equals(won)) {
                plugin.scheduler().sync(() -> requester.sendMessage(plugin.config().prefix()
                        + plugin.config().message("evidence.already-recovered")));
                return;
            }

            plugin.scheduler().sync(() -> {
                if (active) {
                    try {
                        plugin.renderer().remove(record);
                        plugin.evidenceIndex().remove(record);
                    } catch (Throwable throwable) {
                        plugin.getLogger().log(Level.WARNING,
                                "Could not remove evidence #" + record.id + " before recovery", throwable);
                        requester.sendMessage(plugin.config().prefix()
                                + plugin.config().message("evidence.recovery-failed",
                                "reason", "world evidence could not be removed; try again"));
                        return;
                    }
                }

                plugin.storage().finishProcessing(record.id, System.currentTimeMillis())
                        .whenComplete((finished, finishError) -> {
                            if (finishError != null || !Boolean.TRUE.equals(finished)) {
                                if (finishError != null) {
                                    plugin.getLogger().log(Level.SEVERE,
                                            "Could not finish processing evidence #" + record.id, finishError);
                                }
                                plugin.scheduler().sync(() -> requester.sendMessage(plugin.config().prefix()
                                        + plugin.config().message("error.database")));
                                return;
                            }

                            giveArchived(archive, target, requester);
                        });
            });
        });
    }

    private ArchiveRecord buildFromRecord(EvidenceRecord record) {
        List<ItemStack> payload = ItemCodec.flatten(ItemCodec.decode(record.chestItems, plugin.getLogger()));

        ArchiveRecord archive = new ArchiveRecord();
        archive.chestItems = ItemCodec.encode(ItemCodec.toArray(payload));
        archive.overflowItems = record.overflowItems;
        archive.armorItems = record.armorItems;
        archive.offhandItem = record.offhandItem;
        archive.expTotal = record.expTotal;
        archive.expLevel = record.expLevel;
        archive.itemCount = payload.size();
        return archive;
    }

    private void giveArchived(ArchiveRecord archive, Player target, CommandSender requester) {
        List<ItemStack> items;
        try {
            items = ItemCodec.flatten(ItemCodec.decode(archive.chestItems, plugin.getLogger()));
        } catch (ItemCodec.ItemCodecException exception) {
            plugin.getLogger().log(Level.SEVERE,
                    "Archived inventory for evidence #" + archive.evidenceId + " is corrupt", exception);
            requester.sendMessage(plugin.config().prefix() + plugin.config().message("evidence.recovery-failed",
                    "reason", "archived inventory data is corrupt"));
            return;
        }

        plugin.storage().claimRecovery(archive.evidenceId, actorUuid(requester), requester.getName(),
                        System.currentTimeMillis())
                .whenComplete((claimed, error) -> {
                    if (error != null) {
                        plugin.getLogger().log(Level.SEVERE,
                                "Could not claim archive for evidence #" + archive.evidenceId, error);
                        plugin.scheduler().sync(() -> requester.sendMessage(plugin.config().prefix()
                                + plugin.config().message("error.database")));
                        return;
                    }

                    if (!Boolean.TRUE.equals(claimed)) {
                        plugin.scheduler().sync(() -> requester.sendMessage(plugin.config().prefix()
                                + plugin.config().message("evidence.already-recovered")));
                        return;
                    }

                    plugin.scheduler().sync(() -> deliver(archive, items, target, requester));
                });
    }

    private void deliver(ArchiveRecord archive, List<ItemStack> items, Player target, CommandSender requester) {
        if (!target.isOnline()) {
            requester.sendMessage(plugin.config().prefix() + plugin.config().message("evidence.recovery-failed",
                    "reason", "the receiving player is no longer online"));
            return;
        }

        int stacks = items.size();
        Map<Integer, ItemStack> leftovers = target.getInventory()
                .addItem(items.toArray(new ItemStack[0]));

        int dropped = 0;
        for (ItemStack leftover : leftovers.values()) {
            target.getWorld().dropItemNaturally(target.getLocation(), leftover);
            dropped += leftover.getAmount();
        }

        if (plugin.config().grantExperienceOnRecovery() && archive.expTotal > 0) {
            target.giveExp(archive.expTotal);
        }

        String message = plugin.config().message("evidence.recovered",
                "id", Long.toString(archive.evidenceId),
                "player", target.getName(),
                "items", Integer.toString(stacks),
                "overflow", Integer.toString(dropped));

        requester.sendMessage(plugin.config().prefix() + message);
        if (!(requester instanceof Player player) || !player.getUniqueId().equals(target.getUniqueId())) {
            target.sendMessage(plugin.config().prefix() + message);
        }
    }

    private UUID actorUuid(CommandSender sender) {
        return sender instanceof Player player ? player.getUniqueId() : null;
    }

    private record Context(EvidenceRecord record, ArchiveRecord archive) {
    }
}
