/*
 * qS Death System
 * Copyright (C) qS Death System contributors.
 *
 * This file is part of a derivative work of GravesX, GPL-3.0-only.
 */
package dev.qsdeath.police;

import dev.qsdeath.QSDeathSystem;
import dev.qsdeath.evidence.EvidenceRecord;
import dev.qsdeath.evidence.EvidenceStatus;
import dev.qsdeath.storage.ArchiveRecord;
import dev.qsdeath.util.ItemCodec;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Handles a police officer using the evidence shovel on a crime scene chest.
 *
 * <p>The inventory is archived in SQLite before anything in the world is
 * removed. The conditional database update is the authoritative duplicate
 * guard; the in-memory claim only prevents double clicks from starting two
 * archive attempts in the same tick.</p>
 */
public final class PoliceService {

    private static final String POLICE_PERMISSION = "qscrimescenes.police";

    private final QSDeathSystem plugin;
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    public PoliceService(QSDeathSystem plugin) {
        this.plugin = plugin;
    }

    /**
     * Processes an evidence chest for an officer.
     *
     * @param officer officer
     * @param record  active evidence record
     */
    public void inspect(Player officer, EvidenceRecord record) {
        if (!officer.hasPermission(POLICE_PERMISSION)) {
            officer.sendMessage(plugin.config().prefix() + plugin.config().message("police.denied"));
            return;
        }

        if (!plugin.policeTool().isTool(officer.getInventory().getItemInMainHand())) {
            officer.sendMessage(plugin.config().prefix() + plugin.config().message("police.sealed"));
            return;
        }

        if (!record.isActive()) {
            officer.sendMessage(plugin.config().prefix() + plugin.config().message("police.processed"));
            return;
        }

        if (!inFlight.add(record.id)) {
            officer.sendMessage(plugin.config().prefix() + plugin.config().message("police.busy"));
            return;
        }

        ArchiveRecord archive;
        try {
            archive = buildArchive(record);
        } catch (Throwable throwable) {
            inFlight.remove(record.id);
            plugin.getLogger().log(Level.SEVERE, "Could not read the inventory of evidence #" + record.id, throwable);
            officer.sendMessage(plugin.config().prefix() + plugin.config().message("error.internal"));
            return;
        }

        long now = System.currentTimeMillis();

        plugin.storage().beginProcessing(record.id, archive, officer.getUniqueId(), officer.getName(), now)
                .whenComplete((won, error) -> plugin.scheduler().sync(() -> {
                    try {
                        if (error != null) {
                            plugin.getLogger().log(Level.SEVERE,
                                    "Could not archive evidence #" + record.id, error);
                            officer.sendMessage(plugin.config().prefix()
                                    + plugin.config().message("error.database"));
                            return;
                        }

                        if (!Boolean.TRUE.equals(won)) {
                            officer.sendMessage(plugin.config().prefix()
                                    + plugin.config().message("police.processed"));
                            plugin.evidenceIndex().remove(record);
                            return;
                        }

                        for (String line : plugin.crimeReport().build(record)) {
                            officer.sendMessage(line);
                        }

                        removeAndFinish(record, officer, 3);
                    } finally {
                        inFlight.remove(record.id);
                    }
                }));
    }

    /**
     * Builds the archive payload from the live chest plus any overflow items.
     * If the chest has already vanished, the stored payload is archived so no
     * data is lost.
     */
    private ArchiveRecord buildArchive(EvidenceRecord record) {
        World world = plugin.renderer().resolveWorld(record);
        List<ItemStack> live = new ArrayList<>();

        if (world != null && record.hasChestLocation()) {
            Block block = world.getBlockAt(record.chestX, record.chestY, record.chestZ);
            if (block.getType() == Material.CHEST && block.getState() instanceof Chest chest) {
                live.addAll(ItemCodec.flatten(chest.getBlockInventory().getContents()));
            }
        }

        List<ItemStack> stored = ItemCodec.flatten(ItemCodec.decode(record.chestItems, plugin.getLogger()));
        List<ItemStack> overflow = ItemCodec.flatten(
                ItemCodec.decodeLenient(record.overflowItems, plugin.getLogger()));

        List<ItemStack> payload;

        if (live.isEmpty()) {
            payload = stored;
        } else {
            payload = new ArrayList<>(live);
            payload.addAll(overflow);
            payload = ItemCodec.mergeSimilar(payload);
        }

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

    private void removeAndFinish(EvidenceRecord record, Player officer, int attemptsLeft) {
        boolean removed = true;

        try {
            plugin.renderer().remove(record);
            plugin.evidenceIndex().remove(record);
        } catch (Throwable throwable) {
            removed = false;
            plugin.getLogger().log(Level.WARNING,
                    "Could not fully remove evidence #" + record.id + "; retrying.", throwable);
        }

        if (!removed) {
            if (attemptsLeft > 0) {
                plugin.scheduler().later(() -> removeAndFinish(record, officer, attemptsLeft - 1), 40L);
            } else {
                plugin.getLogger().severe("Evidence #" + record.id
                        + " could not be removed after 4 attempts. Its inventory is archived; "
                        + "the remaining blocks will be cleaned up on the next server start.");
            }
            return;
        }

        record.status = EvidenceStatus.PROCESSED.name();

        plugin.storage().finishProcessing(record.id, System.currentTimeMillis())
                .whenComplete((done, error) -> {
                    if (error != null) {
                        plugin.getLogger().log(Level.SEVERE,
                                "Evidence #" + record.id + " was removed but could not be marked processed.",
                                error);
                    }
                });

        officer.sendMessage(plugin.config().prefix() + plugin.config().message("police.archived"));
    }
}
