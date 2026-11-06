/*
 * qS Death System
 * Copyright (C) qS Death System contributors.
 *
 * This file is part of a derivative work of GravesX
 * (https://github.com/Legoman99573/GravesX), originally created by Ranull.
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation, version 3 only (GPL-3.0-only).
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for details.
 *
 * Original GravesX copyright notices and attribution are preserved in NOTICE
 * and in the source headers of derived files.
 */
package dev.qsdeath;

import dev.qsdeath.command.HospitalCommand;
import dev.qsdeath.command.QSDSCommand;
import dev.qsdeath.config.PluginConfig;
import dev.qsdeath.death.DeathListener;
import dev.qsdeath.death.DeathService;
import dev.qsdeath.death.RespawnListener;
import dev.qsdeath.display.HeadService;
import dev.qsdeath.display.NameplateService;
import dev.qsdeath.evidence.EvidenceIndex;
import dev.qsdeath.evidence.EvidenceLocator;
import dev.qsdeath.evidence.EvidenceProtectionListener;
import dev.qsdeath.evidence.EvidenceRecord;
import dev.qsdeath.evidence.EvidenceRenderer;
import dev.qsdeath.evidence.EvidenceStatus;
import dev.qsdeath.evidence.RecoveryService;
import dev.qsdeath.hospital.HospitalManager;
import dev.qsdeath.police.CrimeReport;
import dev.qsdeath.police.PoliceService;
import dev.qsdeath.police.PoliceTool;
import dev.qsdeath.storage.EvidenceJournal;
import dev.qsdeath.storage.Storage;
import dev.qsdeath.util.Scheduler;
import org.bukkit.NamespacedKey;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * qS Death System plugin entry point.
 *
 * <p>Persistent death evidence with private police reports, SQLite archival and
 * automatic hospital transfer for Minecraft roleplay servers.</p>
 */
public final class QSDeathSystem extends JavaPlugin {

    private PluginConfig config;
    private Storage storage;
    private EvidenceJournal journal;
    private Scheduler scheduler;

    private EvidenceIndex evidenceIndex;
    private EvidenceLocator locator;
    private HeadService headService;
    private NameplateService nameplate;
    private EvidenceRenderer renderer;
    private CrimeReport crimeReport;
    private PoliceTool policeTool;
    private PoliceService policeService;
    private HospitalManager hospital;
    private RecoveryService recovery;
    private DeathService deathService;

    private NamespacedKey evidenceKey;

