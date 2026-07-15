package com.jbisb.hivetask;

import net.minecraft.world.phys.Vec3;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ProgressWatchdogTest {
    @Test
    public void standingStillDoesNotResetProgressTime() {
        ProgressWatchdog watchdog = new ProgressWatchdog();
        watchdog.reset(1000L, new Vec3(1, 2, 3), 50);
        watchdog.observe(5000L, new Vec3(1, 2, 3), 50);
        assertEquals(4000L, watchdog.idleFor(5000L));
    }

    @Test
    public void walkingOrMiningCountsAsProgress() {
        ProgressWatchdog watchdog = new ProgressWatchdog();
        watchdog.reset(1000L, new Vec3(1, 2, 3), 50);
        watchdog.observe(5000L, new Vec3(2, 2, 3), 50);
        assertEquals(0L, watchdog.idleFor(5000L));
        watchdog.observe(9000L, new Vec3(2, 2, 3), 49);
        assertEquals(0L, watchdog.idleFor(9000L));
    }
}
