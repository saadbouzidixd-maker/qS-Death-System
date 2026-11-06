/*
 * qS Death System
 * Copyright (C) qS Death System contributors.
 *
 * This file is part of a derivative work of GravesX, GPL-3.0-only.
 */
package dev.qsdeath.util;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Formats death timestamps for police reports and listings.
 */
public final class TimeUtil {

    private final ZoneId zoneId;
    private final DateTimeFormatter dateFormatter;
    private final DateTimeFormatter timeFormatter;

    public TimeUtil(String zone, String datePattern, String timePattern) {
        this.zoneId = resolveZone(zone);
        this.dateFormatter = DateTimeFormatter.ofPattern(
                datePattern == null || datePattern.isBlank() ? "yyyy-MM-dd" : datePattern, Locale.ROOT);
        this.timeFormatter = DateTimeFormatter.ofPattern(
                timePattern == null || timePattern.isBlank() ? "HH:mm:ss" : timePattern, Locale.ROOT);
    }

    private static ZoneId resolveZone(String zone) {
        if (zone == null || zone.isBlank() || zone.equalsIgnoreCase("system")) {
            return ZoneId.systemDefault();
        }

        try {
            return ZoneId.of(zone);
        } catch (RuntimeException ignored) {
            return ZoneId.systemDefault();
        }
    }

    public String date(long epochMillis) {
        return dateFormatter.format(Instant.ofEpochMilli(epochMillis).atZone(zoneId));
    }

    public String time(long epochMillis) {
        return timeFormatter.format(Instant.ofEpochMilli(epochMillis).atZone(zoneId));
    }

    public String dateTime(long epochMillis) {
        return date(epochMillis) + " " + time(epochMillis);
    }
}
