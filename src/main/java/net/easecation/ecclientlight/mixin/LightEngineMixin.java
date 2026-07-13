package net.easecation.ecclientlight.mixin;

import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.easecation.ecclientlight.light.ClientLightManager;
import net.easecation.ecclientlight.light.LocalLightWorkBudget;
import net.easecation.ecclientlight.light.PublishedLightOverlay;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.LightEngine;
import net.minecraft.world.level.lighting.SkyLightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Worker budgeting plus the immutable last-good publication view used by render consumers. */
@Mixin(LightEngine.class)
public abstract class LightEngineMixin {

    @Inject(method = "getDataLayerData", at = @At("HEAD"), cancellable = true)
    private void ecclientlight$readPublishedLayer(
        SectionPos pos,
        CallbackInfoReturnable<DataLayer> cir
    ) {
        LightEngine<?, ?> engine = (LightEngine<?, ?>)(Object)this;
        PublishedLightOverlay.SectionSnapshot snapshot = ClientLightManager.overlaySection(engine, pos.asLong());
        if (snapshot == null) {
            return;
        }
        LightLayer layer = ClientLightManager.overlayLayer(engine);
        if (layer != null) {
            cir.setReturnValue(snapshot.dataLayer(layer));
        }
    }

    @Inject(method = "getLightValue", at = @At("HEAD"), cancellable = true)
    private void ecclientlight$readPublishedValue(BlockPos pos, CallbackInfoReturnable<Integer> cir) {
        int value = ClientLightManager.overlayLightValue(
            (LightEngine<?, ?>)(Object)this,
            pos.asLong()
        );
        if (value >= 0) {
            cir.setReturnValue(value);
        }
    }

    @Redirect(
        method = "runLightUpdates",
        at = @At(
            value = "INVOKE",
            target = "Lit/unimi/dsi/fastutil/longs/LongIterator;hasNext()Z"
        ),
        require = 1,
        expect = 1,
        allow = 1
    )
    private boolean ecclientlight$limitBlockNodeChecks(LongIterator iterator) {
        if (!iterator.hasNext()) {
            return false;
        }
        return !LocalLightWorkBudget.shouldStop(false, this.ecclientlight$isSkyEngine());
    }

    @Redirect(
        method = "runLightUpdates",
        at = @At(
            value = "INVOKE",
            target = "Lit/unimi/dsi/fastutil/longs/LongIterator;nextLong()J"
        ),
        require = 1,
        expect = 1,
        allow = 1
    )
    private long ecclientlight$removeProcessedBlockNode(LongIterator iterator) {
        long node = iterator.nextLong();
        if (LocalLightWorkBudget.isActive()) {
            iterator.remove();
        }
        return node;
    }

    @Inject(
        method = "runLightUpdates",
        at = @At(
            value = "INVOKE",
            target = "Lit/unimi/dsi/fastutil/longs/LongOpenHashSet;clear()V"
        ),
        cancellable = true,
        require = 1,
        expect = 1,
        allow = 1
    )
    private void ecclientlight$deferRemainingBlockNodes(CallbackInfoReturnable<Integer> cir) {
        if (LocalLightWorkBudget.isActive() && !this.ecclientlight$getBlockNodesToCheck().isEmpty()) {
            this.ecclientlight$clearChunkCache();
            cir.setReturnValue(0);
        }
    }

    @Redirect(
        method = "propagateDecreases",
        at = @At(
            value = "INVOKE",
            target = "Lit/unimi/dsi/fastutil/longs/LongArrayFIFOQueue;isEmpty()Z"
        ),
        require = 1,
        expect = 1,
        allow = 1
    )
    private boolean ecclientlight$limitDecreasePropagation(LongArrayFIFOQueue queue) {
        return LocalLightWorkBudget.shouldStop(queue.isEmpty(), this.ecclientlight$isSkyEngine());
    }

    @Redirect(
        method = "propagateIncreases",
        at = @At(
            value = "INVOKE",
            target = "Lit/unimi/dsi/fastutil/longs/LongArrayFIFOQueue;isEmpty()Z"
        ),
        require = 1,
        expect = 1,
        allow = 1
    )
    private boolean ecclientlight$limitIncreasePropagation(LongArrayFIFOQueue queue) {
        return LocalLightWorkBudget.shouldStop(queue.isEmpty(), this.ecclientlight$isSkyEngine());
    }

    @Inject(
        method = "runLightUpdates",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/lighting/LightEngine;clearChunkCache()V"
        ),
        cancellable = true,
        require = 1,
        expect = 1,
        allow = 1
    )
    private void ecclientlight$deferPartialLightMapSwap(CallbackInfoReturnable<Integer> cir) {
        if (
            LocalLightWorkBudget.isActive()
                && (
                    !this.ecclientlight$getDecreaseQueue().isEmpty()
                        || !this.ecclientlight$getIncreaseQueue().isEmpty()
                        || LocalLightWorkBudget.isExhausted()
                )
        ) {
            this.ecclientlight$clearChunkCache();
            cir.setReturnValue(0);
        }
    }

    private boolean ecclientlight$isSkyEngine() {
        return (Object)this instanceof SkyLightEngine;
    }

    @Accessor("blockNodesToCheck")
    protected abstract LongOpenHashSet ecclientlight$getBlockNodesToCheck();

    @Accessor("decreaseQueue")
    protected abstract LongArrayFIFOQueue ecclientlight$getDecreaseQueue();

    @Accessor("increaseQueue")
    protected abstract LongArrayFIFOQueue ecclientlight$getIncreaseQueue();

    @Invoker("clearChunkCache")
    protected abstract void ecclientlight$clearChunkCache();
}
