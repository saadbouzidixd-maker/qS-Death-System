/*
 * qS Death System
 * Copyright (C) qS Death System contributors.
 *
 * This file is part of a derivative work of GravesX, GPL-3.0-only.
 */
package dev.qsdeath.storage;

import java.util.UUID;

/**
 * The single configured hospital operating-room spawn.
 */
public final class HospitalRecord {

    public UUID worldUuid;
    public String worldName = "";
    public double x;
    public double y;
    public double z;
    public float yaw;
    public float pitch;
    public long updatedAt;
}
