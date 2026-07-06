/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package baritone.behavior;

import baritone.Baritone;
import baritone.api.event.events.PacketEvent;
import baritone.api.event.events.TickEvent;
import baritone.api.event.events.type.EventState;
import baritone.api.utils.Helper;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.MovementHelper;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.GraphicsPreset;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.PrioritizeChunkUpdates;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BedItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.FireworkRocketItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.List;
import java.util.ArrayList;
import java.util.Optional;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TaskBotBehavior extends Behavior implements Helper {

    private static final Path COMMAND_FILE = commandFile();
    private static final BlockPos PICKAXE_TOOL_CHEST_POS = new BlockPos(-233, 63, 134);
    private static final BlockPos SHOVEL_TOOL_CHEST_POS = new BlockPos(-233, 63, 135);
    private static final int TOOL_DURABILITY_THRESHOLD = 10;
    private static final long NIGHT_START = 12520L;
    private static final long DAY_END = 12000L;
    private static final int ACTION_COOLDOWN_TICKS = 20;
    private static final int FULL_INVENTORY_CHECK_TICKS = 40;
    private static final long CLEARBOX_INACTIVITY_REMINDER_MS = 120_000L;
    private static final Pattern SET_SPAWN_COMMAND = Pattern.compile("^setspawn\\s+(-?\\d+)\\s+(-?\\d+)\\s+(-?\\d+)$");
    private static final Pattern CLEARBOX_COMMAND = Pattern.compile("^clearbox\\s+(-?\\d+)\\s+(-?\\d+)\\s+(-?\\d+)\\s+(-?\\d+)\\s+(-?\\d+)\\s+(-?\\d+)$");
    private static BlockPos allowedBreakMin;
    private static BlockPos allowedBreakMax;
    private static final Set<BlockPos> allowedBreakPositions = new HashSet<>();

    public static boolean isTaskBreakAllowed(BlockPos pos) {
        if (allowedBreakMin == null || allowedBreakMax == null || allowedBreakPositions.isEmpty()) {
            return false;
        }
        return allowedBreakPositions.contains(pos.immutable());
    }

    public static boolean hasTaskBreakSnapshot() {
        return allowedBreakMin != null && allowedBreakMax != null && !allowedBreakPositions.isEmpty();
    }

    public static boolean isSafeTravelBreak(BlockState state) {
        if (state == null || state.isAir()) {
            return true;
        }
        Block block = state.getBlock();
        return state.canBeReplaced()
                || block == Blocks.SHORT_GRASS
                || block == Blocks.TALL_GRASS
                || block == Blocks.FERN
                || block == Blocks.LARGE_FERN
                || block == Blocks.DEAD_BUSH
                || block == Blocks.VINE
                || block == Blocks.CAVE_VINES
                || block == Blocks.CAVE_VINES_PLANT
                || block == Blocks.WEEPING_VINES
                || block == Blocks.WEEPING_VINES_PLANT
                || block == Blocks.TWISTING_VINES
                || block == Blocks.TWISTING_VINES_PLANT;
    }

    private static void setAllowedBreakCuboid(BlockPos a, BlockPos b) {
        allowedBreakMin = new BlockPos(
                Math.min(a.getX(), b.getX()),
                Math.min(a.getY(), b.getY()),
                Math.min(a.getZ(), b.getZ())
        );
        allowedBreakMax = new BlockPos(
                Math.max(a.getX(), b.getX()),
                Math.max(a.getY(), b.getY()),
                Math.max(a.getZ(), b.getZ())
        );
        allowedBreakPositions.clear();
    }

    private static void clearAllowedBreakCuboid() {
        allowedBreakMin = null;
        allowedBreakMax = null;
        allowedBreakPositions.clear();
    }

    private static Path commandFile() {
        String explicit = System.getenv("TASK_NATIVE_COMMAND_FILE");
        if (explicit != null && !explicit.isBlank()) {
            return Path.of(explicit);
        }
        String botId = System.getenv("TASK_BOT_ID");
        if (botId != null && !botId.isBlank()) {
            String safeBotId = botId.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "-");
            return Path.of("/tmp/taskbot-native-" + safeBotId + "-command.txt");
        }

        String launchContext = String.join(" ",
                System.getProperty("user.dir", ""),
                System.getProperty("java.library.path", ""),
                System.getProperty("sun.java.command", "")
        ).toLowerCase(Locale.ROOT);
        if (launchContext.contains("baritone-26.2-native-azure") || launchContext.contains("prism-azure")) {
            return Path.of("/tmp/taskbot-native-azure-command.txt");
        }
        return Path.of("/tmp/taskbot-native-command.txt");
    }

    private enum ToolServiceMode {
        NONE,
        DEPOSIT_LOW,
        RESTOCK_PICKAXES,
        RESTOCK_SHOVELS,
        WAITING_FOR_RESTOCK
    }

    private boolean dontSleepThisNight;
    private int sleepCooldown;
    private int depositCooldown;
    private int dumpSlot;
    private int autoRespawnTicks;
    private int sleepLogCooldown;
    private int nightDebugCooldown;
    private int commandPollCooldown;
    private int toolServiceCooldown;
    private int durabilityLogCooldown;
    private int toolSwapCooldown;
    private int toolSwapLogCooldown;
    private int torchCooldown;
    private int bedPickupCooldown;
    private int toolChestCommandCooldown;
    private int vineEscapeCooldown;
    private int lastHandledTick = -1;
    private boolean equipAfterRestock;
    private BlockPos activeToolChestPos = PICKAXE_TOOL_CHEST_POS;
    private ToolServiceMode toolServiceMode = ToolServiceMode.NONE;
    private BlockPos managedChest;
    private BlockPos managedBed;
    private BlockPos spawnBedTarget;
    private Boolean previousAllowBreak;
    private Boolean previousAllowPlace;
    private BlockPos pendingSelPos1;
    private BlockPos pendingSelPos2;
    private BlockPos clearBoxMin;
    private BlockPos clearBoxMax;
    private BlockPos clearBoxTarget;
    private final Set<BlockPos> clearBoxSkippedTargets = new HashSet<>();
    private BlockPos clearBoxSkipOrigin;
    private BlockPos clearBoxLastActivityFeet;
    private BlockPos clearBoxLastGotoTarget;
    private BlockPos clearBoxBreakLock;
    private long clearBoxLastActivityAt;
    private int clearBoxGotoCooldown;
    private int clearBoxLogCooldown;
    private int clearBoxBlockedReports;
    private int clearBoxNoPathSkips;
    private long nativeClearAreaLastRefreshAt;
    private boolean nativeClearAreaRecovering;
    private boolean clearBoxBreakSnapshotReady;
    private BlockPos pendingNativeClearMin;
    private BlockPos pendingNativeClearMax;
    private BlockPos pendingNativeClearApproach;
    private boolean taskClientOptionsApplied;

    public TaskBotBehavior(Baritone baritone) {
        super(baritone);
        protectUtilityBlocks();
    }

    private void protectUtilityBlocks() {
        Baritone.settings().blockReachDistance.value = 2.75f;
        Baritone.settings().walkWhileBreaking.value = false;
        Baritone.settings().allowVines.value = false;
        Baritone.settings().allowDownward.value = true;
        Baritone.settings().maxFallHeightNoWater.value = 3;
        Baritone.settings().maxFallHeightBucket.value = 20;
        protectBlock(Blocks.CHEST);
        protectBlock(Blocks.TRAPPED_CHEST);
        protectBlock(Blocks.TORCH);
        protectBlock(Blocks.WALL_TORCH);
        protectBlock(Blocks.SOUL_TORCH);
        protectBlock(Blocks.SOUL_WALL_TORCH);
        protectLeaves();
    }

    private void protectLeaves() {
        for (Block block : BuiltInRegistries.BLOCK) {
            if (block instanceof LeavesBlock) {
                protectBlock(block);
            }
        }
    }

    private void protectBlock(net.minecraft.world.level.block.Block block) {
        addIfMissing(Baritone.settings().blocksToDisallowBreaking.value, block);
        addIfMissing(Baritone.settings().buildIgnoreBlocks.value, block);
    }

    private void applyTaskClientOptions() {
        if (taskClientOptionsApplied) {
            return;
        }
        Options options = ctx.minecraft().options;
        setOption(options.renderDistance(), 2);
        setOption(options.simulationDistance(), 2);
        setOption(options.entityDistanceScaling(), 0.25D);
        setOption(options.framerateLimit(), 30);
        setOption(options.mipmapLevels(), 0);
        setOption(options.biomeBlendRadius(), 0);
        setOption(options.graphicsPreset(), GraphicsPreset.FAST);
        setOption(options.cloudStatus(), CloudStatus.OFF);
        setOption(options.particles(), ParticleStatus.MINIMAL);
        setOption(options.entityShadows(), false);
        setOption(options.bobView(), false);
        setOption(options.ambientOcclusion(), false);
        setOption(options.autoJump(), false);
        setOption(options.fullscreen(), false);
        setOption(options.showAutosaveIndicator(), false);
        setOption(options.prioritizeChunkUpdates(), PrioritizeChunkUpdates.NONE);
        for (SoundSource source : SoundSource.values()) {
            setOption(options.getSoundSourceOptionInstance(source), 0.0D);
        }
        options.save();
        taskClientOptionsApplied = true;
        logDirect("TaskBot: forced low-render client options for headless mining.");
    }

    private <T> void setOption(OptionInstance<T> option, T value) {
        if (!value.equals(option.get())) {
            option.set(value);
        }
    }

    private static <T> void addIfMissing(java.util.List<T> list, T value) {
        if (!list.contains(value)) {
            list.add(value);
        }
    }

    @Override
    public void onTick(TickEvent event) {
        if (event.getType() != TickEvent.Type.IN || event.getCount() == lastHandledTick) {
            return;
        }
        lastHandledTick = event.getCount();
        if (autoRespawnTicks > 0) {
            autoRespawnTicks--;
            if (autoRespawnTicks == 0) {
                LocalPlayer player = ctx.player();
                if (player == null) {
                    autoRespawnTicks = 5;
                    return;
                }
                player.respawn();
                logDirect("TaskBot: respawned after death.");
            }
            return;
        }
        if (ctx.player() == null || ctx.world() == null || ctx.minecraft().gameMode == null) {
            return;
        }
        applyTaskClientOptions();

        if (sleepCooldown > 0) {
            sleepCooldown--;
        }
        if (depositCooldown > 0) {
            depositCooldown--;
        }
        if (sleepLogCooldown > 0) {
            sleepLogCooldown--;
        }
        if (nightDebugCooldown > 0) {
            nightDebugCooldown--;
        }
        if (commandPollCooldown > 0) {
            commandPollCooldown--;
        }
        if (toolServiceCooldown > 0) {
            toolServiceCooldown--;
        }
        if (durabilityLogCooldown > 0) {
            durabilityLogCooldown--;
        }
        if (toolSwapCooldown > 0) {
            toolSwapCooldown--;
        }
        if (toolSwapLogCooldown > 0) {
            toolSwapLogCooldown--;
        }
        if (torchCooldown > 0) {
            torchCooldown--;
        }
        if (bedPickupCooldown > 0) {
            bedPickupCooldown--;
        }
        if (toolChestCommandCooldown > 0) {
            toolChestCommandCooldown--;
        }
        if (vineEscapeCooldown > 0) {
            vineEscapeCooldown--;
        }
        if (clearBoxGotoCooldown > 0) {
            clearBoxGotoCooldown--;
        }
        if (clearBoxLogCooldown > 0) {
            clearBoxLogCooldown--;
        }

        pollCommandFile();
        monitorPendingNativeClearArea();
        monitorNativeClearAreaCompletion();
        enforceIdleSafety();
        if (handleSetSpawn()) {
            return;
        }
        if (handleToolService()) {
            return;
        }
        if (handleLowDurability()) {
            return;
        }
        ensureAppropriateMiningToolReady();
        if (handleVineEscape()) {
            return;
        }
        long dayTime = Math.floorMod(ctx.world().getOverworldClockTime(), 24000L);
        int skyDarken = ctx.world().getSkyDarken();
        boolean sleepTime = dayTime >= NIGHT_START;
        if (dayTime < DAY_END || playerIsSleeping()) {
            dontSleepThisNight = false;
        }
        if (nightDebugCooldown == 0) {
            nightDebugCooldown = ACTION_COOLDOWN_TICKS * 10;
            BlockPos eyePos = ctx.playerFeet().above();
            int rawLight = ctx.world().getLightEngine().getRawBrightness(eyePos, skyDarken);
            int skyLight = ctx.world().getLightEngine().getLayerListener(LightLayer.SKY).getLightValue(eyePos);
            int blockLight = ctx.world().getLightEngine().getLayerListener(LightLayer.BLOCK).getLightValue(eyePos);
            logDirect("TaskBot: sleep check clock=" + dayTime + " skyDarken=" + skyDarken + " rawLight=" + rawLight + " skyLight=" + skyLight + " blockLight=" + blockLight + " sleepTime=" + sleepTime + ".");
        }

        if (handleBedPickup(sleepTime)) {
            return;
        }
        if (handleDeposit()) {
            return;
        }
        if (handleTorchPlacement()) {
            return;
        }
        if (handleClearBox()) {
            return;
        }
        handleSleep(sleepTime, dayTime, skyDarken);
    }

    private void enforceIdleSafety() {
        if (clearBoxMin == null && !hasTaskBreakSnapshot() && spawnBedTarget == null && toolServiceMode == ToolServiceMode.NONE) {
            Baritone.settings().allowBreak.value = false;
            Baritone.settings().allowPlace.value = false;
        }
    }

    private void monitorNativeClearAreaCompletion() {
        if (clearBoxMin != null || !hasTaskBreakSnapshot()) {
            return;
        }
        if (!hasRemainingAllowedBreakBlocks()) {
            logDirect("TaskBot: native Baritone cleararea complete " + allowedBreakMin + " -> " + allowedBreakMax + ".");
            forceSafeIdle();
            nativeClearAreaRecovering = false;
            nativeClearAreaLastRefreshAt = 0L;
            return;
        }
        BlockPos feet = ctx.playerFeet();
        if (nativeClearAreaRecovering) {
            if (isNearAllowedCuboid(feet)) {
                nativeClearAreaRecovering = false;
                refreshNativeClearArea("recovered to work area");
                return;
            }
            if (handleNativeClearAreaDirectBreak()) {
                nativeClearAreaLastRefreshAt = System.currentTimeMillis();
                return;
            }
            if (System.currentTimeMillis() - nativeClearAreaLastRefreshAt >= 20_000L) {
                BlockPos approach = allowedCuboidApproachTarget(feet);
                baritone.getCommandManager().execute("goto " + approach.getX() + " " + approach.getY() + " " + approach.getZ());
                nativeClearAreaLastRefreshAt = System.currentTimeMillis();
                logDirect("TaskBot: native Baritone cleararea recovery retry; going to " + approach + ".");
            }
            return;
        }
        if (feet.getY() < allowedBreakMin.getY() - 8) {
            nativeClearAreaRecovering = true;
            addRecoveryColumn(feet);
            Baritone.settings().allowBreak.value = true;
            Baritone.settings().allowPlace.value = false;
            baritone.getPathingBehavior().cancelEverything();
            BlockPos approach = allowedCuboidApproachTarget(feet);
            baritone.getCommandManager().execute("goto " + approach.getX() + " " + approach.getY() + " " + approach.getZ());
            nativeClearAreaLastRefreshAt = System.currentTimeMillis();
            logDirect("TaskBot: native Baritone cleararea recovery; bot is below work floor at " + feet + ", going to " + approach + ".");
            return;
        }
        boolean noSelectedBlock = ctx.getSelectedBlock().isEmpty();
        boolean noPath = !baritone.getPathingBehavior().isPathing();
        if (noSelectedBlock && noPath && handleNativeClearAreaDirectBreak()) {
            nativeClearAreaLastRefreshAt = System.currentTimeMillis();
            return;
        }
        if (noSelectedBlock && noPath && System.currentTimeMillis() - nativeClearAreaLastRefreshAt >= 20_000L) {
            refreshNativeClearArea("Baritone idle with blocks remaining");
        }
    }

    private void addRecoveryColumn(BlockPos feet) {
        if (allowedBreakMin == null || allowedBreakMax == null) {
            return;
        }
        int x = Math.max(allowedBreakMin.getX(), Math.min(allowedBreakMax.getX(), feet.getX()));
        int z = Math.max(allowedBreakMin.getZ(), Math.min(allowedBreakMax.getZ(), feet.getZ()));
        int minY = Math.min(feet.getY(), allowedBreakMin.getY());
        for (int y = minY; y < allowedBreakMin.getY(); y++) {
            BlockPos pos = new BlockPos(x, y, z);
            BlockState state = ctx.world().getBlockState(pos);
            if (!state.isAir() && state.getDestroySpeed(ctx.world(), pos) >= 0) {
                allowedBreakPositions.add(pos.immutable());
            }
        }
    }

    private boolean handleNativeClearAreaDirectBreak() {
        BlockPos target = findReachableAllowedBreakBlock();
        if (target == null) {
            return false;
        }
        Optional<Rotation> reachable = RotationUtils.reachable(ctx, target, ctx.playerController().getBlockReachDistance());
        if (reachable.isEmpty()) {
            return false;
        }
        Baritone.settings().allowBreak.value = true;
        Baritone.settings().allowPlace.value = false;
        MovementHelper.switchToBestToolFor(ctx, ctx.world().getBlockState(target));
        Rotation rotation = reachable.get();
        baritone.getLookBehavior().updateTarget(rotation, true);
        if (ctx.isLookingAt(target) || ctx.playerRotations().isReallyCloseTo(rotation)) {
            baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
        }
        return true;
    }

    private boolean retargetNearestAllowedBlock() {
        BlockPos target = findNearestAllowedBreakStand();
        if (target == null) {
            return false;
        }
        Baritone.settings().allowBreak.value = true;
        Baritone.settings().allowPlace.value = false;
        baritone.getCommandManager().execute("goto " + target.getX() + " " + target.getY() + " " + target.getZ());
        logDirect("TaskBot: retargeting nearest approved stand position " + target + ".");
        return true;
    }

    private BlockPos findNearestAllowedBreakStand() {
        BlockPos feet = ctx.playerFeet();
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (BlockPos pos : allowedBreakPositions) {
            if (!isBreakableAllowedPosition(pos)) {
                continue;
            }
            for (Direction direction : Direction.values()) {
                BlockPos stand = pos.relative(direction);
                if (!canStandAt(stand)) {
                    continue;
                }
                double score = feet.distSqr(stand) + stand.distSqr(pos) * 0.25D;
                if (score < bestScore) {
                    bestScore = score;
                    best = stand.immutable();
                }
            }
        }
        return best;
    }

    private BlockPos findReachableAllowedBreakBlock() {
        BlockPos feet = ctx.playerFeet();
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (BlockPos pos : allowedBreakPositions) {
            if (!isBreakableAllowedPosition(pos)) {
                continue;
            }
            if (RotationUtils.reachable(ctx, pos, ctx.playerController().getBlockReachDistance()).isEmpty()) {
                continue;
            }
            double score = feet.distSqr(pos);
            if (score < bestScore) {
                bestScore = score;
                best = pos.immutable();
            }
        }
        return best;
    }

    private boolean isBreakableAllowedPosition(BlockPos pos) {
        if (!allowedBreakPositions.contains(pos.immutable())) {
            return false;
        }
        BlockState state = ctx.world().getBlockState(pos);
        if (state.isAir() || state.canBeReplaced() || state.getDestroySpeed(ctx.world(), pos) < 0) {
            return false;
        }
        Block block = state.getBlock();
        return !(block instanceof ChestBlock)
                && !(block instanceof BedBlock)
                && block != Blocks.TORCH
                && block != Blocks.WALL_TORCH
                && block != Blocks.SOUL_TORCH
                && block != Blocks.SOUL_WALL_TORCH;
    }

    private boolean isNearAllowedCuboid(BlockPos feet) {
        return feet.getX() >= allowedBreakMin.getX() - 4 && feet.getX() <= allowedBreakMax.getX() + 4
                && feet.getZ() >= allowedBreakMin.getZ() - 4 && feet.getZ() <= allowedBreakMax.getZ() + 4
                && feet.getY() >= allowedBreakMin.getY() - 1 && feet.getY() <= allowedBreakMax.getY() + 8;
    }

    private boolean isNearCuboid(BlockPos feet, BlockPos min, BlockPos max) {
        return feet.getX() >= min.getX() - 4 && feet.getX() <= max.getX() + 4
                && feet.getZ() >= min.getZ() - 4 && feet.getZ() <= max.getZ() + 4
                && feet.getY() >= min.getY() - 1 && feet.getY() <= max.getY() + 8;
    }

    private BlockPos allowedCuboidApproachTarget(BlockPos feet) {
        int centerX = (allowedBreakMin.getX() + allowedBreakMax.getX()) / 2;
        int centerZ = (allowedBreakMin.getZ() + allowedBreakMax.getZ()) / 2;
        return new BlockPos(centerX, allowedBreakMin.getY(), centerZ);
    }

    private BlockPos findNativeClearApproach(BlockPos min, BlockPos max) {
        BlockPos feet = ctx.playerFeet();
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (int y = max.getY() + 4; y >= min.getY() - 1; y--) {
            for (int x = min.getX() - 1; x <= max.getX() + 1; x++) {
                for (int z = min.getZ() - 1; z <= max.getZ() + 1; z++) {
                    boolean perimeter = x == min.getX() - 1 || x == max.getX() + 1 || z == min.getZ() - 1 || z == max.getZ() + 1;
                    if (!perimeter) {
                        continue;
                    }
                    BlockPos stand = new BlockPos(x, y, z);
                    if (!canStandAt(stand)) {
                        continue;
                    }
                    double score = feet.distSqr(stand);
                    if (score < bestScore) {
                        bestScore = score;
                        best = stand.immutable();
                    }
                }
            }
        }
        if (best != null) {
            return best;
        }
        return new BlockPos((min.getX() + max.getX()) / 2, max.getY() + 1, (min.getZ() + max.getZ()) / 2);
    }

    private void monitorPendingNativeClearArea() {
        if (pendingNativeClearMin == null || pendingNativeClearMax == null) {
            return;
        }
        BlockPos feet = ctx.playerFeet();
        if (isCloseEnoughToStartNativeClearArea(feet, pendingNativeClearMin, pendingNativeClearMax)) {
            BlockPos min = pendingNativeClearMin;
            BlockPos max = pendingNativeClearMax;
            pendingNativeClearMin = null;
            pendingNativeClearMax = null;
            pendingNativeClearApproach = null;
            startNativeClearArea(min, max);
            return;
        }
        if (pendingNativeClearApproach == null || !canStandAt(pendingNativeClearApproach)) {
            pendingNativeClearApproach = findNativeClearApproach(pendingNativeClearMin, pendingNativeClearMax);
        }
        boolean noPath = !baritone.getPathingBehavior().isPathing();
        if (noPath && System.currentTimeMillis() - nativeClearAreaLastRefreshAt >= 20_000L) {
            beginNonBreakingTravel();
            baritone.getCommandManager().execute("goto " + pendingNativeClearApproach.getX() + " " + pendingNativeClearApproach.getY() + " " + pendingNativeClearApproach.getZ());
            nativeClearAreaLastRefreshAt = System.currentTimeMillis();
            logDirect("TaskBot: approaching native cleararea " + pendingNativeClearMin + " -> " + pendingNativeClearMax + " via " + pendingNativeClearApproach + ".");
        }
    }

    private boolean isCloseEnoughToStartNativeClearArea(BlockPos feet, BlockPos min, BlockPos max) {
        return feet.getX() >= min.getX() - 12 && feet.getX() <= max.getX() + 12
                && feet.getZ() >= min.getZ() - 12 && feet.getZ() <= max.getZ() + 12
                && feet.getY() >= min.getY() - 8 && feet.getY() <= max.getY() + 16;
    }

    private void refreshNativeClearArea(String reason) {
        if (allowedBreakMin == null || allowedBreakMax == null || allowedBreakPositions.isEmpty()) {
            return;
        }
        Baritone.settings().allowBreak.value = true;
        Baritone.settings().allowPlace.value = false;
        baritone.getPathingBehavior().cancelEverything();
        baritone.getCommandManager().execute("stop");
        baritone.getCommandManager().execute("sel clear");
        baritone.getCommandManager().execute("sel pos1 " + allowedBreakMin.getX() + " " + allowedBreakMin.getY() + " " + allowedBreakMin.getZ());
        baritone.getCommandManager().execute("sel pos2 " + allowedBreakMax.getX() + " " + allowedBreakMax.getY() + " " + allowedBreakMax.getZ());
        baritone.getCommandManager().execute("sel cleararea");
        nativeClearAreaLastRefreshAt = System.currentTimeMillis();
        logDirect("TaskBot: refreshed native Baritone cleararea (" + reason + ") " + allowedBreakMin + " -> " + allowedBreakMax + ".");
    }

    private void pollCommandFile() {
        if (commandPollCooldown > 0) {
            return;
        }
        commandPollCooldown = 10;
        if (!Files.isRegularFile(COMMAND_FILE)) {
            return;
        }
        try {
            String commands = Files.readString(COMMAND_FILE).trim().toLowerCase(Locale.ROOT);
            Files.deleteIfExists(COMMAND_FILE);
            for (String rawCommand : commands.split("\\R+")) {
                String command = rawCommand.trim();
                if (command.isEmpty()) {
                    continue;
                }
                if (command.startsWith("baritone:")) {
                    String nativeCommand = command.substring("baritone:".length()).trim();
                    if (toolServiceMode != ToolServiceMode.NONE && !isToolServiceOverrideCommand(nativeCommand)) {
                        logDirect("TaskBot: refused Baritone command `" + nativeCommand + "` because tool service is active.");
                        continue;
                    }
                    runNativeBaritoneCommand(nativeCommand);
                    continue;
                }
                if (command.startsWith("slash ")) {
                    runSlashCommand(command.substring("slash ".length()).trim());
                    continue;
                }
                switch (command) {
                    case "durability" -> logDurabilityReport();
                    case "status" -> logTaskStatus();
                    case "dropcobblestone" -> dropCobblestone();
                    case "toolrestock" -> {
                        toolServiceMode = ToolServiceMode.RESTOCK_PICKAXES;
                        activeToolChestPos = PICKAXE_TOOL_CHEST_POS;
                        goToToolChest("restock requested");
                    }
                    default -> {
                        Matcher clearBox = CLEARBOX_COMMAND.matcher(command);
                        if (clearBox.matches()) {
                            startClearBox(
                                    new BlockPos(Integer.parseInt(clearBox.group(1)), Integer.parseInt(clearBox.group(2)), Integer.parseInt(clearBox.group(3))),
                                    new BlockPos(Integer.parseInt(clearBox.group(4)), Integer.parseInt(clearBox.group(5)), Integer.parseInt(clearBox.group(6)))
                            );
                            continue;
                        }
                        Matcher setSpawn = SET_SPAWN_COMMAND.matcher(command);
                        if (setSpawn.matches()) {
                            spawnBedTarget = new BlockPos(
                                    Integer.parseInt(setSpawn.group(1)),
                                    Integer.parseInt(setSpawn.group(2)),
                                    Integer.parseInt(setSpawn.group(3))
                            );
                            beginNonBreakingTravel();
                            baritone.getPathingBehavior().cancelEverything();
                            baritone.getCommandManager().execute("stop");
                            baritone.getCommandManager().execute("goto " + spawnBedTarget.getX() + " " + spawnBedTarget.getY() + " " + spawnBedTarget.getZ());
                            logDirect("TaskBot: going to set spawn at bed " + spawnBedTarget + ".");
                        } else {
                            logDirect("TaskBot: ignored unknown native command `" + command + "`.");
                        }
                    }
                }
            }
        } catch (Exception error) {
            logDirect("TaskBot: failed reading native command file: " + error.getMessage());
        }
    }

    private void runSlashCommand(String command) {
        command = command.trim();
        if (command.startsWith("/")) {
            command = command.substring(1).trim();
        }
        if (!command.matches("(?i)^(kill|spawn|home|back)$")) {
            logDirect("TaskBot: refused unsafe slash command `/" + command + "`.");
            return;
        }
        ctx.player().connection.sendCommand(command);
        logDirect("TaskBot: sent slash command `/" + command + "`.");
    }

    private String normalizeNativeCommand(String command) {
        command = command.trim();
        if (command.startsWith("#")) {
            command = command.substring(1).trim();
        }
        return command;
    }

    private void runNativeBaritoneCommand(String command) {
        command = normalizeNativeCommand(command);
        if (command.matches("(?i)^stop$")) {
            toolServiceMode = ToolServiceMode.NONE;
            spawnBedTarget = null;
            toolChestCommandCooldown = 0;
            toolServiceCooldown = 0;
            stopClearBox();
            forceSafeIdle();
        } else if (command.matches("(?i)^(goto|goal)\s+.*") || command.matches("(?i)^elytra.*")) {
            beginNonBreakingTravel();
        } else if (command.matches("(?i)^sel clear$")) {
            pendingSelPos1 = null;
            pendingSelPos2 = null;
            clearAllowedBreakCuboid();
        } else if (recordSelectionPosition(command)) {
            // Selection state recorded for the cleararea safety gate.
        } else if (command.matches("(?i)^sel cleararea$")) {
            if (pendingSelPos1 != null && pendingSelPos2 != null) {
                setAllowedBreakCuboid(pendingSelPos1, pendingSelPos2);
                beginBreakingTask();
                logDirect("TaskBot: cleararea breaking enabled for selected cuboid " + allowedBreakMin + " -> " + allowedBreakMax + "; allowBreak=" + Baritone.settings().allowBreak.value + " allowPlace=" + Baritone.settings().allowPlace.value + ".");
            } else {
                logDirect("TaskBot: refused cleararea without both selection points recorded.");
                return;
            }
        } else if (command.matches("(?i)^mine .+")) {
            if (allowedBreakMin == null || allowedBreakMax == null) {
                logDirect("TaskBot: refused mine command without a designated break cuboid.");
                return;
            }
            beginBreakingTask();
        }
        if (!isSafeBaritoneCommand(command)) {
            logDirect("TaskBot: refused unsafe Baritone command `" + command + "`.");
            return;
        }
        boolean handled = baritone.getCommandManager().execute(command);
        logDirect("TaskBot: ran Baritone command `" + command + "` handled=" + handled + ".");
    }

    private boolean recordSelectionPosition(String command) {
        Matcher matcher = Pattern.compile("(?i)^sel pos([12]) (-?\\d+) (-?\\d+) (-?\\d+)$").matcher(command);
        if (!matcher.matches()) {
            return false;
        }
        BlockPos pos = new BlockPos(
                Integer.parseInt(matcher.group(2)),
                Integer.parseInt(matcher.group(3)),
                Integer.parseInt(matcher.group(4))
        );
        if ("1".equals(matcher.group(1))) {
            pendingSelPos1 = pos;
        } else {
            pendingSelPos2 = pos;
        }
        return true;
    }

    private boolean isSafeBaritoneCommand(String command) {
        return command.matches("(?i)^(sel( clear| pos[12] -?\\d+ -?\\d+ -?\\d+| cleararea)?|goto -?\\d+ -?\\d+ -?\\d+|goal -?\\d+ -?\\d+ -?\\d+|mine [a-z0-9:_./-]+|elytra( supported| reset| repack)?|set (elytra(termsaccepted|autojump|conservefireworks) (true|false)|elytra(minimumdurability|minfireworksbeforelanding) \\d+|buildonlyselection (true|false)|buildinlayers (true|false)|breakfromabove (true|false)|layerheight \\d+)|stop|pause|resume)$");
    }

    private boolean isToolServiceOverrideCommand(String command) {
        String normalized = command.startsWith("#") ? command.substring(1).trim() : command;
        return normalized.matches("(?i)^(stop|pause|resume)$");
    }

    private void dropCobblestone() {
        if (!(ctx.player().containerMenu instanceof InventoryMenu)) {
            ctx.player().closeContainer();
            toolServiceCooldown = ACTION_COOLDOWN_TICKS;
            return;
        }
        int dropped = 0;
        NonNullList<ItemStack> inv = ctx.player().getInventory().getNonEquipmentItems();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.get(i);
            if (isDropFiller(stack.getItem())) {
                int slotId = i < 9 ? i + 36 : i;
                ctx.playerController().windowClick(ctx.player().inventoryMenu.containerId, slotId, 1, ContainerInput.THROW, ctx.player());
                dropped += stack.getCount();
            }
        }
        logDirect("TaskBot: dropped " + dropped + " filler terrain item(s).");
    }

    private boolean handleSetSpawn() {
        if (spawnBedTarget == null) {
            return false;
        }
        if (toolServiceCooldown > 0) {
            return true;
        }
        if (ctx.playerFeet().distSqr(spawnBedTarget) > 16) {
            baritone.getCommandManager().execute("goto " + spawnBedTarget.getX() + " " + spawnBedTarget.getY() + " " + spawnBedTarget.getZ());
            toolServiceCooldown = ACTION_COOLDOWN_TICKS * 2;
            return true;
        }
        BlockPos bed = ctx.world().getBlockState(spawnBedTarget).getBlock() instanceof BedBlock ? spawnBedTarget : findNearbyBed();
        if (bed == null) {
            logDirect("TaskBot: cannot set spawn; no bed found at/near " + spawnBedTarget + ".");
            spawnBedTarget = null;
            restoreBreakingTravel();
            toolServiceCooldown = ACTION_COOLDOWN_TICKS;
            return true;
        }
        useBlock(bed, bestBedHand());
        managedBed = bed.immutable();
        logDirect("TaskBot: used bed to set spawn at " + bed + ".");
        spawnBedTarget = null;
        restoreBreakingTravel();
        toolServiceCooldown = ACTION_COOLDOWN_TICKS * 2;
        return true;
    }

    private void beginNonBreakingTravel() {
        if (previousAllowBreak == null) {
            previousAllowBreak = Baritone.settings().allowBreak.value;
            previousAllowPlace = Baritone.settings().allowPlace.value;
        }
        // Once a snapshot exists, pathing may break only the exact pre-approved blocks.
        // Movement/CalculationContext still hard-block every other solid block.
        Baritone.settings().allowBreak.value = clearBoxBreakSnapshotReady;
        Baritone.settings().allowPlace.value = false;
    }

    private void restoreBreakingTravel() {
        if (previousAllowBreak != null) {
            Baritone.settings().allowBreak.value = previousAllowBreak;
            Baritone.settings().allowPlace.value = previousAllowPlace;
            previousAllowBreak = null;
            previousAllowPlace = null;
        }
    }

    private void beginBreakingTask() {
        previousAllowBreak = null;
        previousAllowPlace = null;
        Baritone.settings().allowBreak.value = true;
        Baritone.settings().allowPlace.value = true;
    }

    private void forceSafeIdle() {
        previousAllowBreak = null;
        previousAllowPlace = null;
        nativeClearAreaRecovering = false;
        nativeClearAreaLastRefreshAt = 0L;
        clearAllowedBreakCuboid();
        Baritone.settings().allowBreak.value = false;
        Baritone.settings().allowPlace.value = false;
        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
        baritone.getPathingBehavior().cancelEverything();
    }

    private boolean isDropFiller(Item item) {
        return item == Items.COBBLESTONE
                || item == Items.COBBLED_DEEPSLATE
                || item == Items.STONE
                || item == Items.DIRT
                || item == Items.COARSE_DIRT
                || item == Items.ROOTED_DIRT
                || item == Items.GRAVEL
                || item == Items.GRANITE
                || item == Items.DIORITE
                || item == Items.ANDESITE;
    }

    private boolean handleVineEscape() {
        if (vineEscapeCooldown > 0 || ctx.player().isPassenger() || ctx.player().isFallFlying() || playerIsSleeping()) {
            return false;
        }
        BlockPos vine = findBlockingVine();
        if (vine == null) {
            return false;
        }
        Optional<Rotation> reachable = RotationUtils.reachable(ctx, vine, ctx.playerController().getBlockReachDistance());
        if (reachable.isEmpty()) {
            baritone.getCommandManager().execute("goto " + vine.getX() + " " + vine.getY() + " " + vine.getZ());
            vineEscapeCooldown = 10;
            return true;
        }
        baritone.getPathingBehavior().cancelEverything();
        Rotation rotation = reachable.get();
        baritone.getLookBehavior().updateTarget(rotation, true);
        if (ctx.isLookingAt(vine) || ctx.playerRotations().isReallyCloseTo(rotation)) {
            baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
        }
        vineEscapeCooldown = 2;
        return true;
    }

    private BlockPos findBlockingVine() {
        BlockPos feet = ctx.playerFeet();
        for (int dy = 0; dy <= 2; dy++) {
            BlockPos pos = feet.above(dy);
            if (isVineBlock(ctx.world().getBlockState(pos))) {
                return pos.immutable();
            }
        }
        BlockPos selected = ctx.getSelectedBlock().orElse(null);
        if (selected != null && selected.distSqr(feet) <= 4 && isVineBlock(ctx.world().getBlockState(selected))) {
            return selected.immutable();
        }
        return null;
    }

    private boolean isVineBlock(BlockState state) {
        return state.is(Blocks.VINE)
                || state.is(Blocks.CAVE_VINES)
                || state.is(Blocks.CAVE_VINES_PLANT)
                || state.is(Blocks.WEEPING_VINES)
                || state.is(Blocks.WEEPING_VINES_PLANT)
                || state.is(Blocks.TWISTING_VINES)
                || state.is(Blocks.TWISTING_VINES_PLANT);
    }

    private boolean handleLowDurability() {
        ItemStack low = findLowDurabilityStack();
        if (low.isEmpty()) {
            return false;
        }
        if (isLowDurability(ctx.player().getMainHandItem()) && switchAwayFromLowMainHand()) {
            return false;
        }
        if (toolServiceMode == ToolServiceMode.NONE) {
            toolServiceMode = ToolServiceMode.DEPOSIT_LOW;
            goToToolChest("low durability " + itemLabel(low));
        }
        return true;
    }

    private boolean switchAwayFromLowMainHand() {
        if (stowLowSelectedHotbarTool()) {
            return true;
        }
        NonNullList<ItemStack> inv = ctx.player().getInventory().getNonEquipmentItems();
        for (int i = 0; i < Math.min(9, inv.size()); i++) {
            if (inv.get(i).isEmpty()) {
                ctx.player().getInventory().setSelectedSlot(i);
                logDirect("TaskBot: switched off low-durability selected tool to empty hand.");
                return true;
            }
        }
        for (int i = 0; i < Math.min(9, inv.size()); i++) {
            ItemStack stack = inv.get(i);
            if (!stack.isEmpty() && !isLowDurability(stack) && !isToolOrArmor(stack) && !isChestStack(stack) && !(stack.getItem() instanceof BedItem) && !(stack.getItem() instanceof FireworkRocketItem)) {
                ctx.player().getInventory().setSelectedSlot(i);
                logDirect("TaskBot: switched off low-durability selected tool to " + stack.getHoverName().getString() + ".");
                return true;
            }
        }
        return false;
    }

    private boolean stowLowSelectedHotbarTool() {
        if (!(ctx.player().containerMenu instanceof InventoryMenu)) {
            return false;
        }
        int selectedSlot = ctx.player().getInventory().getSelectedSlot();
        NonNullList<ItemStack> inv = ctx.player().getInventory().getNonEquipmentItems();
        if (selectedSlot < 0 || selectedSlot >= Math.min(9, inv.size()) || !isLowDurability(inv.get(selectedSlot))) {
            return false;
        }
        for (int i = 9; i < inv.size(); i++) {
            if (inv.get(i).isEmpty()) {
                ctx.playerController().windowClick(ctx.player().inventoryMenu.containerId, i, selectedSlot, ContainerInput.SWAP, ctx.player());
                logDirect("TaskBot: moved low-durability selected tool out of hotbar.");
                return true;
            }
        }
        return false;
    }

    private void ensureAppropriateMiningToolReady() {
        if (toolSwapCooldown > 0 || !(ctx.player().containerMenu instanceof InventoryMenu)) {
            return;
        }
        BlockPos target = ctx.getSelectedBlock().orElse(null);
        if (target == null) {
            return;
        }
        BlockState state = ctx.world().getBlockState(target);
        if (state.isAir() || state.getDestroySpeed(ctx.world(), target) < 0) {
            return;
        }

        int bestSlot = findBestSafeToolSlot(state);
        if (bestSlot < 0 || bestSlot < 9) {
            return;
        }

        int hotbarSlot = chooseToolSwapHotbarSlot();
        int slotId = bestSlot;
        ctx.playerController().windowClick(ctx.player().inventoryMenu.containerId, slotId, hotbarSlot, ContainerInput.SWAP, ctx.player());
        ctx.player().getInventory().setSelectedSlot(hotbarSlot);
        toolSwapCooldown = 6;
        if (toolSwapLogCooldown == 0) {
            toolSwapLogCooldown = ACTION_COOLDOWN_TICKS * 10;
            logDirect("TaskBot: moved " + itemLabel(ctx.player().getInventory().getItem(hotbarSlot)) + " to hotbar for " + state.getBlock().getName().getString() + ".");
        }
    }

    private int findBestSafeToolSlot(BlockState state) {
        NonNullList<ItemStack> inv = ctx.player().getInventory().getNonEquipmentItems();
        int bestSlot = -1;
        float bestSpeed = 1.0F;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.get(i);
            if (!isAppropriateMiningTool(stack, state) || isLowDurability(stack)) {
                continue;
            }
            float speed = stack.getDestroySpeed(state);
            if (speed > bestSpeed) {
                bestSpeed = speed;
                bestSlot = i;
            }
        }
        return bestSlot;
    }

    private boolean isAppropriateMiningTool(ItemStack stack, BlockState state) {
        if (stack.isEmpty()) {
            return false;
        }
        return stack.isCorrectToolForDrops(state) || stack.getDestroySpeed(state) > 1.0F;
    }

    private int chooseToolSwapHotbarSlot() {
        NonNullList<ItemStack> inv = ctx.player().getInventory().getNonEquipmentItems();
        for (int i = 0; i < 9; i++) {
            if (inv.get(i).isEmpty()) {
                return i;
            }
        }
        int selected = ctx.player().getInventory().getSelectedSlot();
        if (canDisplaceForTool(inv.get(selected))) {
            return selected;
        }
        for (int i = 0; i < 9; i++) {
            if (canDisplaceForTool(inv.get(i))) {
                return i;
            }
        }
        return selected;
    }

    private boolean canDisplaceForTool(ItemStack stack) {
        if (stack.isEmpty()) {
            return true;
        }
        Item item = stack.getItem();
        return !(item instanceof BedItem)
                && !(item instanceof FireworkRocketItem)
                && item != Items.ELYTRA
                && !isFood(item)
                && !isToolOrArmor(stack);
    }

    private boolean handleToolService() {
        LocalPlayer player = ctx.player();
        if (toolServiceMode == ToolServiceMode.NONE) {
            toolChestCommandCooldown = 0;
            if (equipAfterRestock && player.containerMenu instanceof InventoryMenu) {
                int equipped = equipRepairedArmorFromInventory();
                equipAfterRestock = false;
                if (equipped > 0) {
                    logDirect("TaskBot: equipped " + equipped + " repaired armor/elytra item(s) after restock.");
                    toolServiceCooldown = ACTION_COOLDOWN_TICKS;
                    return true;
                }
            }
            return false;
        }
        if (toolServiceCooldown > 0) {
            return true;
        }

        if (player.containerMenu instanceof ChestMenu) {
            if (toolServiceMode == ToolServiceMode.RESTOCK_PICKAXES) {
                int moved = quickMoveFromChest(true);
                logDirect("TaskBot: restock pulled " + moved + " repaired stack(s) from pickaxe/tool chest.");
                player.closeContainer();
                toolServiceMode = ToolServiceMode.RESTOCK_SHOVELS;
                activeToolChestPos = SHOVEL_TOOL_CHEST_POS;
                goToToolChest("restock continuing to shovel chest");
                toolServiceCooldown = ACTION_COOLDOWN_TICKS * 2;
                return true;
            }

            if (toolServiceMode == ToolServiceMode.RESTOCK_SHOVELS) {
                int moved = quickMoveFromChest(true);
                logDirect("TaskBot: restock pulled " + moved + " repaired stack(s) from shovel chest.");
                player.closeContainer();
                toolServiceMode = ToolServiceMode.NONE;
                activeToolChestPos = PICKAXE_TOOL_CHEST_POS;
                equipAfterRestock = true;
                toolServiceCooldown = ACTION_COOLDOWN_TICKS;
                return true;
            }

            if (toolServiceMode == ToolServiceMode.DEPOSIT_LOW) {
                int deposited = quickMovePlayerLowDurabilityToChest();
                int restocked = quickMoveFromChest(true);
                player.closeContainer();
                if (deposited > 0 || restocked > 0) {
                    logDirect("TaskBot: deposited " + deposited + " low-durability stack(s) and pulled " + restocked + " repaired stack(s) from " + activeToolChestPos + ".");
                    toolServiceMode = ToolServiceMode.NONE;
                    activeToolChestPos = PICKAXE_TOOL_CHEST_POS;
                    equipAfterRestock = true;
                } else if (hasLowEquippedStack()) {
                    logDirect("TaskBot: low-durability item is equipped; closing chest to move it into inventory first.");
                } else {
                    logDirect("TaskBot: found no low-durability inventory item to deposit and no repaired replacement in " + activeToolChestPos + "; waiting for /toolrestock.");
                    toolServiceMode = ToolServiceMode.WAITING_FOR_RESTOCK;
                }
                toolServiceCooldown = ACTION_COOLDOWN_TICKS;
                return true;
            }
        }

        if (toolServiceMode == ToolServiceMode.WAITING_FOR_RESTOCK) {
            return false;
        }

        if (player.containerMenu instanceof InventoryMenu && moveLowEquipmentToInventory()) {
            toolServiceCooldown = ACTION_COOLDOWN_TICKS;
            return true;
        }

        if (toolServiceMode == ToolServiceMode.DEPOSIT_LOW) {
            activeToolChestPos = chestForLowDurabilityItems();
        }

        if (ctx.playerFeet().distSqr(activeToolChestPos) > 25) {
            goToToolChest("tool service");
            toolServiceCooldown = ACTION_COOLDOWN_TICKS * 2;
            return true;
        }

        BlockPos chest = isUsableChest(activeToolChestPos) ? activeToolChestPos : findNearbyChest();
        if (chest == null) {
            logDirect("TaskBot: no tool chest found at/near " + activeToolChestPos + ".");
            toolServiceCooldown = ACTION_COOLDOWN_TICKS * 5;
            return true;
        }
        useBlock(chest);
        toolServiceCooldown = ACTION_COOLDOWN_TICKS;
        return true;
    }

    private void goToToolChest(String reason) {
        if (toolChestCommandCooldown > 0) {
            return;
        }
        toolChestCommandCooldown = ACTION_COOLDOWN_TICKS * 20;

        baritone.getPathingBehavior().cancelEverything();
        baritone.getCommandManager().execute("stop");
        baritone.getCommandManager().execute("sel clear");

        if (canUseElytraTravel()) {
            baritone.getCommandManager().execute("set elytraTermsAccepted true");
            baritone.getCommandManager().execute("set elytraAutoJump true");
            baritone.getCommandManager().execute("set elytraMinimumDurability " + TOOL_DURABILITY_THRESHOLD);
            baritone.getCommandManager().execute("set elytraMinFireworksBeforeLanding 3");
            baritone.getCommandManager().execute("set elytraConserveFireworks false");
            baritone.getCommandManager().execute("goal " + activeToolChestPos.getX() + " " + activeToolChestPos.getY() + " " + activeToolChestPos.getZ());
            baritone.getCommandManager().execute("elytra");
            logDirect("TaskBot: " + reason + "; elytra-travelling to tool chest at " + activeToolChestPos + ".");
            return;
        }

        baritone.getCommandManager().execute("goto " + activeToolChestPos.getX() + " " + activeToolChestPos.getY() + " " + activeToolChestPos.getZ());
        logDirect("TaskBot: " + reason + "; walking to tool chest at " + activeToolChestPos + ".");
    }

    private boolean canUseElytraTravel() {
        ItemStack chest = ctx.player().getItemBySlot(EquipmentSlot.CHEST);
        return chest.getItem() == Items.ELYTRA
                && durabilityLeft(chest) > TOOL_DURABILITY_THRESHOLD
                && hasFireworkRocket();
    }

    private boolean hasFireworkRocket() {
        if (ctx.player().getOffhandItem().getItem() instanceof FireworkRocketItem) {
            return true;
        }
        for (ItemStack stack : ctx.player().getInventory().getNonEquipmentItems()) {
            if (stack.getItem() instanceof FireworkRocketItem) {
                return true;
            }
        }
        return false;
    }

    private BlockPos chestForLowDurabilityItems() {
        ItemStack low = findLowDurabilityStack();
        if (low.getItem() instanceof ShovelItem) {
            return SHOVEL_TOOL_CHEST_POS;
        }
        return PICKAXE_TOOL_CHEST_POS;
    }


    private int quickMovePlayerLowDurabilityToChest() {
        int moved = 0;
        int chestSlots = chestSlotCount();
        for (int slotId = chestSlots; slotId < ctx.player().containerMenu.slots.size(); slotId++) {
            Slot slot = ctx.player().containerMenu.slots.get(slotId);
            ItemStack stack = slot.getItem();
            if (!stack.isEmpty() && isLowDurability(stack)) {
                ctx.playerController().windowClick(ctx.player().containerMenu.containerId, slotId, 0, ContainerInput.QUICK_MOVE, ctx.player());
                moved++;
            }
        }
        return moved;
    }

    private boolean hasLowEquippedStack() {
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack stack = ctx.player().getItemBySlot(slot);
            if (isLowDurability(stack)) {
                return true;
            }
        }
        return false;
    }

    private boolean moveLowEquipmentToInventory() {
        if (firstEmptyInventorySlot() < 0) {
            ItemStack low = findLowEquippedStack();
            if (!low.isEmpty() && durabilityLogCooldown == 0) {
                durabilityLogCooldown = ACTION_COOLDOWN_TICKS * 5;
                logDirect("TaskBot: cannot unequip low-durability " + itemLabel(low) + " because inventory is full.");
            }
            return false;
        }
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack stack = ctx.player().getItemBySlot(slot);
            if (isLowDurability(stack)) {
                int slotId = inventoryMenuEquipmentSlotId(slot);
                if (slotId < 0) {
                    continue;
                }
                ctx.playerController().windowClick(ctx.player().inventoryMenu.containerId, slotId, 0, ContainerInput.QUICK_MOVE, ctx.player());
                logDirect("TaskBot: moved equipped low-durability " + itemLabel(stack) + " into inventory for deposit.");
                return true;
            }
        }
        return false;
    }

    private ItemStack findLowEquippedStack() {
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack stack = ctx.player().getItemBySlot(slot);
            if (isLowDurability(stack)) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    private int firstEmptyInventorySlot() {
        NonNullList<ItemStack> inv = ctx.player().getInventory().getNonEquipmentItems();
        for (int i = 0; i < inv.size(); i++) {
            if (inv.get(i).isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    private int inventoryMenuEquipmentSlotId(EquipmentSlot slot) {
        return switch (slot) {
            case HEAD -> 5;
            case CHEST -> 6;
            case LEGS -> 7;
            case FEET -> 8;
            default -> -1;
        };
    }

    private int quickMoveFromChest(boolean repairedOnly) {
        int moved = 0;
        int chestSlots = chestSlotCount();
        boolean needsBed = findSlot(stack -> stack.getItem() instanceof BedItem) < 0 && !(ctx.player().getOffhandItem().getItem() instanceof BedItem);
        for (int slotId = 0; slotId < chestSlots; slotId++) {
            Slot slot = ctx.player().containerMenu.slots.get(slotId);
            ItemStack stack = slot.getItem();
            if (!stack.isEmpty() && isToolOrArmor(stack) && (!repairedOnly || durabilityLeft(stack) > TOOL_DURABILITY_THRESHOLD)) {
                ctx.playerController().windowClick(ctx.player().containerMenu.containerId, slotId, 0, ContainerInput.QUICK_MOVE, ctx.player());
                moved++;
            } else if (needsBed && stack.getItem() instanceof BedItem) {
                ctx.playerController().windowClick(ctx.player().containerMenu.containerId, slotId, 0, ContainerInput.QUICK_MOVE, ctx.player());
                moved++;
                needsBed = false;
            }
        }
        return moved;
    }

    private int equipRepairedArmorFromInventory() {
        int equipped = 0;
        NonNullList<ItemStack> inv = ctx.player().getInventory().getNonEquipmentItems();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.get(i);
            EquipmentSlot slot = equipmentSlotForItem(stack.getItem());
            if (slot == null || durabilityLeft(stack) <= TOOL_DURABILITY_THRESHOLD) {
                continue;
            }
            if (!ctx.player().getItemBySlot(slot).isEmpty()) {
                continue;
            }
            int slotId = i < 9 ? i + 36 : i;
            ctx.playerController().windowClick(ctx.player().inventoryMenu.containerId, slotId, 0, ContainerInput.QUICK_MOVE, ctx.player());
            equipped++;
        }
        return equipped;
    }

    private EquipmentSlot equipmentSlotForItem(Item item) {
        if (item == Items.LEATHER_BOOTS || item == Items.CHAINMAIL_BOOTS || item == Items.IRON_BOOTS || item == Items.GOLDEN_BOOTS || item == Items.DIAMOND_BOOTS || item == Items.NETHERITE_BOOTS) {
            return EquipmentSlot.FEET;
        }
        if (item == Items.LEATHER_LEGGINGS || item == Items.CHAINMAIL_LEGGINGS || item == Items.IRON_LEGGINGS || item == Items.GOLDEN_LEGGINGS || item == Items.DIAMOND_LEGGINGS || item == Items.NETHERITE_LEGGINGS) {
            return EquipmentSlot.LEGS;
        }
        if (item == Items.LEATHER_CHESTPLATE || item == Items.CHAINMAIL_CHESTPLATE || item == Items.IRON_CHESTPLATE || item == Items.GOLDEN_CHESTPLATE || item == Items.DIAMOND_CHESTPLATE || item == Items.NETHERITE_CHESTPLATE || item == Items.ELYTRA) {
            return EquipmentSlot.CHEST;
        }
        if (item == Items.LEATHER_HELMET || item == Items.CHAINMAIL_HELMET || item == Items.IRON_HELMET || item == Items.GOLDEN_HELMET || item == Items.DIAMOND_HELMET || item == Items.NETHERITE_HELMET || item == Items.TURTLE_HELMET) {
            return EquipmentSlot.HEAD;
        }
        return null;
    }

    private int chestSlotCount() {
        return Math.max(0, ctx.player().containerMenu.slots.size() - 36);
    }

    private ItemStack findLowDurabilityStack() {
        ItemStack selected = ctx.player().getMainHandItem();
        if (isLowDurability(selected)) {
            return selected;
        }
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack stack = ctx.player().getItemBySlot(slot);
            if (isLowDurability(stack)) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    private boolean isLowDurability(ItemStack stack) {
        return isToolOrArmor(stack) && durabilityLeft(stack) <= TOOL_DURABILITY_THRESHOLD;
    }

    private boolean isToolOrArmor(ItemStack stack) {
        return !stack.isEmpty() && stack.isDamageableItem();
    }

    private int durabilityLeft(ItemStack stack) {
        if (!stack.isDamageableItem()) {
            return -1;
        }
        return stack.getMaxDamage() - stack.getDamageValue();
    }

    private String itemLabel(ItemStack stack) {
        if (stack.isEmpty()) {
            return "empty";
        }
        return stack.getHoverName().getString() + "=" + durabilityLeft(stack) + "/" + stack.getMaxDamage();
    }

    private void logTaskStatus() {
        BlockPos feet = ctx.playerFeet();
        String selected = ctx.getSelectedBlock()
                .map(pos -> pos.getX() + "," + pos.getY() + "," + pos.getZ())
                .orElse("none");
        logDirect("TaskBotStatus: pos=" + feet.getX() + "," + feet.getY() + "," + feet.getZ()
                + " allowBreak=" + Baritone.settings().allowBreak.value
                + " allowPlace=" + Baritone.settings().allowPlace.value
                + " selected=" + selected
                + " toolMode=" + toolServiceMode
                + " clearBox=" + clearBoxMin + "->" + clearBoxMax
                + " clearTarget=" + clearBoxTarget
                + " allowedCuboid=" + allowedBreakMin + "->" + allowedBreakMax + ".");
    }

    private void logDurabilityReport() {
        StringBuilder report = new StringBuilder("TaskBotDurability:");
        if (isToolOrArmor(ctx.player().getMainHandItem())) {
            report.append(" selected=").append(itemLabel(ctx.player().getMainHandItem()));
        }
        if (isToolOrArmor(ctx.player().getOffhandItem())) {
            report.append("; offhand=").append(itemLabel(ctx.player().getOffhandItem()));
        }
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack stack = ctx.player().getItemBySlot(slot);
            if (isToolOrArmor(stack)) {
                report.append("; ").append(slot.getName()).append("=").append(itemLabel(stack));
            }
        }
        NonNullList<ItemStack> inv = ctx.player().getInventory().getNonEquipmentItems();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.get(i);
            if (isToolOrArmor(stack)) {
                report.append("; slot").append(i).append("=").append(itemLabel(stack));
            }
        }
        if (report.length() == "TaskBotDurability:".length()) {
            report.append(" no damageable armor/tools found");
        }
        logDirect(report.toString());
    }

    @Override
    public void onReceivePacket(PacketEvent event) {
        if (event.getState() != EventState.POST) {
            return;
        }
        Object packet = event.getPacket();
        String text;
        if (packet instanceof ClientboundSystemChatPacket systemChat) {
            text = systemChat.content().getString();
        } else if (packet instanceof ClientboundPlayerChatPacket playerChat) {
            text = playerChat.unsignedContent() != null
                    ? playerChat.unsignedContent().getString()
                    : playerChat.body().content();
        } else {
            return;
        }
        text = text.toLowerCase(Locale.ROOT);
        if (text.contains("dont sleep") || text.contains("don't sleep")) {
            dontSleepThisNight = true;
            logDirect("TaskBot: received dont sleep, skipping sleep for this night.");
        }
    }

    @Override
    public void onPlayerDeath() {
        autoRespawnTicks = ACTION_COOLDOWN_TICKS;
        managedChest = null;
        managedBed = null;
        logDirect("TaskBot: death detected, auto-respawn queued.");
    }

    private boolean playerIsSleeping() {
        return ctx.player() != null && ctx.player().isSleeping();
    }

    private void startClearBox(BlockPos a, BlockPos b) {
        BlockPos min = new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
        BlockPos max = new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
        if (!isCloseEnoughToStartNativeClearArea(ctx.playerFeet(), min, max)) {
            clearCurrentClearBoxState();
            clearAllowedBreakCuboid();
            pendingNativeClearMin = min;
            pendingNativeClearMax = max;
            pendingNativeClearApproach = findNativeClearApproach(min, max);
            beginNonBreakingTravel();
            baritone.getPathingBehavior().cancelEverything();
            baritone.getCommandManager().execute("stop");
            baritone.getCommandManager().execute("goto " + pendingNativeClearApproach.getX() + " " + pendingNativeClearApproach.getY() + " " + pendingNativeClearApproach.getZ());
            nativeClearAreaLastRefreshAt = System.currentTimeMillis();
            logDirect("TaskBot: staged native cleararea " + min + " -> " + max + "; travelling non-destructively via " + pendingNativeClearApproach + ".");
            return;
        }
        startNativeClearArea(min, max);
    }

    private void startNativeClearArea(BlockPos min, BlockPos max) {
        clearCurrentClearBoxState();
        pendingNativeClearMin = null;
        pendingNativeClearMax = null;
        pendingNativeClearApproach = null;
        clearBoxMin = null;
        clearBoxMax = null;
        clearBoxTarget = null;
        clearBoxBreakLock = null;
        clearBoxSkippedTargets.clear();
        clearBoxSkipOrigin = null;
        clearBoxLastActivityFeet = ctx.playerFeet().immutable();
        clearBoxLastGotoTarget = null;
        clearBoxLastActivityAt = System.currentTimeMillis();
        clearBoxGotoCooldown = 0;
        clearBoxLogCooldown = 0;
        clearBoxBlockedReports = 0;
        clearBoxNoPathSkips = 0;
        clearBoxBreakSnapshotReady = false;
        setAllowedBreakCuboid(min, max);
        buildAllowedBreakSnapshot();
        if (allowedBreakPositions.isEmpty()) {
            forceSafeIdle();
            logDirect("TaskBot: native cleararea snapshot found no breakable blocks in " + min + " -> " + max + "; stopping.");
            return;
        }
        clearBoxBreakSnapshotReady = true;
        beginBreakingTask();
        Baritone.settings().allowPlace.value = false;
        nativeClearAreaRecovering = false;
        baritone.getPathingBehavior().cancelEverything();
        baritone.getCommandManager().execute("stop");
        baritone.getCommandManager().execute("sel clear");
        baritone.getCommandManager().execute("sel pos1 " + min.getX() + " " + min.getY() + " " + min.getZ());
        baritone.getCommandManager().execute("sel pos2 " + max.getX() + " " + max.getY() + " " + max.getZ());
        baritone.getCommandManager().execute("sel cleararea");
        nativeClearAreaLastRefreshAt = System.currentTimeMillis();
        logDirect("TaskBot: native Baritone cleararea started " + min + " -> " + max + "; snapshot locked " + allowedBreakPositions.size() + " block(s); allowBreak=" + Baritone.settings().allowBreak.value + " allowPlace=" + Baritone.settings().allowPlace.value + ".");
    }

    private void clearCurrentClearBoxState() {
        clearBoxMin = null;
        clearBoxMax = null;
        clearBoxTarget = null;
        clearBoxBreakLock = null;
        clearBoxSkippedTargets.clear();
        clearBoxSkipOrigin = null;
        clearBoxLastActivityFeet = null;
        clearBoxLastGotoTarget = null;
        clearBoxLastActivityAt = 0L;
        clearBoxGotoCooldown = 0;
        clearBoxLogCooldown = 0;
        clearBoxBlockedReports = 0;
        clearBoxNoPathSkips = 0;
        clearBoxBreakSnapshotReady = false;
        nativeClearAreaRecovering = false;
    }

    private void stopClearBox() {
        clearCurrentClearBoxState();
        pendingNativeClearMin = null;
        pendingNativeClearMax = null;
        pendingNativeClearApproach = null;
        forceSafeIdle();
    }

    private boolean handleClearBox() {
        if (clearBoxMin == null || clearBoxMax == null) {
            return false;
        }
        BlockPos feet = ctx.playerFeet();
        markClearBoxMovementActivity(feet);
        if (clearBoxBreakSnapshotReady && feet.getY() < clearBoxMin.getY() - 8) {
            logDirect("TaskBot: native clearbox blocked; bot dropped below task floor at " + feet + " for " + clearBoxMin + " -> " + clearBoxMax + ". Stopping safe.");
            stopClearBox();
            return false;
        }
        if (clearBoxSkipOrigin == null || clearBoxSkipOrigin.distSqr(feet) > 9.0D) {
            clearBoxSkippedTargets.clear();
            clearBoxSkipOrigin = feet.immutable();
        }
        if (clearBoxBreakLock != null) {
            if (holdClearBoxBreakLock()) {
                return true;
            }
            clearBoxBreakLock = null;
        }
        if (shouldApproachClearBox(feet)) {
            Baritone.settings().allowBreak.value = false;
            Baritone.settings().allowPlace.value = false;
            BlockPos approach = clearBoxApproachTarget();
            if (clearBoxGotoCooldown == 0 && shouldIssueClearBoxTravelGoto(approach)) {
                baritone.getCommandManager().execute("goto " + approach.getX() + " " + approach.getY() + " " + approach.getZ());
                clearBoxLastGotoTarget = approach.immutable();
                clearBoxGotoCooldown = ACTION_COOLDOWN_TICKS * 3;
                markClearBoxActivity();
                logDirect("TaskBot: native clearbox approaching " + clearBoxMin + " -> " + clearBoxMax + " via " + approach + ".");
            }
            return true;
        }
        if (!clearBoxBreakSnapshotReady) {
            buildClearBoxBreakSnapshot();
            if (allowedBreakPositions.isEmpty()) {
                logDirect("TaskBot: native clearbox snapshot found no breakable blocks in " + clearBoxMin + " -> " + clearBoxMax + "; stopping.");
                stopClearBox();
                return false;
            }
            clearBoxBreakSnapshotReady = true;
            markClearBoxActivity();
            logDirect("TaskBot: native clearbox snapshot locked " + allowedBreakPositions.size() + " block(s). Only those positions may be broken.");
        }
        if (clearBoxTarget == null || !shouldClearBlock(clearBoxTarget)) {
            if (clearBoxTarget != null) {
                clearBoxSkipOrigin = feet.immutable();
                clearBoxBlockedReports = 0;
            }
            clearBoxTarget = findNextClearBoxTarget();
            clearBoxLastGotoTarget = null;
            if (clearBoxTarget == null) {
                if (!hasRemainingClearBoxBlocks()) {
                    logDirect("TaskBot: native clearbox complete " + clearBoxMin + " -> " + clearBoxMax + ".");
                    stopClearBox();
                } else {
                    clearBoxBlockedReports++;
                    logDirect("TaskBot: native clearbox blocked; " + remainingClearBoxBlockCount(256) + "+ mineable block(s) remain but no exposed reachable target exists in " + clearBoxMin + " -> " + clearBoxMax + " from pos " + ctx.playerFeet() + ".");
                    if (clearBoxBlockedReports >= 3) {
                        stopClearBox();
                    }
                }
                return false;
            }
            markClearBoxActivity();
            if (clearBoxLogCooldown == 0) {
                clearBoxLogCooldown = ACTION_COOLDOWN_TICKS * 4;
                logDirect("TaskBot: native clearbox target " + clearBoxTarget + ".");
            }
        }

        if (clearBoxTarget != null
                && clearBoxLastGotoTarget != null
                && clearBoxLastGotoTarget.equals(clearBoxTarget)
                && !baritone.getPathingBehavior().isPathing()
                && System.currentTimeMillis() - clearBoxLastActivityAt >= 8_000L) {
            clearBoxNoPathSkips++;
            logDirect("TaskBot: native clearbox no active path to stand for " + clearBoxTarget + "; skipping target.");
            if (clearBoxNoPathSkips >= 16) {
                logDirect("TaskBot: native clearbox blocked; 16 stand targets had no path from " + ctx.playerFeet() + " without breaking outside " + clearBoxMin + " -> " + clearBoxMax + ". Stopping safe.");
                stopClearBox();
                return false;
            }
            clearBoxSkippedTargets.add(clearBoxTarget.immutable());
            clearBoxSkipOrigin = ctx.playerFeet().immutable();
            clearBoxTarget = null;
            clearBoxLastGotoTarget = null;
            clearBoxGotoCooldown = 0;
            return true;
        }

        Optional<Rotation> reachable = RotationUtils.reachable(ctx, clearBoxTarget, ctx.playerController().getBlockReachDistance());
        if (reachable.isPresent()) {
            baritone.getPathingBehavior().cancelEverything();
            Baritone.settings().allowBreak.value = true;
            Baritone.settings().allowPlace.value = true;
            MovementHelper.switchToBestToolFor(ctx, ctx.world().getBlockState(clearBoxTarget));
            Rotation rotation = reachable.get();
            baritone.getLookBehavior().updateTarget(rotation, true);
            if (ctx.isLookingAt(clearBoxTarget) || ctx.playerRotations().isReallyCloseTo(rotation)) {
                clearBoxBreakLock = clearBoxTarget.immutable();
                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
                markClearBoxActivity();
            }
            return true;
        }

        Baritone.settings().allowBreak.value = false;
        Baritone.settings().allowPlace.value = false;
        if (clearBoxGotoCooldown == 0 && shouldIssueClearBoxGoto()) {
            BlockPos stand = findStandNear(clearBoxTarget);
            if (stand != null) {
                if (stand.distSqr(ctx.playerFeet()) <= 2.0D) {
                    clearBoxNoPathSkips++;
                    logDirect("TaskBot: native clearbox cannot reach " + clearBoxTarget + " from current stand " + stand + "; skipping.");
                    if (clearBoxNoPathSkips >= 16) {
                        logDirect("TaskBot: native clearbox blocked; 16 targets were unreachable from " + ctx.playerFeet() + " inside " + clearBoxMin + " -> " + clearBoxMax + ". Stopping safe.");
                        stopClearBox();
                        return false;
                    }
                    clearBoxSkippedTargets.add(clearBoxTarget.immutable());
                    clearBoxSkipOrigin = ctx.playerFeet().immutable();
                    clearBoxTarget = null;
                } else {
                    baritone.getCommandManager().execute("goto " + stand.getX() + " " + stand.getY() + " " + stand.getZ());
                    clearBoxLastGotoTarget = clearBoxTarget.immutable();
                    markClearBoxActivity();
                    logDirect("TaskBot: native clearbox walking to " + stand + " for " + clearBoxTarget + ".");
                }
            } else {
                logDirect("TaskBot: native clearbox cannot find stand position near " + clearBoxTarget + "; skipping.");
                clearBoxSkippedTargets.add(clearBoxTarget.immutable());
                clearBoxSkipOrigin = ctx.playerFeet().immutable();
                clearBoxTarget = null;
            }
            clearBoxGotoCooldown = ACTION_COOLDOWN_TICKS * 3;
        }
        return true;
    }

    private boolean holdClearBoxBreakLock() {
        if (clearBoxBreakLock == null || !shouldClearBlock(clearBoxBreakLock)) {
            baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
            return false;
        }
        Optional<Rotation> reachable = RotationUtils.reachable(ctx, clearBoxBreakLock, ctx.playerController().getBlockReachDistance());
        if (reachable.isEmpty()) {
            baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
            return false;
        }
        baritone.getPathingBehavior().cancelEverything();
        Baritone.settings().allowBreak.value = true;
        Baritone.settings().allowPlace.value = true;
        clearBoxTarget = clearBoxBreakLock.immutable();
        MovementHelper.switchToBestToolFor(ctx, ctx.world().getBlockState(clearBoxBreakLock));
        Rotation rotation = reachable.get();
        baritone.getLookBehavior().updateTarget(rotation, true);
        if (ctx.isLookingAt(clearBoxBreakLock) || ctx.playerRotations().isReallyCloseTo(rotation)) {
            baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
            markClearBoxActivity();
        }
        return true;
    }

    private void markClearBoxMovementActivity(BlockPos feet) {
        if (clearBoxLastActivityFeet == null || !clearBoxLastActivityFeet.equals(feet)) {
            clearBoxLastActivityFeet = feet.immutable();
            clearBoxNoPathSkips = 0;
            markClearBoxActivity();
        }
    }

    private void markClearBoxActivity() {
        clearBoxLastActivityAt = System.currentTimeMillis();
    }

    private boolean shouldIssueClearBoxGoto() {
        if (clearBoxLastGotoTarget == null || !clearBoxLastGotoTarget.equals(clearBoxTarget)) {
            return true;
        }
        return System.currentTimeMillis() - clearBoxLastActivityAt >= CLEARBOX_INACTIVITY_REMINDER_MS;
    }

    private boolean shouldIssueClearBoxTravelGoto(BlockPos approach) {
        if (clearBoxLastGotoTarget == null || !clearBoxLastGotoTarget.equals(approach)) {
            return true;
        }
        return System.currentTimeMillis() - clearBoxLastActivityAt >= CLEARBOX_INACTIVITY_REMINDER_MS;
    }

    private boolean shouldApproachClearBox(BlockPos feet) {
        return feet.getX() < clearBoxMin.getX() - 32 || feet.getX() > clearBoxMax.getX() + 32
                || feet.getZ() < clearBoxMin.getZ() - 32 || feet.getZ() > clearBoxMax.getZ() + 32
                || feet.getY() < clearBoxMin.getY() - 1 || feet.getY() > clearBoxMax.getY() + 16;
    }

    private BlockPos clearBoxApproachTarget() {
        BlockPos feet = ctx.playerFeet();
        int centerX = (clearBoxMin.getX() + clearBoxMax.getX()) / 2;
        int centerZ = (clearBoxMin.getZ() + clearBoxMax.getZ()) / 2;
        int dx = Math.max(0, Math.max(clearBoxMin.getX() - feet.getX(), feet.getX() - clearBoxMax.getX()));
        int dz = Math.max(0, Math.max(clearBoxMin.getZ() - feet.getZ(), feet.getZ() - clearBoxMax.getZ()));
        if (Math.max(dx, dz) > 12 && feet.getY() > clearBoxMax.getY() + 8) {
            return new BlockPos(centerX, feet.getY(), centerZ);
        }
        return new BlockPos(
                centerX,
                clearBoxMin.getY(),
                centerZ
        );
    }

    private BlockPos findNextClearBoxTarget() {
        BlockPos player = ctx.playerFeet();
        return findNextClearBoxTarget(player, false);
    }

    private BlockPos findNextClearBoxTarget(BlockPos player, boolean includeSkipped) {
        int targetY = highestClearBoxLayerWithTargets(includeSkipped);
        if (targetY == Integer.MIN_VALUE) {
            return null;
        }
        BlockPos bestReachable = null;
        double bestReachableScore = Double.MAX_VALUE;
        BlockPos bestStandable = null;
        double bestStandableScore = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(clearBoxMin, clearBoxMax)) {
            BlockPos candidate = pos.immutable();
            if (!shouldClearBlock(candidate) || (!includeSkipped && clearBoxSkippedTargets.contains(candidate))) {
                continue;
            }
            boolean exposed = isExposedForClearBox(candidate);
            if (!exposed && !includeSkipped) {
                continue;
            }
            Optional<Rotation> reachable = RotationUtils.reachable(ctx, candidate, ctx.playerController().getBlockReachDistance());
            if (reachable.isPresent()) {
                double score = clearBoxTargetScore(player, candidate, 0.0D);
                if (score < bestReachableScore) {
                    bestReachableScore = score;
                    bestReachable = candidate;
                }
                continue;
            }
            BlockPos stand = findStandNear(candidate);
            if (stand == null) {
                if (!includeSkipped) {
                    clearBoxSkippedTargets.add(candidate);
                }
                continue;
            }
            double score = clearBoxTargetScore(player, candidate, player.distSqr(stand));
            if (score < bestStandableScore) {
                bestStandableScore = score;
                bestStandable = candidate;
            }
        }
        return bestReachable != null ? bestReachable : bestStandable;
    }

    private double clearBoxTargetScore(BlockPos player, BlockPos candidate, double standDistance) {
        double directDistance = player.distSqr(candidate);
        double verticalPenalty = Math.abs(candidate.getY() - player.getY()) * 12.0D;
        double belowTopPenalty = Math.max(0, clearBoxMax.getY() - candidate.getY()) * 0.75D;
        return directDistance + standDistance * 1.5D + verticalPenalty + belowTopPenalty;
    }

    private int highestClearBoxLayerWithTargets(boolean includeSkipped) {
        for (int y = clearBoxMax.getY(); y >= clearBoxMin.getY(); y--) {
            for (int x = clearBoxMin.getX(); x <= clearBoxMax.getX(); x++) {
                for (int z = clearBoxMin.getZ(); z <= clearBoxMax.getZ(); z++) {
                    BlockPos candidate = new BlockPos(x, y, z);
                    if (shouldClearBlock(candidate)
                            && (includeSkipped || !clearBoxSkippedTargets.contains(candidate))
                            && (includeSkipped || isExposedForClearBox(candidate))) {
                        return y;
                    }
                }
            }
        }
        return Integer.MIN_VALUE;
    }

    private boolean hasRemainingClearBoxBlocks() {
        for (BlockPos pos : BlockPos.betweenClosed(clearBoxMin, clearBoxMax)) {
            if (shouldClearBlock(pos)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasRemainingAllowedBreakBlocks() {
        if (allowedBreakMin == null || allowedBreakMax == null) {
            return false;
        }
        for (BlockPos pos : allowedBreakPositions) {
            if (isBreakableClearBoxBlock(pos)) {
                return true;
            }
        }
        return false;
    }

    private int remainingClearBoxBlockCount(int cap) {
        int count = 0;
        for (BlockPos pos : BlockPos.betweenClosed(clearBoxMin, clearBoxMax)) {
            if (shouldClearBlock(pos)) {
                count++;
                if (count >= cap) {
                    return count;
                }
            }
        }
        return count;
    }

    private boolean isExposedForClearBox(BlockPos pos) {
        for (Direction direction : Direction.values()) {
            BlockPos neighbor = pos.relative(direction);
            if (!insideClearBox(neighbor)) {
                return true;
            }
            BlockState state = ctx.world().getBlockState(neighbor);
            if (state.isAir() || state.canBeReplaced() || state.getDestroySpeed(ctx.world(), neighbor) < 0) {
                return true;
            }
        }
        return false;
    }

    private void buildAllowedBreakSnapshot() {
        allowedBreakPositions.clear();
        if (allowedBreakMin == null || allowedBreakMax == null) {
            return;
        }
        for (BlockPos pos : BlockPos.betweenClosed(allowedBreakMin, allowedBreakMax)) {
            BlockPos candidate = pos.immutable();
            if (isBreakableClearBoxBlock(candidate)) {
                allowedBreakPositions.add(candidate);
            }
        }
    }

    private void buildClearBoxBreakSnapshot() {
        buildAllowedBreakSnapshot();
    }

    private boolean shouldClearBlock(BlockPos pos) {
        return clearBoxBreakSnapshotReady
                && allowedBreakPositions.contains(pos.immutable())
                && isBreakableClearBoxBlock(pos);
    }

    private boolean isBreakableClearBoxBlock(BlockPos pos) {
        if (!insideClearBox(pos)) {
            return false;
        }
        BlockState state = ctx.world().getBlockState(pos);
        if (state.isAir() || state.canBeReplaced() || state.getDestroySpeed(ctx.world(), pos) < 0) {
            return false;
        }
        Block block = state.getBlock();
        return !(block instanceof ChestBlock)
                && !(block instanceof BedBlock)
                && block != Blocks.VINE
                && block != Blocks.CAVE_VINES
                && block != Blocks.CAVE_VINES_PLANT
                && block != Blocks.WEEPING_VINES
                && block != Blocks.WEEPING_VINES_PLANT
                && block != Blocks.TWISTING_VINES
                && block != Blocks.TWISTING_VINES_PLANT
                && block != Blocks.TORCH
                && block != Blocks.WALL_TORCH
                && block != Blocks.SOUL_TORCH
                && block != Blocks.SOUL_WALL_TORCH;
    }

    private boolean insideClearBox(BlockPos pos) {
        BlockPos min = clearBoxMin != null ? clearBoxMin : allowedBreakMin;
        BlockPos max = clearBoxMax != null ? clearBoxMax : allowedBreakMax;
        return min != null && max != null
                && pos.getX() >= min.getX() && pos.getX() <= max.getX()
                && pos.getY() >= min.getY() && pos.getY() <= max.getY()
                && pos.getZ() >= min.getZ() && pos.getZ() <= max.getZ();
    }

    private BlockPos findStandNear(BlockPos target) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        double reach = Math.max(3.0D, ctx.playerController().getBlockReachDistance() - 0.25D);
        for (int dx = -3; dx <= 3; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -3; dz <= 3; dz++) {
                    BlockPos feet = target.offset(dx, dy, dz);
                    if (feet.getY() > target.getY() || !canStandAt(feet)) {
                        continue;
                    }
                    if (feet.getX() == target.getX() && feet.getZ() == target.getZ() && feet.getY() >= target.getY()) {
                        continue;
                    }
                    if (!canSeeTargetFrom(feet, target, reach)) {
                        continue;
                    }
                    double distance = ctx.playerFeet().distSqr(feet) + Math.abs(target.getY() - feet.getY()) * 4.0D;
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = feet.immutable();
                    }
                }
            }
        }
        return best;
    }

    private boolean canSeeTargetFrom(BlockPos feet, BlockPos target, double reach) {
        Vec3 eye = Vec3.atLowerCornerOf(feet).add(0.5D, ctx.player().getEyeHeight(), 0.5D);
        double reachSq = reach * reach;
        for (Vec3 point : targetSightPoints(target)) {
            if (eye.distanceToSqr(point) > reachSq) {
                continue;
            }
            HitResult hit = ctx.world().clip(new ClipContext(eye, point, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, ctx.player()));
            if (hit != null && hit.getType() == HitResult.Type.BLOCK && ((BlockHitResult) hit).getBlockPos().equals(target)) {
                return true;
            }
        }
        return false;
    }

    private Vec3[] targetSightPoints(BlockPos target) {
        double x = target.getX();
        double y = target.getY();
        double z = target.getZ();
        return new Vec3[] {
                new Vec3(x + 0.5D, y + 0.5D, z + 0.5D),
                new Vec3(x + 0.5D, y + 0.98D, z + 0.5D),
                new Vec3(x + 0.5D, y + 0.02D, z + 0.5D),
                new Vec3(x + 0.02D, y + 0.5D, z + 0.5D),
                new Vec3(x + 0.98D, y + 0.5D, z + 0.5D),
                new Vec3(x + 0.5D, y + 0.5D, z + 0.02D),
                new Vec3(x + 0.5D, y + 0.5D, z + 0.98D)
        };
    }

    private boolean canStandAt(BlockPos feet) {
        return isEmpty(feet)
                && isEmpty(feet.above())
                && canPlaceOn(feet.below());
    }

    private Direction faceToward(BlockPos target) {
        Vec3 eye = ctx.player().getEyePosition();
        Vec3 center = Vec3.atCenterOf(target);
        double dx = eye.x - center.x;
        double dy = eye.y - center.y;
        double dz = eye.z - center.z;
        double ax = Math.abs(dx);
        double ay = Math.abs(dy);
        double az = Math.abs(dz);
        if (ay >= ax && ay >= az) {
            return dy > 0 ? Direction.UP : Direction.DOWN;
        }
        if (ax >= az) {
            return dx > 0 ? Direction.EAST : Direction.WEST;
        }
        return dz > 0 ? Direction.SOUTH : Direction.NORTH;
    }

    private void handleSleep(boolean sleepTime, long dayTime, int skyDarken) {
        if (dontSleepThisNight || sleepCooldown > 0 || !sleepTime) {
            return;
        }
        LocalPlayer player = ctx.player();
        if (player.isSleeping() || player.isPassenger() || player.isFallFlying()) {
            return;
        }

        BlockPos nearbyBed = findNearbyBed();
        if (nearbyBed != null && useBlock(nearbyBed, bestBedHand())) {
            managedBed = nearbyBed.immutable();
            logDirect("TaskBot: using nearby bed at " + nearbyBed + ".");
            sleepCooldown = ACTION_COOLDOWN_TICKS * 3;
            return;
        }

        int bedSlot = findSlot(stack -> stack.getItem() instanceof BedItem);
        InteractionHand bedHand = bestBedHand();
        if (bedSlot < 0 && bedHand == InteractionHand.MAIN_HAND) {
            logSleepFailure("no bed found in inventory/offhand at clock=" + dayTime + " skyDarken=" + skyDarken);
            sleepCooldown = ACTION_COOLDOWN_TICKS * 5;
            return;
        }

        BlockPos placeAgainst = findBedPlacementSupport();
        if (placeAgainst == null) {
            logSleepFailure("no valid bed placement support at clock=" + dayTime + " skyDarken=" + skyDarken + " pos=" + ctx.playerFeet());
            sleepCooldown = ACTION_COOLDOWN_TICKS * 5;
            return;
        }

        if (bedHand == InteractionHand.OFF_HAND || selectHotbarSlot(bedSlot)) {
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(placeAgainst), Direction.UP, placeAgainst, false);
            InteractionResult result = ctx.playerController().processRightClickBlock(player, ctx.world(), bedHand, hit);
            player.swing(bedHand);
            logDirect("TaskBot: tried to place/use bed with " + bedHand + " at clock=" + dayTime + " skyDarken=" + skyDarken + " result=" + result + ".");
            sleepCooldown = ACTION_COOLDOWN_TICKS;
            if (result.consumesAction()) {
                managedChest = null;
                managedBed = placeAgainst.above().immutable();
            }
        } else {
            logSleepFailure("failed to move bed slot " + bedSlot + " to hotbar at clock=" + dayTime + " skyDarken=" + skyDarken);
            sleepCooldown = ACTION_COOLDOWN_TICKS * 5;
        }
    }

    private boolean handleBedPickup(boolean sleepTime) {
        if (bedPickupCooldown > 0 || sleepTime || playerIsSleeping()) {
            return false;
        }
        if (managedBed == null && !hasBedItem()) {
            BlockPos nearbyBed = findNearbyBed();
            if (nearbyBed != null && isTaskBreakAllowed(nearbyBed)) {
                managedBed = nearbyBed.immutable();
                logDirect("TaskBot: recovered nearby bed at " + managedBed + " for pickup.");
            }
        }
        if (managedBed == null) {
            return false;
        }
        BlockState state = ctx.world().getBlockState(managedBed);
        if (!(state.getBlock() instanceof BedBlock)) {
            BlockPos nearbyBed = !hasBedItem() ? findNearbyBed() : null;
            if (nearbyBed != null) {
                managedBed = nearbyBed.immutable();
                state = ctx.world().getBlockState(managedBed);
            }
        }
        if (!(state.getBlock() instanceof BedBlock)) {
            managedBed = null;
            return false;
        }
        if (ctx.playerFeet().distSqr(managedBed) > 25) {
            logDirect("TaskBot: managed bed at " + managedBed + " is too far away to pick up without interrupting the task.");
            managedBed = null;
            return false;
        }
        ctx.playerController().clickBlock(managedBed, Direction.UP);
        ctx.playerController().onPlayerDamageBlock(managedBed, Direction.UP);
        ctx.player().swing(InteractionHand.MAIN_HAND);
        bedPickupCooldown = 2;
        if (!(ctx.world().getBlockState(managedBed).getBlock() instanceof BedBlock)) {
            logDirect("TaskBot: picked up managed bed at " + managedBed + ".");
            managedBed = null;
        }
        return true;
    }

    private boolean hasBedItem() {
        return findSlot(stack -> stack.getItem() instanceof BedItem) >= 0 || ctx.player().getOffhandItem().getItem() instanceof BedItem;
    }

    private InteractionHand bestBedHand() {
        return ctx.player().getOffhandItem().getItem() instanceof BedItem ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
    }

    private void logSleepFailure(String reason) {
        if (sleepLogCooldown > 0) {
            return;
        }
        sleepLogCooldown = ACTION_COOLDOWN_TICKS * 10;
        logDirect("TaskBot: sleep skipped, " + reason + ".");
    }

    private boolean handleDeposit() {
        LocalPlayer player = ctx.player();
        if (!(player.containerMenu instanceof InventoryMenu)) {
            if (isActiveMiningTask()) {
                player.closeContainer();
                depositCooldown = ACTION_COOLDOWN_TICKS;
                return false;
            }
            dumpInventoryIntoOpenContainer();
            return true;
        }
        if (depositCooldown > 0) {
            if (isChestStack(player.getMainHandItem())) {
                selectNonChestHotbarSlot();
            }
            return false;
        }
        if (!inventoryFull()) {
            return false;
        }

        if (isActiveMiningTask()) {
            int dropped = dropMiningOverflowItems();
            if (dropped > 0) {
                logDirect("TaskBot: mining inventory full; dropped " + dropped + " overflow stack(s) and kept clearing.");
            } else {
                logDirect("TaskBot: mining inventory full but only protected gear remains; keeping clear task active.");
            }
            depositCooldown = ACTION_COOLDOWN_TICKS;
            return false;
        }

        int chestSlot = findSlot(this::isChestStack);
        if (chestSlot < 0) {
            int dropped = dropFillerTerrainItems();
            if (dropped > 0) {
                logDirect("TaskBot: inventory full and no chests; dropped " + dropped + " filler stack(s) to keep moving.");
            } else {
                logDirect("TaskBot: inventory needs deposit but no chest stack/filler item was found.");
            }
            depositCooldown = FULL_INVENTORY_CHECK_TICKS;
            return false;
        }

        if (managedChest != null && !isNearby(managedChest, 25)) {
            managedChest = null;
        }
        BlockPos chest = managedChest != null && isUsableChest(managedChest) ? managedChest : findNearbyChest();
        if (chest != null && useBlock(chest)) {
            managedChest = chest;
            selectNonChestHotbarSlot();
            logDirect("TaskBot: opening deposit chest at " + chest + ".");
            depositCooldown = ACTION_COOLDOWN_TICKS;
            return true;
        }

        BlockPos support = findChestPlacementSupport();
        if (support == null) {
            logDirect("TaskBot: inventory needs deposit but no valid chest placement support found at " + ctx.playerFeet() + ".");
            depositCooldown = FULL_INVENTORY_CHECK_TICKS;
            return false;
        }

        if (selectHotbarSlot(chestSlot)) {
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(support), Direction.UP, support, false);
            InteractionResult result = ctx.playerController().processRightClickBlock(player, ctx.world(), InteractionHand.MAIN_HAND, hit);
            player.swing(InteractionHand.MAIN_HAND);
            if (result.consumesAction()) {
                managedChest = support.above();
                logDirect("TaskBot: placed deposit chest at " + managedChest + ".");
            } else {
                logDirect("TaskBot: failed placing deposit chest at " + support.above() + " result=" + result + ".");
            }
            selectNonChestHotbarSlot();
            depositCooldown = ACTION_COOLDOWN_TICKS;
            return true;
        }

        depositCooldown = FULL_INVENTORY_CHECK_TICKS;
        return false;
    }

    private int dropFillerTerrainItems() {
        if (!(ctx.player().containerMenu instanceof InventoryMenu)) {
            return 0;
        }
        int dropped = 0;
        NonNullList<ItemStack> inv = ctx.player().getInventory().getNonEquipmentItems();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.get(i);
            if (!stack.isEmpty() && isDropFiller(stack.getItem())) {
                int slotId = i < 9 ? i + 36 : i;
                ctx.playerController().windowClick(ctx.player().inventoryMenu.containerId, slotId, 1, ContainerInput.THROW, ctx.player());
                dropped++;
                if (dropped >= 8) {
                    break;
                }
            }
        }
        return dropped;
    }

    private int dropMiningOverflowItems() {
        if (!(ctx.player().containerMenu instanceof InventoryMenu)) {
            return 0;
        }
        int dropped = 0;
        NonNullList<ItemStack> inv = ctx.player().getInventory().getNonEquipmentItems();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.get(i);
            if (!stack.isEmpty() && shouldDropForMiningOverflow(stack)) {
                int slotId = i < 9 ? i + 36 : i;
                ctx.playerController().windowClick(ctx.player().inventoryMenu.containerId, slotId, 1, ContainerInput.THROW, ctx.player());
                dropped++;
                if (dropped >= 18) {
                    break;
                }
            }
        }
        return dropped;
    }

    private boolean shouldDropForMiningOverflow(ItemStack stack) {
        if (stack.isEmpty() || shouldKeep(stack)) {
            return false;
        }
        Item item = stack.getItem();
        return isDropFiller(item) || item instanceof BlockItem;
    }

    private boolean isActiveMiningTask() {
        return clearBoxMin != null || hasTaskBreakSnapshot();
    }

    private boolean isNearby(BlockPos pos, int maxDistanceSq) {
        return ctx.playerFeet().distSqr(pos) <= maxDistanceSq;
    }

    private void selectNonChestHotbarSlot() {
        NonNullList<ItemStack> inv = ctx.player().getInventory().getNonEquipmentItems();
        for (int i = 0; i < Math.min(9, inv.size()); i++) {
            ItemStack stack = inv.get(i);
            if (!stack.isEmpty() && !isChestStack(stack)) {
                ctx.player().getInventory().setSelectedSlot(i);
                return;
            }
        }
    }

    private boolean handleTorchPlacement() {
        if (torchCooldown > 0 || !(ctx.player().containerMenu instanceof InventoryMenu)) {
            return false;
        }
        if (ctx.getSelectedBlock().isPresent()) {
            return false;
        }
        BlockPos torchPos = findTorchPlacementPos();
        if (torchPos == null) {
            torchCooldown = ACTION_COOLDOWN_TICKS;
            return false;
        }
        int torchSlot = findSlot(this::isTorchStack);
        if (torchSlot < 0 || !selectHotbarSlot(torchSlot)) {
            torchCooldown = ACTION_COOLDOWN_TICKS * 3;
            return false;
        }
        BlockPos support = torchPos.below();
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(support), Direction.UP, support, false);
        InteractionResult result = ctx.playerController().processRightClickBlock(ctx.player(), ctx.world(), InteractionHand.MAIN_HAND, hit);
        ctx.player().swing(InteractionHand.MAIN_HAND);
        torchCooldown = ACTION_COOLDOWN_TICKS * 4;
        if (result.consumesAction()) {
            logDirect("TaskBot: placed torch at " + torchPos + ".");
            return true;
        }
        return false;
    }

    private BlockPos findTorchPlacementPos() {
        if (findSlot(this::isTorchStack) < 0) {
            return null;
        }
        BlockPos playerPos = ctx.playerFeet();
        for (BlockPos pos : BlockPos.betweenClosed(playerPos.offset(-3, -1, -3), playerPos.offset(3, 1, 3))) {
            if (!isEmpty(pos) || !canPlaceOn(pos.below())) {
                continue;
            }
            int blockLight = ctx.world().getLightEngine().getLayerListener(LightLayer.BLOCK).getLightValue(pos);
            int rawLight = ctx.world().getLightEngine().getRawBrightness(pos, ctx.world().getSkyDarken());
            if (blockLight <= 6 && rawLight <= 7) {
                return pos.immutable();
            }
        }
        return null;
    }

    private void dumpInventoryIntoOpenContainer() {
        LocalPlayer player = ctx.player();
        if (depositCooldown > 0) {
            return;
        }
        NonNullList<ItemStack> inv = player.getInventory().getNonEquipmentItems();
        while (dumpSlot < inv.size() && shouldKeep(inv.get(dumpSlot))) {
            dumpSlot++;
        }
        if (dumpSlot >= inv.size()) {
            dumpSlot = 0;
            player.closeContainer();
            depositCooldown = ACTION_COOLDOWN_TICKS * 2;
            return;
        }
        int slotId = openContainerInventorySlotId(dumpSlot);
        ctx.playerController().windowClick(player.containerMenu.containerId, slotId, 0, ContainerInput.QUICK_MOVE, player);
        dumpSlot++;
        depositCooldown = 2;
    }

    private int openContainerInventorySlotId(int inventorySlot) {
        int containerSlots = chestSlotCount();
        if (containerSlots <= 0) {
            return inventorySlot < 9 ? inventorySlot + 36 : inventorySlot;
        }
        // ChestMenu layout: chest slots, then player main inventory, then hotbar.
        return containerSlots + (inventorySlot < 9 ? 27 + inventorySlot : inventorySlot - 9);
    }

    private boolean inventoryFull() {
        int emptySlots = 0;
        int depositableSlots = 0;
        for (ItemStack stack : ctx.player().getInventory().getNonEquipmentItems()) {
            if (stack.isEmpty()) {
                emptySlots++;
            } else if (!shouldKeep(stack)) {
                depositableSlots++;
            }
        }
        return depositableSlots > 0 && emptySlots <= 2;
    }

    private int findSlot(java.util.function.Predicate<ItemStack> predicate) {
        NonNullList<ItemStack> inv = ctx.player().getInventory().getNonEquipmentItems();
        for (int i = 0; i < inv.size(); i++) {
            if (!inv.get(i).isEmpty() && predicate.test(inv.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private boolean selectHotbarSlot(int slot) {
        if (slot < 0) {
            return false;
        }
        if (slot < 9) {
            ctx.player().getInventory().setSelectedSlot(slot);
            return true;
        }
        int hotbarSlot = ctx.player().getInventory().getSelectedSlot();
        int slotId = slot < 9 ? slot + 36 : slot;
        ctx.playerController().windowClick(ctx.player().inventoryMenu.containerId, slotId, hotbarSlot, ContainerInput.SWAP, ctx.player());
        ctx.player().getInventory().setSelectedSlot(hotbarSlot);
        return true;
    }

    private boolean useBlock(BlockPos pos) {
        return useBlock(pos, InteractionHand.MAIN_HAND);
    }

    private boolean useBlock(BlockPos pos, InteractionHand hand) {
        Direction side = Direction.UP;
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), side, pos, false);
        InteractionResult result = ctx.playerController().processRightClickBlock(ctx.player(), ctx.world(), hand, hit);
        ctx.player().swing(hand);
        return result.consumesAction();
    }

    private BlockPos findNearbyBed() {
        BlockPos playerPos = ctx.playerFeet();
        for (BlockPos pos : BlockPos.betweenClosed(playerPos.offset(-4, -2, -4), playerPos.offset(4, 2, 4))) {
            if (ctx.world().getBlockState(pos).getBlock() instanceof BedBlock) {
                return pos.immutable();
            }
        }
        return null;
    }

    private BlockPos findNearbyChest() {
        BlockPos playerPos = ctx.playerFeet();
        for (BlockPos pos : BlockPos.betweenClosed(playerPos.offset(-4, -2, -4), playerPos.offset(4, 2, 4))) {
            if (isUsableChest(pos)) {
                return pos.immutable();
            }
        }
        return null;
    }

    private boolean isUsableChest(BlockPos pos) {
        return ctx.world().getBlockState(pos).getBlock() instanceof ChestBlock;
    }

    private BlockPos findBedPlacementSupport() {
        BlockPos playerPos = ctx.playerFeet();
        for (BlockPos pos : BlockPos.betweenClosed(playerPos.offset(-2, -1, -2), playerPos.offset(2, 0, 2))) {
            BlockPos foot = pos.above();
            BlockPos head = foot.relative(ctx.player().getDirection());
            if (canPlaceOn(pos) && isEmpty(foot) && isEmpty(head)) {
                return pos.immutable();
            }
        }
        return null;
    }

    private BlockPos findChestPlacementSupport() {
        BlockPos playerPos = ctx.playerFeet();
        for (BlockPos pos : BlockPos.betweenClosed(playerPos.offset(-2, -1, -2), playerPos.offset(2, 0, 2))) {
            BlockPos place = pos.above();
            if (canPlaceOn(pos) && isEmpty(place)) {
                return pos.immutable();
            }
        }
        return null;
    }

    private boolean canPlaceOn(BlockPos pos) {
        BlockState state = ctx.world().getBlockState(pos);
        return !state.isAir() && state.isSolidRender();
    }

    private boolean isEmpty(BlockPos pos) {
        return ctx.world().getBlockState(pos).canBeReplaced();
    }

    private boolean isChestStack(ItemStack stack) {
        return stack.getItem() instanceof BlockItem && ((BlockItem) stack.getItem()).getBlock() == Blocks.CHEST;
    }

    private boolean isTorchStack(ItemStack stack) {
        Item item = stack.getItem();
        return item == Items.TORCH || item == Items.SOUL_TORCH;
    }

    private boolean shouldKeep(ItemStack stack) {
        if (stack.isEmpty()) {
            return true;
        }
        Item item = stack.getItem();
        if (item instanceof BedItem || item instanceof FireworkRocketItem || item == Items.ELYTRA || item == Items.TORCH || item == Items.SOUL_TORCH) {
            return true;
        }
        if (item == Items.BREAD || item == Items.COOKED_BEEF || item == Items.COOKED_PORKCHOP || item == Items.COOKED_CHICKEN || item == Items.COOKED_MUTTON || item == Items.COOKED_COD || item == Items.COOKED_SALMON) {
            return true;
        }
        if (isChestStack(stack)) {
            return true;
        }
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (item == ctx.player().getItemBySlot(slot).getItem()) {
                return true;
            }
        }
        return stack.getMaxDamage() > 0;
    }

    private boolean isFood(Item item) {
        return item == Items.BREAD
                || item == Items.COOKED_BEEF
                || item == Items.COOKED_PORKCHOP
                || item == Items.COOKED_CHICKEN
                || item == Items.COOKED_MUTTON
                || item == Items.COOKED_COD
                || item == Items.COOKED_SALMON;
    }
}
