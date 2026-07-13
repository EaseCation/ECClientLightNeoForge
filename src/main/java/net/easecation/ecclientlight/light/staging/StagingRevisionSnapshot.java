package net.easecation.ecclientlight.light.staging;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Immutable level/chunk versions captured with a staging input snapshot. */
public record StagingRevisionSnapshot(long levelEpoch, Map<Long, Long> chunkRevisions) {

    public static final long MISSING_REVISION = -1L;

    public StagingRevisionSnapshot {
        if (levelEpoch < 0L) {
            throw new IllegalArgumentException("levelEpoch must be non-negative");
        }
        Objects.requireNonNull(chunkRevisions, "chunkRevisions");
        LinkedHashMap<Long, Long> copy = new LinkedHashMap<>(chunkRevisions.size());
        chunkRevisions.forEach((chunkKey, revision) -> {
            Objects.requireNonNull(chunkKey, "chunk key");
            Objects.requireNonNull(revision, "chunk revision");
            if (revision < 0L) {
                throw new IllegalArgumentException("chunk revisions must be non-negative");
            }
            copy.put(chunkKey, revision);
        });
        chunkRevisions = Map.copyOf(copy);
    }

    /** Must be called against live state on the client thread immediately before publication. */
    public Validation validate(CurrentRevisions current) {
        Objects.requireNonNull(current, "current");
        long currentEpoch = current.levelEpoch();
        if (currentEpoch != this.levelEpoch) {
            return new Validation(Status.EPOCH_MISMATCH, 0L, this.levelEpoch, currentEpoch);
        }

        for (Map.Entry<Long, Long> entry : this.chunkRevisions.entrySet()) {
            long chunkKey = entry.getKey();
            long expectedRevision = entry.getValue();
            long actualRevision = current.chunkRevision(chunkKey);
            if (actualRevision == MISSING_REVISION) {
                return new Validation(Status.CHUNK_MISSING, chunkKey, expectedRevision, MISSING_REVISION);
            }
            if (actualRevision != expectedRevision) {
                return new Validation(Status.REVISION_MISMATCH, chunkKey, expectedRevision, actualRevision);
            }
        }
        return new Validation(Status.CURRENT, 0L, 0L, 0L);
    }

    public interface CurrentRevisions {

        long levelEpoch();

        /** Returns a non-negative revision, or {@link #MISSING_REVISION} when unloaded. */
        long chunkRevision(long chunkKey);
    }

    public enum Status {
        CURRENT,
        EPOCH_MISMATCH,
        CHUNK_MISSING,
        REVISION_MISMATCH
    }

    public record Validation(Status status, long chunkKey, long expected, long actual) {

        public Validation {
            Objects.requireNonNull(status, "status");
        }

        public boolean isCurrent() {
            return this.status == Status.CURRENT;
        }
    }
}
