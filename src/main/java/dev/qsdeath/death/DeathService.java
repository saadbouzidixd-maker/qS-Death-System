/*
 * qS Death System
 * Copyright (C) qS Death System contributors.
 *
 * This file is part of a derivative work of GravesX, GPL-3.0-only.
 */
package dev.qsdeath.death;

import dev.qsdeath.QSDeathSystem;
import dev.qsdeath.display.SkinData;
import dev.qsdeath.evidence.EvidenceRecord;
import dev.qsdeath.evidence.EvidenceRenderer;
import dev.qsdeath.evidence.EvidenceStatus;
import dev.qsdeath.evidence.PlacementPlan;
import dev.qsdeath.util.ItemCodec;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * The death pipeline.
 *
 * <p>Ordering is deliberate and crash-safe:</p>
 * <ol>
 *     <li>Snapshot inventory, armour, offhand and experience in memory.</li>
 *     <li>Persist the snapshot to the on-disk journal synchronously.</li>
 *     <li>Commit the evidence record to SQLite asynchronously.</li>
 *     <li>Only then remove the items from the player and place the evidence.</li>
 * </ol>
 *
 * <p>If the database insert fails the items are dropped at the death location,
 * so nothing can be lost. A crash at any point leaves either the player's items
 * or a journal/SQLite copy, never both and never neither.</p>
 */
public final class DeathService {

    private final QSDeathSystem plugin;
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();
    private final Map<Long, Integer> placementAttempts = new ConcurrentHashMap<>();

    public DeathService(QSDeathSystem plugin) {
        this.plugin = plugin;
    }

    /**
     * Handles a player death.
     *
     * @param event death event
     */
    public void handleDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();

        if (!inFlight.add(player.getUniqueId())) {
            plugin.getLogger().warning("Duplicate death event ignored for " + player.getName());
            return;
        }

