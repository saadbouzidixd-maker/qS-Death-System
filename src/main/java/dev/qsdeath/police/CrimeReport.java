/*
 * qS Death System
 * Copyright (C) qS Death System contributors.
 *
 * This file is part of a derivative work of GravesX, GPL-3.0-only.
 */
package dev.qsdeath.police;

import dev.qsdeath.QSDeathSystem;
import dev.qsdeath.evidence.EvidenceRecord;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the crime scene report shown privately to the officer.
 */
public final class CrimeReport {

    private final QSDeathSystem plugin;

    public CrimeReport(QSDeathSystem plugin) {
        this.plugin = plugin;
    }

    /**
     * Builds the report lines.
     *
     * @param record evidence record
     * @return formatted, coloured lines
     */
    public List<String> build(EvidenceRecord record) {
        List<String> configured = plugin.config().reportLines();
        List<String> output = new ArrayList<>();

        if (configured.isEmpty()) {
            return fallback(record);
        }

        for (String line : configured) {
            if (line.contains("%killer%") && !record.isKillerKnown()) {
                continue;
            }

            output.add(plugin.config().color(apply(record, line)));
        }

        return output;
    }

    private List<String> fallback(EvidenceRecord record) {
        List<String> lines = new ArrayList<>();
        lines.add(plugin.config().color("&8&m----------------------------------------"));
        lines.add(plugin.config().color("&c&lCRIME SCENE REPORT"));
        lines.add(plugin.config().color("&7Victim: &f" + record.victimName));
        if (record.isKillerKnown()) {
            lines.add(plugin.config().color("&7Killer: &f" + record.killerName));
        }
        lines.add(plugin.config().color("&7Cause: &f" + record.deathCause));
        lines.add(plugin.config().color("&7Location: &f" + record.worldName));
        lines.add(plugin.config().color("&7Coordinates: &fX: " + blockX(record) + ", Y: "
                + blockY(record) + ", Z: " + blockZ(record)));
        lines.add(plugin.config().color("&7Date: &f" + plugin.config().time().date(record.deathTime)));
        lines.add(plugin.config().color("&7Time: &f" + plugin.config().time().time(record.deathTime)));
        lines.add(plugin.config().color("&7Evidence ID: &f#" + record.id));
        lines.add(plugin.config().color("&8&m----------------------------------------"));
        lines.add(plugin.config().color("&aEvidence archived and removed."));
        return lines;
    }

    private String apply(EvidenceRecord record, String line) {
        return line
                .replace("%victim%", record.victimName)
                .replace("%killer%", record.isKillerKnown() ? record.killerName : "")
                .replace("%cause%", record.deathCause)
                .replace("%world%", record.worldName)
                .replace("%x%", Integer.toString(blockX(record)))
                .replace("%y%", Integer.toString(blockY(record)))
                .replace("%z%", Integer.toString(blockZ(record)))
                .replace("%date%", plugin.config().time().date(record.deathTime))
                .replace("%time%", plugin.config().time().time(record.deathTime))
                .replace("%id%", Long.toString(record.id));
    }

    private int blockX(EvidenceRecord record) {
        return (int) Math.floor(record.deathX);
    }

    private int blockY(EvidenceRecord record) {
        return (int) Math.floor(record.deathY);
    }

    private int blockZ(EvidenceRecord record) {
        return (int) Math.floor(record.deathZ);
    }
}
