package com.jbisb.hivetask;

import net.minecraft.world.phys.Vec3;

final class ProgressWatchdog {
    private static final double POSITION_EPSILON_SQUARED = 0.04D;

    private long lastProgressMs;
    private Vec3 lastPosition;
    private int lastRemaining = -1;

    void reset(long now, Vec3 position, int remaining) {
        lastProgressMs = now;
        lastPosition = position;
        lastRemaining = remaining;
    }

    void observe(long now, Vec3 position, int remaining) {
        boolean moved = lastPosition == null || lastPosition.distanceToSqr(position) >= POSITION_EPSILON_SQUARED;
        boolean mined = lastRemaining >= 0 && remaining < lastRemaining;
        if (moved || mined) lastProgressMs = now;
        lastPosition = position;
        lastRemaining = remaining;
    }

    long idleFor(long now) {
        return Math.max(0L, now - lastProgressMs);
    }
}
