package net.easecation.ecclientlight.light;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongPredicate;

/**
 * Main-thread-only collector for immutable regional relight batches.
 *
 * <p>At most one batch is active while one generation-coalescing map collects the next batch.
 * Work arriving after a freeze can therefore never mutate the active transaction.
 */
final class FrozenRegionalRelightScheduler {

    private static final int AFFECTED_RADIUS = 1;
    private static final int SOURCE_RADIUS = 2;
    private static final int MIN_AFFECTED_COLUMNS = 9;
    private static final int MIN_SOURCE_COLUMNS = 25;

    private final Limits limits;
    private final Map<Long, PendingCenter> nextCenters = new HashMap<>();
    private ActiveBatch active;
    private long nextBatchId;

    private long offers;
    private long centersAdded;
    private long generationsSuperseded;
    private long duplicateOffers;
    private long staleOffers;
    private long capacityRejections;
    private long batchesFrozen;
    private long batchesCompleted;
    private long batchesAborted;
    private long centersFrozen;
    private long nextCentersCancelled;
    private long centersCleared;
    private long centersSatisfied;
    private long rawSourceReferences;
    private long uniqueSourceColumns;
    private int nextHighWaterMark;

    FrozenRegionalRelightScheduler(Limits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    OfferResult offer(long centerKey, long generation, long queuedAt) {
        if (generation <= 0L) {
            throw new IllegalArgumentException("generation must be positive");
        }
        if (queuedAt < 0L) {
            throw new IllegalArgumentException("queuedAt must be non-negative");
        }
        this.offers++;

        PendingCenter pending = this.nextCenters.get(centerKey);
        if (pending != null) {
            if (generation == pending.generation()) {
                this.duplicateOffers++;
                return OfferResult.DUPLICATE;
            }
            if (generation < pending.generation()) {
                this.staleOffers++;
                return OfferResult.STALE;
            }
            this.nextCenters.put(
                centerKey,
                new PendingCenter(
                    centerKey,
                    generation,
                    pending.firstQueuedAt(),
                    Math.max(pending.lastUpdatedAt(), queuedAt)
                )
            );
            this.generationsSuperseded++;
            return OfferResult.SUPERSEDED;
        }

        CenterRequest activeCenter = this.active == null
            ? null
            : this.active.centersByKey().get(centerKey);
        if (activeCenter != null) {
            if (generation == activeCenter.generation()) {
                this.duplicateOffers++;
                return OfferResult.DUPLICATE;
            }
            if (generation < activeCenter.generation()) {
                this.staleOffers++;
                return OfferResult.STALE;
            }
        }

        if (this.nextCenters.size() >= this.limits.maxQueuedCenters()) {
            this.capacityRejections++;
            return OfferResult.BACKPRESSURED;
        }
        this.nextCenters.put(centerKey, new PendingCenter(centerKey, generation, queuedAt, queuedAt));
        this.centersAdded++;
        this.nextHighWaterMark = Math.max(this.nextHighWaterMark, this.nextCenters.size());
        return OfferResult.ADDED;
    }

    /** Freezes a bounded subset of the next collector. Unselected centers remain queued. */
    Optional<FrozenBatch> freezeNext(FreezeContext context) {
        Objects.requireNonNull(context, "context");
        if (this.active != null || this.nextCenters.isEmpty()) {
            return Optional.empty();
        }

        List<PendingCenter> candidates = new ArrayList<>(this.nextCenters.values());
        candidates.sort(centerComparator(context));

        List<PendingCenter> selected = new ArrayList<>();
        LinkedHashSet<Long> affected = new LinkedHashSet<>();
        LinkedHashSet<Long> sources = new LinkedHashSet<>();
        long rawAffected = 0L;
        long rawSources = 0L;

        for (PendingCenter candidate : candidates) {
            if (selected.size() >= this.limits.maxCentersPerBatch()) {
                break;
            }
            Contribution contribution = contribution(candidate.centerKey(), context);
            int affectedAdditions = countAdditions(affected, contribution.affectedColumns());
            int sourceAdditions = countAdditions(sources, contribution.sourceColumns());
            if (
                affected.size() + affectedAdditions > this.limits.maxAffectedColumns()
                    || sources.size() + sourceAdditions > this.limits.maxSourceColumns()
            ) {
                continue;
            }

            selected.add(candidate);
            affected.addAll(contribution.affectedColumns());
            sources.addAll(contribution.sourceColumns());
            rawAffected += contribution.affectedColumns().size();
            rawSources += contribution.sourceColumns().size();
        }

        if (selected.isEmpty()) {
            throw new IllegalStateException("configured limits cannot fit one relight center");
        }
        for (PendingCenter center : selected) {
            this.nextCenters.remove(center.centerKey(), center);
        }

        List<CenterRequest> frozenCenters = selected.stream().map(PendingCenter::freeze).toList();
        WorkSavings savings = new WorkSavings(
            rawAffected,
            affected.size(),
            rawSources,
            sources.size(),
            candidates.size() - selected.size()
        );
        FrozenBatch batch = new FrozenBatch(
            ++this.nextBatchId,
            frozenCenters,
            affected,
            sources,
            savings
        );
        this.active = new ActiveBatch(batch);
        this.batchesFrozen++;
        this.centersFrozen += frozenCenters.size();
        this.rawSourceReferences += rawSources;
        this.uniqueSourceColumns += sources.size();
        return Optional.of(batch);
    }

    /** Cancels matching work that has not yet entered the immutable active batch. */
    int cancel(long centerKey, long throughGeneration) {
        int cancelled = 0;
        PendingCenter pending = this.nextCenters.get(centerKey);
        if (pending != null && pending.generation() <= throughGeneration) {
            if (this.nextCenters.remove(centerKey, pending)) {
                this.nextCentersCancelled++;
                cancelled++;
            }
        }
        return cancelled;
    }

    /** Removes queued centers whose exact-or-newer state was already covered by a committed output column. */
    int satisfyCovered(Map<Long, Long> committedRevisions, Set<Long> sourceColumns) {
        Objects.requireNonNull(committedRevisions, "committedRevisions");
        Objects.requireNonNull(sourceColumns, "sourceColumns");
        int satisfied = 0;
        var iterator = this.nextCenters.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Long, PendingCenter> entry = iterator.next();
            Long committedRevision = committedRevisions.get(entry.getKey());
            if (
                committedRevision != null
                    && entry.getValue().generation() <= committedRevision
                    && square(entry.getKey(), AFFECTED_RADIUS, committedRevisions::containsKey).size()
                        == MIN_AFFECTED_COLUMNS
                    && square(entry.getKey(), SOURCE_RADIUS, sourceColumns::contains).size()
                        == MIN_SOURCE_COLUMNS
            ) {
                iterator.remove();
                satisfied++;
            }
        }
        this.centersSatisfied += satisfied;
        return satisfied;
    }

