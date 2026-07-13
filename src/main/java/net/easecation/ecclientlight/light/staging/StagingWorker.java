package net.easecation.ecclientlight.light.staging;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

/** Single-threaded, bounded and non-blocking staging job executor. */
public final class StagingWorker<I, O> implements AutoCloseable {

    private final Config config;
    private final ArrayBlockingQueue<QueuedJob<I, O>> pending;
    private final ArrayBlockingQueue<StagingCompletion<O>> completions;
    private final AtomicReference<QueuedJob<I, O>> active = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong completedJobs = new AtomicLong();
    private final AtomicLong cancelledJobs = new AtomicLong();
    private final AtomicLong droppedCompletions = new AtomicLong();
    private final Thread workerThread;

    public StagingWorker(Config config) {
        this.config = Objects.requireNonNull(config, "config");
        this.pending = new ArrayBlockingQueue<>(config.pendingCapacity());
        this.completions = new ArrayBlockingQueue<>(config.completionCapacity());
        this.workerThread = Thread.ofPlatform()
            .name(config.threadName())
            .daemon(true)
            .priority(config.threadPriority())
            .unstarted(this::runLoop);
        this.workerThread.start();
    }

    /** Attempts to enqueue without waiting for queue capacity. */
    public StagingJobHandle submit(StagingJob<I, O> job) {
        Objects.requireNonNull(job, "job");
        StagingJobHandle handle = new StagingJobHandle(job.jobId());
        if (this.closed.get()) {
            handle.markRejected(StagingJobHandle.State.REJECTED_CLOSED);
            return handle;
        }

        QueuedJob<I, O> queued = new QueuedJob<>(job, handle);
        if (!this.pending.offer(queued)) {
            handle.markRejected(StagingJobHandle.State.REJECTED_FULL);
            return handle;
        }

        // Close may race the first check. Remove the entry if the worker did not claim it.
        if (this.closed.get() && this.pending.remove(queued)) {
            handle.markRejected(StagingJobHandle.State.REJECTED_CLOSED);
        }
        return handle;
    }

    /** Drains at most maxCount completions without waiting for the worker. */
    public int drainCompletions(int maxCount, Consumer<? super StagingCompletion<O>> consumer) {
        if (maxCount < 0) {
            throw new IllegalArgumentException("maxCount must be non-negative");
        }
        Objects.requireNonNull(consumer, "consumer");
        int drained = 0;
        while (drained < maxCount) {
            StagingCompletion<O> completion = this.completions.poll();
            if (completion == null) {
                break;
            }
            consumer.accept(completion);
            drained++;
        }
        return drained;
    }

    public boolean isWorkerAlive() {
        return this.workerThread.isAlive();
    }

    public long completedJobs() {
        return this.completedJobs.get();
    }

    public long cancelledJobs() {
        return this.cancelledJobs.get();
    }

    public long droppedCompletions() {
        return this.droppedCompletions.get();
    }

    /** Requests cancellation and returns immediately; this method never joins the worker. */
    @Override
    public void close() {
        if (!this.closed.compareAndSet(false, true)) {
            return;
        }

        QueuedJob<I, O> current = this.active.get();
        if (current != null) {
            current.handle().requestCancellation();
        }
        this.workerThread.interrupt();

        QueuedJob<I, O> queued;
        while ((queued = this.pending.poll()) != null) {
            queued.handle().requestCancellation();
            this.publishCancelled(queued);
        }
    }

    private void runLoop() {
        while (!this.closed.get()) {
            QueuedJob<I, O> queued;
            try {
                queued = this.pending.take();
            } catch (InterruptedException ignored) {
                if (this.closed.get()) {
                    break;
                }
                continue;
            }

            this.active.set(queued);
            try {
                this.execute(queued);
            } finally {
                this.active.compareAndSet(queued, null);
            }
        }

        QueuedJob<I, O> queued;
        while ((queued = this.pending.poll()) != null) {
            queued.handle().requestCancellation();
            this.publishCancelled(queued);
        }
    }

