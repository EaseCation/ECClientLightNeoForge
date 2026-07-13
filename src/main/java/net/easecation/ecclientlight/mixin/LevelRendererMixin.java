package net.easecation.ecclientlight.mixin;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import javax.annotation.Nullable;
import net.easecation.ecclientlight.light.ClientLightManager;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps renderer rebuilds behind the same commit boundary as vanilla light storage. */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {

    @Shadow
    @Nullable
    private ClientLevel level;

    @Inject(
        method = "renderLevel",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;pollLightUpdates()V"
        ),
        require = 1,
        expect = 1,
        allow = 1
    )
    private void ecclientlight$prepareLightUpdatePoll(
        GraphicsResourceAllocator graphicsResourceAllocator,
        DeltaTracker deltaTracker,
        boolean renderBlockOutline,
        Camera camera,
        Matrix4f modelViewMatrix,
        Matrix4f projectionMatrix,
        GpuBufferSlice fogBuffer,
        Vector4f fogColor,
        boolean renderSky,
        CallbackInfo ci
    ) {
        if (this.level != null) {
            ClientLightManager.prepareLightUpdatePoll(this.level);
        }
    }

    @Inject(
        method = "renderLevel",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/lighting/LevelLightEngine;runLightUpdates()I",
            shift = At.Shift.BEFORE
        ),
        require = 1,
        expect = 1,
        allow = 1
    )
    private void ecclientlight$measureLightUpdatesStart(
        GraphicsResourceAllocator graphicsResourceAllocator,
        DeltaTracker deltaTracker,
        boolean renderBlockOutline,
        Camera camera,
        Matrix4f modelViewMatrix,
        Matrix4f projectionMatrix,
        GpuBufferSlice fogBuffer,
        Vector4f fogColor,
        boolean renderSky,
        CallbackInfo ci
    ) {
        if (this.level != null) {
            ClientLightManager.beforeLightEngineRun(this.level);
        }
    }

    @Inject(
        method = "renderLevel",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/lighting/LevelLightEngine;runLightUpdates()I",
            shift = At.Shift.AFTER
        ),
        require = 1,
        expect = 1,
        allow = 1
    )
    private void ecclientlight$publishCommittedLight(
        GraphicsResourceAllocator graphicsResourceAllocator,
        DeltaTracker deltaTracker,
        boolean renderBlockOutline,
        Camera camera,
        Matrix4f modelViewMatrix,
        Matrix4f projectionMatrix,
        GpuBufferSlice fogBuffer,
        Vector4f fogColor,
        boolean renderSky,
        CallbackInfo ci
    ) {
        if (this.level != null) {
            ClientLightManager.afterLightEngineRun(this.level);
        }
    }

    @Inject(method = "setSectionDirty(III)V", at = @At("HEAD"), cancellable = true)
    private void ecclientlight$deferDirtySection(int sectionX, int sectionY, int sectionZ, CallbackInfo ci) {
        if (this.level != null && ClientLightManager.isRenderPending(this.level, sectionX, sectionZ)) {
            ci.cancel();
        }
    }

    @Inject(method = "setSectionDirty(IIIZ)V", at = @At("HEAD"), cancellable = true)
    private void ecclientlight$deferFinalDirtySection(
        int sectionX,
        int sectionY,
        int sectionZ,
        boolean important,
        CallbackInfo ci
    ) {
        if (this.level != null && ClientLightManager.isRenderPending(this.level, sectionX, sectionZ)) {
            ci.cancel();
        }
    }

    @Inject(
        method = "setBlockDirty(Lnet/minecraft/core/BlockPos;Z)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void ecclientlight$deferFinalDirtyBlock(BlockPos pos, boolean important, CallbackInfo ci) {
        if (
            this.level != null
                && ClientLightManager.isRenderPending(
                    this.level,
                    SectionPos.blockToSectionCoord(pos.getX()),
                    SectionPos.blockToSectionCoord(pos.getZ())
                )
        ) {
            ci.cancel();
        }
    }

    @Inject(
        method = "setBlockDirty(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/state/BlockState;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void ecclientlight$deferDirtyBlock(
        BlockPos pos,
        BlockState oldState,
        BlockState newState,
        CallbackInfo ci
    ) {
        if (
            this.level != null
                && ClientLightManager.isRenderPending(
                    this.level,
                    SectionPos.blockToSectionCoord(pos.getX()),
                    SectionPos.blockToSectionCoord(pos.getZ())
                )
        ) {
            ci.cancel();
        }
    }
}