    boolean completeActive(long batchId) {
        if (this.active == null || this.active.batch().id() != batchId) {
            return false;
        }
        this.active = null;
        this.batchesCompleted++;
        return true;
    }

    boolean abortActive(long batchId) {
        if (this.active == null || this.active.batch().id() != batchId) {
            return false;
        }
        this.active = null;
        this.batchesAborted++;
        return true;
    }

    void clear() {
        this.centersCleared += this.nextCenters.size();
        if (this.active != null) {
            this.centersCleared += this.active.batch().centers().size();
        }
        this.nextCenters.clear();
        this.active = null;
    }

    boolean hasActiveBatch() {
        return this.active != null;
    }

    int nextCenterCount() {
        return this.nextCenters.size();
    }

    boolean isEmpty() {
        return this.active == null && this.nextCenters.isEmpty();
    }

    Stats stats() {
        return new Stats(
            this.offers,
            this.centersAdded,
            this.generationsSuperseded,
            this.duplicateOffers,
            this.staleOffers,
            this.capacityRejections,
            this.batchesFrozen,
            this.batchesCompleted,
            this.batchesAborted,
            this.centersFrozen,
            this.nextCentersCancelled,
            this.centersCleared,
            this.centersSatisfied,
            this.rawSourceReferences,
            this.uniqueSourceColumns,
            this.nextHighWaterMark
        );
    }

