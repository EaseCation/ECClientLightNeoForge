package net.easecation.ecclientlight.mixin;

import net.easecation.ecclientlight.ECClientLight;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Spike: make the Java client recompute chunk lighting locally instead of trusting the server.
 *
 * <p>Injection points (1.21.8 {@code ClientPacketListener}):
 * <ul>
 *   <li>{@code applyLightData} (HEAD, cancellable): in self-compute mode, skip loading the server
 *       light nibbles ({@code readSectionList}); only {@code setLightEnabled} for the column, then
 *       cancel — the local engine will derive light from block data instead.</li>
 *   <li>{@code enableChunkLight} (RETURN): vanilla has already called {@code updateSectionStatus}
 *       for every section (and {@code setSectionRangeDirty}), so section state is ready. Drive the
 *       local {@link LevelLightEngine}: {@code propagateLightSources} seeds sky light from the
 *       chunk heightmap and block light from block emission, then {@code runLightUpdates} spreads it.</li>
 * </ul>
 *
 * <p>Sodium is not touched: {@code runLightUpdates} triggers {@code ClientChunkCache.onLightUpdate}
 * -> {@code LevelRenderer.setSectionDirty}, which Sodium redirects into a chunk-mesh rebuild.
 *
 * <p>Spike scope: initial chunk load only. Standalone {@code ClientboundLightUpdatePacket}s (which
 * ViaBedrock's async engine emits when experimental light is on) are intercepted but not re-lit here;
 * the target setup sends placeholder light with experimental light off, so those don't occur. The
 * per-chunk {@code runLightUpdates} is intentionally eager for the spike (verify it lights up first);
 * batching / relying on the vanilla per-tick pump is a later optimisation.
 */
@Mixin(ClientPacketListener.class)
public class ClientPacketListenerMixin {

    @Shadow
    private ClientLevel level;

    @Inject(method = "applyLightData", at = @At("HEAD"), cancellable = true)
    private void ecclientlight$skipServerLight(int chunkX, int chunkZ, ClientboundLightUpdatePacketData data, boolean bl, CallbackInfo ci) {
        if (!ECClientLight.SELF_COMPUTE_LIGHT) {
            return;
        }
        // Do not feed server nibbles; just enable the column so the local engine owns it.
        this.level.getChunkSource().getLightEngine().setLightEnabled(new ChunkPos(chunkX, chunkZ), true);
        ci.cancel();
    }

    @Inject(method = "enableChunkLight", at = @At("RETURN"))
    private void ecclientlight$recompute(LevelChunk chunk, int chunkX, int chunkZ, CallbackInfo ci) {
        if (!ECClientLight.SELF_COMPUTE_LIGHT) {
            return;
        }
        final LevelLightEngine lightEngine = this.level.getChunkSource().getLightEngine();
        final ChunkPos pos = new ChunkPos(chunkX, chunkZ);
        // Sky light from heightmap sources + block light from emission, then spread.
        lightEngine.propagateLightSources(pos);
        lightEngine.runLightUpdates();

        if (!ecclientlight$firstLogged) {
            ecclientlight$firstLogged = true;
            ECClientLight.LOGGER.info("[ECClientLight] Local relight active — recomputed light for first chunk {},{}", chunkX, chunkZ);
        }
    }

    private static boolean ecclientlight$firstLogged = false;
}
