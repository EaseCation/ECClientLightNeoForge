package net.easecation.ecclientlight.mixin;

import net.easecation.ecclientlight.light.ClientLightManager;
import net.easecation.ecclientlight.network.ClientLightSession;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.DisconnectionDetails;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Resets capability state on a real network disconnect, not on reconfiguration's level teardown. */
@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class ClientCommonPacketListenerMixin {

    @Inject(method = "onDisconnect", at = @At("HEAD"))
    private void ecclientlight$resetConnectionState(DisconnectionDetails details, CallbackInfo ci) {
        ClientCommonPacketListenerImpl listener = (ClientCommonPacketListenerImpl)(Object)this;
        ClientLightSession.reset(listener.getConnection(), "disconnect");
        ClientLightManager.clearAll();
    }
}
