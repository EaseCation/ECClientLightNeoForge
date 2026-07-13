package net.easecation.ecclientlight.light;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import org.junit.jupiter.api.Test;

class PublishedLightOverlayTest {

    @Test
    void publishesExactLayersAndPackedLightValues() {
        PublishedLightOverlay overlay = new PublishedLightOverlay(8L * 1024L * 1024L, true);
        long blockPos = BlockPos.asLong(17, 37, 33);
        long sectionKey = SectionPos.blockToSection(blockPos);
        DataLayer block = new DataLayer();
        DataLayer sky = new DataLayer(15);
        block.set(1, 5, 1, 9);

        PublishedLightOverlay.Capture capture = PublishedLightOverlay.newCapture(1);
        capture.put(sectionKey, block, sky);
        assertTrue(overlay.tryOpen(1L, capture).accepted());

        PublishedLightOverlay.SectionSnapshot snapshot = overlay.publishedView().section(sectionKey);
        assertSame(block, snapshot.dataLayer(LightLayer.BLOCK));
        assertSame(sky, snapshot.dataLayer(LightLayer.SKY));
        assertEquals(9, overlay.publishedView().lightValue(LightLayer.BLOCK, blockPos));
        assertEquals(15, overlay.publishedView().lightValue(LightLayer.SKY, blockPos));
        assertEquals(
            PublishedLightOverlay.View.NOT_PROTECTED,
            overlay.publishedView().lightValue(LightLayer.BLOCK, BlockPos.asLong(1000, 37, 1000))
        );
    }

    @Test
    void missingSkyInheritsTheNearestUpperBottomSlice() {
        PublishedLightOverlay overlay = new PublishedLightOverlay(8L * 1024L * 1024L, true);
        long lower = SectionPos.asLong(3, 4, -2);
        long middle = SectionPos.asLong(3, 5, -2);
        long upper = SectionPos.asLong(3, 6, -2);
        DataLayer upperSky = new DataLayer();
        upperSky.set(7, 0, 9, 11);
        upperSky.set(7, 15, 9, 2);
        PublishedLightOverlay.Capture capture = PublishedLightOverlay.newCapture(3);
        capture.put(lower, null, null);
        capture.put(middle, null, null);
        capture.put(upper, null, upperSky);
        assertTrue(overlay.tryOpen(1L, capture).accepted());

        long pos = BlockPos.asLong(SectionPos.sectionToBlockCoord(3, 7), SectionPos.sectionToBlockCoord(4, 8), SectionPos.sectionToBlockCoord(-2, 9));
        assertEquals(11, overlay.publishedView().lightValue(LightLayer.SKY, pos));
        assertEquals(0, overlay.publishedView().lightValue(LightLayer.BLOCK, pos));
    }

    @Test
    void noSkyDimensionAlwaysReturnsZero() {
        PublishedLightOverlay overlay = new PublishedLightOverlay(1024L, false);
        long sectionKey = SectionPos.asLong(0, 0, 0);
        PublishedLightOverlay.Capture capture = PublishedLightOverlay.newCapture(1);
        capture.put(sectionKey, new DataLayer(), new DataLayer(15));
        assertTrue(overlay.tryOpen(1L, capture).accepted());
        assertEquals(0, overlay.publishedView().lightValue(LightLayer.SKY, BlockPos.asLong(1, 1, 1)));
    }

    @Test
    void overlappingEpochsHandVisibilityToTheNextOwner() {
        PublishedLightOverlay overlay = new PublishedLightOverlay(8L * 1024L * 1024L, true);
        long a = SectionPos.asLong(0, 0, 0);
        long b = SectionPos.asLong(1, 0, 0);
        long c = SectionPos.asLong(2, 0, 0);
        DataLayer firstB = new DataLayer(4);
        PublishedLightOverlay.Capture first = PublishedLightOverlay.newCapture(2);
        first.put(a, new DataLayer(1), new DataLayer(15));
        first.put(b, firstB, new DataLayer(15));
        assertTrue(overlay.tryOpen(1L, first).accepted());

        PublishedLightOverlay.View firstView = overlay.publishedView();
        DataLayer secondB = new DataLayer(12);
        PublishedLightOverlay.Capture sharedOnly = PublishedLightOverlay.newCapture(1);
        sharedOnly.put(b, secondB, new DataLayer());
        assertTrue(overlay.tryOpen(2L, sharedOnly).accepted());
        assertSame(firstView, overlay.publishedView());
        assertSame(firstB, overlay.publishedView().section(b).dataLayer(LightLayer.BLOCK));

        PublishedLightOverlay.Capture third = PublishedLightOverlay.newCapture(2);
        third.put(b, new DataLayer(13), new DataLayer());
        third.put(c, new DataLayer(3), new DataLayer(15));
        assertTrue(overlay.tryOpen(3L, third).accepted());
        assertTrue(overlay.release(1L).released());
        assertSame(secondB, overlay.publishedView().section(b).dataLayer(LightLayer.BLOCK));
        assertNull(overlay.publishedView().section(a));
        assertTrue(overlay.release(2L).released());
        assertEquals(13, overlay.publishedView().section(b).dataLayer(LightLayer.BLOCK).get(0, 0, 0));
        assertTrue(overlay.release(3L).released());
        assertTrue(overlay.isEmpty());
        assertEquals(0L, overlay.retainedBytes());
    }

