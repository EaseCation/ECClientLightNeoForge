package net.easecation.ecclientlight.light;

/** Pure coordinate helpers shared by runtime batching and unit tests. */
final class RelightTargets {

    private RelightTargets() {
    }

    static long chunkKey(int chunkX, int chunkZ) {
        return chunkX & 0xFFFFFFFFL | (chunkZ & 0xFFFFFFFFL) << 32;
    }

    static long[] square(int chunkX, int chunkZ, int radius) {
        int diameter = radius * 2 + 1;
        long[] targets = new long[diameter * diameter];
        int index = 0;
        for (int offsetX = -radius; offsetX <= radius; offsetX++) {
            for (int offsetZ = -radius; offsetZ <= radius; offsetZ++) {
                targets[index++] = chunkKey(chunkX + offsetX, chunkZ + offsetZ);
            }
        }
        return targets;
    }
}
