package net.easecation.ecclientlight.light.staging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class StagingWorkerTest {

    private static final StagingRevisionSnapshot REVISIONS =
        new StagingRevisionSnapshot(1L, Map.of(10L, 2L));

    @Test
    @Timeout(5)
    void executesMultipleQuantaOnTheDedicatedWorker() {
        AtomicInteger quanta = new AtomicInteger();
        AtomicReference<Thread> executionThread = new AtomicReference<>();
        try (StagingWorker<String, String> worker = new StagingWorker<>(config("multi-quantum"))) {
            StagingJob<String, String> job = new StagingJob<>(1L, REVISIONS, "snapshot", input -> quantum -> {
                executionThread.compareAndSet(null, Thread.currentThread());
                int current = quanta.incrementAndGet();
                if (current < 3) {
                    return StagingJob.Step.continueWork();
                }
                return StagingJob.Step.complete(input + "-" + current);
            });

            StagingJobHandle handle = worker.submit(job);
            StagingCompletion<String> completion = awaitCompletion(worker);

            assertEquals(StagingCompletion.Kind.SUCCEEDED, completion.kind());
            assertEquals("snapshot-3", completion.result());
            assertEquals(StagingJobHandle.State.SUCCEEDED, handle.state());
            assertEquals(3, quanta.get());
            assertNotEquals(Thread.currentThread(), executionThread.get());
        }
    }

    @Test
    @Timeout(5)
    void cancellationBetweenQuantumStartAndCompleteCannotPublishSuccess() throws InterruptedException {
        CountDownLatch entered = new CountDownLatch(1);
        AtomicBoolean release = new AtomicBoolean();
        try (StagingWorker<String, String> worker = new StagingWorker<>(config("cancel-race"))) {
            StagingJob<String, String> job = blockingCompleteJob(1L, entered, release);
            StagingJobHandle handle = worker.submit(job);
            assertTrue(entered.await(2, TimeUnit.SECONDS));

            assertTrue(handle.requestCancellation());
            release.set(true);
            StagingCompletion<String> completion = awaitCompletion(worker);

            assertEquals(StagingCompletion.Kind.CANCELLED, completion.kind());
            assertEquals(StagingJobHandle.State.CANCELLED, handle.state());
            assertEquals(0L, worker.completedJobs());
            assertEquals(1L, worker.cancelledJobs());
        }
    }

    @Test
    @Timeout(5)
    void closeCancelsActiveAndQueuedJobsWithoutJoiningWorker() throws InterruptedException {
        CountDownLatch entered = new CountDownLatch(1);
        AtomicBoolean release = new AtomicBoolean();
        StagingWorker<String, String> worker = new StagingWorker<>(config("close-race"));
        try {
            StagingJobHandle active = worker.submit(blockingCompleteJob(1L, entered, release));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            StagingJobHandle queued = worker.submit(new StagingJob<>(
                2L,
                REVISIONS,
                "queued",
                input -> quantum -> StagingJob.Step.complete(input)
            ));

            long started = System.nanoTime();
            worker.close();
            long closeNanos = System.nanoTime() - started;
            assertTrue(closeNanos < TimeUnit.MILLISECONDS.toNanos(500), "close must not join the worker");
            assertEquals(
                StagingJobHandle.State.REJECTED_CLOSED,
                worker.submit(new StagingJob<>(
                    3L,
                    REVISIONS,
                    "late",
                    input -> quantum -> StagingJob.Step.complete(input)
                )).state()
            );

            release.set(true);
            List<StagingCompletion<String>> completions = awaitCompletions(worker, 2);
            assertEquals(Set.of(1L, 2L), completionIds(completions));
            assertTrue(completions.stream().allMatch(c -> c.kind() == StagingCompletion.Kind.CANCELLED));
            assertEquals(StagingJobHandle.State.CANCELLED, active.state());
            assertEquals(StagingJobHandle.State.CANCELLED, queued.state());
        } finally {
            release.set(true);
            worker.close();
        }
    }

    private static StagingJob<String, String> blockingCompleteJob(
        long jobId,
        CountDownLatch entered,
        AtomicBoolean release
    ) {
        return new StagingJob<>(jobId, REVISIONS, "result", input -> quantum -> {
            entered.countDown();
            awaitRelease(release);
            return StagingJob.Step.complete(input);
        });
    }

    private static void awaitRelease(AtomicBoolean release) {
        while (!release.get()) {
            LockSupport.parkNanos(100_000L);
            Thread.interrupted();
        }
    }

    private static StagingWorker.Config config(String suffix) {
        return new StagingWorker.Config(
            4,
            8,
            1_000_000L,
            0L,
            "ECClientLight Test " + suffix,
            Thread.NORM_PRIORITY
        );
    }

    private static <O> StagingCompletion<O> awaitCompletion(StagingWorker<?, O> worker) {
        return awaitCompletions(worker, 1).getFirst();
    }

    private static <O> List<StagingCompletion<O>> awaitCompletions(StagingWorker<?, O> worker, int count) {
        List<StagingCompletion<O>> completions = new ArrayList<>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (completions.size() < count && System.nanoTime() < deadline) {
            worker.drainCompletions(count - completions.size(), completions::add);
            if (completions.size() < count) {
                LockSupport.parkNanos(100_000L);
            }
        }
        if (completions.size() != count) {
            fail("Timed out waiting for " + count + " completions; received " + completions.size());
        }
        return completions;
    }

    private static Set<Long> completionIds(List<? extends StagingCompletion<?>> completions) {
        Set<Long> ids = new HashSet<>();
        completions.forEach(completion -> ids.add(completion.jobId()));
        return ids;
    }
}
