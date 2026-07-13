package net.easecation.ecclientlight.light;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PublicationGuardSetTest {

    @Test
    void keepsExactlyOneGuardPerPartiallyWrittenColumn() {
        PublicationGuardSet guards = new PublicationGuardSet();
        long column = RelightTargets.chunkKey(4, -3);

        PublicationGuardSet.Guard guard = guards.add(column, 17L);

        assertEquals(1, guards.size());
        assertEquals(17L, guard.overlayEpoch());
        assertTrue(guards.contains(column));
        assertThrows(IllegalStateException.class, () -> guards.add(column, 18L));
        assertEquals(1, guards.size());
    }

    @Test
    void overlappingDirtyReferencesRemainUntilTheirLastGuardLeaves() {
        PublicationGuardSet guards = new PublicationGuardSet();
        long first = RelightTargets.chunkKey(0, 0);
        long second = RelightTargets.chunkKey(1, 0);
        long sharedDirtyColumn = RelightTargets.chunkKey(0, 0);
        long firstOnlyDirtyColumn = RelightTargets.chunkKey(-1, 0);

        guards.add(first, 1L);
        guards.add(second, 2L);
        assertTrue(guards.protectsDirtyColumn(sharedDirtyColumn));
        assertTrue(guards.protectsDirtyColumn(firstOnlyDirtyColumn));

        assertNotNull(guards.remove(first));
        assertTrue(guards.protectsDirtyColumn(sharedDirtyColumn));
        assertFalse(guards.protectsDirtyColumn(firstOnlyDirtyColumn));
        assertNotNull(guards.remove(second));
        assertFalse(guards.protectsDirtyColumn(sharedDirtyColumn));
        assertEquals(0, guards.size());
        assertNull(guards.remove(second));
    }

    @Test
    void clearDropsAllColumnAndDirtyProtection() {
        PublicationGuardSet guards = new PublicationGuardSet();
        long column = RelightTargets.chunkKey(8, 9);
        guards.add(column, 99L);

        guards.clear();

        assertEquals(0, guards.size());
        assertFalse(guards.contains(column));
        assertFalse(guards.protectsDirtyColumn(column));
    }
}
