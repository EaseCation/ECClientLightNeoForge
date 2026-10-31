package net.easecation.ecclientlight.mixin;

import net.easecation.ecclientlight.light.ClientLightManager;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin {

    // 1.21.8 int locals: queue size (0), computed drain limit (1), loop index (2).
    @ModifyVariable(
        method = "pollLightUpdates",
        at = @At("STORE"),
        ordinal = 1,
        require = 1,
        expect = 1,
        allow = 1
    )
    private int ecclientlight$limitQueuedLightTasks(int vanillaLimit) {
        return ClientLightManager.limitQueuedLightTasks((ClientLevel)(Object)this, vanillaLimit);
    }

    @Inject(method = "pollLightUpdates", at = @At("RETURN"))
    private void ecclientlight$flushRelightBatch(CallbackInfo ci) {
        ClientLightManager.afterLightUpdatePoll((ClientLevel)(Object)this);
    }

    @Inject(method = "setBlocksDirty", at = @At("HEAD"))
    private void ecclientlight$prepareTemporaryLight(BlockPos pos, BlockState oldState, BlockState newState, CallbackInfo ci) {
        ClientLightManager.onBlockChanged((ClientLevel)(Object)this, pos, oldState, newState);
    }

    @Inject(method = "unload", at = @At("HEAD"))
    private void ecclientlight$forgetChunk(LevelChunk chunk, CallbackInfo ci) {
        ClientLightManager.onChunkUnloaded((ClientLevel)(Object)this, chunk.getPos());
    }
}
