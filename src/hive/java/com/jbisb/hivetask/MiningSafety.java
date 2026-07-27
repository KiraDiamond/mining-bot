package com.jbisb.hivetask;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ConcurrentHashMap;

public final class MiningSafety {
    private static final Minecraft MC = Minecraft.getInstance();
    private static final AtomicReference<Map<Long, Block>> ALLOWED_BREAKS = new AtomicReference<>(Map.of());
    private static final AtomicReference<BlockPos> BREAK_TARGET = new AtomicReference<>();
    private static final Map<Long, Block> PLACED_SUPPORTS = new ConcurrentHashMap<>();
    private static final AtomicLong DENIED_BREAKS = new AtomicLong();
    private static final Set<Block> PROTECTED_BLOCKS = buildProtectedBlocks();

    private static volatile boolean managedTask;
    private static volatile double breakReach = 3.0D;
    private static volatile Cuboid allowedPlacementCell;
    private static volatile boolean retainPlacedSupports;
    private static volatile BlockPos lastDeniedBreak;

    private MiningSafety() {}

    static void beginManagedTask() {
        managedTask = true;
        PLACED_SUPPORTS.clear();
        disarmBreaking();
    }

    public static void setBreakReach(double reach) {
        breakReach = Math.max(1.0D, reach);
    }

    public static double breakReach() {
        return breakReach;
    }

    static void endManagedTask() {
        disarmBreaking();
        PLACED_SUPPORTS.clear();
        managedTask = false;
    }

    static void armBreaking(Map<Long, Block> snapshot) {
        armBreaking(snapshot, null);
    }

    static void armBreaking(Map<Long, Block> snapshot, Cuboid placementCell) {
        ALLOWED_BREAKS.set(Map.copyOf(snapshot));
        allowedPlacementCell = placementCell;
        retainPlacedSupports = placementCell != null
            && placementCell.y1 == placementCell.y2
            && (placementCell.x1 != placementCell.x2 || placementCell.z1 != placementCell.z2);
        if (retainPlacedSupports) {
            // A repaired walking floor is permanent terrain, not scaffold. A
            // support may have been recorded while climbing into the same hole,
            // so untrack it before the next cell's cleanup can remove it.
            PLACED_SUPPORTS.keySet().removeIf(key -> placementCell.contains(BlockPos.of(key)));
        }
        BREAK_TARGET.set(null);
    }

    static void armSupportCleanup(Map<Long, Block> supports) {
        ALLOWED_BREAKS.set(Map.copyOf(supports));
        allowedPlacementCell = null;
        retainPlacedSupports = false;
        BREAK_TARGET.set(null);
    }

    static void disarmBreaking() {
        ALLOWED_BREAKS.set(Map.of());
        allowedPlacementCell = null;
        retainPlacedSupports = false;
        BREAK_TARGET.set(null);
    }

    static int allowedCount() {
        return ALLOWED_BREAKS.get().size();
    }

    static boolean hasReachableBreak() {
        for (long key : ALLOWED_BREAKS.get().keySet()) {
            if (isAllowedAndReachable(BlockPos.of(key))) return true;
        }
        return false;
    }

    static void replaceSnapshot(Map<Long, Block> snapshot) {
        ALLOWED_BREAKS.set(Map.copyOf(snapshot));
        BlockPos target = BREAK_TARGET.get();
        if (target != null && !snapshot.containsKey(target.asLong())) BREAK_TARGET.compareAndSet(target, null);
    }

    static boolean isProtected(Block block) {
        return PROTECTED_BLOCKS.contains(block);
    }

    static List<Block> protectedBlocks() {
        return new ArrayList<>(PROTECTED_BLOCKS);
    }

    static boolean isPlacedSupport(BlockPos pos, BlockState state) {
        Block expected = PLACED_SUPPORTS.get(pos.asLong());
        return expected != null && state.getBlock() == expected;
    }

    static Map<Long, Block> placedSupports(Cuboid cell) {
        Map<Long, Block> supports = new HashMap<>();
        if (MC.level == null || cell == null) return supports;
        PLACED_SUPPORTS.entrySet().removeIf(entry -> {
            BlockPos pos = BlockPos.of(entry.getKey());
            if (MC.level.getBlockState(pos).getBlock() != entry.getValue()) return true;
            if (cell.contains(pos)) supports.put(entry.getKey(), entry.getValue());
            return false;
        });
        return supports;
    }

