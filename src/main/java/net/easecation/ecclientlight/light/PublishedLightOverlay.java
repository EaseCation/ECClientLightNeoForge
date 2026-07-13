package net.easecation.ecclientlight.light;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;

/**
 * Lock-free render view of the last fully published light epoch.
 *
 * <p>All mutations are client-thread-only. Readers, including Sodium workers, only observe an
 * immutable {@link View} through one volatile load.
 */
public final class PublishedLightOverlay {

    static final long DEFAULT_MAX_BYTES = 16L * 1024L * 1024L;
    static final long ABSOLUTE_MAX_BYTES = 64L * 1024L * 1024L;
    private static final long SECTION_METADATA_BYTES = 160L;
    private static final long LAYER_REFERENCE_BYTES = 32L;
    private static final long EPOCH_METADATA_BYTES = 128L;
    private static final int MAX_CAPTURE_EXPECTED_SIZE = 4096;
    private static final int MAX_CAPTURE_SECTIONS = 8192;
    private static final int MAX_BATCH_EPOCHS = 256;

    private final long maxBytes;
    private final boolean hasSkyLight;
    private final Long2ObjectOpenHashMap<ActiveSection> activeSections = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<ActiveColumn> activeColumns = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<ActiveEpoch> activeEpochs = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<ColumnSnapshot> materializedColumns = new Long2ObjectOpenHashMap<>();
    private volatile View published = View.empty(false, 0L);
    private long lastAcceptedEpoch;
    private long retainedBytes;
    private long viewVersion;

    public PublishedLightOverlay(long maxBytes, boolean hasSkyLight) {
        this.maxBytes = Math.clamp(maxBytes, 0L, ABSOLUTE_MAX_BYTES);
        this.hasSkyLight = hasSkyLight;
        this.published = View.empty(hasSkyLight, 0L);
    }

    static long resolveMaxBytes(@Nullable Long configured) {
        return configured == null ? DEFAULT_MAX_BYTES : Math.clamp(configured, 0L, ABSOLUTE_MAX_BYTES);
    }

    public static Capture newCapture(int expectedSections) {
        return new Capture(Math.clamp(expectedSections, 0, MAX_CAPTURE_EXPECTED_SIZE));
    }

    /** Opens an epoch atomically. A rejected epoch leaves the currently published view unchanged. */
    public OpenResult tryOpen(long epoch, Capture capture) {
        return this.tryOpenBatch(epoch, List.of(capture));
    }

    /** Opens consecutive epochs with one immutable view publication. */
    public OpenResult tryOpenBatch(long firstEpoch, List<Capture> captures) {
        Objects.requireNonNull(captures, "captures");
        if (captures.isEmpty() || captures.size() > MAX_BATCH_EPOCHS) {
            return new OpenResult(false, Rejection.INVALID_EPOCH, 0L, 0, 0);
        }
        long lastEpoch;
        try {
            lastEpoch = Math.addExact(firstEpoch, captures.size() - 1L);
        } catch (ArithmeticException ignored) {
            return new OpenResult(false, Rejection.INVALID_EPOCH, 0L, 0, 0);
        }
        if (firstEpoch <= this.lastAcceptedEpoch || lastEpoch < firstEpoch) {
            return new OpenResult(false, Rejection.INVALID_EPOCH, 0L, 0, 0);
        }
        for (int index = 0; index < captures.size(); index++) {
            long epoch = firstEpoch + index;
            if (this.activeEpochs.containsKey(epoch)) {
                return new OpenResult(false, Rejection.INVALID_EPOCH, 0L, 0, 0);
            }
        }

        long requiredBytes = 0L;
        int addedSections = 0;
        int sharedSections = 0;
        Set<Long> newlyAddedSections = new HashSet<>();
        for (Capture capture : captures) {
            Objects.requireNonNull(capture, "capture");
            requiredBytes = saturatingAdd(requiredBytes, epochBytes(capture.sections.size()));
            for (Long2ObjectMap.Entry<SectionSnapshot> entry : capture.sections.long2ObjectEntrySet()) {
                long sectionKey = entry.getLongKey();
                if (this.activeSections.containsKey(sectionKey) || !newlyAddedSections.add(sectionKey)) {
                    sharedSections++;
                } else {
                    addedSections++;
                }
                requiredBytes = saturatingAdd(requiredBytes, sectionBytes(entry.getValue()));
            }
        }

        if (requiredBytes > this.maxBytes - Math.min(this.maxBytes, this.retainedBytes)) {
            return new OpenResult(
                false,
                Rejection.BUDGET_EXCEEDED,
                requiredBytes,
                addedSections,
                sharedSections
            );
        }

        Set<Long> visibleChangedColumns = new HashSet<>();
        for (int index = 0; index < captures.size(); index++) {
            long epoch = firstEpoch + index;
            Capture capture = captures.get(index);
            long[] sectionKeys = capture.sections.keySet().toLongArray();
            for (long sectionKey : sectionKeys) {
                ActiveSection active = this.activeSections.get(sectionKey);
                if (active == null) {
                    active = new ActiveSection();
                    this.activeSections.put(sectionKey, active);
                    long columnKey = SectionPos.getZeroNode(sectionKey);
                    this.activeColumns.computeIfAbsent(columnKey, ignored -> new ActiveColumn())
                        .put(SectionPos.y(sectionKey), active);
                    visibleChangedColumns.add(columnKey);
                }
                SectionSnapshot snapshot = capture.sections.get(sectionKey);
                active.add(epoch, snapshot, sectionBytes(snapshot));
            }
            this.activeEpochs.put(epoch, new ActiveEpoch(sectionKeys, epochBytes(sectionKeys.length)));
        }
        this.retainedBytes = saturatingAdd(this.retainedBytes, requiredBytes);
        this.lastAcceptedEpoch = lastEpoch;
        if (!visibleChangedColumns.isEmpty()) {
            this.publishColumns(visibleChangedColumns);
        }
        return new OpenResult(true, Rejection.NONE, requiredBytes, addedSections, sharedSections);
    }

