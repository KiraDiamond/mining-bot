package com.jbisb.hivetask;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalXZ;
import baritone.utils.ToolSet;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class HiveTaskClient {
    private static final Gson GSON = new Gson();
    private static final Minecraft MC = Minecraft.getInstance();
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_CLIENT_WORK_PER_TICK = 8;
    private static final int CELL_SIZE = envInt("TASK_CELL_SIZE", 12, 4, 32);
    private static final int LAYER_HEIGHT = envInt("TASK_LAYER_HEIGHT", 5, 2, 8);
    private static final int ARRIVAL_RADIUS = envInt("TASK_ARRIVAL_RADIUS", 24, 4, 32);
    private static final long STATUS_INTERVAL_MS = envLong("TASK_STATUS_INTERVAL_MS", 10000L);
    private static final long STUCK_TIMEOUT_MS = envLong("TASK_STUCK_TIMEOUT_MS", 120000L);
    private static final long TAIL_STUCK_TIMEOUT_MS = envLong("TASK_TAIL_STUCK_TIMEOUT_MS", 30000L);
    private static final long INACTIVE_TIMEOUT_MS = envLong("TASK_INACTIVE_TIMEOUT_MS", 20000L);
    private static final long WORLD_JOIN_TIMEOUT_MS = envLong("TASK_WORLD_JOIN_TIMEOUT_MS", 90000L);
    private static final int MAX_CELL_ATTEMPTS = envInt("TASK_MAX_CELL_ATTEMPTS", 3, 1, 10);

    private final ConcurrentLinkedQueue<Runnable> clientWork = new ConcurrentLinkedQueue<>();
    private final ProgressWatchdog watchdog = new ProgressWatchdog();
    private ControlConnection connection;
    private ActiveTask task;
    private Stage stage = Stage.IDLE;
    private long stageSinceMs;
    private long lastStatusMs;
    private long lastSnapshotCheckMs;
    private long lastRespawnAttemptMs;
    private long lastNativeActiveMs;
    private long lastSettingsEnforceMs;
    private long lastAccessAttemptMs;
    private boolean worldTimeoutTriggered;
    private String blocker = "";

    private static final HiveTaskClient INSTANCE = new HiveTaskClient();
    private static boolean initialized;

    public static void tickClient() {
        if (!initialized) {
            initialized = true;
            INSTANCE.initialize();
        }
        INSTANCE.tick();
    }

    private void initialize() {
        LOGGER.info("[HiveMiner] Initializing safety-first task controller.");
        connection = new ControlConnection(this);
        connection.start();
    }

    private void tick() {
        Runnable work;
        for (int processed = 0; processed < MAX_CLIENT_WORK_PER_TICK && (work = clientWork.poll()) != null; processed++) {
            try {
                work.run();
            } catch (RuntimeException error) {
                LOGGER.error("[HiveMiner] Controller work failed", error);
                sendError(error.getMessage());
            }
        }

        long now = System.currentTimeMillis();
        if (task != null && now - lastSettingsEnforceMs >= 1000L) {
            lastSettingsEnforceMs = now;
            enforceStageSettings();
        }
        if (task != null) tickTask(now);
        if (now - lastStatusMs >= STATUS_INTERVAL_MS) {
            lastStatusMs = now;
            sendStatus();
        }
    }

    private void handleMessage(JsonObject message) {
        String type = string(message, "type", "");
        if ("task".equals(type) && message.has("task")) {
            JsonObject incoming = message.getAsJsonObject("task").deepCopy();
            clientWork.add(() -> startTask(incoming));
        } else if ("stop".equals(type)) {
            clientWork.add(() -> stopTask("Stopped by controller.", true));
        } else if ("ping".equals(type)) {
            sendStatus();
        }
    }

    private void startTask(JsonObject input) {
        stopTask(null, false);
        String id = string(input, "id", UUID.randomUUID().toString());
        String kind = string(input, "kind", "");
        int minDurability = integer(input, "minDurability", 5);

        if ("mine-cuboid".equals(kind)) {
            Cuboid cuboid = Cuboid.fromJson(input.getAsJsonObject("cuboid"));
            validateNoZones(cuboid, input);
            task = ActiveTask.mining(id, cuboid, minDurability);
        } else if ("goto".equals(kind)) {
            task = ActiveTask.travel(id, new BlockPos(
                requiredInt(input, "x"), requiredInt(input, "y"), requiredInt(input, "z")
            ));
        } else {
            throw new IllegalArgumentException("Unsupported safety-mode task kind: " + kind);
        }

        MiningSafety.beginManagedTask();
        configureTravelSettings();
        if (MC.player == null || MC.level == null) {
            setStage(Stage.WAITING_WORLD, "Waiting to join the server.");
            return;
        }
        initializeTaskInWorld();
    }

    private void validateNoZones(Cuboid requested, JsonObject input) {
        JsonArray zones = input.has("noZones") && input.get("noZones").isJsonArray()
            ? input.getAsJsonArray("noZones")
            : new JsonArray();
        for (JsonElement element : zones) {
            if (!element.isJsonObject()) continue;
            JsonObject zone = element.getAsJsonObject();
            if (zone.has("disabled") && zone.get("disabled").getAsBoolean()) continue;
            if (zone.has("cuboid") && requested.intersects(Cuboid.fromJson(zone.getAsJsonObject("cuboid")))) {
                throw new IllegalStateException("Requested cuboid intersects a no-mine zone.");
            }
        }
    }

    private void initializeTaskInWorld() {
        LocalPlayer player = MC.player;
        if (player == null || task == null) return;
        if (task.kind == TaskKind.GOTO) {
            beginTravel(task.destination, false);
            return;
        }
        if (!task.cellsInitialized) {
            task.cells.addAll(task.cuboid.cells(CELL_SIZE, LAYER_HEIGHT, player.blockPosition()));
            task.totalCells = task.cells.size();
            task.cellsInitialized = true;
            sendEvent("Prepared " + task.totalCells + " non-overlapping mining cells for " + task.cuboid + ".");
        }
        beginNextCell();
    }

    private void tickTask(long now) {
        LocalPlayer player = MC.player;
        if (player == null || MC.level == null) {
            if (stage != Stage.WAITING_WORLD) {
                cancelNative();
                MiningSafety.disarmBreaking();
                setStage(Stage.WAITING_WORLD, "Disconnected; task retained for reconnect.");
                sendEvent("Disconnected with task retained; mining is disarmed.");
            }
            if (!worldTimeoutTriggered && now - stageSinceMs >= WORLD_JOIN_TIMEOUT_MS) {
                worldTimeoutTriggered = true;
                sendEvent("World join timed out after " + WORLD_JOIN_TIMEOUT_MS / 1000L
                    + " seconds; exiting for managed relaunch.");
                Thread exitThread = new Thread(() -> {
                    try {
                        Thread.sleep(500L);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    System.exit(86);
                }, "HiveMinerJoinTimeout");
                exitThread.setDaemon(true);
                exitThread.start();
            }
            return;
        }
        worldTimeoutTriggered = false;

        if (player.isDeadOrDying()) {
            if (stage != Stage.WAITING_RESPAWN) {
                cancelNative();
                MiningSafety.disarmBreaking();
                setStage(Stage.WAITING_RESPAWN, "Died; waiting to respawn safely.");
                sendEvent("Death detected; task retained and breaking disarmed.");
            }
            if (now - lastRespawnAttemptMs >= 2000L) {
                lastRespawnAttemptMs = now;
                player.respawn();
            }
            return;
        }

        if (stage == Stage.WAITING_WORLD || stage == Stage.WAITING_RESPAWN) {
            if (now - stageSinceMs >= 3000L) {
                sendEvent("World is ready; resuming task with non-destructive travel.");
                resumeCurrentTask();
            }
            return;
        }

        if (stage == Stage.RECOVERING) {
            if (now - stageSinceMs >= 3000L) retryCurrentWork();
            return;
        }

        if (stage == Stage.TRAVELLING) {
            tickTravel(now, player);
        } else if (stage == Stage.MINING) {
            tickMining(now, player);
        } else if (stage == Stage.ESCAPING) {
            tickEscape(now, player);
        }
    }

    private void resumeCurrentTask() {
        if (task == null) return;
        if (task.kind == TaskKind.GOTO) {
            beginTravel(task.destination, false);
        } else if (task.currentCell != null) {
            beginTravel(task.currentCell.center(), true);
        } else {
            initializeTaskInWorld();
        }
    }

    private void beginNextCell() {
        if (task == null || task.kind != TaskKind.MINE_CUBOID) return;
        Cuboid next = task.cells.pollFirst();
        if (next == null) {
            finishMiningTask();
            return;
        }
        task.currentCell = next;
        task.travelAttempts = 0;
        task.cleaningSupports = false;
        task.descendingSupports = false;
        task.cellSnapshot.clear();
        task.cellPassMined = 0;
        beginTravel(next.center(), true);
    }

    private void beginTravel(BlockPos destination, boolean toMiningCell) {
        if (task == null || MC.player == null) return;
        cancelNative();
        MiningSafety.disarmBreaking();
        task.escapeSnapshot.clear();
        task.escapeDestination = null;
        task.escapeClearing = false;
        task.escapeDescending = false;
        configureTravelSettings();
        task.travelDestination = destination;
        task.travelToCell = toMiningCell;
        blocker = "";
        setStage(Stage.TRAVELLING, "");
        watchdog.reset(stageSinceMs, MC.player.position(), 0);

        IBaritone baritone = primaryBaritone();
        if (toMiningCell) {
            baritone.getCustomGoalProcess().setGoalAndPath(new GoalXZ(destination.getX(), destination.getZ()));
            sendEvent("Travelling non-destructively to cell " + task.currentCell + ".");
        } else {
            baritone.getCustomGoalProcess().setGoalAndPath(new GoalBlock(destination));
            sendEvent("Travelling non-destructively to " + destination + ".");
        }
    }

    private void tickTravel(long now, LocalPlayer player) {
        BlockPos destination = task.travelDestination;
        if (destination == null) return;
        watchdog.observe(now, player.position(), 0);
        double horizontalDistance = horizontalDistance(player.blockPosition(), destination);

        if (task.travelToCell) {
            if (horizontalDistance <= ARRIVAL_RADIUS && currentCellChunksLoaded()) {
                startMiningCell();
                return;
            }
        } else if (player.blockPosition().distSqr(destination) <= 4.0D) {
            completeTask("Reached target " + destination + ".");
            return;
        }

        boolean active = primaryBaritone().getCustomGoalProcess().isActive();
        if (active) lastNativeActiveMs = now;
        long idleFor = watchdog.idleFor(now);
        if (idleFor >= INACTIVE_TIMEOUT_MS
            && now - lastAccessAttemptMs >= 5000L
            && tryOpenNearbyAccess(player)) {
            lastAccessAttemptMs = now;
            sendEvent("Opened a nearby access block and retried the same non-destructive route.");
            beginTravel(destination, task.travelToCell);
        } else if (idleFor >= INACTIVE_TIMEOUT_MS
            && beginRouteDescent("Route is stranded on an underfoot block; descending inside the assigned cuboid.")) {
            return;
        } else if (!active && now - stageSinceMs >= INACTIVE_TIMEOUT_MS) {
            recoverTravel("Native Baritone stopped before reaching the destination.");
        } else if (idleFor >= STUCK_TIMEOUT_MS) {
            recoverTravel("No walking progress for " + STUCK_TIMEOUT_MS / 1000L + " seconds.");
        }
    }

    private boolean tryOpenNearbyAccess(LocalPlayer player) {
        if (MC.level == null) return false;
        BlockPos origin = player.blockPosition();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = -1; y <= 2; y++) {
            for (int x = -2; x <= 2; x++) {
                for (int z = -2; z <= 2; z++) {
                    cursor.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                    BlockState state = MC.level.getBlockState(cursor);
                    Block block = state.getBlock();
                    if (!(block instanceof DoorBlock || block instanceof FenceGateBlock || block instanceof TrapDoorBlock)
                        || !state.hasProperty(BlockStateProperties.OPEN)
                        || state.getValue(BlockStateProperties.OPEN)) {
                        continue;
                    }
                    BlockPos target = cursor.immutable();
                    BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(target), Direction.UP, target, false);
                    primaryBaritone().getPlayerContext().playerController().processRightClickBlock(
                        player, MC.level, InteractionHand.MAIN_HAND, hit
                    );
                    return true;
                }
            }
        }
        return false;
    }

    private boolean beginRouteDescent(String reason) {
        if (task == null || task.kind != TaskKind.MINE_CUBOID || task.travelDestination == null) return false;
        task.escapeDestination = task.travelDestination;
        blocker = reason;
        return beginEscapeDescent();
    }

    private boolean currentCellChunksLoaded() {
        if (MC.level == null || task == null || task.currentCell == null) return false;
        Cuboid cell = task.currentCell;
        return MC.level.hasChunkAt(new BlockPos(cell.x1, cell.y1, cell.z1))
            && MC.level.hasChunkAt(new BlockPos(cell.x2, cell.y1, cell.z1))
            && MC.level.hasChunkAt(new BlockPos(cell.x1, cell.y1, cell.z2))
            && MC.level.hasChunkAt(new BlockPos(cell.x2, cell.y1, cell.z2));
    }

    private void startMiningCell() {
        if (task == null || task.currentCell == null || MC.level == null || MC.player == null) return;
        cancelNative();
        task.travelAttempts = 0;
        task.cleaningSupports = false;
        task.descendingSupports = false;
        task.escapeSnapshot.clear();
        configureMiningSettings();
        Map<Long, Block> snapshot = snapshotCell(task.currentCell, true);
        task.cellSnapshot.clear();
        task.cellSnapshot.putAll(snapshot);
        task.cellInitialBlocks = snapshot.size();
        if (snapshot.isEmpty()) {
            completeCurrentCell();
            return;
        }

        Cuboid scaffoldColumn = currentScaffoldColumn();
        MiningSafety.armBreaking(task.cellSnapshot, scaffoldColumn);
        setStage(Stage.MINING, "");
        watchdog.reset(stageSinceMs, MC.player.position(), task.cellSnapshot.size());
        lastNativeActiveMs = stageSinceMs;
        primaryBaritone().getBuilderProcess().clearArea(
            new BlockPos(task.currentCell.x1, task.currentCell.y1, task.currentCell.z1),
            new BlockPos(task.currentCell.x2, task.currentCell.y2, task.currentCell.z2)
        );
        sendEvent("Snapshotted " + snapshot.size() + " breakable blocks and started cell " + task.currentCell + ".");
    }

    private Map<Long, Block> snapshotCell(Cuboid cell) {
        return snapshotCell(cell, false);
    }

    private Map<Long, Block> snapshotCell(Cuboid cell, boolean excludePlacedSupports) {
        Map<Long, Block> snapshot = new HashMap<>();
        Set<Block> dynamicProtected = new HashSet<>(MiningSafety.protectedBlocks());
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = cell.x1; x <= cell.x2; x++) {
            for (int z = cell.z1; z <= cell.z2; z++) {
                for (int y = cell.y2; y >= cell.y1; y--) {
                    pos.set(x, y, z);
                    BlockState state = MC.level.getBlockState(pos);
                    if (state.isAir() || !state.getFluidState().isEmpty()) continue;
                    if (excludePlacedSupports && MiningSafety.isPlacedSupport(pos, state)) continue;
                    Block block = state.getBlock();
                    if (MiningSafety.isProtected(block) || MC.level.getBlockEntity(pos) != null) {
                        dynamicProtected.add(block);
                        continue;
                    }
                    if (state.getDestroySpeed(MC.level, pos) < 0.0F) continue;
                    if (state.requiresCorrectToolForDrops() && !hasSafeCorrectTool(state)) {
                        task.skippedToolBlocks++;
                        continue;
                    }
                    snapshot.put(pos.asLong(), block);
                }
            }
        }
        applyProtectedBlocks(dynamicProtected);
        return snapshot;
    }

    private void tickMining(long now, LocalPlayer player) {
        if (now - lastSnapshotCheckMs >= 500L) {
            lastSnapshotCheckMs = now;
            pruneSnapshot();
        }
        if (task.descendingSupports) {
            tickSupportDescent(now, player);
            return;
        }
        int remaining = task.cellSnapshot.size();
        watchdog.observe(now, player.position(), remaining);
        if (remaining == 0) {
            if (task.cleaningSupports) completeCurrentCell();
            else beginSupportCleanup();
            return;
        }

        boolean active = primaryBaritone().getBuilderProcess().isActive();
        if (active) lastNativeActiveMs = now;
        long idleFor = watchdog.idleFor(now);
        if (task.cleaningSupports) {
            if ((!active && now - lastNativeActiveMs >= INACTIVE_TIMEOUT_MS) || idleFor >= STUCK_TIMEOUT_MS) {
                sendEvent("Left " + remaining + " unreachable temporary support block(s); continuing without rebuilding them.");
                completeCurrentCell();
            }
            return;
        }
        if (!active && now - lastNativeActiveMs >= INACTIVE_TIMEOUT_MS) {
            escapeOrRecover("Native Baritone became inactive with " + remaining + " snapshotted blocks remaining.");
        } else if (remaining <= 4 && idleFor >= TAIL_STUCK_TIMEOUT_MS) {
            recover("Small unreachable tail made no progress for " + TAIL_STUCK_TIMEOUT_MS / 1000L + " seconds.");
        } else if (!task.currentCell.containsHorizontal(player.blockPosition()) && idleFor >= INACTIVE_TIMEOUT_MS) {
            escapeOrRecover("Bot is outside the current cell and made no progress for " + INACTIVE_TIMEOUT_MS / 1000L + " seconds.");
        } else if (idleFor >= STUCK_TIMEOUT_MS) {
            escapeOrRecover("No movement or mined-block progress for " + STUCK_TIMEOUT_MS / 1000L + " seconds.");
        }
    }

    private Cuboid currentScaffoldColumn() {
        return new Cuboid(
            task.currentCell.x1, task.cuboid.y1, task.currentCell.z1,
            task.currentCell.x2, task.currentCell.y2, task.currentCell.z2
        );
    }

    private void beginSupportCleanup() {
        if (task == null || task.currentCell == null || MC.player == null) return;
        Cuboid scaffoldColumn = currentScaffoldColumn();
        Map<Long, Block> supports = MiningSafety.placedSupports(scaffoldColumn);
        if (supports.isEmpty()) {
            completeCurrentCell();
            return;
        }

        cancelNative();
        task.cleaningSupports = true;
        task.descendingSupports = false;
        task.cellSnapshot.clear();
        task.cellSnapshot.putAll(supports);
        configureBreakOnlySettings();
        MiningSafety.armSupportCleanup(task.cellSnapshot);
        setStage(Stage.MINING, "Cleaning temporary supports without placement.");
        watchdog.reset(stageSinceMs, MC.player.position(), supports.size());
        lastNativeActiveMs = stageSinceMs;
        task.escapeBreakTarget = null;

        BlockPos underfoot = MC.player.blockPosition().below();
        if (supports.containsKey(underfoot.asLong())) {
            task.descendingSupports = true;
            sendEvent("Terrain cleared; descending the recorded scaffold before cleanup pathing.");
            return;
        }
        startSupportBuilderCleanup();
    }

    private void startSupportBuilderCleanup() {
        if (task == null || task.currentCell == null || MC.player == null) return;
        pruneSnapshot();
        if (task.cellSnapshot.isEmpty()) {
            completeCurrentCell();
            return;
        }
        task.descendingSupports = false;
        configureBreakOnlySettings();
        MiningSafety.armSupportCleanup(task.cellSnapshot);
        watchdog.reset(System.currentTimeMillis(), MC.player.position(), task.cellSnapshot.size());
        lastNativeActiveMs = System.currentTimeMillis();
        Cuboid scaffoldColumn = currentScaffoldColumn();
        primaryBaritone().getBuilderProcess().clearArea(
            new BlockPos(scaffoldColumn.x1, scaffoldColumn.y1, scaffoldColumn.z1),
            new BlockPos(scaffoldColumn.x2, scaffoldColumn.y2, scaffoldColumn.z2)
        );
        sendEvent("Removing " + task.cellSnapshot.size() + " recorded scaffold block(s) from ground-safe cleanup with placement disabled.");
    }

    private void tickSupportDescent(long now, LocalPlayer player) {
        int remaining = task.cellSnapshot.size();
        watchdog.observe(now, player.position(), remaining);
        BlockPos support = player.blockPosition().below();
        Block expected = task.cellSnapshot.get(support.asLong());
        if (expected != null && MC.level.getBlockState(support).getBlock() == expected) {
            BlockState state = MC.level.getBlockState(support);
            if (!selectSupportBreakTool(state)) {
                sendEvent("Could not safely tool the underfoot scaffold; leaving it and continuing instead of freezing.");
                resetDirectBreak();
                task.descendingSupports = false;
                task.cellSnapshot.remove(support.asLong());
                MiningSafety.replaceSnapshot(task.cellSnapshot);
                startSupportBuilderCleanup();
                return;
            }
            var controller = primaryBaritone().getPlayerContext().playerController();
            if (!support.equals(task.escapeBreakTarget)) {
                controller.resetBlockRemoving();
                task.escapeBreakTarget = support.immutable();
                controller.clickBlock(support, Direction.UP);
            } else {
                controller.onPlayerDamageBlock(support, Direction.UP);
            }
            return;
        }
        resetDirectBreak();
        if (player.onGround()) startSupportBuilderCleanup();
    }

    private void escapeOrRecover(String reason) {
        if (beginSafeEscape(reason)) return;
        recover(reason);
    }

    private boolean beginSafeEscape(String reason) {
        if (task == null || task.currentCell == null || MC.player == null || MC.level == null) return false;
        BlockPos playerPos = MC.player.blockPosition();
        String key = task.currentCell.toString();
        if (!task.cuboid.contains(playerPos) || !task.escapeAttempted.add(key)) return false;

        int targetX = clamp(playerPos.getX(), task.currentCell.x1, task.currentCell.x2);
        int targetZ = clamp(playerPos.getZ(), task.currentCell.z1, task.currentCell.z2);
        Cuboid corridor = new Cuboid(
            Math.max(task.cuboid.x1, Math.min(playerPos.getX(), targetX) - 1),
            Math.max(task.cuboid.y1, playerPos.getY() - 3),
            Math.max(task.cuboid.z1, Math.min(playerPos.getZ(), targetZ) - 1),
            Math.min(task.cuboid.x2, Math.max(playerPos.getX(), targetX) + 1),
            Math.min(task.cuboid.y2, playerPos.getY() + 2),
            Math.min(task.cuboid.z2, Math.max(playerPos.getZ(), targetZ) + 1)
        );
        Map<Long, Block> snapshot = snapshotCell(corridor);
        // Builder intentionally preserves the block supporting the player. It is a safe
        // step-off platform, not an obstruction that should keep recovery waiting forever.
        snapshot.remove(playerPos.below().asLong());

        cancelNative();
        task.escapeSnapshot.clear();
        task.escapeSnapshot.putAll(snapshot);
        task.escapeDestination = new BlockPos(targetX, playerPos.getY(), targetZ);
        blocker = reason;
        if (snapshot.isEmpty()) {
            beginEscapeTraversal("Escape corridor is already open");
            return true;
        }

        task.escapeClearing = true;
        task.escapeDescending = false;
        configureBreakOnlySettings();
        MiningSafety.armBreaking(task.escapeSnapshot);
        setStage(Stage.ESCAPING, reason);
        watchdog.reset(stageSinceMs, MC.player.position(), snapshot.size());
        lastNativeActiveMs = stageSinceMs;
        primaryBaritone().getBuilderProcess().clearArea(
            new BlockPos(corridor.x1, corridor.y1, corridor.z1),
            new BlockPos(corridor.x2, corridor.y2, corridor.z2)
        );
        sendEvent("Opening coordinate-gated escape corridor " + corridor + " with " + snapshot.size() + " snapshotted blocks.");
        return true;
    }

    private void tickEscape(long now, LocalPlayer player) {
        if (task.escapeDescending) {
            tickEscapeDescent(now, player);
            return;
        }
        if (!task.escapeClearing) {
            tickEscapeTraversal(now, player);
            return;
        }
        if (now - lastSnapshotCheckMs >= 500L) {
            lastSnapshotCheckMs = now;
            pruneSnapshot(task.escapeSnapshot);
        }
        int remaining = task.escapeSnapshot.size();
        watchdog.observe(now, player.position(), remaining);
        if (remaining == 0) {
            beginEscapeTraversal("Escape corridor cleared");
            return;
        }
        boolean active = primaryBaritone().getBuilderProcess().isActive();
        if (active) lastNativeActiveMs = now;
        if ((!active && now - lastNativeActiveMs >= INACTIVE_TIMEOUT_MS)
                || watchdog.idleFor(now) >= STUCK_TIMEOUT_MS) {
            task.escapeSnapshot.clear();
            recover("Coordinate-gated escape could not free the bot; " + remaining + " blocks remained.");
        }
    }

    private void beginEscapeTraversal(String reason) {
        if (task == null || task.escapeDestination == null || MC.player == null) return;
        cancelNative();
        MiningSafety.disarmBreaking();
        task.escapeSnapshot.clear();
        task.escapeClearing = false;
        task.escapeDescending = false;
        configureTravelSettings();
        setStage(Stage.ESCAPING, blocker);
        watchdog.reset(stageSinceMs, MC.player.position(), 0);
        lastNativeActiveMs = stageSinceMs;
        BlockPos destination = task.escapeDestination;
        primaryBaritone().getCustomGoalProcess().setGoalAndPath(new GoalXZ(destination.getX(), destination.getZ()));
        sendEvent(reason + "; stepping non-destructively into cell at "
            + destination.getX() + "," + destination.getZ() + ".");
    }

    private void tickEscapeTraversal(long now, LocalPlayer player) {
        watchdog.observe(now, player.position(), 0);
        BlockPos destination = task.escapeDestination;
        if (destination == null) {
            recover("Escape traversal lost its destination.");
            return;
        }
        if (task.currentCell.containsHorizontal(player.blockPosition())) {
            sendEvent("Stepped safely into the assigned cell; resuming its snapshot.");
            startMiningCell();
            return;
        }
        boolean active = primaryBaritone().getCustomGoalProcess().isActive();
        if (active) lastNativeActiveMs = now;
        long idleFor = watchdog.idleFor(now);
        if (idleFor >= INACTIVE_TIMEOUT_MS && beginEscapeDescent()) {
            return;
        }
        if (!active && now - lastNativeActiveMs >= INACTIVE_TIMEOUT_MS) {
            recover("Non-destructive escape traversal became inactive.");
        } else if (idleFor >= STUCK_TIMEOUT_MS) {
            recover("Non-destructive escape traversal made no movement for "
                + STUCK_TIMEOUT_MS / 1000L + " seconds.");
        }
    }

    private boolean beginEscapeDescent() {
        if (task == null || task.currentCell == null || MC.player == null || MC.level == null) return false;
        BlockPos playerPos = MC.player.blockPosition();
        String key = task.currentCell.toString();
        if (!task.cuboid.contains(playerPos)
                || playerPos.getY() <= task.cuboid.y1
                || !task.escapeDescentAttempted.add(key)) {
            return false;
        }

        Cuboid column = new Cuboid(
            playerPos.getX(), task.cuboid.y1, playerPos.getZ(),
            playerPos.getX(), playerPos.getY() - 1, playerPos.getZ()
        );
        Map<Long, Block> snapshot = snapshotCell(column);
        BlockPos underfoot = playerPos.below();
        BlockState underfootState = MC.level.getBlockState(underfoot);
        if (task.cuboid.contains(underfoot)
                && !underfootState.isAir()
                && underfootState.getDestroySpeed(MC.level, underfoot) >= 0.0F
                && !MiningSafety.isProtected(underfootState.getBlock())
                && MC.level.getBlockEntity(underfoot) == null) {
            snapshot.put(underfoot.asLong(), underfootState.getBlock());
        }
        if (snapshot.isEmpty() || !snapshot.containsKey(playerPos.below().asLong())) return false;

        cancelNative();
        task.escapeSnapshot.clear();
        task.escapeSnapshot.putAll(snapshot);
        task.escapeClearing = false;
        task.escapeDescending = true;
        configureBreakOnlySettings();
        MiningSafety.armBreaking(task.escapeSnapshot);
        setStage(Stage.ESCAPING, blocker);
        watchdog.reset(stageSinceMs, MC.player.position(), snapshot.size());
        lastNativeActiveMs = stageSinceMs;
        task.escapeBreakTarget = null;
        sendEvent("Horizontal escape is blocked; directly clearing " + snapshot.size()
            + " exact snapshotted support block(s) without placement.");
        return true;
    }

    private void tickEscapeDescent(long now, LocalPlayer player) {
        if (now - lastSnapshotCheckMs >= 500L) {
            lastSnapshotCheckMs = now;
            pruneSnapshot(task.escapeSnapshot);
        }
        int remaining = task.escapeSnapshot.size();
        watchdog.observe(now, player.position(), remaining);
        if (remaining == 0 || player.blockPosition().getY() <= task.cuboid.y1) {
            resetDirectBreak();
            beginEscapeTraversal("Support descent completed");
            return;
        }
        BlockPos support = player.blockPosition().below();
        Block expected = task.escapeSnapshot.get(support.asLong());
        if (expected != null && MC.level.getBlockState(support).getBlock() == expected) {
            BlockState state = MC.level.getBlockState(support);
            if (!selectSupportBreakTool(state)) {
                resetDirectBreak();
                recover("No safe hotbar tool can clear the snapshotted support block.");
                return;
            }
            var controller = primaryBaritone().getPlayerContext().playerController();
            if (!support.equals(task.escapeBreakTarget)) {
                controller.resetBlockRemoving();
                task.escapeBreakTarget = support.immutable();
                controller.clickBlock(support, Direction.UP);
            } else {
                controller.onPlayerDamageBlock(support, Direction.UP);
            }
        }
        if (watchdog.idleFor(now) >= STUCK_TIMEOUT_MS) {
            resetDirectBreak();
            recover("Coordinate-gated support descent could not progress; " + remaining + " blocks remained.");
        }
    }

    private boolean selectSafeHotbarTool(BlockState state) {
        if (MC.player == null) return false;
        int slot = new ToolSet(MC.player).getBestSlot(state.getBlock(), false);
        ItemStack stack = MC.player.getInventory().getItem(slot);
        if (state.requiresCorrectToolForDrops() && !stack.isCorrectToolForDrops(state)) return false;
        if (stack.isDamageableItem()
                && stack.getMaxDamage() - stack.getDamageValue() <= task.minDurability) {
            return false;
        }
        MC.player.getInventory().setSelectedSlot(slot);
        primaryBaritone().getPlayerContext().playerController().syncHeldItem();
        return true;
    }

    private boolean selectSupportBreakTool(BlockState state) {
        if (selectSafeHotbarTool(state)) return true;
        if (MC.player == null) return false;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = MC.player.getInventory().getItem(slot);
            if (!stack.isEmpty() && stack.isDamageableItem()) continue;
            MC.player.getInventory().setSelectedSlot(slot);
            primaryBaritone().getPlayerContext().playerController().syncHeldItem();
            return true;
        }
        return false;
    }

    private void resetDirectBreak() {
        if (task != null) task.escapeBreakTarget = null;
        try {
            primaryBaritone().getPlayerContext().playerController().resetBlockRemoving();
        } catch (RuntimeException ignored) {
        }
    }

    private void pruneSnapshot() {
        pruneSnapshot(task == null ? null : task.cellSnapshot);
    }

    private void pruneSnapshot(Map<Long, Block> snapshot) {
        if (task == null || MC.level == null || snapshot == null || snapshot.isEmpty()) return;
        int mined = 0;
        int skipped = 0;
        var iterator = snapshot.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Long, Block> entry = iterator.next();
            BlockPos pos = BlockPos.of(entry.getKey());
            BlockState state = MC.level.getBlockState(pos);
            if (state.getBlock() != entry.getValue()) {
                iterator.remove();
                mined++;
            } else if (state.requiresCorrectToolForDrops() && !hasSafeCorrectTool(state)) {
                iterator.remove();
                skipped++;
            }
        }
        if (mined > 0 || skipped > 0) {
            task.minedBlocks += mined;
            task.cellPassMined += mined;
            task.skippedToolBlocks += skipped;
            MiningSafety.replaceSnapshot(snapshot);
        }
    }

    private boolean hasSafeCorrectTool(BlockState state) {
        if (MC.player == null) return false;
        for (int slot = 0; slot < MC.player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = MC.player.getInventory().getItem(slot);
            if (stack.isEmpty() || !stack.isCorrectToolForDrops(state)) continue;
            if (!stack.isDamageableItem()) return true;
            int durability = stack.getMaxDamage() - stack.getDamageValue();
            if (durability > task.minDurability) return true;
        }
        return false;
    }

    private void completeCurrentCell() {
        cancelNative();
        MiningSafety.disarmBreaking();
        task.cleaningSupports = false;
        task.descendingSupports = false;
        task.completedCells++;
        task.failedAttempts.remove(task.currentCell.toString());
        task.escapeAttempted.remove(task.currentCell.toString());
        task.escapeDescentAttempted.remove(task.currentCell.toString());
        sendEvent("Completed cell " + task.currentCell + " (" + task.completedCells + "/" + task.totalCells + ").");
        task.currentCell = null;
        beginNextCell();
    }

    private void recover(String reason) {
        if (task == null) return;
        cancelNative();
        MiningSafety.disarmBreaking();
        task.escapeSnapshot.clear();
        task.escapeDestination = null;
        task.escapeClearing = false;
        task.escapeDescending = false;
        blocker = reason;

        if (task.kind == TaskKind.GOTO) {
            int attempt = ++task.travelAttempts;
            if (attempt > MAX_CELL_ATTEMPTS) {
                stopTask("Blocked after " + MAX_CELL_ATTEMPTS + " native path retries: " + reason, true);
                return;
            }
            setStage(Stage.RECOVERING, reason + " Retry " + attempt + "/" + MAX_CELL_ATTEMPTS + ".");
            sendEvent(blocker);
            return;
        }

        String key = task.currentCell.toString();
        int attempt = task.failedAttempts.merge(key, 1, Integer::sum);
        if (attempt >= MAX_CELL_ATTEMPTS) {
            task.blockedCells.add(task.currentCell);
            sendEvent("Marked cell blocked after " + attempt + " safe retries: " + task.currentCell + ". Continuing other cells.");
            task.currentCell = null;
            beginNextCell();
            return;
        }

        task.cells.addLast(task.currentCell);
        sendEvent(reason + " Requeued cell for safe retry " + attempt + "/" + MAX_CELL_ATTEMPTS + ".");
        task.currentCell = null;
        setStage(Stage.RECOVERING, reason);
    }

    private void recoverTravel(String reason) {
        if (task == null || !task.travelToCell || task.currentCell == null) {
            recover(reason);
            return;
        }
        cancelNative();
        MiningSafety.disarmBreaking();
        blocker = reason;
        int attempt = ++task.travelAttempts;
        if (attempt > MAX_CELL_ATTEMPTS) {
            recover(reason);
            return;
        }
        setStage(Stage.RECOVERING, reason + " Route retry " + attempt + "/" + MAX_CELL_ATTEMPTS + ".");
        sendEvent(blocker);
    }

    private void retryCurrentWork() {
        if (task == null) return;
        if (task.kind == TaskKind.GOTO) beginTravel(task.destination, false);
        else if (task.currentCell != null) beginTravel(task.currentCell.center(), true);
        else beginNextCell();
    }

    private void finishMiningTask() {
        if (task.blockedCells.isEmpty()) {
            completeTask("Mining task completed: " + task.minedBlocks + " blocks across " + task.completedCells + " cells.");
            return;
        }
        String message = "Mining pass completed with " + task.minedBlocks + " blocks cleared; "
            + task.blockedCells.size() + " cell(s) remain blocked and were not mined unsafely; "
            + task.skippedToolBlocks + " block(s) required a safe tool.";
        completeTask(message);
    }

    private void completeTask(String message) {
        String id = task == null ? null : task.id;
        stopTask(null, false);
        JsonObject event = new JsonObject();
        event.addProperty("type", "complete");
        event.addProperty("taskId", id);
        event.addProperty("message", message);
        connection.send(event);
    }

    private void stopTask(String message, boolean report) {
        String id = task == null ? null : task.id;
        cancelNative();
        MiningSafety.endManagedTask();
        task = null;
        blocker = "";
        setStage(Stage.IDLE, "");
        if (report && message != null) {
            JsonObject event = new JsonObject();
            event.addProperty("type", "stopped");
            event.addProperty("taskId", id);
            event.addProperty("message", message);
            connection.send(event);
        }
    }

    private void configureTravelSettings() {
        BaritoneAPI.getSettings().allowBreak.value = false;
        BaritoneAPI.getSettings().allowPlace.value = false;
        BaritoneAPI.getSettings().allowParkour.value = false;
        BaritoneAPI.getSettings().allowParkourPlace.value = false;
        BaritoneAPI.getSettings().itemSaver.value = true;
        BaritoneAPI.getSettings().itemSaverThreshold.value = task == null ? 10 : task.minDurability;
        applyProtectedBlocks(new HashSet<>(MiningSafety.protectedBlocks()));
    }

    private void configureMiningSettings() {
        BaritoneAPI.getSettings().allowBreak.value = true;
        BaritoneAPI.getSettings().allowPlace.value = true;
        BaritoneAPI.getSettings().allowParkour.value = false;
        BaritoneAPI.getSettings().allowParkourPlace.value = false;
        BaritoneAPI.getSettings().itemSaver.value = true;
        BaritoneAPI.getSettings().itemSaverThreshold.value = task == null ? 10 : task.minDurability;
        applyProtectedBlocks(new HashSet<>(MiningSafety.protectedBlocks()));
    }

    private void configureBreakOnlySettings() {
        configureMiningSettings();
        BaritoneAPI.getSettings().allowPlace.value = false;
    }

    private void enforceStageSettings() {
        if (stage == Stage.MINING) {
            if (task != null && task.cleaningSupports) configureBreakOnlySettings();
            else configureMiningSettings();
        } else if (stage == Stage.ESCAPING && task != null && (task.escapeClearing || task.escapeDescending)) {
            configureBreakOnlySettings();
        }
        else configureTravelSettings();
    }

    private void applyProtectedBlocks(Set<Block> protectedBlocks) {
        BaritoneAPI.getSettings().blocksToDisallowBreaking.value = new ArrayList<>(protectedBlocks);
        BaritoneAPI.getSettings().buildIgnoreBlocks.value = new ArrayList<>(protectedBlocks);
    }

    private void cancelNative() {
        try {
            IBaritone baritone = primaryBaritone();
            baritone.getPlayerContext().playerController().resetBlockRemoving();
            baritone.getMineProcess().cancel();
            baritone.getBuilderProcess().onLostControl();
            baritone.getCustomGoalProcess().setGoal(null);
            baritone.getPathingBehavior().forceCancel();
        } catch (RuntimeException ignored) {
        }
        if (task != null) task.escapeBreakTarget = null;
    }

    private IBaritone primaryBaritone() {
        return BaritoneAPI.getProvider().getPrimaryBaritone();
    }

    private void setStage(Stage next, String reason) {
        Stage previous = stage;
        stage = next;
        stageSinceMs = System.currentTimeMillis();
        if (reason != null) blocker = reason;
        if (previous != next) {
            LOGGER.info("[HiveMiner] Stage {} -> {}{}", previous, next,
                blocker.isBlank() ? "" : ": " + blocker);
        }
    }

    private void sendStatus() {
        JsonObject status = new JsonObject();
        status.addProperty("type", "status");
        status.addProperty("stage", stage.name().toLowerCase(Locale.ROOT));
        status.addProperty("taskId", task == null ? null : task.id);
        status.addProperty("blocker", blocker);
        status.addProperty("deniedBreaks", MiningSafety.deniedBreakCount());
        BlockPos denied = MiningSafety.lastDeniedBreak();
        if (denied != null) status.add("lastDeniedBreak", positionJson(denied));

        LocalPlayer player = MC.player;
        status.addProperty("username", player == null ? env("TASK_BOT_ID", "task-bot") : player.getGameProfile().name());
        if (player != null) {
            status.add("position", positionJson(player.blockPosition()));
            status.addProperty("health", player.getHealth());
            status.addProperty("food", player.getFoodData().getFoodLevel());
            ItemStack hand = player.getMainHandItem();
            JsonObject item = new JsonObject();
            item.addProperty("item", hand.isEmpty() ? "empty" : hand.getItem().toString());
            if (hand.isDamageableItem()) item.addProperty("durabilityLeft", hand.getMaxDamage() - hand.getDamageValue());
            status.add("selectedItem", item);
        }
        if (task != null) {
            status.addProperty("kind", task.kind.name().toLowerCase(Locale.ROOT));
            status.addProperty("minedBlocks", task.minedBlocks);
            status.addProperty("completedCells", task.completedCells);
            status.addProperty("totalCells", task.totalCells);
            status.addProperty("blockedCells", task.blockedCells.size());
            status.addProperty("skippedToolBlocks", task.skippedToolBlocks);
            status.addProperty("cellRemaining", stage == Stage.ESCAPING ? task.escapeSnapshot.size() : task.cellSnapshot.size());
            if (task.cuboid != null) status.add("cuboid", task.cuboid.toJson());
            if (task.currentCell != null) status.add("currentCell", task.currentCell.toJson());
        }
        connection.send(status);
    }

    private void sendEvent(String message) {
        LOGGER.info("[HiveMiner] {}", message);
        JsonObject event = new JsonObject();
        event.addProperty("type", "event");
        event.addProperty("taskId", task == null ? null : task.id);
        event.addProperty("message", message);
        connection.send(event);
    }

    private void sendError(String message) {
        JsonObject event = new JsonObject();
        event.addProperty("type", "error");
        event.addProperty("taskId", task == null ? null : task.id);
        event.addProperty("error", message == null ? "Unknown client error" : message);
        connection.send(event);
    }

    private static JsonObject positionJson(BlockPos pos) {
        JsonObject json = new JsonObject();
        json.addProperty("x", pos.getX());
        json.addProperty("y", pos.getY());
        json.addProperty("z", pos.getZ());
        return json;
    }

    private static double horizontalDistance(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static String string(JsonObject object, String key, String fallback) {
        return object != null && object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : fallback;
    }

    private static int integer(JsonObject object, String key, int fallback) {
        return object != null && object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsInt() : fallback;
    }

    private static int requiredInt(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) throw new IllegalArgumentException(key + " is required");
        return object.get(key).getAsInt();
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static long envLong(String key, long fallback) {
        try {
            return Long.parseLong(env(key, Long.toString(fallback)));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static int envInt(String key, int fallback, int min, int max) {
        try {
            return Math.max(min, Math.min(max, Integer.parseInt(env(key, Integer.toString(fallback)))));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private enum Stage {
        IDLE,
        WAITING_WORLD,
        WAITING_RESPAWN,
        TRAVELLING,
        MINING,
        ESCAPING,
        RECOVERING
    }

    private enum TaskKind {
        GOTO,
        MINE_CUBOID
    }

    private static final class ActiveTask {
        private final String id;
        private final TaskKind kind;
        private final Cuboid cuboid;
        private final BlockPos destination;
        private final int minDurability;
        private final ArrayDeque<Cuboid> cells = new ArrayDeque<>();
        private final List<Cuboid> blockedCells = new ArrayList<>();
        private final Map<String, Integer> failedAttempts = new HashMap<>();
        private final Map<Long, Block> cellSnapshot = new HashMap<>();
        private final Map<Long, Block> escapeSnapshot = new HashMap<>();
        private final Set<String> escapeAttempted = new HashSet<>();
        private final Set<String> escapeDescentAttempted = new HashSet<>();
        private boolean cellsInitialized;
        private boolean travelToCell;
        private boolean escapeClearing;
        private boolean escapeDescending;
        private boolean cleaningSupports;
        private boolean descendingSupports;
        private Cuboid currentCell;
        private BlockPos travelDestination;
        private BlockPos escapeDestination;
        private BlockPos escapeBreakTarget;
        private int totalCells;
        private int completedCells;
        private int travelAttempts;
        private int cellInitialBlocks;
        private int cellPassMined;
        private long minedBlocks;
        private long skippedToolBlocks;

        private ActiveTask(String id, TaskKind kind, Cuboid cuboid, BlockPos destination, int minDurability) {
            this.id = id;
            this.kind = kind;
            this.cuboid = cuboid;
            this.destination = destination;
            this.minDurability = minDurability;
        }

        private static ActiveTask mining(String id, Cuboid cuboid, int minDurability) {
            return new ActiveTask(id, TaskKind.MINE_CUBOID, cuboid, null, minDurability);
        }

        private static ActiveTask travel(String id, BlockPos destination) {
            return new ActiveTask(id, TaskKind.GOTO, null, destination, 0);
        }
    }

    private static final class ControlConnection extends Thread {
        private static final int MAX_OUTBOUND_MESSAGES = 64;
        private final HiveTaskClient owner;
        private final BlockingQueue<String> outbound = new ArrayBlockingQueue<>(MAX_OUTBOUND_MESSAGES);
        private final AtomicReference<String> latestStatus = new AtomicReference<>();

        private ControlConnection(HiveTaskClient owner) {
            super("HiveMinerControlConnection");
            this.owner = owner;
            setDaemon(true);
        }

        @Override
        public void run() {
            String host = env("TASK_CONTROL_HOST", "127.0.0.1");
            int port = Integer.parseInt(env("TASK_CONTROL_PORT", "47391"));
            String token = env("TASK_CONTROL_TOKEN", "dev-task-token");
            String botId = env("TASK_BOT_ID", "task-bot");

            while (!isInterrupted()) {
                try (Socket socket = new Socket(host, port);
                     BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                     BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
                    socket.setTcpNoDelay(true);
                    JsonObject hello = new JsonObject();
                    hello.addProperty("type", "hello");
                    hello.addProperty("token", token);
                    hello.addProperty("botId", botId);
                    LocalPlayer player = MC.player;
                    hello.addProperty("username", player == null ? botId : player.getGameProfile().name());
                    writePayload(writer, GSON.toJson(hello));
                    LOGGER.info("[HiveMiner] Connected to controller as {}.", botId);

                    AtomicBoolean active = new AtomicBoolean(true);
                    Thread sender = new Thread(() -> pumpOutbound(writer, socket, active), "HiveMinerControlSender");
                    sender.setDaemon(true);
                    sender.start();
                    try {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            JsonObject message = GSON.fromJson(line, JsonObject.class);
                            if (message != null) owner.handleMessage(message);
                        }
                    } finally {
                        active.set(false);
                        sender.interrupt();
                    }
                } catch (Exception error) {
                    LOGGER.warn("[HiveMiner] Controller connection failed: {}", error.getMessage());
                    try {
                        Thread.sleep(5000L);
                    } catch (InterruptedException interrupted) {
                        interrupt();
                    }
                }
            }
        }

        private void send(JsonObject object) {
            String payload = GSON.toJson(object);
            if ("status".equals(string(object, "type", ""))) {
                latestStatus.set(payload);
                return;
            }
            if (outbound.offer(payload)) return;
            outbound.poll();
            if (!outbound.offer(payload)) LOGGER.warn("[HiveMiner] Dropped outbound control message because queue is full.");
        }

        private void pumpOutbound(BufferedWriter writer, Socket socket, AtomicBoolean active) {
            while (active.get()) {
                try {
                    String payload = outbound.poll(250L, TimeUnit.MILLISECONDS);
                    if (payload == null) payload = latestStatus.getAndSet(null);
                    if (payload != null) writePayload(writer, payload);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (IOException error) {
                    try {
                        socket.close();
                    } catch (IOException ignored) {
                    }
                    return;
                }
            }
        }

        private static void writePayload(BufferedWriter writer, String payload) throws IOException {
            writer.write(payload);
            writer.newLine();
            writer.flush();
        }
    }
}
