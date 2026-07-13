package net.easecation.ecclientlight.mixin;

import javax.annotation.Nullable;
import net.minecraft.world.level.lighting.LightEngine;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LevelLightEngine.class)
public interface LevelLightEngineAccessor {

    @Accessor("blockEngine")
    @Nullable
    LightEngine<?, ?> ecclientlight$getBlockEngine();

    @Accessor("skyEngine")
    @Nullable
    LightEngine<?, ?> ecclientlight$getSkyEngine();
}
