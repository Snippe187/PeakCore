package de.peakverse.core.mod;

import de.peakverse.core.protocol.Frame;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Traegt die Rohbytes eines Pakets auf dem Kanal {@code peakcore:main}. Die Bytes entsprechen 1:1 den Daten des
 * Plugin-Messaging-Kanals; die Kodierung liegt in core-protocol.
 */
public record RawPayload(byte[] data) implements CustomPacketPayload {
    public static final Identifier ID = Identifier.parse(Frame.CHANNEL);
    public static final CustomPacketPayload.Type<RawPayload> TYPE = new CustomPacketPayload.Type<>(ID);

    /** Kein Laengenpraefix: der Kanal enthaelt genau die Paket-Bytes. */
    public static final StreamCodec<RegistryFriendlyByteBuf, RawPayload> CODEC = StreamCodec.of(
            (buf, payload) -> buf.writeBytes(payload.data),
            buf -> {
                byte[] bytes = new byte[buf.readableBytes()];
                buf.readBytes(bytes);
                return new RawPayload(bytes);
            });

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
