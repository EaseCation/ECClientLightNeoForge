package net.easecation.ecclientlight.light;

import java.util.function.LongSupplier;

/** Thread-local propagation budget used only by the worker-owned Vanilla light engine. */
public final class LocalLightWorkBudget {

    static final int DEFAULT_WORKER_NODES_PER_QUANTUM = 262_144;
    static final int TIME_CHECK_INTERVAL = 256;
    private static final int PROPAGATION_SHARE_NUMERATOR = 7;
    private static final int PROPAGATION_SHARE_DENOMINATOR = 8;
    private static final int BLOCK_SHARE_NUMERATOR = 2;
    private static final int BLOCK_SHARE_DENOMINATOR = 3;
    private static final int WORKER_NODES_PER_QUANTUM = resolveWorkerNodes(
        Integer.getInteger("ecclientlight.workerNodesPerQuantum")
    );
    private static final ThreadLocal<BudgetWindow> ACTIVE = new ThreadLocal<>();

    private LocalLightWorkBudget() {
    }

    /** Applies the caller's cooperative deadline to a staging-engine worker quantum. */
    public static void beginWorker(long maxNanos) {
        ACTIVE.set(new BudgetWindow(
            WORKER_NODES_PER_QUANTUM,
            Math.max(1L, maxNanos),
            System::nanoTime
        ));
    }

    public static void end() {
        ACTIVE.remove();
    }

    public static boolean isActive() {
        return ACTIVE.get() != null;
    }

    public static boolean isExhausted() {
        BudgetWindow budget = ACTIVE.get();
        return budget != null && budget.isExhausted();
    }

    /** Returns true when a propagation loop should behave as if its queue were empty. */
    public static boolean shouldStop(boolean queueIsEmpty, boolean skyEngine) {
        if (queueIsEmpty) {
            return true;
        }
        BudgetWindow budget = ACTIVE.get();
        return budget != null && !budget.tryAcquire(skyEngine);
    }

    static int resolveWorkerNodes(Integer configured) {
        return Math.clamp(configured == null ? DEFAULT_WORKER_NODES_PER_QUANTUM : configured, 4_096, 1_048_576);
    }

    static final class BudgetWindow {

        private final int maxNodes;
        private final long maxNanos;
        private final LongSupplier clock;
        private final long startedAt;
        private final long propagationNanos;
        private final long blockPropagationNanos;
        private final int blockNodeLimit;
        private int nodesUsed;

        BudgetWindow(int maxNodes, long maxNanos, LongSupplier clock) {
            this.maxNodes = maxNodes;
            this.maxNanos = maxNanos;
            this.clock = clock;
            this.startedAt = clock.getAsLong();
            this.propagationNanos = Math.max(
                1L,
                scale(maxNanos, PROPAGATION_SHARE_NUMERATOR, PROPAGATION_SHARE_DENOMINATOR)
            );
            this.blockPropagationNanos = Math.max(
                1L,
                scale(this.propagationNanos, BLOCK_SHARE_NUMERATOR, BLOCK_SHARE_DENOMINATOR)
            );
            this.blockNodeLimit = Math.max(
                1,
                scale(maxNodes, BLOCK_SHARE_NUMERATOR, BLOCK_SHARE_DENOMINATOR)
            );
        }

        boolean tryAcquire(boolean skyEngine) {
            int nodeLimit = skyEngine ? this.maxNodes : this.blockNodeLimit;
            if (this.nodesUsed >= nodeLimit) {
                return false;
            }
            long propagationDeadline = skyEngine ? this.propagationNanos : this.blockPropagationNanos;
            if (
                this.nodesUsed % TIME_CHECK_INTERVAL == 0
                    && this.clock.getAsLong() - this.startedAt >= propagationDeadline
            ) {
                return false;
            }
            this.nodesUsed++;
            return true;
        }

        boolean isExhausted() {
            return this.nodesUsed >= this.maxNodes || this.clock.getAsLong() - this.startedAt >= this.maxNanos;
        }

        private static long scale(long value, int numerator, int denominator) {
            return value / denominator * numerator + value % denominator * numerator / denominator;
        }

        private static int scale(int value, int numerator, int denominator) {
            return value / denominator * numerator + value % denominator * numerator / denominator;
        }
    }
}
