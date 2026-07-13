package net.easecation.ecclientlight.mixin;

import net.minecraft.world.level.lighting.LayerLightSectionStorage;
import net.minecraft.world.level.lighting.LightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LightEngine.class)
public interface LightEngineStorageAccessor {

    @Accessor("storage")
    LayerLightSectionStorage<?> ecclientlight$getStorage();
}
