package net.easecation.ecclientlight.light;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class RetainedLightCacheTest {

    private static final int STANDARD_LIGHT_SECTIONS = 26;
    private static final long TTL = Duration.ofSeconds(120).toNanos();

    @Test
    void defaultsReserveLessThanSixteenMiBForAStandardWorld() {
        long perColumn = RetainedLightCache.estimateColumnBytes(STANDARD_LIGHT_SECTIONS);

        assertEquals(128, RetainedLightCache.resolveMaxChunks(null));
        assertEquals(16L * 1024L * 1024L, RetainedLightCache.resolveMaxBytes(null));
        assertEquals(TTL, RetainedLightCache.resolveTtlNanos(null));
        assertTrue(perColumn * 128L < 16L * 1024L * 1024L);
    }

    @Test
    void policyOverridesAreClampedAndZeroDisablesCaching() {
        assertEquals(0, RetainedLightCache.resolveMaxChunks(-1));
        assertEquals(1024, RetainedLightCache.resolveMaxChunks(Integer.MAX_VALUE));
        assertEquals(0L, RetainedLightCache.resolveMaxBytes(-1L));
        assertEquals(128L * 1024L * 1024L, RetainedLightCache.resolveMaxBytes(Long.MAX_VALUE));
        assertEquals(0L, RetainedLightCache.resolveTtlNanos(-1L));
        assertEquals(Duration.ofSeconds(600).toNanos(), RetainedLightCache.resolveTtlNanos(Long.MAX_VALUE));

        RetainedLightCache disabled = new RetainedLightCache(0, Long.MAX_VALUE, TTL);
        assertFalse(disabled.reserve(1L, -1, STANDARD_LIGHT_SECTIONS, 0L).accepted());
        assertEquals(0, disabled.size());
    }

    @Test
    void pendingRetentionBecomesClaimableOnlyAfterAnEngineCommit() {
        RetainedLightCache cache = new RetainedLightCache(4, 1024L * 1024L, TTL);
        long chunk = RelightTargets.chunkKey(4, -7);

        assertTrue(cache.reserve(chunk, -5, STANDARD_LIGHT_SECTIONS, 100L).accepted());
        assertFalse(cache.shouldPreserveIncomingLight(chunk, -5, STANDARD_LIGHT_SECTIONS, 101L));
        cache.promotePending();
        assertTrue(cache.shouldPreserveIncomingLight(chunk, -5, STANDARD_LIGHT_SECTIONS, 101L));
        assertFalse(cache.shouldPreserveIncomingLight(chunk, -4, STANDARD_LIGHT_SECTIONS, 101L));
        assertFalse(cache.shouldPreserveIncomingLight(chunk, -5, STANDARD_LIGHT_SECTIONS + 1, 101L));

        RetainedLightCache.Entry claimed = cache.claimReady(chunk);
        assertNotNull(claimed);
        assertEquals(chunk, claimed.chunkKey());
        assertEquals(1L, cache.hits());
        assertEquals(0, cache.size());
        assertEquals(0L, cache.retainedBytes());
    }

    @Test
    void byteAndEntryBudgetsEvictTheOldestColumnWithoutExceedingReservation() {
        long perColumn = RetainedLightCache.estimateColumnBytes(STANDARD_LIGHT_SECTIONS);
        RetainedLightCache cache = new RetainedLightCache(2, perColumn * 2L, TTL);

        assertTrue(cache.reserve(1L, -1, STANDARD_LIGHT_SECTIONS, 0L).accepted());
        assertTrue(cache.reserve(2L, -1, STANDARD_LIGHT_SECTIONS, 0L).accepted());
        RetainedLightCache.Reservation newest = cache.reserve(3L, -1, STANDARD_LIGHT_SECTIONS, 0L);
        assertTrue(newest.accepted());
        assertEquals(1, newest.evicted().size());
        assertEquals(1L, newest.evicted().getFirst().chunkKey());
        assertEquals(2, cache.size());
        assertEquals(perColumn * 2L, cache.retainedBytes());
        assertEquals(1L, cache.evictions());
        assertEquals(0L, cache.rejected());
        cache.promotePending();
        assertFalse(cache.shouldPreserveIncomingLight(1L, -1, STANDARD_LIGHT_SECTIONS, 1L));
        assertTrue(cache.shouldPreserveIncomingLight(3L, -1, STANDARD_LIGHT_SECTIONS, 1L));

        RetainedLightCache tallWorld = new RetainedLightCache(128, perColumn, TTL);
        assertFalse(tallWorld.reserve(4L, -64, STANDARD_LIGHT_SECTIONS * 2, 0L).accepted());
        assertEquals(0L, tallWorld.retainedBytes());
    }

    @Test
    void expirationEvictsAtMostOneColumnPerPoll() {
        RetainedLightCache cache = new RetainedLightCache(4, 1024L * 1024L, 10L);
        assertTrue(cache.reserve(1L, 0, 1, 100L).accepted());
        assertTrue(cache.reserve(2L, 0, 1, 101L).accepted());
        cache.promotePending();

        assertNull(cache.pollExpired(109L));
        assertEquals(1L, cache.pollExpired(110L).chunkKey());
        assertEquals(1, cache.size());
        assertEquals(2L, cache.pollExpired(111L).chunkKey());
        assertEquals(0, cache.size());
        assertEquals(2L, cache.evictions());
    }

    @Test
    void discardAndClearRemovePendingTokensAndReservations() {
        RetainedLightCache cache = new RetainedLightCache(4, 1024L * 1024L, TTL);
        assertTrue(cache.reserve(1L, 0, 1, 0L).accepted());
        assertNotNull(cache.discard(1L));
        cache.promotePending();
        assertNull(cache.claimReady(1L));

        assertTrue(cache.reserve(2L, 0, 1, 0L).accepted());
        cache.clear();
        cache.promotePending();
        assertEquals(0, cache.size());
        assertEquals(0L, cache.retainedBytes());
    }
}
