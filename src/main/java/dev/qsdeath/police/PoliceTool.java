/*
 * qS Death System
 * Copyright (C) qS Death System contributors.
 *
 * This file is part of a derivative work of GravesX, GPL-3.0-only.
 */
package dev.qsdeath.police;

import dev.qsdeath.QSDeathSystem;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Creates and validates the police evidence shovel.
 *
 * <p>The tool is a wooden shovel carrying two persistent data entries: a
 * version marker and a per-installation secret token stored in
 * {@code keys.yml}. A normal wooden shovel, a renamed shovel or a tool copied
 * from another installation never passes {@link #isTool(ItemStack)}.</p>
 */
public final class PoliceTool {

    private static final String TOOL_VERSION = "1";
    private static final String KEY_FILE = "keys.yml";
    private static final String TOKEN_PATH = "police-tool-token";

    private final QSDeathSystem plugin;
    private final NamespacedKey toolKey;
    private final NamespacedKey versionKey;
    private final String token;
    private final LegacyComponentSerializer legacy = LegacyComponentSerializer.legacySection();

    public PoliceTool(QSDeathSystem plugin, String token) {
        this.plugin = plugin;
        this.toolKey = new NamespacedKey(plugin, "police_tool");
        this.versionKey = new NamespacedKey(plugin, "police_tool_version");
        this.token = token;
    }

    /**
     * Loads the installation token or creates it on first start.
     *
     * @param dataFolder plugin data folder
     * @param logger     logger
     * @return secret token
     */
    public static String loadOrCreateToken(File dataFolder, Logger logger) {
        File file = new File(dataFolder, KEY_FILE);
        YamlConfiguration configuration = file.exists()
                ? YamlConfiguration.loadConfiguration(file)
                : new YamlConfiguration();

        String existing = configuration.getString(TOKEN_PATH);
        if (existing != null && !existing.isBlank()) {
            return existing;
        }

        String token = UUID.randomUUID().toString();
        configuration.set(TOKEN_PATH, token);

        try {
            configuration.save(file);
            logger.info("Generated a new police tool token in " + file.getName());
        } catch (IOException exception) {
            logger.log(Level.SEVERE, "Could not save the police tool token; existing tools will stop "
                    + "working after a restart.", exception);
        }

        return token;
    }

    /**
     * @return a fresh, valid police evidence shovel
     */
    public ItemStack create() {
        ItemStack item = new ItemStack(Material.WOODEN_SHOVEL);
        ItemMeta meta = item.getItemMeta();

        if (meta == null) {
            return item;
        }

        meta.displayName(legacy.deserialize(plugin.config().color(plugin.config().toolName())));

        List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
        for (String line : plugin.config().toolLore()) {
            lore.add(legacy.deserialize(plugin.config().color(line)));
        }
        meta.lore(lore);

        if (plugin.config().toolUnbreakable()) {
            meta.setUnbreakable(true);
            meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE);
        }

        if (plugin.config().toolGlow()) {
            meta.addEnchant(Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        }

        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);

        PersistentDataContainer container = meta.getPersistentDataContainer();
        container.set(toolKey, PersistentDataType.STRING, token);
        container.set(versionKey, PersistentDataType.STRING, TOOL_VERSION);

        item.setItemMeta(meta);
        return item;
    }

    /**
     * @param item item in hand
     * @return true only for a genuine tool from this installation
     */
    public boolean isTool(ItemStack item) {
        if (item == null || item.getType() != Material.WOODEN_SHOVEL) {
            return false;
        }

        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }

        PersistentDataContainer container = meta.getPersistentDataContainer();
        String storedToken = container.get(toolKey, PersistentDataType.STRING);
        String storedVersion = container.get(versionKey, PersistentDataType.STRING);

        return token != null && token.equals(storedToken) && TOOL_VERSION.equals(storedVersion);
    }
}
