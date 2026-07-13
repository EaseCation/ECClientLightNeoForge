package net.easecation.ecclientlight.light;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import javax.annotation.Nullable;
import net.easecation.ecclientlight.light.staging.StagingJob;
import net.easecation.ecclientlight.mixin.LevelLightEngineAccessor;
import net.easecation.ecclientlight.mixin.LightEngineInvoker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.lighting.BlockLightEngine;
import net.minecraft.world.level.lighting.ChunkSkyLightSources;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.lighting.LightEngine;
import net.minecraft.world.level.lighting.SkyLightEngine;
import net.minecraft.world.level.material.FluidState;

/** Builds complete BLOCK/SKY layers in a worker-owned vanilla light engine. */
final class StagingLightEngine {

    private static final long SNAPSHOT_BASE_BYTES = 1_024L;
    private static final long EMPTY_SECTION_BYTES = 64L;
    private static final long COPIED_SECTION_BYTES = 16L * 1_024L;
    private static final long EMISSION_BYTES = 32L;

    private StagingLightEngine() {
    }

    /** Starts a section-at-a-time immutable snapshot on the client thread. */
    static ChunkSnapshotCapture beginChunkCapture(ClientLevel level, LevelChunk chunk) {
        return new ChunkSnapshotCapture(level, chunk);
    }

    static StagingJob.Computation<Result> createComputation(Input input) {
        return new Computation(input);
    }

    private static boolean isEmptyShape(BlockState state) {
        return !state.canOcclude() || !state.useShapeForLightOcclusion();
    }

    record Input(
        int minY,
        int height,
        boolean hasSkyLight,
        int minLightSection,
        int lightSectionCount,
        List<Long> outputColumns,
        List<ChunkSnapshot> chunks
    ) {
        Input {
            if (height <= 0 || lightSectionCount <= 0) {
                throw new IllegalArgumentException("invalid staging light dimensions");
            }
            outputColumns = List.copyOf(outputColumns);
            chunks = List.copyOf(chunks);
        }
    }

    record Result(List<ColumnResult> columns) {
        Result {
            columns = List.copyOf(columns);
        }
    }

    record ColumnResult(
        long chunkKey,
        List<Boolean> emptySections,
        List<DataLayer> blockLayers,
        @Nullable List<DataLayer> skyLayers
    ) {
        ColumnResult {
            emptySections = List.copyOf(emptySections);
            blockLayers = List.copyOf(blockLayers);
            skyLayers = skyLayers == null ? null : List.copyOf(skyLayers);
        }
    }

    record ChunkSnapshot(
        long chunkKey,
        int minSectionY,
        List<BlockSectionSnapshot> sections,
        List<Emission> emissions,
        @Nullable int[] skySources,
        int highestSkySource
    ) {
        ChunkSnapshot {
            sections = List.copyOf(sections);
            emissions = List.copyOf(emissions);
            skySources = skySources == null ? null : skySources.clone();
        }

        @Nullable
        int[] copySkySources() {
            return this.skySources == null ? null : this.skySources.clone();
        }

        long estimatedBytes() {
            long bytes = SNAPSHOT_BASE_BYTES;
            for (BlockSectionSnapshot section : this.sections) {
                bytes = saturatingAdd(bytes, section.empty() ? EMPTY_SECTION_BYTES : COPIED_SECTION_BYTES);
            }
            bytes = saturatingAdd(bytes, (long)this.emissions.size() * EMISSION_BYTES);
            if (this.skySources != null) {
                bytes = saturatingAdd(bytes, (long)this.skySources.length * Integer.BYTES);
            }
            return bytes;
        }
    }

    record BlockSectionSnapshot(@Nullable PalettedContainer<BlockState> states, boolean empty) {
        BlockSectionSnapshot {
            if (empty && states != null) {
                throw new IllegalArgumentException("empty sections must not retain a block-state container");
            }
            if (!empty) {
                Objects.requireNonNull(states, "states");
            }
        }
    }

    record Emission(long blockPos, int level, boolean emptyShape) {
        Emission {
            if (level < 1 || level > 15) {
                throw new IllegalArgumentException("light emission must be in [1, 15]");
            }
        }
    }

    static final class ChunkSnapshotCapture {
        private static final int SKY_COLUMNS_PER_STEP = 16;

        private final LevelChunk chunk;
        private final int minSectionY;
        private final LevelChunkSection[] liveSections;
        private final List<BlockSectionSnapshot> sections;
        private final List<Emission> emissions = new ArrayList<>();
        @Nullable
        private final ChunkSkyLightSources liveSkySources;
        @Nullable
        private final int[] skySources;
        private int sectionIndex;
        private int skyIndex;
        private int highestSkySource = Integer.MIN_VALUE;
        private Phase phase = Phase.SECTIONS;

