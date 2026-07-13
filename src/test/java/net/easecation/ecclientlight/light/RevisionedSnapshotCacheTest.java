package net.easecation.ecclientlight.light;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RevisionedSnapshotCacheTest {

    @Test
    void reusesOnlyTheExactChunkRevision() {
        RevisionedSnapshotCache<Object> cache = new RevisionedSnapshotCache<>(4, 100L);
        Object snapshot = new Object();
        assertTrue(cache.put(7L, 3L, snapshot, 20L));

        assertSame(snapshot, cache.get(7L, 3L));
        assertNull(cache.get(7L, 4L));
        assertEquals(0, cache.size());
        assertEquals(1L, cache.hits());
        assertEquals(1L, cache.misses());
    }

    @Test
    void entryAndByteLimitsEvictLeastRecentlyUsedSnapshots() {
        RevisionedSnapshotCache<String> cache = new RevisionedSnapshotCache<>(2, 50L);
        assertTrue(cache.put(1L, 1L, "one", 20L));
        assertTrue(cache.put(2L, 1L, "two", 20L));
        assertEquals("one", cache.get(1L, 1L));
        assertTrue(cache.put(3L, 1L, "three", 20L));

        assertNull(cache.get(2L, 1L));
        assertEquals("one", cache.get(1L, 1L));
        assertEquals("three", cache.get(3L, 1L));
        assertEquals(40L, cache.retainedBytes());
        assertEquals(1L, cache.evictions());
    }

    @Test
    void oversizedSnapshotsAreUsedByTheCallerButNeverRetained() {
        RevisionedSnapshotCache<String> cache = new RevisionedSnapshotCache<>(2, 10L);
        assertFalse(cache.put(1L, 1L, "large", 11L));
        assertNull(cache.get(1L, 1L));
        assertEquals(0L, cache.retainedBytes());
    }

    @Test
    void invalidateAndClearReleaseAllAccounting() {
        RevisionedSnapshotCache<String> cache = new RevisionedSnapshotCache<>(2, 100L);
        assertTrue(cache.put(1L, 1L, "one", 20L));
        assertTrue(cache.put(2L, 1L, "two", 30L));
        cache.invalidate(1L);
        assertEquals(30L, cache.retainedBytes());
        cache.clear();
        assertEquals(0, cache.size());
        assertEquals(0L, cache.retainedBytes());
    }
}