    /** Releases one epoch. Shared sections retain the first snapshot until their final owner exits. */
    public ReleaseResult release(long epoch) {
        ReleaseMutation mutation = this.releaseWithoutPublishing(epoch);
        if (mutation == null) {
            return new ReleaseResult(false, 0L, 0);
        }
        if (!mutation.visibleChangedColumns.isEmpty()) {
            this.publishColumns(mutation.visibleChangedColumns);
        }
        return new ReleaseResult(true, mutation.releasedBytes, mutation.removedSections);
    }

    /** Releases related epochs with one volatile view publication. Missing epochs reject atomically. */
    public ReleaseResult releaseAll(long... epochs) {
        Set<Long> unique = new HashSet<>();
        for (long epoch : epochs) {
            if (!unique.add(epoch) || !this.activeEpochs.containsKey(epoch)) {
                return new ReleaseResult(false, 0L, 0);
            }
        }

        long releasedBytes = 0L;
        int removedSections = 0;
        Set<Long> visibleChangedColumns = new HashSet<>();
        for (long epoch : epochs) {
            ReleaseMutation mutation = this.releaseWithoutPublishing(epoch);
            if (mutation == null) {
                throw new IllegalStateException("Published light overlay epoch vanished during atomic release");
            }
            releasedBytes = saturatingAdd(releasedBytes, mutation.releasedBytes);
            removedSections += mutation.removedSections;
            visibleChangedColumns.addAll(mutation.visibleChangedColumns);
        }
        if (!visibleChangedColumns.isEmpty()) {
            this.publishColumns(visibleChangedColumns);
        }
        return new ReleaseResult(true, releasedBytes, removedSections);
    }

    @Nullable
    private ReleaseMutation releaseWithoutPublishing(long epoch) {
        ActiveEpoch activeEpoch = this.activeEpochs.remove(epoch);
        if (activeEpoch == null) {
            return null;
        }

        long releasedBytes = activeEpoch.reservedBytes;
        int removedSections = 0;
        Set<Long> visibleChangedColumns = new HashSet<>();
        for (long sectionKey : activeEpoch.sectionKeys) {
            ActiveSection active = this.activeSections.get(sectionKey);
            if (active == null) {
                throw new IllegalStateException("Published light overlay reference accounting underflow");
            }
            boolean wasVisible = active.firstEpoch() == epoch;
            OwnedSnapshot removed = active.remove(epoch);
            if (removed == null) {
                throw new IllegalStateException("Published light overlay owner disappeared during release");
            }
            releasedBytes = saturatingAdd(releasedBytes, removed.reservedBytes);
            if (active.isEmpty()) {
                if (!this.activeSections.remove(sectionKey, active)) {
                    throw new IllegalStateException("Published light overlay section disappeared during release");
                }
                long columnKey = SectionPos.getZeroNode(sectionKey);
                ActiveColumn column = this.activeColumns.get(columnKey);
                if (column == null || column.remove(SectionPos.y(sectionKey)) != active) {
                    throw new IllegalStateException("Published light overlay column reference disappeared during release");
                }
                if (column.isEmpty()) {
                    this.activeColumns.remove(columnKey);
                }
                removedSections++;
            }
            if (wasVisible) {
                visibleChangedColumns.add(SectionPos.getZeroNode(sectionKey));
            }
        }
        if (releasedBytes > this.retainedBytes) {
            throw new IllegalStateException("Published light overlay byte accounting underflow");
        }
        this.retainedBytes -= releasedBytes;
        return new ReleaseMutation(releasedBytes, removedSections, Set.copyOf(visibleChangedColumns));
    }

