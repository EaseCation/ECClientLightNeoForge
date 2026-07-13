package net.easecation.ecclientlight.light;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class LocalLightWorkBudgetTest {

    @Test
    void workerNodeConfigurationIsHardBounded() {
        assertEquals(262_144, LocalLightWorkBudget.resolveWorkerNodes(null));
        assertEquals(4_096, LocalLightWorkBudget.resolveWorkerNodes(0));
        assertEquals(4_096, LocalLightWorkBudget.resolveWorkerNodes(-4));
        assertEquals(8_192, LocalLightWorkBudget.resolveWorkerNodes(8_192));
        assertEquals(1_048_576, LocalLightWorkBudget.resolveWorkerNodes(Integer.MAX_VALUE));
    }

    @Test
    void nodeLimitStopsBeforeOneMorePropagationEntry() {
        LocalLightWorkBudget.BudgetWindow budget = new LocalLightWorkBudget.BudgetWindow(
            3,
            TimeUnit.SECONDS.toNanos(1),
            () -> 0L
        );

        assertTrue(budget.tryAcquire(true));
        assertTrue(budget.tryAcquire(true));
        assertTrue(budget.tryAcquire(true));
        assertFalse(budget.tryAcquire(true));
    }

    @Test
    void timeLimitIsSampledEveryTwoHundredFiftySixNodes() {
        AtomicLong clock = new AtomicLong();
        long maxNanos = TimeUnit.MILLISECONDS.toNanos(2L);
        LocalLightWorkBudget.BudgetWindow budget = new LocalLightWorkBudget.BudgetWindow(
            1_000,
            maxNanos,
            clock::get
        );
        assertTrue(budget.tryAcquire(false));
        clock.set(maxNanos + 1L);
        for (int node = 1; node < LocalLightWorkBudget.TIME_CHECK_INTERVAL; node++) {
            assertTrue(budget.tryAcquire(false));
        }
        assertFalse(budget.tryAcquire(false));
    }

    @Test
    void commitGateChecksTheDeadlineWithoutConsumingAnotherNode() {
        AtomicLong clock = new AtomicLong();
        long maxNanos = TimeUnit.MILLISECONDS.toNanos(2L);
        LocalLightWorkBudget.BudgetWindow budget = new LocalLightWorkBudget.BudgetWindow(
            10,
            maxNanos,
            clock::get
        );

        assertFalse(budget.isExhausted());
        assertTrue(budget.tryAcquire(true));
        clock.set(maxNanos);
        assertTrue(budget.isExhausted());
    }

    @Test
    void blockPropagationLeavesNodeCapacityForSkyPropagation() {
        LocalLightWorkBudget.BudgetWindow budget = new LocalLightWorkBudget.BudgetWindow(
            9,
            TimeUnit.SECONDS.toNanos(1),
            () -> 0L
        );

        for (int node = 0; node < 6; node++) {
            assertTrue(budget.tryAcquire(false));
        }
        assertFalse(budget.tryAcquire(false));
        for (int node = 0; node < 3; node++) {
            assertTrue(budget.tryAcquire(true));
        }
        assertFalse(budget.tryAcquire(true));
    }

    @Test
    void blockTimeShareStopsBeforeTheSkyDeadline() {
        AtomicLong clock = new AtomicLong();
        LocalLightWorkBudget.BudgetWindow budget = new LocalLightWorkBudget.BudgetWindow(
            1_000,
            800L,
            clock::get
        );

        for (int node = 0; node < LocalLightWorkBudget.TIME_CHECK_INTERVAL; node++) {
            assertTrue(budget.tryAcquire(false));
        }
        clock.set(500L);
        assertFalse(budget.tryAcquire(false));
        assertTrue(budget.tryAcquire(true));
    }

    @Test
    void propagationDeadlineLeavesTimeForAStorageCommit() {
        AtomicLong clock = new AtomicLong(700L);
        LocalLightWorkBudget.BudgetWindow budget = new LocalLightWorkBudget.BudgetWindow(
            1_000,
            800L,
            clock::get
        );

        clock.set(1_400L);
        assertFalse(budget.tryAcquire(true));
        assertFalse(budget.isExhausted());
        clock.set(1_500L);
        assertTrue(budget.isExhausted());
    }

}
