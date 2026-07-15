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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class MiningSafety {
    private static final Minecraft MC = Minecraft.getInstance();
    private static final AtomicReference<Map<Long, Block>> ALLOWED_BREAKS = new AtomicReference<>(Map.of());
    private static final AtomicLong DENIED_BREAKS = new AtomicLong();
    private static final Set<Block> PROTECTED_BLOCKS = buildProtectedBlocks();

    private static volatile boolean managedTask;
    private static volatile BlockPos lastDeniedBreak;

    private MiningSafety() {}

    static void beginManagedTask() {
        managedTask = true;
        disarmBreaking();
    }

    static void endManagedTask() {
        disarmBreaking();
        managedTask = false;
    }

    static void armBreaking(Map<Long, Block> snapshot) {
        ALLOWED_BREAKS.set(Map.copyOf(snapshot));
    }

    static void disarmBreaking() {
        ALLOWED_BREAKS.set(Map.of());
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
        Block expected = ALLOWED_BREAKS.get().get(pos.asLong());
        boolean allowed = expected != null && MC.level != null && MC.level.getBlockState(pos).getBlock() == expected;
        if (!allowed) {
            DENIED_BREAKS.incrementAndGet();
            lastDeniedBreak = pos.immutable();
        }
        return allowed;
    }

    public static boolean canPlanBreak(int x, int y, int z) {
        return !managedTask || ALLOWED_BREAKS.get().containsKey(BlockPos.asLong(x, y, z));
    }

    public static boolean canUseItem(ItemStack held, BlockPos target) {
        if (!managedTask || !(held.getItem() instanceof BlockItem)) return true;
        if (MC.level == null || target == null) return false;
        Block block = MC.level.getBlockState(target).getBlock();
        return block instanceof DoorBlock || block instanceof FenceGateBlock || block instanceof TrapDoorBlock;
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
