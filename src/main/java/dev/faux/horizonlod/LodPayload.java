package dev.faux.horizonlod;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** One message on the {@code horizon:lod} channel, both ways: its raw bytes (see PROTOCOL.md). */
public record LodPayload(byte[] data) implements CustomPacketPayload {
	public static final Type<LodPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("horizon", "lod"));
	public static final StreamCodec<FriendlyByteBuf, LodPayload> CODEC = StreamCodec.of(
			(buf, payload) -> buf.writeBytes(payload.data),
			buf -> {
				byte[] data = new byte[buf.readableBytes()];
				buf.readBytes(data);
				return new LodPayload(data);
			});

	@Override
	public Type<LodPayload> type() {
		return TYPE;
	}
}
