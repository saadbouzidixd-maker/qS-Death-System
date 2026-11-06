/*
 * qS Death System
 * Copyright (C) qS Death System contributors.
 *
 * This file is part of a derivative work of GravesX, GPL-3.0-only.
 */
package dev.qsdeath.display;

import dev.qsdeath.QSDeathSystem;
import dev.qsdeath.evidence.EvidenceRecord;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;

import java.util.Locale;
import java.util.UUID;

/**
 * Manages the persistent {@link TextDisplay} that shows only the victim's name
 * above the evidence.
 */
public final class NameplateService {

    private final QSDeathSystem plugin;
    private final LegacyComponentSerializer legacy = LegacyComponentSerializer.legacySection();

    public NameplateService(QSDeathSystem plugin) {
        this.plugin = plugin;
    }

    /**
     * Creates or refreshes the name display above an evidence chest.
     *
     * @param record      evidence record
     * @param chestBlock  chest block location
     * @return display UUID or {@code null} when disabled or creation failed
     */
    public UUID ensure(EvidenceRecord record, Location chestBlock) {
        if (!plugin.config().nameEnabled() || chestBlock == null || chestBlock.getWorld() == null) {
            return null;
        }

        World world = chestBlock.getWorld();
        Location target = nameplateLocation(chestBlock);
        Component text = nameComponent(record);

        TextDisplay existing = findExisting(world, target, record.evidenceUuid);
        if (existing != null) {
            existing.text(text);
            existing.setPersistent(true);
            existing.setInvulnerable(true);
            return existing.getUniqueId();
        }

        try {
            Entity entity = world.spawnEntity(target, EntityType.TEXT_DISPLAY);
            if (!(entity instanceof TextDisplay display)) {
                entity.remove();
                return null;
            }

            display.setPersistent(true);
            display.setInvulnerable(true);
            display.setGravity(false);
            display.setBillboard(parseBillboard(plugin.config().nameBillboard()));
            display.setDefaultBackground(false);
            display.setShadowed(plugin.config().nameShadowed());
            display.setSeeThrough(plugin.config().nameSeeThrough());
            display.setAlignment(TextDisplay.TextAlignment.CENTER);
            display.setLineWidth(200);
            display.setTextOpacity((byte) 255);
            display.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            display.text(text);
            display.getPersistentDataContainer().set(plugin.evidenceKey(),
                    PersistentDataType.STRING, record.evidenceUuid);

            return display.getUniqueId();
        } catch (Throwable throwable) {
            plugin.getLogger().warning("Could not create the evidence name display: " + throwable.getMessage());
            return null;
        }
    }

    /**
     * Removes the name display and any duplicate displays for the record.
     *
     * @param record evidence record
     */
    public void remove(EvidenceRecord record) {
        if (record == null) {
            return;
        }

        if (record.nameplateUuid != null) {
            Entity entity = Bukkit.getEntity(record.nameplateUuid);
            if (entity instanceof TextDisplay display) {
                display.remove();
            }
        }

        if (record.chestWorldUuid == null || !record.hasChestLocation()) {
            return;
        }

        World world = Bukkit.getWorld(record.chestWorldUuid);
        if (world == null) {
            return;
        }

        Location center = new Location(world, record.chestX + 0.5, record.chestY + 1.5, record.chestZ + 0.5);
        var nearby = world.getNearbyEntities(center, 3.0, 5.0, 3.0);

        for (Entity entity : nearby) {
            if (!(entity instanceof TextDisplay display)) {
                continue;
            }

            String stored = display.getPersistentDataContainer()
                    .get(plugin.evidenceKey(), PersistentDataType.STRING);

            if (record.evidenceUuid.equals(stored)) {
                display.remove();
            }
        }
    }

    private TextDisplay findExisting(World world, Location target, String evidenceUuid) {
        for (Entity entity : world.getNearbyEntities(target, 2.5, 4.5, 2.5)) {
            if (!(entity instanceof TextDisplay display)) {
                continue;
            }

            String stored = display.getPersistentDataContainer()
                    .get(plugin.evidenceKey(), PersistentDataType.STRING);

            if (evidenceUuid.equals(stored)) {
                return display;
            }
        }

        return null;
    }

    private Location nameplateLocation(Location chestBlock) {
        return new Location(chestBlock.getWorld(),
                chestBlock.getX() + 0.5,
                chestBlock.getY() + plugin.config().nameOffsetY(),
                chestBlock.getZ() + 0.5);
    }

    private Component nameComponent(EvidenceRecord record) {
        String format = plugin.config().nameFormat().replace("%victim%", record.victimName);
        return legacy.deserialize(plugin.config().color(format));
    }

    private Display.Billboard parseBillboard(String raw) {
        try {
            return Display.Billboard.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException ignored) {
            return Display.Billboard.CENTER;
        }
    }
}
