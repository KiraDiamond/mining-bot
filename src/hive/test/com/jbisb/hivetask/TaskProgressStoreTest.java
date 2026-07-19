package com.jbisb.hivetask;

import org.junit.Test;

import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TaskProgressStoreTest {
    @Test
    public void completedCellsAndMinedBlocksSurviveReopen() throws Exception {
        Path progressFile = Files.createTempDirectory("hive-progress-test").resolve("progress.log");
        TaskProgressStore.State first = stateAt(progressFile);

        assertTrue(first.recordCompleted("0,60,0 -> 3,64,3"));
        assertFalse(first.recordCompleted("0,60,0 -> 3,64,3"));
        first.recordMined(17);
        first.flushNow();

        TaskProgressStore.State reopened = stateAt(progressFile);
        assertEquals(1, reopened.completedCells().size());
        assertTrue(reopened.completedCells().contains("0,60,0 -> 3,64,3"));
        assertEquals(17L, reopened.minedBlocks());
    }

    private static TaskProgressStore.State stateAt(Path path) throws Exception {
        Constructor<TaskProgressStore.State> constructor =
            TaskProgressStore.State.class.getDeclaredConstructor(Path.class);
        constructor.setAccessible(true);
        TaskProgressStore.State state = constructor.newInstance(path);
        var load = TaskProgressStore.State.class.getDeclaredMethod("load");
        load.setAccessible(true);
        load.invoke(state);
        return state;
    }
}