        private ChunkSnapshotCapture(ClientLevel level, LevelChunk chunk) {
            this.chunk = chunk;
            this.minSectionY = level.getMinSectionY();
            this.liveSections = chunk.getSections();
            this.sections = new ArrayList<>(this.liveSections.length);
            boolean hasSkyLight = level.dimensionType().hasSkyLight();
            this.liveSkySources = hasSkyLight ? chunk.getSkyLightSources() : null;
            this.skySources = hasSkyLight ? new int[16 * 16] : null;
        }

        /** Advances one small capture unit and returns true once the immutable snapshot is complete. */
        boolean advance() {
            if (this.phase == Phase.COMPLETE) {
                return true;
            }
            if (this.phase == Phase.SECTIONS) {
                if (this.sectionIndex < this.liveSections.length) {
                    LevelChunkSection section = this.liveSections[this.sectionIndex++];
                    boolean empty = section.hasOnlyAir();
                    this.sections.add(new BlockSectionSnapshot(empty ? null : section.getStates().copy(), empty));
                    return false;
                }
                this.phase = Phase.EMISSIONS;
            }
            if (this.phase == Phase.EMISSIONS) {
                this.chunk.findBlockLightSources((pos, state) -> {
                    int light = state.getLightEmission(this.chunk, pos);
                    if (light > 0) {
                        this.emissions.add(new Emission(pos.asLong(), light, isEmptyShape(state)));
                    }
                });
                this.phase = this.liveSkySources == null ? Phase.COMPLETE : Phase.SKY_SOURCES;
                return this.phase == Phase.COMPLETE;
            }
            if (this.phase == Phase.SKY_SOURCES) {
                int end = Math.min(this.skyIndex + SKY_COLUMNS_PER_STEP, this.skySources.length);
                while (this.skyIndex < end) {
                    int x = this.skyIndex & 15;
                    int z = this.skyIndex >>> 4;
                    this.skySources[this.skyIndex++] = this.liveSkySources.getLowestSourceY(x, z);
                }
                if (this.skyIndex == this.skySources.length) {
                    this.highestSkySource = this.liveSkySources.getHighestLowestSourceY();
                    this.phase = Phase.COMPLETE;
                    return true;
                }
            }
            return false;
        }

        ChunkSnapshot finish() {
            if (this.phase != Phase.COMPLETE) {
                throw new IllegalStateException("Chunk snapshot capture is incomplete");
            }
            return new ChunkSnapshot(
                this.chunk.getPos().toLong(),
                this.minSectionY,
                this.sections,
                this.emissions,
                this.skySources,
                this.highestSkySource
            );
        }

        private enum Phase {
            SECTIONS,
            EMISSIONS,
            SKY_SOURCES,
            COMPLETE
        }
    }

    private static final class Computation implements StagingJob.Computation<Result> {
        private final Input input;
        private final SnapshotWorld world;
        private final LevelLightEngine engine;
        @Nullable
        private final BlockLightEngine blockEngine;
        @Nullable
        private final SkyLightEngine skyEngine;
        private final List<SnapshotChunk> chunks;
        private final List<ColumnResult> results = new ArrayList<>();
        private Phase phase = Phase.INITIALIZE;
        private int index;

        private Computation(Input input) {
            this.input = input;
            this.world = new SnapshotWorld(input);
            this.engine = new LevelLightEngine(this.world, true, input.hasSkyLight());
            LevelLightEngineAccessor accessor = (LevelLightEngineAccessor)(Object)this.engine;
            LightEngine<?, ?> block = accessor.ecclientlight$getBlockEngine();
            LightEngine<?, ?> sky = accessor.ecclientlight$getSkyEngine();
            this.blockEngine = block == null ? null : (BlockLightEngine)block;
            this.skyEngine = sky == null ? null : (SkyLightEngine)sky;
            this.chunks = this.world.chunks();
        }

        @Override
        public StagingJob.Step<Result> runQuantum(StagingJob.Quantum quantum) {
            quantum.throwIfCancellationRequested();
            while (!quantum.shouldYield()) {
                switch (this.phase) {
                    case INITIALIZE -> this.initializeOneChunk();
                    case SEED -> this.seedOneChunk();
                    case PROPAGATE -> {
                        if (this.propagate(quantum)) {
                            return StagingJob.Step.continueWork();
                        }
                    }
                    case EXTRACT -> this.extractOneColumn();
                    case COMPLETE -> {
                        return StagingJob.Step.complete(new Result(this.results));
                    }
                }
                quantum.throwIfCancellationRequested();
            }
            return StagingJob.Step.continueWork();
        }

