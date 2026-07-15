package com.jbisb.hivetask;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

final class Cuboid {
    final int x1;
    final int y1;
    final int z1;
    final int x2;
    final int y2;
    final int z2;

    Cuboid(int x1, int y1, int z1, int x2, int y2, int z2) {
        this.x1 = Math.min(x1, x2);
        this.y1 = Math.min(y1, y2);
        this.z1 = Math.min(z1, z2);
        this.x2 = Math.max(x1, x2);
        this.y2 = Math.max(y1, y2);
        this.z2 = Math.max(z1, z2);
    }

    static Cuboid fromJson(JsonObject json) {
        if (json == null) throw new IllegalArgumentException("cuboid is required");
        return new Cuboid(
            requiredInt(json, "x1"), requiredInt(json, "y1"), requiredInt(json, "z1"),
            requiredInt(json, "x2"), requiredInt(json, "y2"), requiredInt(json, "z2")
        );
    }

    boolean contains(BlockPos pos) {
        return pos.getX() >= x1 && pos.getX() <= x2
            && pos.getY() >= y1 && pos.getY() <= y2
            && pos.getZ() >= z1 && pos.getZ() <= z2;
    }

    boolean intersects(Cuboid other) {
        return x1 <= other.x2 && x2 >= other.x1
            && y1 <= other.y2 && y2 >= other.y1
            && z1 <= other.z2 && z2 >= other.z1;
    }

    BlockPos center() {
        return new BlockPos(x1 + (x2 - x1) / 2, y1 + (y2 - y1) / 2, z1 + (z2 - z1) / 2);
    }

    long volume() {
        return (long) (x2 - x1 + 1) * (y2 - y1 + 1) * (z2 - z1 + 1);
    }

    List<Cuboid> cells(int horizontalSize, BlockPos nearestTo) {
        if (horizontalSize < 1) throw new IllegalArgumentException("horizontalSize must be positive");
        List<Cuboid> cells = new ArrayList<>();
        for (int x = x1; x <= x2; x += horizontalSize) {
            int endX = Math.min(x2, x + horizontalSize - 1);
            for (int z = z1; z <= z2; z += horizontalSize) {
                int endZ = Math.min(z2, z + horizontalSize - 1);
                cells.add(new Cuboid(x, y1, z, endX, y2, endZ));
            }
        }
        cells.sort(Comparator.comparingDouble(cell -> cell.horizontalDistanceSquared(nearestTo)));
        return cells;
    }

    double horizontalDistanceSquared(BlockPos pos) {
        BlockPos center = center();
        double dx = center.getX() - pos.getX();
        double dz = center.getZ() - pos.getZ();
        return dx * dx + dz * dz;
    }

    JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("x1", x1);
        json.addProperty("y1", y1);
        json.addProperty("z1", z1);
        json.addProperty("x2", x2);
        json.addProperty("y2", y2);
        json.addProperty("z2", z2);
        return json;
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "%d,%d,%d -> %d,%d,%d", x1, y1, z1, x2, y2, z2);
    }

    private static int requiredInt(JsonObject json, String key) {
        if (!json.has(key) || json.get(key).isJsonNull()) throw new IllegalArgumentException("cuboid." + key + " is required");
        return json.get(key).getAsInt();
    }
}
