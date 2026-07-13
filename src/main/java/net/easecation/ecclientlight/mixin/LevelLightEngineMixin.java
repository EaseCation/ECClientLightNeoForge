package net.easecation.ecclientlight.mixin;

import net.easecation.ecclientlight.light.ClientLightManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keeps dynamic block-light propagation in the staging worker for negotiated worlds. */
@Mixin(LevelLightEngine.class)
public abstract class LevelLightEngineMixin {

    @Inject(method = "checkBlock", at = @At("HEAD"), cancellable = true)
    private void ecclientlight$stageBlockChange(BlockPos pos, CallbackInfo ci) {
        if (ClientLightManager.interceptBlockChange((LevelLightEngine)(Object)this, pos)) {
            ci.cancel();
        }
    }

    @Inject(method = "updateSectionStatus", at = @At("HEAD"), cancellable = true)
    private void ecclientlight$stageSectionStatus(SectionPos pos, boolean empty, CallbackInfo ci) {
        if (ClientLightManager.interceptSectionStatus((LevelLightEngine)(Object)this, pos)) {
            ci.cancel();
        }
    }

    @Inject(method = "lightOnInColumn", at = @At("HEAD"), cancellable = true)
    private void ecclientlight$keepPublishedColumnReady(long zeroNode, CallbackInfoReturnable<Boolean> cir) {
        if (ClientLightManager.isOverlayColumnProtected((LevelLightEngine)(Object)this, zeroNode)) {
            cir.setReturnValue(true);
        }
    }
}
