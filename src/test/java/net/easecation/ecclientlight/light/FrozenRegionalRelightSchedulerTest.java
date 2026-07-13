package net.easecation.ecclientlight.light;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.LongPredicate;
import org.junit.jupiter.api.Test;

class FrozenRegionalRelightSchedulerTest {

    @Test
    void activeBatchIsFrozenWhileNewerWorkCollectsInNext() {
        FrozenRegionalRelightScheduler scheduler = scheduler(8, 16);
        long first = key(0, 0);
        long second = key(1, 0);

        assertEquals(FrozenRegionalRelightScheduler.OfferResult.ADDED, scheduler.offer(first, 1, 0));
        assertEquals(FrozenRegionalRelightScheduler.OfferResult.ADDED, scheduler.offer(second, 1, 0));
        FrozenRegionalRelightScheduler.FrozenBatch active = scheduler.freezeNext(context(0, 0, 1, 10)).orElseThrow();

        assertEquals(List.of(first, second), active.centers().stream().map(center -> center.centerKey()).toList());
        assertEquals(12, active.affectedColumns().size());
        assertEquals(30, active.sourceColumns().size());

        assertEquals(FrozenRegionalRelightScheduler.OfferResult.ADDED, scheduler.offer(first, 2, 2));
        assertEquals(1, scheduler.nextCenterCount());
        assertEquals(1, active.centers().getFirst().generation());
        assertTrue(scheduler.freezeNext(context(0, 0, 3, 10)).isEmpty());

        assertTrue(scheduler.completeActive(active.id()));
        FrozenRegionalRelightScheduler.FrozenBatch next = scheduler.freezeNext(context(0, 0, 4, 10)).orElseThrow();
        assertEquals(2, next.centers().getFirst().generation());
        assertEquals(1, next.centers().size());
    }

    @Test
    void newerGenerationSupersedesOnlyUnstartedWorkAndKeepsOriginalAge() {
        FrozenRegionalRelightScheduler scheduler = scheduler(4, 4);
        long center = key(7, -3);

        assertEquals(FrozenRegionalRelightScheduler.OfferResult.ADDED, scheduler.offer(center, 1, 2));
        assertEquals(FrozenRegionalRelightScheduler.OfferResult.SUPERSEDED, scheduler.offer(center, 3, 9));
        assertEquals(FrozenRegionalRelightScheduler.OfferResult.STALE, scheduler.offer(center, 2, 10));
        assertEquals(FrozenRegionalRelightScheduler.OfferResult.DUPLICATE, scheduler.offer(center, 3, 11));

        FrozenRegionalRelightScheduler.CenterRequest request = scheduler
            .freezeNext(context(0, 0, 12, 5))
            .orElseThrow()
            .centers()
            .getFirst();
        assertEquals(3, request.generation());
        assertEquals(2, request.firstQueuedAt());
        assertEquals(9, request.lastUpdatedAt());

        FrozenRegionalRelightScheduler.Stats stats = scheduler.stats();
        assertEquals(1, stats.generationsSuperseded());
        assertEquals(1, stats.staleOffers());
        assertEquals(1, stats.duplicateOffers());
    }

    @Test
    void distanceOrdersNewWorkButAnOlderAgeBandWinsEventually() {
        long near = key(1, 0);
        long far = key(100, 0);
        FrozenRegionalRelightScheduler distanceFirst = scheduler(1, 2);
        distanceFirst.offer(far, 1, 0);
        distanceFirst.offer(near, 1, 0);
        assertEquals(
            near,
            distanceFirst.freezeNext(context(0, 0, 1, 10)).orElseThrow().centers().getFirst().centerKey()
        );

        FrozenRegionalRelightScheduler agedFirst = scheduler(1, 2);
        agedFirst.offer(far, 1, 0);
        agedFirst.offer(near, 1, 9);
        FrozenRegionalRelightScheduler.FrozenBatch batch = agedFirst
            .freezeNext(context(0, 0, 10, 5))
            .orElseThrow();
        assertEquals(far, batch.centers().getFirst().centerKey());
        assertEquals(1, batch.savings().deferredCenters());
    }