    private static Comparator<PendingCenter> centerComparator(FreezeContext context) {
        return (left, right) -> {
            long leftAgeBand = ageBand(left, context);
            long rightAgeBand = ageBand(right, context);
            int comparison = Long.compare(rightAgeBand, leftAgeBand);
            if (comparison != 0) {
                return comparison;
            }
            comparison = Long.compare(
                distanceSquared(left.centerKey(), context.anchorX(), context.anchorZ()),
                distanceSquared(right.centerKey(), context.anchorX(), context.anchorZ())
            );
            if (comparison != 0) {
                return comparison;
            }
            comparison = Long.compare(left.firstQueuedAt(), right.firstQueuedAt());
            if (comparison != 0) {
                return comparison;
            }
            return Long.compareUnsigned(left.centerKey(), right.centerKey());
        };
    }

    private static long ageBand(PendingCenter center, FreezeContext context) {
        long elapsed = context.now() <= center.firstQueuedAt()
            ? 0L
            : context.now() - center.firstQueuedAt();
        return elapsed / context.agingQuantum();
    }

    private static Contribution contribution(long centerKey, FreezeContext context) {
        LinkedHashSet<Long> affected = square(centerKey, AFFECTED_RADIUS, context.affectedEligible());
        LinkedHashSet<Long> sources = square(centerKey, SOURCE_RADIUS, context.sourceEligible());
        return new Contribution(affected, sources);
    }

    private static LinkedHashSet<Long> square(long centerKey, int radius, LongPredicate eligible) {
        int centerX = (int)centerKey;
        int centerZ = (int)(centerKey >> 32);
        LinkedHashSet<Long> result = new LinkedHashSet<>();
        for (int offsetX = -radius; offsetX <= radius; offsetX++) {
            for (int offsetZ = -radius; offsetZ <= radius; offsetZ++) {
                long key = RelightTargets.chunkKey(centerX + offsetX, centerZ + offsetZ);
                if (eligible.test(key)) {
                    result.add(key);
                }
            }
        }
        return result;
    }

    private static int countAdditions(Set<Long> existing, Set<Long> candidates) {
        int additions = 0;
        for (long candidate : candidates) {
            if (!existing.contains(candidate)) {
                additions++;
            }
        }
        return additions;
    }

    private static long distanceSquared(long centerKey, int anchorX, int anchorZ) {
        long deltaX = (long)(int)centerKey - anchorX;
        long deltaZ = (long)(int)(centerKey >> 32) - anchorZ;
        return saturatingAdd(saturatingSquare(deltaX), saturatingSquare(deltaZ));
    }

    private static long saturatingSquare(long value) {
        long absolute = value == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(value);
        if (absolute > 3_037_000_499L) {
            return Long.MAX_VALUE;
        }
        return value * value;
    }

