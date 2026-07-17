package com.jbisb.hivetask;

import net.minecraft.core.BlockPos;

import java.util.Map;

final class MiningCellScheduler {
    private MiningCellScheduler() {}

    static Cuboid choose(Iterable<Cuboid> cells, Map<String, Integer> failedAttempts, BlockPos playerPos) {
        Cuboid next = null;
        int highestLayer = Integer.MIN_VALUE;
        int fewestFailures = Integer.MAX_VALUE;
        double nearestDistance = Double.POSITIVE_INFINITY;

        for (Cuboid candidate : cells) {
            int failures = failedAttempts.getOrDefault(candidate.toString(), 0);
            double distance = candidate.distanceSquared(playerPos);
            if (candidate.y2 > highestLayer
                    || (candidate.y2 == highestLayer && failures < fewestFailures)
                    || (candidate.y2 == highestLayer
                        && failures == fewestFailures
                        && distance < nearestDistance)) {
                next = candidate;
                highestLayer = candidate.y2;
                fewestFailures = failures;
                nearestDistance = distance;
            }
        }
        return next;
    }
}