    @Test
    void queueAndRegionalColumnCapsApplyBackpressureWithoutDroppingQueuedCenters() {
        FrozenRegionalRelightScheduler scheduler = new FrozenRegionalRelightScheduler(
            new FrozenRegionalRelightScheduler.Limits(3, 3, 9, 25)
        );
        long near = key(0, 0);
        long adjacent = key(1, 0);
        long far = key(50, 50);

        scheduler.offer(near, 1, 0);
        scheduler.offer(adjacent, 1, 0);
        scheduler.offer(far, 1, 0);
        assertEquals(
            FrozenRegionalRelightScheduler.OfferResult.SUPERSEDED,
            scheduler.offer(near, 2, 1)
        );
        assertEquals(
            FrozenRegionalRelightScheduler.OfferResult.BACKPRESSURED,
            scheduler.offer(key(60, 60), 1, 0)
        );

        FrozenRegionalRelightScheduler.FrozenBatch batch = scheduler.freezeNext(context(0, 0, 1, 10)).orElseThrow();
        assertEquals(List.of(near), batch.centers().stream().map(center -> center.centerKey()).toList());
        assertEquals(2, batch.centers().getFirst().generation());
        assertEquals(2, scheduler.nextCenterCount());
        assertEquals(2, batch.savings().deferredCenters());
        assertEquals(1, scheduler.stats().capacityRejections());
        assertEquals(3, scheduler.stats().nextHighWaterMark());
    }

    @Test
    void cancellationDoesNotMutateFrozenUnionsAndClearDropsBothSlots() {
        FrozenRegionalRelightScheduler scheduler = scheduler(2, 4);
        long center = key(4, 5);
        scheduler.offer(center, 1, 0);
        FrozenRegionalRelightScheduler.FrozenBatch active = scheduler.freezeNext(context(4, 5, 1, 10)).orElseThrow();
        List<Long> frozenAffected = active.affectedColumns();

        scheduler.offer(center, 2, 2);
        assertEquals(0, scheduler.cancel(center, 1));
        assertEquals(frozenAffected, active.affectedColumns());
        assertEquals(1, scheduler.nextCenterCount());

        assertEquals(1, scheduler.cancel(center, 2));
        assertEquals(0, scheduler.nextCenterCount());
        assertFalse(scheduler.completeActive(active.id() + 1));
        assertTrue(scheduler.abortActive(active.id()));

        scheduler.offer(key(8, 8), 1, 3);
        scheduler.freezeNext(context(8, 8, 4, 10)).orElseThrow();
        scheduler.offer(key(9, 9), 1, 4);
        scheduler.clear();
        assertTrue(scheduler.isEmpty());
        assertEquals(2, scheduler.stats().centersCleared());
    }

    @Test
    void eligibilityFiltersAreAppliedBeforeUnionAndSavingsAccounting() {
        FrozenRegionalRelightScheduler scheduler = scheduler(2, 2);
        long center = key(0, 0);
        scheduler.offer(center, 1, 0);
        LongPredicate affected = chunk -> x(chunk) == 0;
        LongPredicate sources = chunk -> chunk == center;
        FrozenRegionalRelightScheduler.FreezeContext context = new FrozenRegionalRelightScheduler.FreezeContext(
            0,
            0,
            1,
            10,
            affected,
            sources
        );

        FrozenRegionalRelightScheduler.FrozenBatch batch = scheduler.freezeNext(context).orElseThrow();
        assertEquals(3, batch.affectedColumns().size());
        assertEquals(List.of(center), batch.sourceColumns());
        assertEquals(3, batch.savings().rawAffectedReferences());
        assertEquals(1, batch.savings().rawSourceReferences());
    }

