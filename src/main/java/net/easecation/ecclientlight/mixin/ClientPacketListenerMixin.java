package net.easecation.ecclientlight.mixin;

import java.util.BitSet;
import java.util.Iterator;
import javax.annotation.Nullable;
import net.easecation.ecclientlight.light.ClientLightManager;
import net.easecation.ecclientlight.light.ClientPacketLightPolicy;
import net.easecation.ecclientlight.network.ClientLightSession;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Redirects chunk light ownership only after the configuration-phase capability ACK. */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {

    @Shadow
    @Nullable
    private ClientLevel level;

    @Unique
    @Nullable
    private ClientLevel ecclientlight$levelBeforeRespawn;

    @Inject(method = "applyLightData", at = @At("HEAD"), cancellable = true)
    private void ecclientlight$skipServerLight(
        int chunkX,
        int chunkZ,
        ClientboundLightUpdatePacketData data,
        boolean updateTrustEdges,
        CallbackInfo ci
    ) {
        if (ClientPacketLightPolicy.shouldSuppress(this.ecclientlight$isLocalLightActive(), updateTrustEdges)) {
            // Combined chunk packets must return normally so renderer integrations observe light readiness.
            ci.cancel();
        }
    }

    @Inject(method = "readSectionList", at = @At("HEAD"), cancellable = true)
    private void ecclientlight$preserveRetainedLight(
        int chunkX,
        int chunkZ,
        LevelLightEngine lightEngine,
        LightLayer lightLayer,
        BitSet dataMask,
        BitSet emptyMask,
        Iterator<byte[]> updates,
        boolean updateTrustEdges,
        CallbackInfo ci
    ) {
        boolean localLightActive = this.ecclientlight$isLocalLightActive();
        boolean retainedLightReady = localLightActive
            && this.level != null
            && ClientLightManager.shouldPreserveRetainedLight(this.level, chunkX, chunkZ);
        if (
            ClientPacketLightPolicy.shouldPreserveRetained(
                localLightActive,
                updateTrustEdges,
                retainedLightReady
            )
        ) {
            // Keep applyLightData itself running so Sodium still observes its RETURN readiness hook.
            ci.cancel();
        }
    }

    @Inject(method = "updateLevelChunk", at = @At("HEAD"))
    private void ecclientlight$invalidateBeforeChunkMutation(
        int chunkX,
        int chunkZ,
        ClientboundLevelChunkPacketData data,
        CallbackInfo ci
    ) {
        if (this.ecclientlight$isLocalLightActive() && this.level != null) {
            ClientLightManager.onFullChunkDataIngress(this.level, chunkX, chunkZ);
        }
    }

    @Inject(method = "enableChunkLight", at = @At("RETURN"))
    private void ecclientlight$queueLocalLight(LevelChunk chunk, int chunkX, int chunkZ, CallbackInfo ci) {
        if (!this.ecclientlight$isLocalLightActive() || this.level == null) {
            return;
        }

        ClientLightManager.onFullChunkData(this.level, chunk);
    }

    @Inject(method = "clearLevel", at = @At("HEAD"))
    private void ecclientlight$clearLevelState(CallbackInfo ci) {
        if (this.level != null) {
            ClientLightManager.clearLevel(this.level);
        }
    }

    @Inject(method = "handleRespawn", at = @At("HEAD"))
    private void ecclientlight$captureLevelBeforeRespawn(ClientboundRespawnPacket packet, CallbackInfo ci) {
        this.ecclientlight$levelBeforeRespawn = this.level;
    }

    @Inject(method = "handleRespawn", at = @At("RETURN"))
    private void ecclientlight$clearReplacedLevel(ClientboundRespawnPacket packet, CallbackInfo ci) {
        ClientLevel previous = this.ecclientlight$levelBeforeRespawn;
        this.ecclientlight$levelBeforeRespawn = null;
        if (previous != null && previous != this.level) {
            ClientLightManager.clearLevel(previous);
        }
    }

    private boolean ecclientlight$isLocalLightActive() {
        ClientPacketListener listener = (ClientPacketListener)(Object)this;
        return ClientLightSession.isConfirmed(listener.getConnection());
    }
}
