package com.jbisb.hivetask;

import net.minecraft.core.BlockPos;
import org.junit.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CuboidTest {
    @Test
    public void cellsCoverTheCuboidExactlyWithoutOverlap() {
        Cuboid source = new Cuboid(-21, 58, 66, 35, 72, 106);
        List<Cuboid> cells = source.cells(12, new BlockPos(35, 72, 106));

        long volume = cells.stream().mapToLong(Cuboid::volume).sum();
        assertEquals(source.volume(), volume);
        for (int left = 0; left < cells.size(); left++) {
            for (int right = left + 1; right < cells.size(); right++) {
                assertFalse(cells.get(left).intersects(cells.get(right)));
            }
        }
    }

    @Test
    public void cellsAreUniqueAndNearestFirst() {
        BlockPos origin = new BlockPos(0, 64, 0);
        List<Cuboid> cells = new Cuboid(0, 60, 0, 31, 80, 31).cells(8, origin);
        Set<String> unique = new HashSet<>();
        for (Cuboid cell : cells) assertTrue(unique.add(cell.toString()));
        for (int index = 1; index < cells.size(); index++) {
            assertTrue(cells.get(index - 1).horizontalDistanceSquared(origin)
                <= cells.get(index).horizontalDistanceSquared(origin));
        }
    }
}
