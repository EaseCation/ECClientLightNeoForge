package net.easecation.ecclientlight;

import net.easecation.ecclientlight.light.ClientLightManager;
import net.easecation.ecclientlight.network.ClientLightSession;
import net.easecation.ecclientlight.network.ConfirmClientLightPayload;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ECClientLight — client-side chunk light recomputation for the ViaProxy/ViaBedrock
 * (Java client -> Bedrock server) link.
 *
 * <p>Bedrock servers never send per-block light (Bedrock clients compute it themselves),
 * so ViaBedrock currently recomputes light on the ViaProxy server and stuffs it into the
 * outbound Java chunk packets — which dominates server CPU. This mod moves that work to
 * the client: it makes the Java client recompute chunk lighting locally with the vanilla
 * {@link net.minecraft.world.level.lighting.LevelLightEngine} (exactly like Bedrock clients),
 * so the server can stop computing light entirely.
 *
 * <p>The mode is opt-in per connection. ViaBedrock confirms support during the configuration
 * phase; ordinary Java servers continue to use their supplied light unchanged.
 */
@Mod(value = ECClientLight.MOD_ID, dist = Dist.CLIENT)
public class ECClientLight {

    public static final String MOD_ID = "ecclientlight";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static final boolean ENABLED = Boolean.parseBoolean(
        System.getProperty("ecclientlight.enabled", "true")
    );

    public ECClientLight(IEventBus modEventBus) {
        if (!ENABLED) {
            LOGGER.warn("[ECClientLight] Disabled by -Decclientlight.enabled=false; proxy light remains active");
            return;
        }
        if (!ClientLightManager.canNegotiateLocalLight()) {
            LOGGER.error(
                "[ECClientLight] Staging pipeline is unavailable; capability will not be advertised and proxy light remains active"
            );
            return;
        }
        modEventBus.addListener(ECClientLight::registerPayloadHandlers);
        LOGGER.info(
            "[ECClientLight] Initialized; local light remains disabled until ACK ({})",
            ConfirmClientLightPayload.TYPE.id()
        );
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(
                "[ECClientLight] Staging limits: frozen batches={} center(s), packet callbacks<= {}/frame, snapshot/publish budgets={}/{} us, published overlay={} MiB, retained light={} chunks/{} MiB/{} s, snapshot cache={} chunks/{} MiB",
                ClientLightManager.maxBatchCenters(),
                ClientLightManager.maxQueuedLightTasksPerFrame(),
                ClientLightManager.snapshotMicrosPerFrame(),
                ClientLightManager.publishMicrosPerFrame(),
                ClientLightManager.maxOverlayBytes() / (1024L * 1024L),
                ClientLightManager.maxRetainedLightChunks(),
                ClientLightManager.maxRetainedLightBytes() / (1024L * 1024L),
                ClientLightManager.retainedLightSeconds(),
                ClientLightManager.maxSnapshotCacheChunks(),
                ClientLightManager.maxSnapshotCacheBytes() / (1024L * 1024L)
            );
        }
    }

    private static void registerPayloadHandlers(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
            .optional()
            .configurationToClient(
                ConfirmClientLightPayload.TYPE,
                ConfirmClientLightPayload.STREAM_CODEC,
                (payload, context) -> ClientLightSession.confirm(context.connection())
            );
    }

}
