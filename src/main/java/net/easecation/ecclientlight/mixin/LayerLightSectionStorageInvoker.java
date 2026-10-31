package net.easecation.ecclientlight.mixin;

import javax.annotation.Nullable;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.LayerLightSectionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(LayerLightSectionStorage.class)
public interface LayerLightSectionStorageInvoker {

    @Invoker("getDataLayer")
    @Nullable
    DataLayer ecclientlight$getDataLayer(long sectionKey, boolean updating);

    // 绕过仅用于显示的临时层，读取正式光照供邻居采样使用。
    @Invoker("getLightValue")
    int ecclientlight$getLightValue(long packedBlockPos);
}
