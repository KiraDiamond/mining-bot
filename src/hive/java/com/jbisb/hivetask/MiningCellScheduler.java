package com.jbisb.hivetask;

import net.minecraft.core.BlockPos;

import java.util.Map;

final class MiningCellScheduler {
    private static final int MAX_LAYER_ABOVE_PLAYER = 3;

    private MiningCellScheduler() {}

    static Cuboid choose(Iterable<Cuboid> cells, Map<String, Integer> failedAttempts, BlockPos playerPos) {
        Cuboid reachable = chooseLayer(cells, failedAttempts, playerPos, playerPos.getY() + MAX_LAYER_ABOVE_PLAYER, false);
        if (reachable != null) {
            return reachable;
        }
        return chooseLayer(cells, failedAttempts, playerPos, Integer.MAX_VALUE, true);
    }

    private static Cuboid chooseLayer(
            Iterable<Cuboid> cells,
            Map<String, Integer> failedAttempts,
            BlockPos playerPos,
            int maximumBottomY,
            boolean lowestLayer
    ) {
        Cuboid next = null;
        int selectedLayer = lowestLayer ? Integer.MAX_VALUE : Integer.MIN_VALUE;
        int fewestFailures = Integer.MAX_VALUE;
        double nearestDistance = Double.POSITIVE_INFINITY;

        for (Cuboid candidate : cells) {
            if (candidate.y1 > maximumBottomY) {
                continue;
            }
            int failures = failedAttempts.getOrDefault(candidate.toString(), 0);
            double distance = candidate.distanceSquared(playerPos);
            boolean betterLayer = lowestLayer ? candidate.y2 < selectedLayer : candidate.y2 > selectedLayer;
            if (betterLayer
                    || (candidate.y2 == selectedLayer && failures < fewestFailures)
                    || (candidate.y2 == selectedLayer
                        && failures == fewestFailures
                        && distance < nearestDistance)) {
                next = candidate;
                selectedLayer = candidate.y2;
                fewestFailures = failures;
                nearestDistance = distance;
            }
        }
        return next;
    }
}
