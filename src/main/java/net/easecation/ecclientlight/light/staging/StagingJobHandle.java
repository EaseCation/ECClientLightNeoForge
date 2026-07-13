package net.easecation.ecclientlight.light.staging;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Mutable execution state kept separate from the immutable job input. */
public final class StagingJobHandle {

    private final long jobId;
    private final AtomicReference<State> state = new AtomicReference<>(State.QUEUED);
    private final AtomicBoolean cancellationRequested = new AtomicBoolean();

    StagingJobHandle(long jobId) {
        this.jobId = jobId;
    }

    public long jobId() {
        return this.jobId;
    }

    public State state() {
        return this.state.get();
    }

    public boolean isCancellationRequested() {
        return this.cancellationRequested.get();
    }

    public boolean requestCancellation() {
        if (this.state.get().isTerminal()) {
            return false;
        }
        this.cancellationRequested.set(true);
        return !this.state.get().isTerminal();
    }

    boolean markRunning() {
        return this.state.compareAndSet(State.QUEUED, State.RUNNING);
    }

    void markRejected(State rejectedState) {
        if (rejectedState != State.REJECTED_FULL && rejectedState != State.REJECTED_CLOSED) {
            throw new IllegalArgumentException("not a rejected state: " + rejectedState);
        }
        this.state.compareAndSet(State.QUEUED, rejectedState);
    }

    void markTerminal(State terminalState) {
        if (!terminalState.isTerminal()) {
            throw new IllegalArgumentException("not a terminal state: " + terminalState);
        }
        while (true) {
            State current = this.state.get();
            if (current.isTerminal() || this.state.compareAndSet(current, terminalState)) {
                return;
            }
        }
    }

    void markCompletionDropped(State completedState) {
        this.state.compareAndSet(completedState, State.COMPLETION_DROPPED);
    }

    public enum State {
        QUEUED(false),
        RUNNING(false),
        SUCCEEDED(true),
        FAILED(true),
        CANCELLED(true),
        REJECTED_FULL(true),
        REJECTED_CLOSED(true),
        COMPLETION_DROPPED(true);

        private final boolean terminal;

        State(boolean terminal) {
            this.terminal = terminal;
        }

        public boolean isTerminal() {
            return this.terminal;
        }
    }
}
