package com.centers25.naturalregen;

import org.bukkit.World;

/** An inclusive block box inside one world, clamped to the world's build height. */
public record Selection(World world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

    public static Selection of(World world, int x1, int y1, int z1, int x2, int y2, int z2) {
        int minY = Math.max(Math.min(y1, y2), world.getMinHeight());
        int maxY = Math.min(Math.max(y1, y2), world.getMaxHeight() - 1);
        return new Selection(world,
                Math.min(x1, x2), minY, Math.min(z1, z2),
                Math.max(x1, x2), maxY, Math.max(z1, z2));
    }

    public Selection fullHeight() {
        return new Selection(world, minX, world.getMinHeight(), minZ, maxX, world.getMaxHeight() - 1, maxZ);
    }

    public long sizeX() { return (long) maxX - minX + 1; }
    public long sizeY() { return (long) maxY - minY + 1; }
    public long sizeZ() { return (long) maxZ - minZ + 1; }
    public long volume() { return sizeX() * sizeY() * sizeZ(); }

    public int minChunkX() { return minX >> 4; }
    public int maxChunkX() { return maxX >> 4; }
    public int minChunkZ() { return minZ >> 4; }
    public int maxChunkZ() { return maxZ >> 4; }

    public long chunkCount() {
        return ((long) maxChunkX() - minChunkX() + 1) * ((long) maxChunkZ() - minChunkZ() + 1);
    }

    public boolean contains(double x, double y, double z) {
        return x >= minX && x < maxX + 1 && y >= minY && y < maxY + 1 && z >= minZ && z < maxZ + 1;
    }

    /** Chunk keys (x in high 32 bits, z in low 32 bits) in a snake order so neighbours load together. */
    public long[] chunkKeys() {
        long count = chunkCount();
        if (count > Integer.MAX_VALUE) throw new IllegalStateException("Selection is too large");
        long[] keys = new long[(int) count];
        int i = 0;
        boolean forward = true;
        for (int cx = minChunkX(); cx <= maxChunkX(); cx++) {
            if (forward) {
                for (int cz = minChunkZ(); cz <= maxChunkZ(); cz++) keys[i++] = key(cx, cz);
            } else {
                for (int cz = maxChunkZ(); cz >= minChunkZ(); cz--) keys[i++] = key(cx, cz);
            }
            forward = !forward;
        }
        return keys;
    }

    public static long key(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    public static int keyX(long key) { return (int) (key >> 32); }
    public static int keyZ(long key) { return (int) key; }

    public String describe() {
        return world.getName() + " (" + minX + ", " + minY + ", " + minZ + ") → (" + maxX + ", " + maxY + ", " + maxZ + ")";
    }
}