    private void execute(QueuedJob<I, O> queued) {
        StagingJobHandle handle = queued.handle();
        if (this.closed.get() || handle.isCancellationRequested()) {
            this.publishCancelled(queued);
            return;
        }
        if (!handle.markRunning()) {
            return;
        }

        try {
            StagingJob.Computation<O> computation = Objects.requireNonNull(
                queued.job().computationFactory().create(queued.job().input()),
                "computationFactory returned null"
            );
            while (!this.closed.get() && !handle.isCancellationRequested()) {
                long startedAt = System.nanoTime();
                long deadline = saturatingAdd(startedAt, this.config.quantumNanos());
                StagingJob.Quantum quantum = new StagingJob.Quantum(
                    deadline,
                    () -> this.closed.get() || handle.isCancellationRequested(),
                    System::nanoTime
                );
                StagingJob.Step<O> step = Objects.requireNonNull(computation.runQuantum(quantum), "computation returned null");
                if (step instanceof StagingJob.Complete<O> complete) {
                    if (this.closed.get() || handle.isCancellationRequested()) {
                        this.publishCancelled(queued);
                        return;
                    }
                    this.publish(
                        queued,
                        StagingCompletion.succeeded(queued.job().jobId(), queued.job().revisions(), complete.result()),
                        StagingJobHandle.State.SUCCEEDED
                    );
                    this.completedJobs.incrementAndGet();
                    return;
                }

                if (this.config.parkNanos() > 0L) {
                    LockSupport.parkNanos(this.config.parkNanos());
                    Thread.interrupted();
                } else {
                    Thread.yield();
                }
            }
            this.publishCancelled(queued);
        } catch (CancellationException ignored) {
            this.publishCancelled(queued);
        } catch (Error fatal) {
            throw fatal;
        } catch (Exception failure) {
            this.publish(
                queued,
                StagingCompletion.failed(queued.job().jobId(), queued.job().revisions(), failure),
                StagingJobHandle.State.FAILED
            );
        }
    }

    private void publishCancelled(QueuedJob<I, O> queued) {
        if (queued.handle().state().isTerminal()) {
            return;
        }
        this.cancelledJobs.incrementAndGet();
        this.publish(
            queued,
            StagingCompletion.cancelled(queued.job().jobId(), queued.job().revisions()),
            StagingJobHandle.State.CANCELLED
        );
    }

    private void publish(
        QueuedJob<I, O> queued,
        StagingCompletion<O> completion,
        StagingJobHandle.State completedState
    ) {
        queued.handle().markTerminal(completedState);
        if (!this.completions.offer(completion)) {
            queued.handle().markCompletionDropped(completedState);
            this.droppedCompletions.incrementAndGet();
        }
    }

    private static long saturatingAdd(long value, long increment) {
        return value > Long.MAX_VALUE - increment ? Long.MAX_VALUE : value + increment;
    }

    private record QueuedJob<I, O>(StagingJob<I, O> job, StagingJobHandle handle) {
    }

    public record Config(
        int pendingCapacity,
        int completionCapacity,
        long quantumNanos,
        long parkNanos,
        String threadName,
        int threadPriority
    ) {

        public Config {
            if (pendingCapacity < 1 || completionCapacity < 1) {
                throw new IllegalArgumentException("queue capacities must be positive");
            }
            if (quantumNanos < 1L || parkNanos < 0L) {
                throw new IllegalArgumentException("invalid worker timing");
            }
            Objects.requireNonNull(threadName, "threadName");
            if (threadName.isBlank()) {
                throw new IllegalArgumentException("threadName must not be blank");
            }
            if (threadPriority < Thread.MIN_PRIORITY || threadPriority > Thread.MAX_PRIORITY) {
                throw new IllegalArgumentException("invalid thread priority");
            }
        }
    }
}
