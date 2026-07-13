package net.easecation.ecclientlight.light;

/** Separates standalone light updates from the empty light data carried by full chunks. */
public final class ClientPacketLightPolicy {

    private ClientPacketLightPolicy() {
    }

    public static boolean shouldSuppress(boolean localLightActive, boolean updateTrustEdges) {
        return localLightActive && updateTrustEdges;
    }

    public static boolean shouldPreserveRetained(
        boolean localLightActive,
        boolean updateTrustEdges,
        boolean retainedLightReady
    ) {
        return localLightActive && !updateTrustEdges && retainedLightReady;
    }
}
