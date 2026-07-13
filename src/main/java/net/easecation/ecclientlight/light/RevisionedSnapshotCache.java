package net.easecation.ecclientlight.light;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nullable;

/** Main-thread-only LRU for immutable staging inputs, keyed by the live chunk revision. */
final class RevisionedSnapshotCache<T> {

    private final int maxEntries;
    private final long maxBytes;
    private final LinkedHashMap<Long, Entry<T>> entries = new LinkedHashMap<>(16, 0.75F, true);
    private long retainedBytes;
    private long hits;
    private long misses;
    private long evictions;

    RevisionedSnapshotCache(int maxEntries, long maxBytes) {
        this.maxEntries = Math.max(0, maxEntries);
        this.maxBytes = Math.max(0L, maxBytes);
    }

    @Nullable
    T get(long chunkKey, long revision) {
        Entry<T> entry = this.entries.get(chunkKey);
        if (entry == null) {
            this.misses++;
            return null;
        }
        if (entry.revision() != revision) {
            this.remove(chunkKey);
            this.misses++;
            return null;
        }
        this.hits++;
        return entry.value();
    }

    boolean put(long chunkKey, long revision, T value, long retainedBytes) {
        if (revision < 0L) {
            throw new IllegalArgumentException("revision must be non-negative");
        }
        if (retainedBytes < 0L) {
            throw new IllegalArgumentException("retainedBytes must be non-negative");
        }
        Objects.requireNonNull(value, "value");
        this.remove(chunkKey);
        if (
            this.maxEntries == 0
                || this.maxBytes == 0L
                || retainedBytes > this.maxBytes
        ) {
            return false;
        }

        while (
            this.entries.size() >= this.maxEntries
                || retainedBytes > this.maxBytes - this.retainedBytes
        ) {
            if (!this.evictOldest()) {
                return false;
            }
        }
        this.entries.put(chunkKey, new Entry<>(revision, value, retainedBytes));
        this.retainedBytes += retainedBytes;
        return true;
    }

    void invalidate(long chunkKey) {
        this.remove(chunkKey);
    }

    void clear() {
        this.entries.clear();
        this.retainedBytes = 0L;
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

    long evictions() {
        return this.evictions;
    }

    @Nullable
    private Entry<T> remove(long chunkKey) {
        Entry<T> removed = this.entries.remove(chunkKey);
        if (removed != null) {
            this.retainedBytes -= removed.retainedBytes();
        }
        return removed;
    }

    private boolean evictOldest() {
        Iterator<Map.Entry<Long, Entry<T>>> iterator = this.entries.entrySet().iterator();
        if (!iterator.hasNext()) {
            return false;
        }
        Entry<T> removed = iterator.next().getValue();
        iterator.remove();
        this.retainedBytes -= removed.retainedBytes();
        this.evictions++;
        return true;
    }

    private record Entry<T>(long revision, T value, long retainedBytes) {
    }
}
