/*
 * qS Death System
 * Copyright (C) qS Death System contributors.
 *
 * This file is part of a derivative work of GravesX, GPL-3.0-only.
 */
package dev.qsdeath.evidence;

import dev.qsdeath.QSDeathSystem;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;

/**
 * Protects evidence from unauthorised interaction, griefing and hopper
 * extraction, and routes police-tool clicks to {@link dev.qsdeath.police.PoliceService}.
 */
public final class EvidenceProtectionListener implements Listener {

    private final QSDeathSystem plugin;

    public EvidenceProtectionListener(QSDeathSystem plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (event.getHand() != null && event.getHand() != EquipmentSlot.HAND) {
            return;
        }

        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }

        EvidenceRecord chest = plugin.evidenceIndex().byChest(block);
        EvidenceRecord head = plugin.evidenceIndex().byHead(block);

        if (chest == null && head == null) {
            return;
        }

        // Never allow the vanilla chest interface or head interaction.
        event.setUseInteractedBlock(Event.Result.DENY);
        event.setUseItemInHand(Event.Result.DENY);
        event.setCancelled(true);

        if (chest == null) {
            event.getPlayer().sendMessage(plugin.config().prefix()
                    + plugin.config().message("police.sealed"));
            return;
        }

        if (!plugin.policeTool().isTool(event.getPlayer().getInventory().getItemInMainHand())) {
            if (plugin.config().lockChest()) {
                event.getPlayer().sendMessage(plugin.config().prefix()
                        + plugin.config().message("police.sealed"));
            }
            return;
        }

        plugin.policeService().inspect(event.getPlayer(), chest);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (isEvidenceBlock(event.getBlock())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(plugin.config().prefix()
                    + plugin.config().message("police.sealed"));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getInventory().getHolder() instanceof Chest chest)) {
            return;
        }

        if (plugin.evidenceIndex().byChest(chest.getBlock()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryMove(InventoryMoveItemEvent event) {
        if (event.getSource().getHolder() instanceof Chest chest
                && plugin.evidenceIndex().byChest(chest.getBlock()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(this::isEvidenceBlock);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(this::isEvidenceBlock);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (isEvidenceBlock(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (movesEvidence(event.getBlocks(), event.getDirection())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (movesEvidence(event.getBlocks(), event.getDirection())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Material type = event.getBlockPlaced().getType();
        if (type != Material.CHEST && type != Material.TRAPPED_CHEST) {
            return;
        }

        for (org.bukkit.block.BlockFace face : new org.bukkit.block.BlockFace[]{
                org.bukkit.block.BlockFace.NORTH, org.bukkit.block.BlockFace.SOUTH,
                org.bukkit.block.BlockFace.EAST, org.bukkit.block.BlockFace.WEST}) {
            if (plugin.evidenceIndex().byChest(event.getBlockPlaced().getRelative(face)) != null) {
                event.setCancelled(true);
                event.getPlayer().sendMessage(plugin.config().prefix()
                        + plugin.config().message("police.sealed"));
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        Entity entity = event.getEntity();
        if (entity instanceof TextDisplay display
                && display.getPersistentDataContainer().has(plugin.evidenceKey(), PersistentDataType.STRING)) {
            event.setCancelled(true);
        }
    }

    private boolean movesEvidence(Iterable<Block> blocks, org.bukkit.block.BlockFace direction) {
        for (Block block : blocks) {
            if (isEvidenceBlock(block) || isEvidenceBlock(block.getRelative(direction))) {
                return true;
            }
        }
        return false;
    }

    private boolean isEvidenceBlock(Block block) {
        return block != null
                && (plugin.evidenceIndex().byChest(block) != null || plugin.evidenceIndex().byHead(block) != null);
    }
}
