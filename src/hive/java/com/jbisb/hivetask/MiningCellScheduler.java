package com.jbisb.hivetask;

import net.minecraft.core.BlockPos;

import java.util.Map;

final class MiningCellScheduler {
    private static final int MAX_LAYER_ABOVE_PLAYER = 0;
    private static final double FAILURE_DISTANCE_PENALTY = 16.0D * 16.0D;

    private MiningCellScheduler() {}

    static Cuboid choose(Iterable<Cuboid> cells, Map<String, Integer> failedAttempts, BlockPos playerPos) {
        Cuboid reachable = chooseNearest(cells, failedAttempts, playerPos,
            playerPos.getY() + MAX_LAYER_ABOVE_PLAYER);
        if (reachable != null) {
            return reachable;
        }
        return chooseNearest(cells, failedAttempts, playerPos, Integer.MAX_VALUE);
    }

    private static Cuboid chooseNearest(
            Iterable<Cuboid> cells,
            Map<String, Integer> failedAttempts,
            BlockPos playerPos,
            int maximumBottomY
    ) {
        Cuboid next = null;
        double bestScore = Double.POSITIVE_INFINITY;

        for (Cuboid candidate : cells) {
            if (candidate.y1 > maximumBottomY) {
                continue;
            }
            int failures = failedAttempts.getOrDefault(candidate.toString(), 0);
            double score = candidate.distanceSquared(playerPos)
                + failures * FAILURE_DISTANCE_PENALTY;
            if (score < bestScore
                    || (score == bestScore && next != null && candidate.y2 > next.y2)) {
                next = candidate;
                bestScore = score;
            }
        }
        return next;
    }
}
