package net.easecation.ecclientlight.light.staging;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** Immutable description of one cooperatively executed staging job. */
public record StagingJob<I, O>(
    long jobId,
    StagingRevisionSnapshot revisions,
    I input,
    ComputationFactory<I, O> computationFactory
) {

    public StagingJob {
        if (jobId < 0L) {
            throw new IllegalArgumentException("jobId must be non-negative");
        }
        Objects.requireNonNull(revisions, "revisions");
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(computationFactory, "computationFactory");
    }

    @FunctionalInterface
    public interface ComputationFactory<I, O> {

        Computation<O> create(I input) throws Exception;
    }

    @FunctionalInterface
    public interface Computation<O> {

        Step<O> runQuantum(Quantum quantum) throws Exception;
    }

    public sealed interface Step<O> permits Continue, Complete {

        static <O> Step<O> continueWork() {
            return new Continue<>();
        }

        static <O> Step<O> complete(O result) {
            return new Complete<>(result);
        }
    }

    public record Continue<O>() implements Step<O> {
    }

    public record Complete<O>(O result) implements Step<O> {

        public Complete {
            Objects.requireNonNull(result, "result");
        }
    }

    /** Deadline and cancellation view supplied once per worker quantum. */
    public static final class Quantum {

        private final long deadlineNanos;
        private final BooleanSupplier cancellationRequested;
        private final LongSupplier clock;

        Quantum(long deadlineNanos, BooleanSupplier cancellationRequested, LongSupplier clock) {
            this.deadlineNanos = deadlineNanos;
            this.cancellationRequested = cancellationRequested;
            this.clock = clock;
        }

        public boolean isCancellationRequested() {
            return this.cancellationRequested.getAsBoolean();
        }

        public boolean shouldYield() {
            return this.isCancellationRequested() || this.clock.getAsLong() >= this.deadlineNanos;
        }

        public long remainingNanos() {
            return Math.max(0L, this.deadlineNanos - this.clock.getAsLong());
        }

        public void throwIfCancellationRequested() {
            if (this.isCancellationRequested()) {
                throw new CancellationException("staging job was cancelled");
            }
        }
    }
}