    public static boolean canBreak(BlockPos pos) {
        if (!managedTask) return true;
        boolean allowed = isAllowedAndReachable(pos);
        if (allowed) {
            BlockPos target = lockedBreakTarget();
            if (target == null) {
                BREAK_TARGET.compareAndSet(null, pos.immutable());
                target = BREAK_TARGET.get();
            }
            allowed = pos.equals(target);
        }
        if (!allowed) {
            DENIED_BREAKS.incrementAndGet();
            lastDeniedBreak = pos.immutable();
        }
        return allowed;
    }

    public static boolean canPlanBreak(int x, int y, int z) {
        long key = BlockPos.asLong(x, y, z);
        return !managedTask
            || ALLOWED_BREAKS.get().containsKey(key);
    }

    public static boolean canPlanPlace(int x, int y, int z) {
        Cuboid cell = allowedPlacementCell;
        if (!managedTask) return true;
        return cell != null && cell.contains(x, y, z);
    }

    public static BlockPos lockedBreakTarget() {
        BlockPos target = BREAK_TARGET.get();
        if (target != null && !isAllowedAndReachable(target)) {
            BREAK_TARGET.compareAndSet(target, null);
            return null;
        }
        return target;
    }

    public static void releaseBreakTarget(BlockPos pos) {
        BlockPos target = BREAK_TARGET.get();
        if (target != null && (pos == null || target.equals(pos))) BREAK_TARGET.compareAndSet(target, null);
    }

    private static boolean isAllowedAndReachable(BlockPos pos) {
        if (MC.level == null || MC.player == null) return false;
        Block expected = ALLOWED_BREAKS.get().get(pos.asLong());
        if (expected == null || MC.level.getBlockState(pos).getBlock() != expected) return false;
        Vec3 eyes = MC.player.getEyePosition(1.0F);
        double nearestX = Math.max(pos.getX(), Math.min(eyes.x, pos.getX() + 1.0D));
        double nearestY = Math.max(pos.getY(), Math.min(eyes.y, pos.getY() + 1.0D));
        double nearestZ = Math.max(pos.getZ(), Math.min(eyes.z, pos.getZ() + 1.0D));
        return eyes.distanceToSqr(nearestX, nearestY, nearestZ) <= breakReach * breakReach;
    }

    public static boolean canUseItem(ItemStack held, BlockHitResult hit) {
        if (!managedTask || !(held.getItem() instanceof BlockItem)) return true;
        if (MC.level == null || hit == null) return false;
        BlockPos target = hit.getBlockPos();
        Block block = MC.level.getBlockState(target).getBlock();
        if (block instanceof DoorBlock || block instanceof FenceGateBlock || block instanceof TrapDoorBlock) return true;

        Block placedBlock = ((BlockItem) held.getItem()).getBlock();
        if (isProtected(placedBlock)) return false;
        BlockState targetState = MC.level.getBlockState(target);
        BlockPos placePos = targetState.canBeReplaced() ? target : target.relative(hit.getDirection());
        if (!canPlanPlace(placePos.getX(), placePos.getY(), placePos.getZ())) return false;
        if (!MC.level.getBlockState(placePos).canBeReplaced()) return false;
        if (!retainPlacedSupports) {
            PLACED_SUPPORTS.put(placePos.asLong(), placedBlock);
        }
        return true;
    }

    static long deniedBreakCount() {
        return DENIED_BREAKS.get();
    }

    static BlockPos lastDeniedBreak() {
        return lastDeniedBreak;
    }

    private static Set<Block> buildProtectedBlocks() {
        Set<Block> protectedBlocks = new HashSet<>(List.of(
            Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.BARREL, Blocks.HOPPER,
            Blocks.DISPENSER, Blocks.DROPPER, Blocks.FURNACE, Blocks.BLAST_FURNACE,
            Blocks.SMOKER, Blocks.CRAFTER, Blocks.TORCH, Blocks.WALL_TORCH,
            Blocks.SOUL_TORCH, Blocks.SOUL_WALL_TORCH, Blocks.REDSTONE_TORCH,
            Blocks.REDSTONE_WALL_TORCH, Blocks.LADDER
        ));
        for (Block block : BuiltInRegistries.BLOCK) {
            Identifier id = BuiltInRegistries.BLOCK.getKey(block);
            String path = id.getPath();
            if (path.endsWith("_bed")
                || path.endsWith("_shulker_box")
                || path.endsWith("_sign")
                || path.endsWith("_hanging_sign")) {
                protectedBlocks.add(block);
            }
        }
        return Set.copyOf(protectedBlocks);
    }
}
