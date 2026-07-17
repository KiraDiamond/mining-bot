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
        watchdog.observe(5000L, new Vec3(3, 2, 3), 50);
        assertEquals(0L, watchdog.idleFor(5000L));
        watchdog.observe(9000L, new Vec3(2, 2, 3), 49);
        assertEquals(0L, watchdog.idleFor(9000L));
    }

    @Test
    public void oneBlockVerticalOscillationDoesNotCountAsProgress() {
        ProgressWatchdog watchdog = new ProgressWatchdog();
        watchdog.reset(1000L, new Vec3(-76, 62, 272), 0);
        watchdog.observe(5000L, new Vec3(-76, 63, 272), 0);
        watchdog.observe(9000L, new Vec3(-76, 62, 272), 0);
        watchdog.observe(13000L, new Vec3(-76, 63, 272), 0);
        assertEquals(12000L, watchdog.idleFor(13000L));
    }

    @Test
    public void travelOnlyCountsNewBestDistanceTowardDestination() {
        ProgressWatchdog watchdog = new ProgressWatchdog();
        Vec3 destination = new Vec3(10, 62, 0);
        watchdog.reset(1000L, new Vec3(0, 62, 0), 0);
        watchdog.observeToward(2000L, new Vec3(0, 62, 0), 0, destination);
        watchdog.observeToward(5000L, new Vec3(0, 63, 0), 0, destination);
        watchdog.observeToward(9000L, new Vec3(0, 62, 0), 0, destination);
        assertEquals(8000L, watchdog.idleFor(9000L));

        watchdog.observeToward(12000L, new Vec3(1, 62, 0), 0, destination);
        assertEquals(0L, watchdog.idleFor(12000L));
        watchdog.observeToward(15000L, new Vec3(0, 62, 0), 0, destination);
        assertEquals(3000L, watchdog.idleFor(15000L));
    }
}
