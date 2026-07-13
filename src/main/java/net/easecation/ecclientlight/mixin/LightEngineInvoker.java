package net.easecation.ecclientlight.mixin;

import net.minecraft.world.level.lighting.LightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(LightEngine.class)
public interface LightEngineInvoker {

    @Invoker("enqueueIncrease")
    void ecclientlight$enqueueIncrease(long blockPos, long queueEntry);
}
