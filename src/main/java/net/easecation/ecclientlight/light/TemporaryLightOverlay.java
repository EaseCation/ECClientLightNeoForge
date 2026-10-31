package net.easecation.ecclientlight.light;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToIntFunction;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;

/** 仅供渲染读取的临时亮度，不写入原版光照存储或后台重算输入。 */
final class TemporaryLightOverlay {

    private final ConcurrentHashMap<Long, ConcurrentHashMap<Integer, Values>> sections = new ConcurrentHashMap<>();

    void put(BlockPos pos, int block, int sky) {
        if (block < 0 && sky < 0) {
            remove(pos);
            return;
        }
        this.sections.computeIfAbsent(sectionKey(pos), key -> new ConcurrentHashMap<>())
            .put(localIndex(pos), new Values(block, sky));
    }

    void remove(BlockPos pos) {
        long key = sectionKey(pos);
        ConcurrentHashMap<Integer, Values> section = this.sections.get(key);
        if (section != null) {
            section.remove(localIndex(pos));
            if (section.isEmpty()) {
                this.sections.remove(key, section);
            }
        }
    }

    void removeColumn(long chunkKey) {
        int chunkX = (int)chunkKey;
        int chunkZ = (int)(chunkKey >>> 32);
        this.sections.keySet().removeIf(key -> SectionPos.x(key) == chunkX && SectionPos.z(key) == chunkZ);
    }

    void clear() {
        this.sections.clear();
    }

    boolean containsSection(long sectionKey) {
        return this.sections.containsKey(sectionKey);
    }

    int lightValue(LightLayer layer, BlockPos pos) {
        Map<Integer, Values> section = this.sections.get(sectionKey(pos));
        Values values = section == null ? null : section.get(localIndex(pos));
        return values == null ? -1 : values.forLayer(layer);
    }

    @Nullable
    DataLayer applyLayer(
        LightLayer layer,
        SectionPos sectionPos,
        @Nullable DataLayer original,
        ToIntFunction<BlockPos> committedValue
    ) {
        Map<Integer, Values> section = this.sections.get(sectionPos.asLong());
        if (section == null) {
            return original;
        }
        DataLayer result = null;
        for (Map.Entry<Integer, Values> entry : section.entrySet()) {
            int value = entry.getValue().forLayer(layer);
            if (value < 0) {
                continue;
            }
            if (result == null) {
                result = original == null ? materializeImplicitLayer(sectionPos, committedValue) : original.copy();
            }
            int index = entry.getKey();
            result.set(index & 15, index >>> 8, (index >>> 4) & 15, value);
        }
        return result == null ? original : result;
    }

    private static DataLayer materializeImplicitLayer(SectionPos sectionPos, ToIntFunction<BlockPos> committedValue) {
        // 原版可能用缺省层表达零方块光或来自上层的天空光；不能把缺省天空光整层填成零。
        DataLayer result = new DataLayer();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    cursor.set(sectionPos.minBlockX() + x, sectionPos.minBlockY() + y, sectionPos.minBlockZ() + z);
                    result.set(x, y, z, committedValue.applyAsInt(cursor));
                }
            }
        }
        return result;
    }

    private static long sectionKey(BlockPos pos) {
        return SectionPos.asLong(
            SectionPos.blockToSectionCoord(pos.getX()),
            SectionPos.blockToSectionCoord(pos.getY()),
            SectionPos.blockToSectionCoord(pos.getZ())
        );
    }

    private static int localIndex(BlockPos pos) {
        return ((pos.getY() & 15) << 8) | ((pos.getZ() & 15) << 4) | (pos.getX() & 15);
    }

    private record Values(int block, int sky) {
        private int forLayer(LightLayer layer) {
            return layer == LightLayer.BLOCK ? block : sky;
        }
    }
}