        private void initializeOneChunk() {
            if (this.index >= this.chunks.size()) {
                this.index = 0;
                this.phase = Phase.SEED;
                return;
            }
            SnapshotChunk chunk = this.chunks.get(this.index++);
            ChunkPos pos = chunk.pos();
            for (int sectionIndex = 0; sectionIndex < chunk.sectionCount(); sectionIndex++) {
                this.engine.updateSectionStatus(
                    SectionPos.of(pos, chunk.minSectionY() + sectionIndex),
                    chunk.section(sectionIndex).empty()
                );
            }
        }

        private void seedOneChunk() {
            if (this.index >= this.chunks.size()) {
                this.index = 0;
                this.phase = Phase.PROPAGATE;
                return;
            }
            SnapshotChunk chunk = this.chunks.get(this.index++);
            if (this.blockEngine != null) {
                this.blockEngine.setLightEnabled(chunk.pos(), true);
                LightEngineInvoker invoker = (LightEngineInvoker)(Object)this.blockEngine;
                for (Emission emission : chunk.emissions()) {
                    invoker.ecclientlight$enqueueIncrease(
                        emission.blockPos(),
                        LightEngine.QueueEntry.increaseLightFromEmission(
                            emission.level(),
                            emission.emptyShape()
                        )
                    );
                }
            }
            if (this.skyEngine != null) {
                this.skyEngine.propagateLightSources(chunk.pos());
            }
        }

        /** Returns true while propagation still owns the current worker quantum. */
        private boolean propagate(StagingJob.Quantum quantum) {
            if (!this.engine.hasLightWork()) {
                this.phase = Phase.EXTRACT;
                return false;
            }
            LocalLightWorkBudget.beginWorker(Math.max(1L, quantum.remainingNanos()));
            try {
                this.engine.runLightUpdates();
            } finally {
                LocalLightWorkBudget.end();
            }
            if (!this.engine.hasLightWork()) {
                this.phase = Phase.EXTRACT;
            }
            return true;
        }

        private void extractOneColumn() {
            if (this.index >= this.input.outputColumns().size()) {
                this.phase = Phase.COMPLETE;
                return;
            }
            long chunkKey = this.input.outputColumns().get(this.index++);
            if (this.world.chunk(chunkKey) == null) {
                return;
            }
            ChunkPos pos = new ChunkPos(chunkKey);
            SnapshotChunk snapshotChunk = Objects.requireNonNull(this.world.chunk(chunkKey));
            List<Boolean> emptySections = new ArrayList<>(snapshotChunk.sectionCount());
            for (int sectionIndex = 0; sectionIndex < snapshotChunk.sectionCount(); sectionIndex++) {
                emptySections.add(snapshotChunk.section(sectionIndex).empty());
            }
            List<DataLayer> block = new ArrayList<>(this.input.lightSectionCount());
            List<DataLayer> sky = this.input.hasSkyLight()
                ? new ArrayList<>(this.input.lightSectionCount())
                : null;
            for (int offset = 0; offset < this.input.lightSectionCount(); offset++) {
                SectionPos sectionPos = SectionPos.of(pos, this.input.minLightSection() + offset);
                DataLayer blockLayer = this.engine.getLayerListener(LightLayer.BLOCK).getDataLayerData(sectionPos);
                block.add(blockLayer == null ? new DataLayer() : blockLayer.copy());
                if (sky != null) {
                    DataLayer skyLayer = this.engine.getLayerListener(LightLayer.SKY).getDataLayerData(sectionPos);
                    sky.add(skyLayer == null ? this.materializeEffectiveSky(sectionPos) : skyLayer.copy());
                }
            }
            this.results.add(new ColumnResult(chunkKey, emptySections, block, sky));
        }

