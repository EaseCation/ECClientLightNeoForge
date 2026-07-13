package net.easecation.ecclientlight.light;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

/** Tracks one bounded last-good overlay owner for every partially written column. */
final class PublicationGuardSet {

    private static final int RENDER_DIRTY_RADIUS = 1;

    private final Map<Long, Guard> guards = new HashMap<>();
    private final Map<Long, Integer> dirtyReferences = new HashMap<>();

    Guard add(long columnKey, long overlayEpoch) {
        if (overlayEpoch <= 0L) {
            throw new IllegalArgumentException("overlayEpoch must be positive");
        }
        if (this.guards.containsKey(columnKey)) {
            throw new IllegalStateException("A publication guard already protects column " + columnKey);
        }

        int chunkX = (int)columnKey;
        int chunkZ = (int)(columnKey >>> 32);
        LinkedHashSet<Long> dirtyColumns = new LinkedHashSet<>();
        for (long dirtyKey : RelightTargets.square(chunkX, chunkZ, RENDER_DIRTY_RADIUS)) {
            dirtyColumns.add(dirtyKey);
            this.dirtyReferences.merge(dirtyKey, 1, Math::addExact);
        }
        Guard guard = new Guard(columnKey, overlayEpoch, Set.copyOf(dirtyColumns));
        this.guards.put(columnKey, guard);
        return guard;
    }

    @Nullable
    Guard get(long columnKey) {
        return this.guards.get(columnKey);
    }

    boolean contains(long columnKey) {
        return this.guards.containsKey(columnKey);
    }

    boolean protectsDirtyColumn(long columnKey) {
        return this.dirtyReferences.containsKey(columnKey);
    }

    @Nullable
    Guard remove(long columnKey) {
        Guard guard = this.guards.remove(columnKey);
        if (guard == null) {
            return null;
        }
        for (long dirtyKey : guard.dirtyColumns) {
            Integer references = this.dirtyReferences.get(dirtyKey);
            if (references == null || references <= 0) {
                throw new IllegalStateException("Publication guard dirty reference underflow");
            }
            if (references == 1) {
                this.dirtyReferences.remove(dirtyKey);
            } else {
                this.dirtyReferences.put(dirtyKey, references - 1);
            }
        }
        return guard;
    }

    int size() {
        return this.guards.size();
    }

    void clear() {
        this.guards.clear();
        this.dirtyReferences.clear();
    }

    record Guard(long columnKey, long overlayEpoch, Set<Long> dirtyColumns) {

        Guard {
            if (overlayEpoch <= 0L) {
                throw new IllegalArgumentException("overlayEpoch must be positive");
            }
            dirtyColumns = Set.copyOf(dirtyColumns);
        }
    }
}
