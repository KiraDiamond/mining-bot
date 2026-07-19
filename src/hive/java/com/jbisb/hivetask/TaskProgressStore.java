package com.jbisb.hivetask;

import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

final class TaskProgressStore {
    private static final BlockingQueue<State> DIRTY_STATES = new LinkedBlockingQueue<>();
    private static final Set<State> OPEN_STATES = ConcurrentHashMap.newKeySet();

    static {
        Thread writer = new Thread(TaskProgressStore::writeLoop, "HiveTaskProgressWriter");
        writer.setDaemon(true);
        writer.start();
        Runtime.getRuntime().addShutdownHook(new Thread(
            () -> OPEN_STATES.forEach(State::flushNow),
            "HiveTaskProgressShutdown"
        ));
    }

    private TaskProgressStore() {}

    static State open(String taskId, Cuboid cuboid) {
        String safeId = taskId.replaceAll("[^A-Za-z0-9._-]", "_");
        String cuboidHash = Integer.toUnsignedString(cuboid.toString().hashCode(), 16);
        Path path = Minecraft.getInstance().gameDirectory.toPath()
            .resolve("hive-task-progress")
            .resolve(safeId + "-" + cuboidHash + ".log");
        State state = new State(path);
        state.load();
        OPEN_STATES.add(state);
        return state;
    }

    private static void writeLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                State state = DIRTY_STATES.take();
                state.flushNow();
                state.queued.set(false);
                if (state.hasPendingData()) {
                    state.scheduleWrite();
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    static final class State {
        private final Path path;
        private final Set<String> completedCells = new HashSet<>();
        private final ConcurrentLinkedQueue<String> pendingCells = new ConcurrentLinkedQueue<>();
        private final AtomicLong pendingMined = new AtomicLong();
        private final AtomicBoolean queued = new AtomicBoolean();
        private long minedBlocks;

        private State(Path path) {
            this.path = path;
        }

        Set<String> completedCells() {
            return Set.copyOf(completedCells);
        }

        long minedBlocks() {
            return minedBlocks;
        }

        boolean recordCompleted(String cell) {
            if (!completedCells.add(cell)) {
                return false;
            }
            pendingCells.add(cell);
            scheduleWrite();
            return true;
        }

        void recordMined(long count) {
            if (count <= 0) {
                return;
            }
            minedBlocks += count;
            pendingMined.addAndGet(count);
            scheduleWrite();
        }

        private void load() {
            if (!Files.isRegularFile(path)) {
                return;
            }
            try {
                for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                    if (line.startsWith("C\t")) {
                        completedCells.add(line.substring(2));
                    } else if (line.startsWith("M\t")) {
                        minedBlocks += Long.parseLong(line.substring(2));
                    }
                }
            } catch (IOException | NumberFormatException error) {
                System.err.println("Could not load task progress " + path + ": " + error.getMessage());
            }
        }

        private void scheduleWrite() {
            if (queued.compareAndSet(false, true)) {
                DIRTY_STATES.offer(this);
            }
        }

        private boolean hasPendingData() {
            return !pendingCells.isEmpty() || pendingMined.get() > 0;
        }

        synchronized void flushNow() {
            StringBuilder output = new StringBuilder();
            String cell;
            while ((cell = pendingCells.poll()) != null) {
                output.append("C\t").append(cell).append('\n');
            }
            long mined = pendingMined.getAndSet(0);
            if (mined > 0) {
                output.append("M\t").append(mined).append('\n');
            }
            if (output.isEmpty()) {
                return;
            }
            try {
                Files.createDirectories(path.getParent());
                Files.writeString(
                    path,
                    output,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
                );
            } catch (IOException error) {
                for (String line : output.toString().split("\n")) {
                    if (line.startsWith("C\t")) {
                        pendingCells.add(line.substring(2));
                    } else if (line.startsWith("M\t")) {
                        pendingMined.addAndGet(Long.parseLong(line.substring(2)));
                    }
                }
                System.err.println("Could not save task progress " + path + ": " + error.getMessage());
            }
        }
    }
}
