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
    private static final Map<Long, Block> PLACED_SUPPORTS = new ConcurrentHashMap<>();
    private static final AtomicLong DENIED_BREAKS = new AtomicLong();
    private static final Set<Block> PROTECTED_BLOCKS = buildProtectedBlocks();

    private static volatile boolean managedTask;
    private static volatile Cuboid allowedPlacementCell;
    private static volatile BlockPos lastDeniedBreak;

    private MiningSafety() {}

    static void beginManagedTask() {
        managedTask = true;
        PLACED_SUPPORTS.clear();
        disarmBreaking();
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
    }

    static void disarmBreaking() {
        ALLOWED_BREAKS.set(Map.of());
        allowedPlacementCell = null;
    }

    static int allowedCount() {
        return ALLOWED_BREAKS.get().size();
    }

    static void replaceSnapshot(Map<Long, Block> snapshot) {
        ALLOWED_BREAKS.set(Map.copyOf(snapshot));
    }

    static boolean isProtected(Block block) {
        return PROTECTED_BLOCKS.contains(block);
    }

    static List<Block> protectedBlocks() {
        return new ArrayList<>(PROTECTED_BLOCKS);
    }

    public static boolean canBreak(BlockPos pos) {
        if (!managedTask) return true;
        long key = pos.asLong();
        Block expected = ALLOWED_BREAKS.get().get(key);
        Cuboid cell = allowedPlacementCell;
        if (expected == null && cell != null && cell.contains(pos)) expected = PLACED_SUPPORTS.get(key);
        boolean allowed = expected != null && MC.level != null && MC.level.getBlockState(pos).getBlock() == expected;
        if (!allowed) {
            DENIED_BREAKS.incrementAndGet();
            lastDeniedBreak = pos.immutable();
        }
        return allowed;
    }

    public static boolean canPlanBreak(int x, int y, int z) {
        long key = BlockPos.asLong(x, y, z);
        Cuboid cell = allowedPlacementCell;
        return !managedTask
            || ALLOWED_BREAKS.get().containsKey(key)
            || (cell != null && cell.contains(x, y, z) && PLACED_SUPPORTS.containsKey(key));
    }

    public static boolean canPlanPlace(int x, int y, int z) {
        Cuboid cell = allowedPlacementCell;
        return !managedTask || (cell != null && cell.contains(x, y, z));
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
        PLACED_SUPPORTS.put(placePos.asLong(), placedBlock);
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
