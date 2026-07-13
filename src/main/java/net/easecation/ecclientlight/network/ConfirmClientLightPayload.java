package net.easecation.ecclientlight.network;

import net.easecation.ecclientlight.ECClientLight;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Configuration-phase acknowledgement that ViaBedrock delegated lighting to this client. */
public record ConfirmClientLightPayload() implements CustomPacketPayload {

    public static final ConfirmClientLightPayload INSTANCE = new ConfirmClientLightPayload();
    public static final Type<ConfirmClientLightPayload> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath(ECClientLight.MOD_ID, "confirm_v1")
    );

    /** The channel id carries the protocol version, so the payload body is intentionally empty. */
    public static final StreamCodec<FriendlyByteBuf, ConfirmClientLightPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public ConfirmClientLightPayload decode(FriendlyByteBuf buffer) {
            return INSTANCE;
        }

        @Override
        public void encode(FriendlyByteBuf buffer, ConfirmClientLightPayload payload) {
        }
    };

    @Override
    public Type<ConfirmClientLightPayload> type() {
        return TYPE;
    }
}