    @Test
    void releaseAllPublishesOneAtomicViewChange() {
        PublishedLightOverlay overlay = new PublishedLightOverlay(8L * 1024L * 1024L, true);
        long key = SectionPos.asLong(0, 0, 0);
        PublishedLightOverlay.Capture first = PublishedLightOverlay.newCapture(1);
        first.put(key, new DataLayer(1), new DataLayer(15));
        PublishedLightOverlay.Capture second = PublishedLightOverlay.newCapture(1);
        second.put(key, new DataLayer(2), new DataLayer(15));
        assertTrue(overlay.tryOpen(1L, first).accepted());
        assertTrue(overlay.tryOpen(2L, second).accepted());
        long beforeRelease = overlay.publishedView().version();

        assertTrue(overlay.releaseAll(1L, 2L).released());
        assertEquals(beforeRelease + 1L, overlay.publishedView().version());
        assertEquals(0, overlay.publishedView().size());
    }

    @Test
    void opensColumnEpochsWithOneAtomicViewPublication() {
        PublishedLightOverlay overlay = new PublishedLightOverlay(8L * 1024L * 1024L, true);
        PublishedLightOverlay.Capture first = PublishedLightOverlay.newCapture(1);
        PublishedLightOverlay.Capture second = PublishedLightOverlay.newCapture(1);
        long firstKey = SectionPos.asLong(0, 0, 0);
        long secondKey = SectionPos.asLong(1, 0, 0);
        first.put(firstKey, new DataLayer(3), new DataLayer(15));
        second.put(secondKey, new DataLayer(7), new DataLayer(15));
        long before = overlay.publishedView().version();

        assertTrue(overlay.tryOpenBatch(10L, List.of(first, second)).accepted());
        assertEquals(before + 1L, overlay.publishedView().version());
        assertEquals(3, overlay.publishedView().section(firstKey).dataLayer(LightLayer.BLOCK).get(0, 0, 0));
        assertEquals(7, overlay.publishedView().section(secondKey).dataLayer(LightLayer.BLOCK).get(0, 0, 0));
        assertTrue(overlay.releaseAll(10L, 11L).released());
        assertTrue(overlay.isEmpty());
    }

    @Test
    void updatesOneColumnWhenDifferentHeightSectionsEnterAndLeave() {
        PublishedLightOverlay overlay = new PublishedLightOverlay(8L * 1024L * 1024L, true);
        long lowerKey = SectionPos.asLong(4, -2, 7);
        long upperKey = SectionPos.asLong(4, 3, 7);
        PublishedLightOverlay.Capture lower = PublishedLightOverlay.newCapture(1);
        lower.put(lowerKey, new DataLayer(2), new DataLayer(15));
        PublishedLightOverlay.Capture upper = PublishedLightOverlay.newCapture(1);
        upper.put(upperKey, new DataLayer(9), new DataLayer(15));
        assertTrue(overlay.tryOpen(1L, lower).accepted());
        long lowerVersion = overlay.publishedView().version();

        assertTrue(overlay.tryOpen(2L, upper).accepted());
        assertEquals(lowerVersion + 1L, overlay.publishedView().version());
        assertNotNull(overlay.publishedView().section(lowerKey));
        assertNotNull(overlay.publishedView().section(upperKey));

        assertTrue(overlay.release(2L).released());
        assertEquals(lowerVersion + 2L, overlay.publishedView().version());
        assertNotNull(overlay.publishedView().section(lowerKey));
        assertNull(overlay.publishedView().section(upperKey));
        assertTrue(overlay.release(1L).released());
        assertTrue(overlay.isEmpty());
    }

