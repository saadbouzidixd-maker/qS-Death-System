/*
 * qS Death System
 * Copyright (C) qS Death System contributors.
 *
 * This file is part of a derivative work of GravesX, GPL-3.0-only.
 */
package dev.qsdeath.config;

import dev.qsdeath.QSDeathSystem;
import dev.qsdeath.util.TimeUtil;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.Collections;
import java.util.List;

/**
 * Typed access to {@code config.yml}. Unknown or missing values always fall
 * back to safe defaults so a partially edited configuration cannot break death
 * handling.
 */
public final class PluginConfig {

    private final QSDeathSystem plugin;
    private FileConfiguration file;
    private TimeUtil timeUtil;

    public PluginConfig(QSDeathSystem plugin) {
        this.plugin = plugin;
        reload();
    }

    /**
     * Reloads the configuration from disk.
     */
    public void reload() {
        plugin.reloadConfig();
        this.file = plugin.getConfig();

        this.timeUtil = new TimeUtil(
                file.getString("time.zone", "system"),
                file.getString("time.date-format", "yyyy-MM-dd"),
                file.getString("time.time-format", "HH:mm:ss"));
    }

    public TimeUtil time() {
        return timeUtil;
    }

    public boolean debug() {
        return file.getBoolean("debug", false);
    }

    public String databaseFileName() {
        return requireText(file.getString("storage.file"), "database.db");
    }

    public int databaseBusyTimeoutMs() {
        return Math.max(1000, file.getInt("storage.busy-timeout-ms", 10000));
    }

    public String journalDirectory() {
        return requireText(file.getString("storage.journal-directory"), "journal");
    }

    public boolean captureExperience() {
        return file.getBoolean("death.capture-experience", true);
    }

    public boolean clearVanillaDrops() {
        return file.getBoolean("death.clear-vanilla-drops", true);
    }

    public int placementRetries() {
        return Math.max(1, file.getInt("death.placement-retries", 3));
    }

    public boolean lockChest() {
        return file.getBoolean("evidence.chest.lock", true);
    }

    public boolean headEnabled() {
        return file.getBoolean("evidence.head.enabled", true);
    }

    public boolean nameEnabled() {
        return file.getBoolean("evidence.name.enabled", true);
    }

    public String nameFormat() {
        return requireText(file.getString("evidence.name.format"), "%victim%");
    }

    public double nameOffsetY() {
        return file.getDouble("evidence.name.offset-y", 2.15D);
    }

    public String nameBillboard() {
        return requireText(file.getString("evidence.name.billboard"), "CENTER");
    }

    public boolean nameShadowed() {
        return file.getBoolean("evidence.name.shadowed", true);
    }

    public boolean nameSeeThrough() {
        return file.getBoolean("evidence.name.see-through", false);
    }

    public String toolName() {
        return requireText(file.getString("police.tool.name"), "&ePolice Evidence Shovel");
    }

    public List<String> toolLore() {
        List<String> lore = file.getStringList("police.tool.lore");
        return lore.isEmpty() ? List.of("&7Right-click a crime scene chest.") : lore;
    }

    public boolean toolUnbreakable() {
        return file.getBoolean("police.tool.unbreakable", true);
    }

    public boolean toolGlow() {
        return file.getBoolean("police.tool.glow", false);
    }

    public List<String> reportLines() {
        List<String> lines = file.getStringList("police.report.lines");
        return lines.isEmpty() ? Collections.emptyList() : lines;
    }

    public boolean hospitalEnabled() {
        return file.getBoolean("hospital.enabled", true);
    }

    public String hospitalFallback() {
        return requireText(file.getString("hospital.fallback"), "world-spawn").toLowerCase();
    }

    public String hospitalFallbackWorld() {
        return requireText(file.getString("hospital.fallback-world"), "");
    }

    public boolean warnMissingHospital() {
        return file.getBoolean("hospital.warn-on-death", true);
    }

    public boolean grantExperienceOnRecovery() {
        return file.getBoolean("recovery.grant-experience", true);
    }

    public int listPageSize() {
        return Math.max(1, Math.min(50, file.getInt("evidence-list-page-size", 10)));
    }

    public String rawMessage(String key) {
        return file.getString("messages." + key, "&cMissing message: messages." + key);
    }

    /**
     * Formats a configured message, replacing placeholders and translating
     * colour codes.
     *
     * @param key          message path below {@code messages.}
     * @param replacements alternating placeholder/value pairs
     * @return coloured message
     */
    public String message(String key, String... replacements) {
        String raw = rawMessage(key);

        if (replacements != null) {
            for (int index = 0; index + 1 < replacements.length; index += 2) {
                raw = raw.replace("%" + replacements[index] + "%", replacements[index + 1]);
            }
        }

        return color(raw);
    }

    public String prefix() {
        return color(rawMessage("prefix"));
    }

    /**
     * @param raw raw text
     * @return legacy colour translated text
     */
    public static String color(String raw) {
        return raw == null ? "" : ChatColor.translateAlternateColorCodes('&', raw);
    }

    private static String requireText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
