package net.easecation.ecclientlight.light.staging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StagingRevisionSnapshotTest {

    @Test
    void defensivelyCopiesRevisionMap() {
        Map<Long, Long> mutable = new HashMap<>();
        mutable.put(11L, 3L);
        StagingRevisionSnapshot snapshot = new StagingRevisionSnapshot(5L, mutable);

        mutable.put(11L, 9L);
        mutable.put(12L, 4L);
        assertEquals(Map.of(11L, 3L), snapshot.chunkRevisions());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.chunkRevisions().put(13L, 1L));
    }

    @Test
    void validatesEpochPresenceAndEveryCapturedRevision() {
        StagingRevisionSnapshot snapshot = new StagingRevisionSnapshot(5L, Map.of(42L, 7L));

        StagingRevisionSnapshot.Validation current = snapshot.validate(new Current(5L, Map.of(42L, 7L)));
        assertTrue(current.isCurrent());
        assertEquals(StagingRevisionSnapshot.Status.CURRENT, current.status());

        StagingRevisionSnapshot.Validation epoch = snapshot.validate(new Current(6L, Map.of(42L, 7L)));
        assertEquals(StagingRevisionSnapshot.Status.EPOCH_MISMATCH, epoch.status());
        assertEquals(5L, epoch.expected());
        assertEquals(6L, epoch.actual());

        StagingRevisionSnapshot.Validation missing = snapshot.validate(new Current(5L, Map.of()));
        assertEquals(StagingRevisionSnapshot.Status.CHUNK_MISSING, missing.status());
        assertEquals(42L, missing.chunkKey());
        assertEquals(StagingRevisionSnapshot.MISSING_REVISION, missing.actual());

        StagingRevisionSnapshot.Validation stale = snapshot.validate(new Current(5L, Map.of(42L, 8L)));
        assertEquals(StagingRevisionSnapshot.Status.REVISION_MISMATCH, stale.status());
        assertEquals(7L, stale.expected());
        assertEquals(8L, stale.actual());
    }

    @Test
    void rejectsValuesThatCollideWithMissingRevisionSentinel() {
        assertThrows(IllegalArgumentException.class, () -> new StagingRevisionSnapshot(-1L, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new StagingRevisionSnapshot(0L, Map.of(1L, -1L)));
    }

    private record Current(long levelEpoch, Map<Long, Long> revisions)
        implements StagingRevisionSnapshot.CurrentRevisions {

        @Override
        public long chunkRevision(long chunkKey) {
            return this.revisions.getOrDefault(chunkKey, StagingRevisionSnapshot.MISSING_REVISION);
        }
    }
}
