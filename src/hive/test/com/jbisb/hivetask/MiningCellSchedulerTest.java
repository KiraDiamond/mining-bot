package com.jbisb.hivetask;

import net.minecraft.core.BlockPos;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertSame;

public class MiningCellSchedulerTest {
    @Test
    public void reachableLayerWinsOverAnInaccessibleUpperLayer() {
        Cuboid low = new Cuboid(0, 60, 0, 3, 64, 3);
        Cuboid high = new Cuboid(100, 95, 100, 103, 100, 103);

        Cuboid selected = MiningCellScheduler.choose(
            List.of(low, high),
            Map.of(),
            new BlockPos(0, 64, 0)
        );

        assertSame(low, selected);
    }

    @Test
    public void highestReachableLayerWinsWhenPlayerCanAccessIt() {
        Cuboid low = new Cuboid(0, 85, 0, 3, 89, 3);
        Cuboid high = new Cuboid(100, 95, 100, 103, 100, 103);

        Cuboid selected = MiningCellScheduler.choose(
            List.of(low, high),
            Map.of(),
            new BlockPos(100, 92, 100)
        );

        assertSame(high, selected);
    }

    @Test
    public void lowestLayerWinsWhenAllRemainingLayersAreAboveTheAccessBand() {
        Cuboid lower = new Cuboid(0, 80, 0, 3, 84, 3);
        Cuboid upper = new Cuboid(0, 95, 0, 3, 100, 3);

        Cuboid selected = MiningCellScheduler.choose(
            List.of(upper, lower),
            Map.of(),
            new BlockPos(0, 64, 0)
        );

        assertSame(lower, selected);
    }

    @Test
    public void nearestLeastFailedCellWinsWithinHighestLayer() {
        Cuboid retried = new Cuboid(0, 95, 0, 3, 100, 3);
        Cuboid far = new Cuboid(100, 95, 100, 103, 100, 103);
        Cuboid near = new Cuboid(10, 95, 10, 13, 100, 13);

        Cuboid selected = MiningCellScheduler.choose(
            List.of(retried, far, near),
            Map.of(retried.toString(), 1),
            new BlockPos(8, 100, 8)
        );

        assertSame(near, selected);
    }
}
