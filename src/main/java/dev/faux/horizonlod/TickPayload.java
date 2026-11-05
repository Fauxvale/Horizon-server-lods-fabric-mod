/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Faux. Part of Horizon Server LODs; see LICENSE.
 */
package dev.faux.horizonlod;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** One message on the {@code faux:tick_stats} channel, both ways: its raw bytes (see PROTOCOL.md). */
public record TickPayload(byte[] data) implements CustomPacketPayload {
	public static final Type<TickPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("faux", "tick_stats"));
	public static final StreamCodec<FriendlyByteBuf, TickPayload> CODEC = StreamCodec.of(
			(buf, payload) -> buf.writeBytes(payload.data),
			buf -> {
				byte[] data = new byte[Math.min(buf.readableBytes(), 64)];
				buf.readBytes(data);
				buf.skipBytes(buf.readableBytes());
				return new TickPayload(data);
			});

	@Override
	public Type<TickPayload> type() {
		return TYPE;
	}
}