    @Test
    void fullViewBatchMatchesRegionalUnionMathAndReportsSavedWork() {
        FrozenRegionalRelightScheduler scheduler = new FrozenRegionalRelightScheduler(
            new FrozenRegionalRelightScheduler.Limits(625, 625, 729, 841)
        );
        long generation = 1;
        for (int chunkX = 0; chunkX < 25; chunkX++) {
            for (int chunkZ = 0; chunkZ < 25; chunkZ++) {
                scheduler.offer(key(chunkX, chunkZ), generation++, 0);
            }
        }

        FrozenRegionalRelightScheduler.FrozenBatch batch = scheduler
            .freezeNext(context(12, 12, 1, 10))
            .orElseThrow();
        FrozenRegionalRelightScheduler.WorkSavings savings = batch.savings();
        assertEquals(625, batch.centers().size());
        assertEquals(729, batch.affectedColumns().size());
        assertEquals(841, batch.sourceColumns().size());
        assertEquals(5_625, savings.rawAffectedReferences());
        assertEquals(15_625, savings.rawSourceReferences());
        assertEquals(4_896, savings.affectedReferencesSaved());
        assertEquals(14_784, savings.sourceReplaysSaved());
        assertEquals(254_592, savings.estimatedLayerWritesSaved(26, 2));
    }

    @Test
    void frozenCollectionsAreImmutableAndConfigurationRejectsUnsafeCaps() {
        FrozenRegionalRelightScheduler scheduler = scheduler(1, 1);
        scheduler.offer(key(0, 0), 1, 0);
        FrozenRegionalRelightScheduler.FrozenBatch batch = scheduler.freezeNext(context(0, 0, 1, 1)).orElseThrow();
        assertThrows(UnsupportedOperationException.class, () -> batch.centers().clear());
        assertThrows(UnsupportedOperationException.class, () -> batch.affectedColumns().clear());

        assertThrows(
            IllegalArgumentException.class,
            () -> new FrozenRegionalRelightScheduler.Limits(0, 1, 9, 25)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new FrozenRegionalRelightScheduler.Limits(2, 1, 9, 25)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new FrozenRegionalRelightScheduler.Limits(1, 1, 8, 25)
        );
        assertThrows(IllegalArgumentException.class, () -> scheduler.offer(key(1, 1), 0, 0));
        assertThrows(IllegalArgumentException.class, () -> scheduler.offer(key(1, 1), 1, -1));
        assertThrows(
            IllegalArgumentException.class,
            () -> FrozenRegionalRelightScheduler.FreezeContext.acceptingAll(0, 0, 0, 0)
        );
    }

    @Test
    void committedCoverageRemovesOnlyQueuedGenerationsItActuallyContains() {
        FrozenRegionalRelightScheduler scheduler = scheduler(1, 4);
        long active = key(-10, 0);
        long covered = key(0, 0);
        long newer = key(10, 0);
        scheduler.offer(active, 1, 0);
        scheduler.offer(covered, 2, 0);
        scheduler.offer(newer, 4, 0);
        FrozenRegionalRelightScheduler.FrozenBatch batch = scheduler.freezeNext(context(-10, 0, 1, 10)).orElseThrow();

        java.util.Map<Long, Long> committed = new java.util.HashMap<>();
        for (long output : RelightTargets.square(0, 0, 1)) {
            committed.put(output, output == covered ? 2L : 1L);
        }
        java.util.Set<Long> sources = new java.util.HashSet<>();
        for (long source : RelightTargets.square(0, 0, 2)) {
            sources.add(source);
        }
        assertEquals(1, scheduler.satisfyCovered(committed, sources));
        assertEquals(1, scheduler.nextCenterCount());
        assertEquals(1, scheduler.stats().centersSatisfied());
        assertTrue(scheduler.completeActive(batch.id()));
        assertEquals(newer, scheduler.freezeNext(context(0, 0, 2, 10)).orElseThrow().centers().getFirst().centerKey());
    }

    private static FrozenRegionalRelightScheduler scheduler(int maxCenters, int maxQueued) {
        return new FrozenRegionalRelightScheduler(
            new FrozenRegionalRelightScheduler.Limits(maxCenters, maxQueued, 10_000, 10_000)
        );
    }

    private static FrozenRegionalRelightScheduler.FreezeContext context(
        int anchorX,
        int anchorZ,
        long now,
        long agingQuantum
    ) {
        return FrozenRegionalRelightScheduler.FreezeContext.acceptingAll(
            anchorX,
            anchorZ,
            now,
            agingQuantum
        );
    }

    private static long key(int chunkX, int chunkZ) {
        return RelightTargets.chunkKey(chunkX, chunkZ);
    }

    private static int x(long chunkKey) {
        return (int)chunkKey;
    }
}