    private static long saturatingAdd(long left, long right) {
        if (left == Long.MAX_VALUE || right > Long.MAX_VALUE - left) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private static long saturatingMultiply(long left, long right) {
        if (left == 0L || right == 0L) {
            return 0L;
        }
        if (left > Long.MAX_VALUE / right) {
            return Long.MAX_VALUE;
        }
        return left * right;
    }

    enum OfferResult {
        ADDED,
        SUPERSEDED,
        DUPLICATE,
        STALE,
        BACKPRESSURED
    }

    record Limits(
        int maxCentersPerBatch,
        int maxQueuedCenters,
        int maxAffectedColumns,
        int maxSourceColumns
    ) {
        Limits {
            if (maxCentersPerBatch < 1) {
                throw new IllegalArgumentException("maxCentersPerBatch must be positive");
            }
            if (maxQueuedCenters < maxCentersPerBatch) {
                throw new IllegalArgumentException("maxQueuedCenters must cover one full batch");
            }
            if (maxAffectedColumns < MIN_AFFECTED_COLUMNS) {
                throw new IllegalArgumentException("maxAffectedColumns must fit one affected region");
            }
            if (maxSourceColumns < MIN_SOURCE_COLUMNS) {
                throw new IllegalArgumentException("maxSourceColumns must fit one 5x5 source union");
            }
        }
    }

    record FreezeContext(
        int anchorX,
        int anchorZ,
        long now,
        long agingQuantum,
        LongPredicate affectedEligible,
        LongPredicate sourceEligible
    ) {
        FreezeContext {
            if (now < 0L) {
                throw new IllegalArgumentException("now must be non-negative");
            }
            if (agingQuantum < 1L) {
                throw new IllegalArgumentException("agingQuantum must be positive");
            }
            Objects.requireNonNull(affectedEligible, "affectedEligible");
            Objects.requireNonNull(sourceEligible, "sourceEligible");
        }

        static FreezeContext acceptingAll(int anchorX, int anchorZ, long now, long agingQuantum) {
            LongPredicate acceptAll = ignored -> true;
            return new FreezeContext(
                anchorX,
                anchorZ,
                now,
                agingQuantum,
                acceptAll,
                acceptAll
            );
        }
    }

    record CenterRequest(long centerKey, long generation, long firstQueuedAt, long lastUpdatedAt) {
    }

    static final class FrozenBatch {

        private final long id;
        private final List<CenterRequest> centers;
        private final List<Long> affectedColumns;
        private final List<Long> sourceColumns;
        private final WorkSavings savings;

        private FrozenBatch(
            long id,
            List<CenterRequest> centers,
            Set<Long> affectedColumns,
            Set<Long> sourceColumns,
            WorkSavings savings
        ) {
            this.id = id;
            this.centers = List.copyOf(centers);
            this.affectedColumns = List.copyOf(affectedColumns);
            this.sourceColumns = List.copyOf(sourceColumns);
            this.savings = savings;
        }

        long id() {
            return this.id;
        }

        List<CenterRequest> centers() {
            return this.centers;
        }

        List<Long> affectedColumns() {
            return this.affectedColumns;
        }

        List<Long> sourceColumns() {
            return this.sourceColumns;
        }

        WorkSavings savings() {
            return this.savings;
        }
    }

    record WorkSavings(
        long rawAffectedReferences,
        int affectedColumns,
        long rawSourceReferences,
        int sourceColumns,
        int deferredCenters
    ) {
        long affectedReferencesSaved() {
            return rawAffectedReferences - affectedColumns;
        }

        long sourceReplaysSaved() {
            return rawSourceReferences - sourceColumns;
        }

        long estimatedLayerWritesSaved(int lightSectionCount, int lightLayers) {
            if (lightSectionCount < 0 || lightLayers < 0) {
                throw new IllegalArgumentException("light dimensions must be non-negative");
            }
            return saturatingMultiply(
                saturatingMultiply(this.affectedReferencesSaved(), lightSectionCount),
                lightLayers
            );
        }
    }

    record Stats(
        long offers,
        long centersAdded,
        long generationsSuperseded,
        long duplicateOffers,
        long staleOffers,
        long capacityRejections,
        long batchesFrozen,
        long batchesCompleted,
        long batchesAborted,
        long centersFrozen,
        long nextCentersCancelled,
        long centersCleared,
        long centersSatisfied,
        long rawSourceReferences,
        long uniqueSourceColumns,
        int nextHighWaterMark
    ) {
        long sourceReplaysSaved() {
            return rawSourceReferences - uniqueSourceColumns;
        }

    }

    private record PendingCenter(long centerKey, long generation, long firstQueuedAt, long lastUpdatedAt) {
        CenterRequest freeze() {
            return new CenterRequest(this.centerKey, this.generation, this.firstQueuedAt, this.lastUpdatedAt);
        }
    }

    private record Contribution(
        LinkedHashSet<Long> affectedColumns,
        LinkedHashSet<Long> sourceColumns
    ) {
    }

    private record ActiveBatch(
        FrozenBatch batch,
        Map<Long, CenterRequest> centersByKey
    ) {
        ActiveBatch(FrozenBatch batch) {
            this(batch, index(batch.centers()));
        }

        private static Map<Long, CenterRequest> index(List<CenterRequest> centers) {
            Map<Long, CenterRequest> result = new HashMap<>();
            for (CenterRequest center : centers) {
                result.put(center.centerKey(), center);
            }
            return Map.copyOf(result);
        }
    }
}