    public View publishedView() {
        return this.published;
    }

    public void clear() {
        boolean hadPublishedSections = !this.activeSections.isEmpty();
        this.activeSections.clear();
        this.activeColumns.clear();
        this.activeEpochs.clear();
        this.materializedColumns.clear();
        this.retainedBytes = 0L;
        if (hadPublishedSections) {
            this.published = View.empty(this.hasSkyLight, ++this.viewVersion);
        }
    }

    public boolean isEmpty() {
        return this.activeEpochs.isEmpty() && this.activeSections.isEmpty();
    }

    public long retainedBytes() {
        return this.retainedBytes;
    }

    public long maxBytes() {
        return this.maxBytes;
    }

    private void publishColumns(Set<Long> changedColumns) {
        for (long columnKey : changedColumns) {
            ActiveColumn active = this.activeColumns.get(columnKey);
            if (active == null) {
                this.materializedColumns.remove(columnKey);
            } else {
                this.materializedColumns.put(columnKey, active.snapshot());
            }
        }
        this.published = new View(
            new Long2ObjectOpenHashMap<>(this.materializedColumns),
            this.hasSkyLight,
            ++this.viewVersion,
            this.activeSections.size()
        );
    }

    static long sectionBytes(SectionSnapshot snapshot) {
        return saturatingAdd(
            SECTION_METADATA_BYTES,
            saturatingAdd(layerBytes(snapshot.block), layerBytes(snapshot.sky))
        );
    }

    static long capturedSectionBytes(Capture capture, long sectionKey) {
        SectionSnapshot snapshot = capture.sections.get(sectionKey);
        if (snapshot == null) {
            throw new IllegalArgumentException("capture does not contain section " + sectionKey);
        }
        return sectionBytes(snapshot);
    }

    static long epochBytes(int sectionCount) {
        return saturatingAdd(EPOCH_METADATA_BYTES, saturatingMultiply(sectionCount, Long.BYTES));
    }

    private static long layerBytes(@Nullable DataLayer layer) {
        if (layer == null) {
            return 0L;
        }
        return LAYER_REFERENCE_BYTES + (layer.isDefinitelyHomogenous() ? 0L : DataLayer.SIZE);
    }

