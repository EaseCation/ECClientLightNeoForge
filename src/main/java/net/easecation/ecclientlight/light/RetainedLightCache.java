package net.easecation.ecclientlight.light;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.world.level.chunk.DataLayer;

/** Main-thread-only metadata for vanilla light columns retained across a short unload. */
final class RetainedLightCache {

    static final int DEFAULT_MAX_CHUNKS = 128;
    static final long DEFAULT_MAX_BYTES = 16L * 1024L * 1024L;
    static final long DEFAULT_TTL_SECONDS = 120L;
    private static final int ABSOLUTE_MAX_CHUNKS = 1024;
    private static final long ABSOLUTE_MAX_BYTES = 128L * 1024L * 1024L;
    private static final long ABSOLUTE_MAX_TTL_SECONDS = 600L;
    private static final long ENTRY_OVERHEAD_BYTES = 256L;
    private static final long LAYER_OVERHEAD_BYTES = 64L;

    private final int maxChunks;
    private final long maxBytes;
    private final long ttlNanos;
    private final LinkedHashMap<Long, Entry> entries = new LinkedHashMap<>();
    private final Set<Long> pendingPromotion = new LinkedHashSet<>();
    private long retainedBytes;
    private long hits;
    private long misses;
    private long rejected;
    private long evictions;

    RetainedLightCache(int maxChunks, long maxBytes, long ttlNanos) {
        this.maxChunks = Math.max(0, maxChunks);
        this.maxBytes = Math.max(0L, maxBytes);
        this.ttlNanos = Math.max(0L, ttlNanos);
    }

    static int resolveMaxChunks(@Nullable Integer configured) {
        if (configured == null) {
            return DEFAULT_MAX_CHUNKS;
        }
        return Math.clamp(configured, 0, ABSOLUTE_MAX_CHUNKS);
    }

    static long resolveMaxBytes(@Nullable Long configured) {
        if (configured == null) {
            return DEFAULT_MAX_BYTES;
        }
        return Math.clamp(configured, 0L, ABSOLUTE_MAX_BYTES);
    }

    static long resolveTtlNanos(@Nullable Long configuredSeconds) {
        long seconds = configuredSeconds == null
            ? DEFAULT_TTL_SECONDS
            : Math.clamp(configuredSeconds, 0L, ABSOLUTE_MAX_TTL_SECONDS);
        return Duration.ofSeconds(seconds).toNanos();
    }

    Reservation reserve(long chunkKey, int minLightSection, int lightSectionCount, long nowNanos) {
        if (this.entries.containsKey(chunkKey)) {
            return new Reservation(false, List.of());
        }
        long reservedBytes = estimateColumnBytes(lightSectionCount);
        if (
            this.maxChunks == 0
                || this.maxBytes == 0L
                || this.ttlNanos == 0L
                || reservedBytes > this.maxBytes
        ) {
            this.rejected++;
            return new Reservation(false, List.of());
        }

        List<Entry> evicted = new ArrayList<>();
        while (
            this.entries.size() >= this.maxChunks
                || reservedBytes > this.maxBytes - this.retainedBytes
        ) {
            Entry oldest = this.removeOldest();
            if (oldest == null) {
                this.rejected++;
                return new Reservation(false, List.copyOf(evicted));
            }
            evicted.add(oldest);
            this.evictions++;
        }
        this.entries.put(
            chunkKey,
            new Entry(
                chunkKey,
                minLightSection,
                lightSectionCount,
                reservedBytes,
                saturatingAdd(nowNanos, this.ttlNanos),
                false
            )
        );
        this.pendingPromotion.add(chunkKey);
        this.retainedBytes += reservedBytes;
        return new Reservation(true, List.copyOf(evicted));
    }

    void promotePending() {
        for (long chunkKey : this.pendingPromotion) {
            Entry entry = this.entries.get(chunkKey);
            if (entry != null && !entry.ready()) {
                this.entries.put(chunkKey, entry.withReady());
            }
        }
        this.pendingPromotion.clear();
    }

    boolean shouldPreserveIncomingLight(
        long chunkKey,
        int minLightSection,
        int lightSectionCount,
        long nowNanos
    ) {
        Entry entry = this.entries.get(chunkKey);
        return entry != null
            && entry.ready()
            && entry.minLightSection() == minLightSection
            && entry.lightSectionCount() == lightSectionCount
            && entry.expiresAtNanos() > nowNanos;
    }

    @Nullable
    Entry claimReady(long chunkKey) {
        Entry entry = this.remove(chunkKey);
        if (entry == null || !entry.ready()) {
            this.misses++;
            return null;
        }
        this.hits++;
        return entry;
    }

    @Nullable
    Entry discard(long chunkKey) {
        return this.remove(chunkKey);
    }

    @Nullable
    Entry pollExpired(long nowNanos) {
        Iterator<Map.Entry<Long, Entry>> iterator = this.entries.entrySet().iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next().getValue();
            if (entry.expiresAtNanos() > nowNanos) {
                return null;
            }
            iterator.remove();
            this.pendingPromotion.remove(entry.chunkKey());
            this.retainedBytes -= entry.reservedBytes();
            this.evictions++;
            return entry;
        }
        return null;
    }

    int size() {
        return this.entries.size();
    }

    long retainedBytes() {
        return this.retainedBytes;
    }

    long hits() {
        return this.hits;
    }

    long misses() {
        return this.misses;
    }

    long rejected() {
        return this.rejected;
    }

    long evictions() {
        return this.evictions;
    }

    void clear() {
        this.entries.clear();
        this.pendingPromotion.clear();
        this.retainedBytes = 0L;
    }

    static long estimateColumnBytes(int lightSectionCount) {
        if (lightSectionCount <= 0) {
            return ENTRY_OVERHEAD_BYTES;
        }
        return ENTRY_OVERHEAD_BYTES
            + (long)lightSectionCount * 2L * (DataLayer.SIZE + LAYER_OVERHEAD_BYTES);
    }

    @Nullable
    private Entry remove(long chunkKey) {
        Entry entry = this.entries.remove(chunkKey);
        if (entry != null) {
            this.pendingPromotion.remove(chunkKey);
            this.retainedBytes -= entry.reservedBytes();
        }
        return entry;
    }

    @Nullable
    private Entry removeOldest() {
        Iterator<Map.Entry<Long, Entry>> iterator = this.entries.entrySet().iterator();
        if (!iterator.hasNext()) {
            return null;
        }
        Entry entry = iterator.next().getValue();
        iterator.remove();
        this.pendingPromotion.remove(entry.chunkKey());
        this.retainedBytes -= entry.reservedBytes();
        return entry;
    }

    private static long saturatingAdd(long left, long right) {
        long result = left + right;
        return result < left ? Long.MAX_VALUE : result;
    }

    record Entry(
        long chunkKey,
        int minLightSection,
        int lightSectionCount,
        long reservedBytes,
        long expiresAtNanos,
        boolean ready
    ) {
        private Entry withReady() {
            return new Entry(
                this.chunkKey,
                this.minLightSection,
                this.lightSectionCount,
                this.reservedBytes,
                this.expiresAtNanos,
                true
            );
        }
    }

    record Reservation(boolean accepted, List<Entry> evicted) {
    }
}
