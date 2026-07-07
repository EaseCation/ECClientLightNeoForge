package net.easecation.ecclientlight;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
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
 * <p><b>Spike stage:</b> {@link #SELF_COMPUTE_LIGHT} is hardcoded on and every chunk is
 * relit locally, ignoring server light. A later stage gates this on a server handshake
 * signal (so vanilla / mod-less sessions keep server-side light as a fallback).
 *
 * <p>All behaviour lives in Mixins; this class only exists so NeoForge recognises the mod.
 */
@Mod(value = ECClientLight.MOD_ID, dist = Dist.CLIENT)
public class ECClientLight {

    public static final String MOD_ID = "ecclientlight";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /** Spike: hardcoded self-compute for all chunks. Later gated by a server handshake signal. */
    public static volatile boolean SELF_COMPUTE_LIGHT = true;

    public ECClientLight(IEventBus modEventBus) {
        LOGGER.info("[ECClientLight] Initialized. Self-compute light (spike) = {}", SELF_COMPUTE_LIGHT);
    }
}