    static long saturatingAdd(long left, long right) {
        if (left == Long.MAX_VALUE || right == Long.MAX_VALUE || Long.MAX_VALUE - left < right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    static long saturatingMultiply(long left, long right) {
        if (left == 0L || right == 0L) {
            return 0L;
        }
        return left > Long.MAX_VALUE / right ? Long.MAX_VALUE : left * right;
    }

    public enum Rejection {
        NONE,
        INVALID_EPOCH,
        BUDGET_EXCEEDED
    }

    public record OpenResult(
        boolean accepted,
        Rejection rejection,
        long requiredBytes,
        int addedSections,
        int sharedSections
    ) {
    }

    public record ReleaseResult(boolean released, long releasedBytes, int removedSections) {
    }

    public static final class Capture {
        private final Long2ObjectOpenHashMap<SectionSnapshot> sections;

        private Capture(int expectedSections) {
            this.sections = new Long2ObjectOpenHashMap<>(expectedSections);
        }

        public void put(long sectionKey, @Nullable DataLayer block, @Nullable DataLayer sky) {
            if (!this.sections.containsKey(sectionKey) && this.sections.size() >= MAX_CAPTURE_SECTIONS) {
                throw new IllegalStateException("Published light capture exceeds the bounded section limit");
            }
            this.sections.putIfAbsent(sectionKey, new SectionSnapshot(block, sky));
        }

        public int size() {
            return this.sections.size();
        }

    }

    public static final class View {
        public static final int NOT_PROTECTED = -1;
        private final Long2ObjectOpenHashMap<ColumnSnapshot> columns;
        private final boolean hasSkyLight;
        private final long version;
        private final int sectionCount;

        private View(
            Long2ObjectOpenHashMap<ColumnSnapshot> columns,
            boolean hasSkyLight,
            long version,
            int sectionCount
        ) {
            this.columns = columns;
            this.hasSkyLight = hasSkyLight;
            this.version = version;
            this.sectionCount = sectionCount;
        }

        private static View empty(boolean hasSkyLight, long version) {
            return new View(new Long2ObjectOpenHashMap<>(), hasSkyLight, version, 0);
        }

        @Nullable
        public SectionSnapshot section(long sectionKey) {
            ColumnSnapshot column = this.columns.get(SectionPos.getZeroNode(sectionKey));
            return column == null ? null : column.section(SectionPos.y(sectionKey));
        }

        public int lightValue(LightLayer layer, long packedBlockPos) {
            long sectionKey = SectionPos.blockToSection(packedBlockPos);
            ColumnSnapshot column = this.columns.get(SectionPos.getZeroNode(sectionKey));
            if (column == null) {
                return NOT_PROTECTED;
            }
            SectionSnapshot snapshot = column.section(SectionPos.y(sectionKey));
            if (snapshot == null) {
                return NOT_PROTECTED;
            }
            if (layer == LightLayer.SKY && !this.hasSkyLight) {
                return 0;
            }
            DataLayer data = snapshot.dataLayer(layer);
            if (data != null) {
                return data.get(
                    SectionPos.sectionRelative(BlockPos.getX(packedBlockPos)),
                    SectionPos.sectionRelative(BlockPos.getY(packedBlockPos)),
                    SectionPos.sectionRelative(BlockPos.getZ(packedBlockPos))
                );
            }
            if (layer == LightLayer.BLOCK) {
                return 0;
            }

            int sectionY = SectionPos.y(sectionKey);
            int relativeX = SectionPos.sectionRelative(BlockPos.getX(packedBlockPos));
            int relativeZ = SectionPos.sectionRelative(BlockPos.getZ(packedBlockPos));
            for (int y = sectionY + 1; y <= column.highestSection(); y++) {
                SectionSnapshot upper = column.section(y);
                if (upper != null && upper.sky != null) {
                    return upper.sky.get(relativeX, 0, relativeZ);
                }
            }
            return 15;
        }

        public int size() {
            return this.sectionCount;
        }

        public boolean protectsColumn(long zeroNode) {
            return this.columns.containsKey(zeroNode);
        }

        public long version() {
            return this.version;
        }
    }

    public static final class SectionSnapshot {
        @Nullable
        private final DataLayer block;
        @Nullable
        private final DataLayer sky;

        private SectionSnapshot(@Nullable DataLayer block, @Nullable DataLayer sky) {
            this.block = block;
            this.sky = sky;
        }

        @Nullable
        public DataLayer dataLayer(LightLayer layer) {
            return layer == LightLayer.BLOCK ? this.block : this.sky;
        }
    }

    private record ColumnSnapshot(Int2ObjectOpenHashMap<SectionSnapshot> sections, int highestSection) {

        @Nullable
        private SectionSnapshot section(int sectionY) {
            return this.sections.get(sectionY);
        }
    }

    private static final class ActiveColumn {
        private final TreeMap<Integer, ActiveSection> sections = new TreeMap<>();

        private void put(int sectionY, ActiveSection section) {
            if (this.sections.putIfAbsent(sectionY, section) != null) {
                throw new IllegalStateException("Published light overlay column owns one section twice");
            }
        }

        @Nullable
        private ActiveSection remove(int sectionY) {
            return this.sections.remove(sectionY);
        }

        private boolean isEmpty() {
            return this.sections.isEmpty();
        }

        private ColumnSnapshot snapshot() {
            Int2ObjectOpenHashMap<SectionSnapshot> snapshots = new Int2ObjectOpenHashMap<>(this.sections.size());
            this.sections.forEach((sectionY, active) -> snapshots.put(sectionY.intValue(), active.visibleSnapshot()));
            return new ColumnSnapshot(snapshots, this.sections.lastKey());
        }
    }

    private static final class ActiveSection {
        private final LinkedHashMap<Long, OwnedSnapshot> owners = new LinkedHashMap<>();

        private void add(long epoch, SectionSnapshot snapshot, long reservedBytes) {
            if (this.owners.putIfAbsent(epoch, new OwnedSnapshot(snapshot, reservedBytes)) != null) {
                throw new IllegalStateException("Published light overlay epoch owns one section twice");
            }
        }

        @Nullable
        private OwnedSnapshot remove(long epoch) {
            return this.owners.remove(epoch);
        }

        private long firstEpoch() {
            return this.owners.keySet().iterator().next();
        }

        private SectionSnapshot visibleSnapshot() {
            return this.owners.values().iterator().next().snapshot;
        }

        private boolean isEmpty() {
            return this.owners.isEmpty();
        }
    }

    private record OwnedSnapshot(SectionSnapshot snapshot, long reservedBytes) {
    }

    private record ReleaseMutation(long releasedBytes, int removedSections, Set<Long> visibleChangedColumns) {
    }

    private record ActiveEpoch(long[] sectionKeys, long reservedBytes) {
        private ActiveEpoch {
            sectionKeys = Arrays.copyOf(sectionKeys, sectionKeys.length);
        }
    }
}
