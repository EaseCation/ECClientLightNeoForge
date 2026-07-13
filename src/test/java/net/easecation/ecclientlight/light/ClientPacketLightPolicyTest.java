package net.easecation.ecclientlight.light;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ClientPacketLightPolicyTest {

    @Test
    void combinedChunkLightReturnsNormallyForRendererReadiness() {
        assertFalse(ClientPacketLightPolicy.shouldSuppress(true, false));
    }

    @Test
    void standaloneServerLightIsSuppressedOnlyInLocalLightMode() {
        assertTrue(ClientPacketLightPolicy.shouldSuppress(true, true));
        assertFalse(ClientPacketLightPolicy.shouldSuppress(false, true));
    }

    @Test
    void retainedLayersReplaceOnlyCombinedPlaceholderLight() {
        assertTrue(ClientPacketLightPolicy.shouldPreserveRetained(true, false, true));
        assertFalse(ClientPacketLightPolicy.shouldPreserveRetained(true, true, true));
        assertFalse(ClientPacketLightPolicy.shouldPreserveRetained(false, false, true));
        assertFalse(ClientPacketLightPolicy.shouldPreserveRetained(true, false, false));
    }
}
