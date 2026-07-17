package com.jbisb.hivetask;

import net.minecraft.world.phys.Vec3;

final class ProgressWatchdog {
    private static final double MEANINGFUL_MOVEMENT_SQUARED = 2.25D;
    private static final double TARGET_PROGRESS_EPSILON = 0.25D;

    private long lastProgressMs;
    private Vec3 lastPosition;
    private int lastRemaining = -1;
    private Vec3 target;
    private double bestTargetDistance = Double.POSITIVE_INFINITY;

    void reset(long now, Vec3 position, int remaining) {
        lastProgressMs = now;
        lastPosition = position;
        lastRemaining = remaining;
        target = null;
        bestTargetDistance = Double.POSITIVE_INFINITY;
    }

    void observe(long now, Vec3 position, int remaining) {
        boolean moved = lastPosition == null
            || lastPosition.distanceToSqr(position) >= MEANINGFUL_MOVEMENT_SQUARED;
        boolean mined = lastRemaining >= 0 && remaining < lastRemaining;
        if (moved || mined) lastProgressMs = now;
        if (moved || lastPosition == null) lastPosition = position;
        lastRemaining = remaining;
    }

    void observeWork(long now, int remaining) {
        boolean completedWork = lastRemaining >= 0 && remaining < lastRemaining;
        if (completedWork) lastProgressMs = now;
        lastRemaining = remaining;
    }

    void observeToward(long now, Vec3 position, int remaining, Vec3 destination) {
        boolean targetChanged = target == null || !target.equals(destination);
        double distance = position.distanceTo(destination);
        if (targetChanged) {
            target = destination;
            bestTargetDistance = distance;
        }

        boolean advanced = !targetChanged
            && distance <= bestTargetDistance - TARGET_PROGRESS_EPSILON;
        boolean mined = lastRemaining >= 0 && remaining < lastRemaining;
        if (advanced || mined) {
            lastProgressMs = now;
            if (advanced) bestTargetDistance = distance;
        }
        lastPosition = position;
        lastRemaining = remaining;
    }

    long idleFor(long now) {
        return Math.max(0L, now - lastProgressMs);
    }
}
