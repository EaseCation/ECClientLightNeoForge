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

    @Test
    @Timeout(5)
    void optionalTraceFollowsWorkerAndCompletionWithoutChangingResult() {
        var names=new java.util.concurrent.ConcurrentLinkedQueue<String>();
        net.easecation.performance.api.TraceLink.Sink sink=new net.easecation.performance.api.TraceLink.Sink(){
            public boolean enabled(){return true;}
            public long queued(String owner,String operation){names.add("queue:"+operation);return 1;}
            public long begin(long q,String owner,String operation){names.add("start:"+operation+":"+Thread.currentThread().getName());return 2;}
            public void end(long token,boolean failed){names.add("end:"+failed);}
        };
        net.easecation.performance.api.TraceLink.install(sink);
        try(StagingWorker<String,String> worker=new StagingWorker<>(config("trace-worker"))){
            worker.submit(new StagingJob<>(91L,REVISIONS,"value",input->quantum->StagingJob.Step.complete(input)));
            assertEquals("value",awaitCompletion(worker).result());
            assertTrue(names.contains("queue:staging/job"));assertTrue(names.contains("queue:staging/main_apply"));
            assertTrue(names.stream().anyMatch(n->n.startsWith("start:staging/job:")&&n.contains("trace-worker")));
            assertTrue(names.stream().anyMatch(n->n.equals("start:staging/main_apply:"+Thread.currentThread().getName())));
        }finally{net.easecation.performance.api.TraceLink.uninstall(sink);}
    }

    @Test @Timeout(5)
    void tracedFactoryFailurePreservesFailureAndClosesItsScope(){
        var ids=new java.util.concurrent.atomic.AtomicLong();var operations=new java.util.concurrent.ConcurrentHashMap<Long,String>();var ended=new java.util.concurrent.ConcurrentHashMap<String,Boolean>();
        var failure=new IllegalStateException("fixture factory failure");
        net.easecation.performance.api.TraceLink.Sink factorySink=new net.easecation.performance.api.TraceLink.Sink(){
            public boolean enabled(){return true;}public long queued(String owner,String op){return 0;}
            public long begin(long queued,String owner,String op){long id=ids.incrementAndGet();operations.put(id,op);return id;}
            public void end(long id,boolean failed){ended.put(operations.remove(id),failed);}
        };net.easecation.performance.api.TraceLink.install(factorySink);
        try(StagingWorker<String,String> worker=new StagingWorker<>(config("factory-failure"))){
            worker.submit(new StagingJob<>(92L,REVISIONS,"value",input->{throw failure;}));
            var result=awaitCompletion(worker);assertEquals(StagingCompletion.Kind.FAILED,result.kind());org.junit.jupiter.api.Assertions.assertSame(failure,result.failure());
            assertEquals(Boolean.TRUE,ended.get("staging/factory"));assertTrue(operations.isEmpty());
        }finally{net.easecation.performance.api.TraceLink.uninstall(factorySink);}
    }

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