        private DataLayer materializeEffectiveSky(SectionPos sectionPos) {
            int baseX = sectionPos.minBlockX();
            int baseY = sectionPos.minBlockY();
            int baseZ = sectionPos.minBlockZ();
            BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos(baseX, baseY, baseZ);
            int first = this.engine.getLayerListener(LightLayer.SKY).getLightValue(cursor);
            DataLayer result = new DataLayer(first);
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        int value = this.engine.getLayerListener(LightLayer.SKY).getLightValue(
                            cursor.set(baseX + x, baseY + y, baseZ + z)
                        );
                        if (value != first) {
                            result.set(x, y, z, value);
                        }
                    }
                }
            }
            return result;
        }
    }

    private enum Phase {
        INITIALIZE,
        SEED,
        PROPAGATE,
        EXTRACT,
        COMPLETE
    }

    private static final class SnapshotWorld implements LightChunkGetter, BlockGetter {
        private final int minY;
        private final int height;
        private final Long2ObjectOpenHashMap<SnapshotChunk> chunksByKey = new Long2ObjectOpenHashMap<>();
        private final List<SnapshotChunk> chunks;

        private SnapshotWorld(Input input) {
            this.minY = input.minY();
            this.height = input.height();
            List<SnapshotChunk> ordered = new ArrayList<>(input.chunks().size());
            for (ChunkSnapshot snapshot : input.chunks()) {
                SnapshotChunk chunk = new SnapshotChunk(this, snapshot);
                this.chunksByKey.put(snapshot.chunkKey(), chunk);
                ordered.add(chunk);
            }
            this.chunks = List.copyOf(ordered);
        }

        private List<SnapshotChunk> chunks() {
            return this.chunks;
        }

        @Nullable
        private SnapshotChunk chunk(long chunkKey) {
            return this.chunksByKey.get(chunkKey);
        }

        @Override
        @Nullable
        public LightChunk getChunkForLighting(int chunkX, int chunkZ) {
            return this.chunksByKey.get(ChunkPos.asLong(chunkX, chunkZ));
        }

        @Override
        public BlockGetter getLevel() {
            return this;
        }

        @Override
        @Nullable
        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            SnapshotChunk chunk = this.chunksByKey.get(
                ChunkPos.asLong(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ()))
            );
            return chunk == null ? Blocks.BEDROCK.defaultBlockState() : chunk.getBlockState(pos);
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return this.getBlockState(pos).getFluidState();
        }

        @Override
        public int getHeight() {
            return this.height;
        }

        @Override
        public int getMinY() {
            return this.minY;
        }
    }

    private static final class SnapshotChunk implements LightChunk {
        private final SnapshotWorld world;
        private final ChunkPos pos;
        private final int minSectionY;
        private final List<BlockSectionSnapshot> sections;
        private final List<Emission> emissions;
        @Nullable
        private final ChunkSkyLightSources skyLightSources;

        private SnapshotChunk(SnapshotWorld world, ChunkSnapshot snapshot) {
            this.world = world;
            this.pos = new ChunkPos(snapshot.chunkKey());
            this.minSectionY = snapshot.minSectionY();
            this.sections = snapshot.sections();
            this.emissions = snapshot.emissions();
            int[] sources = snapshot.copySkySources();
            this.skyLightSources = sources == null
                ? null
                : new SnapshotSkyLightSources(world, sources, snapshot.highestSkySource());
        }

        private ChunkPos pos() {
            return this.pos;
        }

        private int minSectionY() {
            return this.minSectionY;
        }

        private int sectionCount() {
            return this.sections.size();
        }

        private BlockSectionSnapshot section(int index) {
            return this.sections.get(index);
        }

        private List<Emission> emissions() {
            return this.emissions;
        }

        @Override
        public void findBlockLightSources(BiConsumer<BlockPos, BlockState> consumer) {
            for (Emission emission : this.emissions) {
                BlockPos pos = BlockPos.of(emission.blockPos());
                consumer.accept(pos, this.getBlockState(pos));
            }
        }

        @Override
        public ChunkSkyLightSources getSkyLightSources() {
            if (this.skyLightSources == null) {
                throw new IllegalStateException("sky sources requested in a dimension without skylight");
            }
            return this.skyLightSources;
        }

        @Override
        @Nullable
        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            int index = SectionPos.blockToSectionCoord(pos.getY()) - this.minSectionY;
            if (index < 0 || index >= this.sections.size()) {
                return Blocks.AIR.defaultBlockState();
            }
            BlockSectionSnapshot section = this.sections.get(index);
            if (section.empty()) {
                return Blocks.AIR.defaultBlockState();
            }
            return Objects.requireNonNull(section.states()).get(
                SectionPos.sectionRelative(pos.getX()),
                SectionPos.sectionRelative(pos.getY()),
                SectionPos.sectionRelative(pos.getZ())
            );
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return this.getBlockState(pos).getFluidState();
        }

        @Override
        public int getHeight() {
            return this.world.getHeight();
        }

        @Override
        public int getMinY() {
            return this.world.getMinY();
        }
    }

    private static long saturatingAdd(long left, long right) {
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    private static final class SnapshotSkyLightSources extends ChunkSkyLightSources {
        private final int[] sources;
        private final int highest;

        private SnapshotSkyLightSources(SnapshotWorld world, int[] sources, int highest) {
            super(world);
            this.sources = sources;
            this.highest = highest;
        }

        @Override
        public int getLowestSourceY(int x, int z) {
            return this.sources[x + z * 16];
        }

        @Override
        public int getHighestLowestSourceY() {
            return this.highest;
        }

        @Override
        public boolean update(BlockGetter level, int x, int y, int z) {
            return false;
        }
    }
}