    @Override
    public void onEnable() {
        long startedAt = System.currentTimeMillis();

        saveDefaultConfig();
        config = new PluginConfig(this);
        scheduler = new Scheduler(this);
        evidenceKey = new NamespacedKey(this, "evidence_uuid");

        File dataFolder = getDataFolder();
        if (!dataFolder.exists() && !dataFolder.mkdirs()) {
            getLogger().severe("Could not create the plugin data folder; disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        journal = new EvidenceJournal(new File(dataFolder, config.journalDirectory()).toPath(), getLogger());
        try {
            journal.initialize();
        } catch (IOException exception) {
            getLogger().log(Level.SEVERE, "Could not create the evidence journal directory; "
                    + "crash safety is reduced.", exception);
        }

        storage = new Storage(new File(dataFolder, config.databaseFileName()),
                config.databaseBusyTimeoutMs(), getLogger(), config.debug());

        try {
            storage.initialize();
        } catch (SQLException exception) {
            getLogger().log(Level.SEVERE, "Could not open the SQLite database. The plugin will not "
                    + "start because death handling must not run without storage.", exception);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        evidenceIndex = new EvidenceIndex();
        locator = new EvidenceLocator(this);
        headService = new HeadService(this);
        nameplate = new NameplateService(this);
        renderer = new EvidenceRenderer(this);
        crimeReport = new CrimeReport(this);

        String toolToken = PoliceTool.loadOrCreateToken(dataFolder, getLogger());
        policeTool = new PoliceTool(this, toolToken);
        policeService = new PoliceService(this);

        hospital = new HospitalManager(this);
        hospital.load();

        recovery = new RecoveryService(this);
        deathService = new DeathService(this);

        getServer().getPluginManager().registerEvents(new DeathListener(this), this);
        getServer().getPluginManager().registerEvents(new RespawnListener(this), this);
        getServer().getPluginManager().registerEvents(new EvidenceProtectionListener(this), this);

        registerCommands();

        startRecovery();

        getLogger().info("qS Death System enabled in "
                + (System.currentTimeMillis() - startedAt) + " ms (Minecraft 1.21.11 / Paper API).");

        if (!hospital.isUsable()) {
            getLogger().warning("No usable hospital is configured (" + hospital.validationError()
                    + "). Players will use the configured fallback until /hospital set is run.");
        }
    }

    @Override
    public void onDisable() {
        if (storage != null) {
            storage.flush();
            storage.close();
        }

        getLogger().info("qS Death System disabled.");
    }

    private void registerCommands() {
        PluginCommand qsds = getCommand("qsds");
        if (qsds != null) {
            QSDSCommand executor = new QSDSCommand(this);
            qsds.setExecutor(executor);
            qsds.setTabCompleter(executor);
        }

        PluginCommand hospitalCommand = getCommand("hospital");
        if (hospitalCommand != null) {
            HospitalCommand executor = new HospitalCommand(this);
            hospitalCommand.setExecutor(executor);
            hospitalCommand.setTabCompleter(executor);
        }
    }

    /**
     * Replays the crash journal, indexes active evidence and completes or
     * retries interrupted work. Runs off the server thread; world changes are
     * scheduled back onto it.
     */
    private void startRecovery() {
        scheduler.async(() -> {
            for (EvidenceRecord record : journal.readAll()) {
                try {
                    Long id = storage.insertEvidence(record).get(20, TimeUnit.SECONDS);
                    record.id = id;
                    journal.delete(record.evidenceUuid);
                    getLogger().info("Replayed journaled evidence #" + id + " for " + record.victimName);
                } catch (Exception exception) {
                    getLogger().log(Level.SEVERE,
                            "Could not replay journal entry " + record.evidenceUuid, exception);
                }
            }

            List<EvidenceRecord> active;
            List<EvidenceRecord> pending;
            List<EvidenceRecord> processing;

            try {
                active = storage.findByStatus(EvidenceStatus.ACTIVE).get(20, TimeUnit.SECONDS);
                pending = storage.findByStatus(EvidenceStatus.PREPARING).get(20, TimeUnit.SECONDS);
                processing = storage.findByStatus(EvidenceStatus.PROCESSING).get(20, TimeUnit.SECONDS);
            } catch (Exception exception) {
                getLogger().log(Level.SEVERE, "Could not load stored evidence during startup", exception);
                return;
            }

            scheduler.sync(() -> {
                for (EvidenceRecord record : active) {
                    evidenceIndex.add(record);
                }

                for (EvidenceRecord record : processing) {
                    getLogger().warning("Completing interrupted evidence processing #" + record.id);
                    try {
                        renderer.remove(record);
                    } catch (Throwable throwable) {
                        getLogger().log(Level.WARNING,
                                "Could not remove interrupted evidence #" + record.id, throwable);
                    }
                    evidenceIndex.remove(record);
                    storage.finishProcessing(record.id, System.currentTimeMillis());
                }

                for (EvidenceRecord record : pending) {
                    getLogger().info("Restoring pending evidence #" + record.id + " for " + record.victimName);
                    deathService.placePending(record);
                }

                getLogger().info("Evidence index loaded: " + active.size() + " active, "
                        + pending.size() + " pending, " + processing.size() + " interrupted.");
            });
        });
    }

    public PluginConfig config() {
        return config;
    }

    public Storage storage() {
        return storage;
    }

    public EvidenceJournal journal() {
        return journal;
    }

    public Scheduler scheduler() {
        return scheduler;
    }

    public EvidenceIndex evidenceIndex() {
        return evidenceIndex;
    }

    public EvidenceLocator locator() {
        return locator;
    }

    public HeadService headService() {
        return headService;
    }

    public NameplateService nameplate() {
        return nameplate;
    }

    public EvidenceRenderer renderer() {
        return renderer;
    }

    public CrimeReport crimeReport() {
        return crimeReport;
    }

    public PoliceTool policeTool() {
        return policeTool;
    }

    public PoliceService policeService() {
        return policeService;
    }

    public HospitalManager hospital() {
        return hospital;
    }

    public RecoveryService recovery() {
        return recovery;
    }

    public DeathService deathService() {
        return deathService;
    }

    public NamespacedKey evidenceKey() {
        return evidenceKey;
    }
}