    @Test
    void batchBudgetRejectionDoesNotOpenAnyColumnEpoch() {
        PublishedLightOverlay sizing = new PublishedLightOverlay(Long.MAX_VALUE, true);
        PublishedLightOverlay.Capture first = PublishedLightOverlay.newCapture(1);
        PublishedLightOverlay.Capture second = PublishedLightOverlay.newCapture(1);
        long firstKey = SectionPos.asLong(0, 0, 0);
        long secondKey = SectionPos.asLong(1, 0, 0);
        first.put(firstKey, new DataLayer(3), new DataLayer(15));
        second.put(secondKey, new DataLayer(7), new DataLayer(15));
        long required = sizing.tryOpenBatch(1L, List.of(first, second)).requiredBytes();
        PublishedLightOverlay overlay = new PublishedLightOverlay(required - 1L, true);

        PublishedLightOverlay.OpenResult rejected = overlay.tryOpenBatch(1L, List.of(first, second));
        assertFalse(rejected.accepted());
        assertEquals(PublishedLightOverlay.Rejection.BUDGET_EXCEEDED, rejected.rejection());
        assertTrue(overlay.isEmpty());
        assertNull(overlay.publishedView().section(firstKey));
        assertNull(overlay.publishedView().section(secondKey));
        assertFalse(overlay.release(1L).released());
        assertFalse(overlay.release(2L).released());
    }

    @Test
    void budgetRejectionIsAtomicAndDoesNotEvictPublishedData() {
        PublishedLightOverlay sizing = new PublishedLightOverlay(Long.MAX_VALUE, true);
        PublishedLightOverlay.Capture first = PublishedLightOverlay.newCapture(1);
        long key = SectionPos.asLong(0, 0, 0);
        DataLayer firstBlock = new DataLayer(15);
        first.put(key, firstBlock, new DataLayer(15));
        long exactBytes = sizing.tryOpen(1L, first).requiredBytes();

        PublishedLightOverlay overlay = new PublishedLightOverlay(exactBytes, true);
        assertTrue(overlay.tryOpen(1L, first).accepted());
        PublishedLightOverlay.View before = overlay.publishedView();
        PublishedLightOverlay.Capture second = PublishedLightOverlay.newCapture(1);
        second.put(SectionPos.asLong(1, 0, 0), new DataLayer(), new DataLayer(15));
        PublishedLightOverlay.OpenResult rejected = overlay.tryOpen(2L, second);
        assertFalse(rejected.accepted());
        assertEquals(PublishedLightOverlay.Rejection.BUDGET_EXCEEDED, rejected.rejection());
        assertSame(before, overlay.publishedView());
        assertSame(firstBlock, overlay.publishedView().section(key).dataLayer(LightLayer.BLOCK));
        assertEquals(exactBytes, overlay.retainedBytes());
    }

    @Test
    void clearPublishesAnEmptyViewButOldReadersRemainStable() {
        PublishedLightOverlay overlay = new PublishedLightOverlay(4096L, true);
        long key = SectionPos.asLong(0, 0, 0);
        PublishedLightOverlay.Capture capture = PublishedLightOverlay.newCapture(1);
        capture.put(key, new DataLayer(7), new DataLayer(15));
        assertTrue(overlay.tryOpen(1L, capture).accepted());
        PublishedLightOverlay.View old = overlay.publishedView();

        overlay.clear();
        assertEquals(1, old.size());
        assertEquals(0, overlay.publishedView().size());
        assertFalse(overlay.release(1L).released());
        assertEquals(PublishedLightOverlay.Rejection.INVALID_EPOCH, overlay.tryOpen(1L, capture).rejection());
        assertTrue(overlay.tryOpen(2L, capture).accepted());
    }

    @Test
    void materializedLayersChargeTheirBackingArray() {
        DataLayer homogeneous = new DataLayer(15);
        DataLayer materialized = new DataLayer(15);
        materialized.getData();
        PublishedLightOverlay.Capture homogeneousCapture = PublishedLightOverlay.newCapture(1);
        homogeneousCapture.put(SectionPos.asLong(0, 0, 0), homogeneous, null);
        PublishedLightOverlay.Capture materializedCapture = PublishedLightOverlay.newCapture(1);
        materializedCapture.put(SectionPos.asLong(0, 0, 0), materialized, null);

        long homogeneousBytes = PublishedLightOverlay.capturedSectionBytes(
            homogeneousCapture,
            SectionPos.asLong(0, 0, 0)
        );
        long materializedBytes = PublishedLightOverlay.capturedSectionBytes(
            materializedCapture,
            SectionPos.asLong(0, 0, 0)
        );
        assertEquals(DataLayer.SIZE, materializedBytes - homogeneousBytes);
    }
}
