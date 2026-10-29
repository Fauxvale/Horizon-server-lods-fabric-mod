package dev.faux.horizonlod;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** Minecraft's VarInt, string and big-endian encodings, for the channel's messages. */
final class Wire {
	private Wire() {}

	static final class Reader {
		private final byte[] data;
		private int at;

		Reader(byte[] data) {
			this.data = data;
		}

		boolean done() {
			return at >= data.length;
		}

		int u8() {
			if (at >= data.length) throw new IllegalArgumentException("message ends early");
			return data[at++] & 0xff;
		}

		int varint() {
			int value = 0;
			for (int shift = 0; shift < 35; shift += 7) {
				int b = u8();
				value |= (b & 0x7f) << shift;
				if ((b & 0x80) == 0) return value;
			}
			throw new IllegalArgumentException("VarInt too long");
		}

		int i16() {
			return (short) ((u8() << 8) | u8());
		}

		String string(int max) {
			int length = varint();
			if (length < 0 || length > max * 3 || at + length > data.length) {
				throw new IllegalArgumentException("bad string length");
			}
			String s = new String(data, at, length, StandardCharsets.UTF_8);
			at += length;
			return s;
		}
	}

	static final class Writer {
		private final ByteArrayOutputStream out = new ByteArrayOutputStream();

		Writer u8(int v) {
			out.write(v);
			return this;
		}

		Writer varint(int v) {
			while ((v & ~0x7f) != 0) {
				out.write((v & 0x7f) | 0x80);
				v >>>= 7;
			}
			out.write(v);
			return this;
		}

		Writer i16(int v) {
			out.write(v >> 8);
			out.write(v);
			return this;
		}

		Writer i32(int v) {
			for (int shift = 24; shift >= 0; shift -= 8) out.write(v >> shift);
			return this;
		}

		Writer f32(float v) {
			return i32(Float.floatToIntBits(v));
		}

		Writer i64(long v) {
			for (int shift = 56; shift >= 0; shift -= 8) out.write((int) (v >> shift));
			return this;
		}

		Writer string(String s) {
			byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
			varint(bytes.length);
			out.writeBytes(bytes);
			return this;
		}

		Writer raw(byte[] bytes) {
			out.writeBytes(bytes);
			return this;
		}

		int size() {
			return out.size();
		}

		byte[] bytes() {
			return out.toByteArray();
		}
	}
}