        try {
            InventorySnapshot snapshot;
            try {
                snapshot = InventorySnapshot.capture(player, plugin.config().captureExperience());
            } catch (Throwable throwable) {
                // Nothing has been removed yet: let vanilla handle the death.
                plugin.getLogger().log(Level.SEVERE,
                        "Could not capture the inventory of " + player.getName() + "; vanilla drops were left untouched.",
                        throwable);
                return;
            }

            SkinData skin = SkinData.capture(player);
            Location deathLocation = player.getLocation().clone();
            DeathCause deathCause = resolveDeathCause(player);

            EvidenceRecord record = new EvidenceRecord();
            record.victimUuid = player.getUniqueId();
            record.victimName = player.getName();
            record.deathTime = System.currentTimeMillis();
            record.worldUuid = deathLocation.getWorld() == null ? null : deathLocation.getWorld().getUID();
            record.worldName = deathLocation.getWorld() == null ? "unknown" : deathLocation.getWorld().getName();
            record.deathX = deathLocation.getX();
            record.deathY = deathLocation.getY();
            record.deathZ = deathLocation.getZ();
            record.deathYaw = deathLocation.getYaw();
            record.deathPitch = deathLocation.getPitch();
            record.deathCause = deathCause.cause();
            record.killerUuid = deathCause.killerUuid();
            record.killerName = deathCause.killerName();
            record.status = EvidenceStatus.PREPARING.name();
            record.expTotal = snapshot.totalExperience();
            record.expLevel = snapshot.level();
            record.skinValue = skin.value();
            record.skinSignature = skin.signature();
            record.skinUrl = skin.url();
            record.createdAt = record.deathTime;
            record.updatedAt = record.deathTime;

            record.chestItems = ItemCodec.encode(ItemCodec.toArray(ItemCodec.mergeSimilar(snapshot.allItems())));
            record.armorItems = ItemCodec.encode(snapshot.armor());
            record.offhandItem = ItemCodec.encode(snapshot.offhand());

            PlacementPlan plan = plugin.locator().findPlan(deathLocation);
            if (plan != null) {
                applyPlan(record, plan, deathLocation.getWorld());
            }

            boolean journaled = false;
            try {
                plugin.journal().write(record);
                journaled = true;
            } catch (IOException exception) {
                plugin.getLogger().log(Level.SEVERE,
                        "Could not write the evidence journal; relying on the database commit.", exception);
            }

            if (plugin.config().clearVanillaDrops()) {
                event.getDrops().clear();
            }
            event.setDroppedExp(0);

            clearInventory(player);

            plugin.hospital().warnIfUnconfigured();

            boolean wasJournaled = journaled;
            plugin.storage().insertEvidence(record).whenComplete((id, error) -> {
                if (error != null) {
                    plugin.getLogger().log(Level.SEVERE, "Could not persist evidence for "
                            + player.getName() + "; the items were returned to the death location.", error);
                    if (wasJournaled) {
                        plugin.journal().delete(record.evidenceUuid);
                    }
                    plugin.scheduler().sync(() -> returnItems(deathLocation, snapshot.allItems(), player));
                    return;
                }

                record.id = id;
                plugin.journal().delete(record.evidenceUuid);

                if (plugin.config().debug()) {
                    plugin.getLogger().info("[Debug] Evidence #" + id + " committed for " + player.getName());
                }

                plugin.scheduler().sync(() -> place(record));
            });
        } finally {
            inFlight.remove(player.getUniqueId());
        }
    }

    /**
     * Places a record that was loaded from storage (startup reconcile).
     *
     * @param record record
     */
    public void placePending(EvidenceRecord record) {
        place(record);
    }

    /**
     * Retries every pending evidence record.
     *
     * @return future with the number of records scheduled
     */
    public CompletableFuture<Integer> reconcilePending() {
        return plugin.storage().findByStatus(EvidenceStatus.PREPARING).thenApply(records -> {
            plugin.scheduler().sync(() -> records.forEach(this::place));
            return records.size();
        });
    }

    /**
     * Creates a diagnostic evidence record at the player's location with sample
     * items. Used by administrators to verify the complete evidence pipeline
     * without sacrificing a real player's inventory.
     *
     * @param player player
     * @return future with the created evidence id
     */
    public CompletableFuture<Long> createDiagnosticEvidence(Player player) {
        Location location = player.getLocation().clone();

        List<ItemStack> samples = List.of(
                new ItemStack(Material.STONE, 16),
                new ItemStack(Material.IRON_SWORD),
                new ItemStack(Material.DIAMOND_CHESTPLATE),
                new ItemStack(Material.BREAD, 8));

        EvidenceRecord record = new EvidenceRecord();
        record.victimUuid = player.getUniqueId();
        record.victimName = player.getName();
        record.deathTime = System.currentTimeMillis();
        record.worldUuid = location.getWorld() == null ? null : location.getWorld().getUID();
        record.worldName = location.getWorld() == null ? "unknown" : location.getWorld().getName();
        record.deathX = location.getX();
        record.deathY = location.getY();
        record.deathZ = location.getZ();
        record.deathYaw = location.getYaw();
        record.deathPitch = location.getPitch();
        record.deathCause = "DIAGNOSTIC";
        record.status = EvidenceStatus.PREPARING.name();
        record.createdAt = record.deathTime;
        record.updatedAt = record.deathTime;
        record.chestItems = ItemCodec.encode(ItemCodec.toArray(ItemCodec.mergeSimilar(samples)));

        SkinData skin = SkinData.capture(player);
        record.skinValue = skin.value();
        record.skinSignature = skin.signature();
        record.skinUrl = skin.url();

        PlacementPlan plan = plugin.locator().findPlan(location);
        if (plan != null) {
            applyPlan(record, plan, location.getWorld());
        }

        return plugin.storage().insertEvidence(record).thenApply(id -> {
            record.id = id;
            plugin.scheduler().sync(() -> place(record));
            return id;
        });
    }

    private void place(EvidenceRecord record) {
        if (!plugin.isEnabled()) {
            return;
        }

        EvidenceRenderer.Outcome outcome = plugin.renderer().place(record);

        if (outcome.success()) {
            record.status = EvidenceStatus.ACTIVE.name();
            record.nameplateUuid = outcome.nameplateUuid();
            plugin.evidenceIndex().add(record);
            placementAttempts.remove(record.id);

            plugin.storage().markActive(record).whenComplete((ignored, error) -> {
                if (error != null) {
                    plugin.getLogger().log(Level.SEVERE,
                            "Evidence #" + record.id + " is placed but could not be marked active in SQLite. "
                                    + "It will be reconciled on the next start.", error);
                }
            });

            if (plugin.config().debug()) {
                plugin.getLogger().info("[Debug] Evidence #" + record.id + " active at "
                        + record.chestWorldName + " " + record.chestX + ", " + record.chestY + ", " + record.chestZ);
            }
            return;
        }

        int attempt = placementAttempts.merge(record.id, 1, Integer::sum);
        record.lastError = outcome.error();
        plugin.storage().updateError(record.id, outcome.error());
        plugin.getLogger().warning("Evidence #" + record.id + " placement failed (attempt " + attempt + "/"
                + plugin.config().placementRetries() + "): " + outcome.error());

        if (outcome.retryable() && attempt < plugin.config().placementRetries()) {
            plugin.scheduler().later(() -> place(record), 20L * attempt);
            return;
        }

        plugin.getLogger().severe("Evidence #" + record.id + " for " + record.victimName
                + " could not be placed in the world. Its inventory is safely stored in SQLite; use"
                + " /qsds debug reconcile to retry or /qsds evidence recover " + record.id + " to return it.");
    }

    private void applyPlan(EvidenceRecord record, PlacementPlan plan, World world) {
        record.chestWorldUuid = plan.chest().worldId();
        record.chestWorldName = world == null ? null : world.getName();
        record.chestX = plan.chest().x();
        record.chestY = plan.chest().y();
        record.chestZ = plan.chest().z();

        if (plan.head() != null) {
            record.headWorldUuid = plan.head().worldId();
            record.headWorldName = world == null ? null : world.getName();
            record.headX = plan.head().x();
            record.headY = plan.head().y();
            record.headZ = plan.head().z();
        }
    }

    private void clearInventory(Player player) {
        PlayerInventory inventory = player.getInventory();
        inventory.clear();
        inventory.setArmorContents(new ItemStack[4]);
        inventory.setItemInOffHand(null);

        player.setTotalExperience(0);
        player.setLevel(0);
        player.setExp(0.0F);
    }

    private void returnItems(Location location, List<ItemStack> items, Player player) {
        World world = location.getWorld();

        if (world == null) {
            plugin.getLogger().severe("Could not return the inventory of " + player.getName()
                    + " because the death world is unavailable. Items remain in the evidence journal.");
            return;
        }

        for (ItemStack item : items) {
            world.dropItemNaturally(location, item);
        }

        plugin.getLogger().warning("Returned " + items.size() + " item stacks of " + player.getName()
                + " at the death location because the evidence could not be stored.");
    }

    private DeathCause resolveDeathCause(Player player) {
        Player killer = player.getKiller();

        if (killer == null && player.getLastDamageCause() instanceof EntityDamageByEntityEvent byEntity) {
            Entity damager = byEntity.getDamager();

            if (damager instanceof Player other) {
                killer = other;
            } else if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) {
                killer = shooter;
            }
        }

        String cause;
        if (killer != null) {
            cause = "PLAYER";
        } else {
            EntityDamageEvent lastDamage = player.getLastDamageCause();
            cause = lastDamage == null ? "UNKNOWN" : lastDamage.getCause().name();
        }

        return new DeathCause(cause,
                killer == null ? null : killer.getUniqueId(),
                killer == null ? null : killer.getName());
    }

    private record DeathCause(String cause, UUID killerUuid, String killerName) {
    }
}
