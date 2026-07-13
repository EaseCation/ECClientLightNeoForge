package net.easecation.ecclientlight.light.staging;

import java.util.Objects;
import javax.annotation.Nullable;

/** Immutable worker-to-client-thread completion message. */
public record StagingCompletion<O>(
    long jobId,
    StagingRevisionSnapshot revisions,
    Kind kind,
    @Nullable O result,
    @Nullable Throwable failure
) {

    public StagingCompletion {
        Objects.requireNonNull(revisions, "revisions");
        Objects.requireNonNull(kind, "kind");
        switch (kind) {
            case SUCCEEDED -> {
                Objects.requireNonNull(result, "successful result");
                if (failure != null) {
                    throw new IllegalArgumentException("successful completion cannot carry a failure");
                }
            }
            case FAILED -> {
                Objects.requireNonNull(failure, "failure");
                if (result != null) {
                    throw new IllegalArgumentException("failed completion cannot carry a result");
                }
            }
            case CANCELLED -> {
                if (result != null || failure != null) {
                    throw new IllegalArgumentException("cancelled completion cannot carry a result or failure");
                }
            }
        }
    }

    public static <O> StagingCompletion<O> succeeded(long jobId, StagingRevisionSnapshot revisions, O result) {
        return new StagingCompletion<>(jobId, revisions, Kind.SUCCEEDED, result, null);
    }

    public static <O> StagingCompletion<O> failed(long jobId, StagingRevisionSnapshot revisions, Throwable failure) {
        return new StagingCompletion<>(jobId, revisions, Kind.FAILED, null, failure);
    }

    public static <O> StagingCompletion<O> cancelled(long jobId, StagingRevisionSnapshot revisions) {
        return new StagingCompletion<>(jobId, revisions, Kind.CANCELLED, null, null);
    }

    public StagingRevisionSnapshot.Validation validate(StagingRevisionSnapshot.CurrentRevisions current) {
        return this.revisions.validate(current);
    }

    public enum Kind {
        SUCCEEDED,
        FAILED,
        CANCELLED
    }
}
