package dev.faux.horizonlod;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Horizon's far-terrain columns, built here as Horizon builds them from a chunk
 * (lod.rs {@code Column::from_chunk} and {@code Column::reduced}): runs of kept
 * blocks with their biome and light, so the client draws the server's chunks as
 * it draws its own. No Minecraft classes: {@link ChunkData} holds the chunk.
 */
final class Columns {
	private Columns() {}

	/** {@code state_kinds} bits: kept in columns, a fluid; the light emission is in the high nibble. */
	static final int KEPT = 1;
	static final int FLUID = 2;
	/**
	 * A block holding water that draws as none (kelp, seagrass, bubble columns, waterlogged blocks
	 * that aren't kept): drawn as water, or kelp reaching the surface leaves holes in the sea.
	 */
	static final int WATERY = 8;

	/** A column: half-open runs from the bottom up; gaps between runs are air. */
	static final class Column {
		int count;
		int[] min = new int[4];
		int[] max = new int[4];
		int[] state = new int[4];
		int[] biome = new int[4];
		int[] light = new int[4];

		/** {@code Column::push}: a run continuing the last one of the same material lengthens it. */
		void push(int lo, int hi, int s, int b, int l) {
			int last = count - 1;
			if (last >= 0 && max[last] == lo && state[last] == s && biome[last] == b && light[last] == l) {
				max[last] = hi;
				return;
			}
			if (count == min.length) {
				int size = count * 2;
				min = Arrays.copyOf(min, size);
				max = Arrays.copyOf(max, size);
				state = Arrays.copyOf(state, size);
				biome = Arrays.copyOf(biome, size);
				light = Arrays.copyOf(light, size);
			}
			min[count] = lo;
			max[count] = hi;
			state[count] = s;
			biome[count] = b;
			light[count] = l;
			count++;
		}

		/** A column {@link #write} wrote. */
		static Column read(Wire.Reader r) {
			Column column = new Column();
			int count = r.varint();
			if (count < 0 || count > 4096) throw new IllegalArgumentException("bad span count");
			for (int i = 0; i < count; i++) {
				int lo = r.i16();
				int hi = r.i16();
				int s = r.varint();
				int b = r.varint();
				column.push(lo, hi, s, b, r.i16() & 0xffff);
			}
			return column;
		}

		void write(Wire.Writer w) {
			w.varint(count);
			for (int i = 0; i < count; i++) {
				w.i16(min[i]).i16(max[i]).varint(state[i]).varint(biome[i]).i16(light[i]);
			}
		}
	}

	/**
	 * {@code Column::from_chunk}: column x, z (0..15) of a chunk; {@code kinds} per block state id,
	 * {@code water} the water source state that {@link #WATERY} blocks are drawn as.
	 */
	static Column fromChunk(ChunkData chunk, byte[] kinds, int water, int x, int z) {
		Column column = new Column();
		for (int section = 0; section < chunk.sections; section++) {
			if (chunk.states[section] == null) continue;
			int bottom = chunk.minY + section * 16;
			for (int y = bottom; y < bottom + 16; y++) {
				int state = chunk.state(x, y, z);
				int kind = state < kinds.length ? kinds[state] & 0xff : 0;
				if ((kind & KEPT) == 0) continue;
				if ((kind & WATERY) != 0) {
					state = water;
					kind = kinds[water] & 0xff;
				}
				int light = faceLight(chunk, x, y, z);
				int sky = light & 0xf0;
				int block = Math.max(light & 15, kind >> 4);
				column.push(y, y + 1, state, chunk.biome(x, y, z), sky | (block << 12));
			}
		}
		return column;
	}

	private static final int[][] FACES = {{0, 0, 0}, {-1, 0, 0}, {1, 0, 0}, {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}};

	/** The brightest sky and block light at a block and its six neighbours inside the chunk. */
	private static int faceLight(ChunkData chunk, int x, int y, int z) {
		int sky = 0;
		int block = 0;
		for (int[] d : FACES) {
			int nx = x + d[0];
			int nz = z + d[2];
			if (nx < 0 || nx > 15 || nz < 0 || nz > 15) continue;
			int value = chunk.light(nx, y + d[1], nz);
			sky = Math.max(sky, value & 0xf0);
			block = Math.max(block, value & 15);
		}
		return sky | block;
	}

	/**
	 * {@code Column::reduced}: the {@code width} x {@code width} footprint from x, z of a chunk's
	 * columns as one column. Each height keeps the footprint's most common material where at least
	 * half of it is occupied, and the brightest light of what occupies it.
	 */
	static Column reduced(Column[] columns, int x, int z, int width, int bottom) {
		if (width == 1) return columns[z * 16 + x];
		List<long[]> events = new ArrayList<>();
		for (int dz = z; dz < z + width; dz++) {
			for (int dx = x; dx < x + width; dx++) {
				Column c = columns[dz * 16 + dx];
				for (int i = 0; i < c.count; i++) {
					events.add(new long[] {c.min[i], c.state[i], c.biome[i], c.light[i], 1});
					events.add(new long[] {c.max[i], c.state[i], c.biome[i], c.light[i], -1});
				}
			}
		}
		events.sort((a, b) -> Long.compare(a[0], b[0]));
		Column result = new Column();
		// Votes: state, biome, count.
		List<int[]> votes = new ArrayList<>();
		int[] sky = new int[16];
		int[] block = new int[16];
		int occupied = 0;
		for (long[] event : events) {
			int y = (int) event[0];
			if (bottom < y) {
				if (occupied * 2 >= width * width) {
					int[] best = null;
					for (int[] vote : votes) {
						if (best == null
								|| vote[2] > best[2]
								|| (vote[2] == best[2]
										&& (vote[0] < best[0] || (vote[0] == best[0] && vote[1] < best[1])))) {
							best = vote;
						}
					}
					if (best != null) {
						int light = (highest(sky) << 4) | (highest(block) << 12);
						result.push(bottom, y, best[0], best[1], light);
					}
				}
				bottom = y;
			}
			int change = (int) event[4];
			int light = (int) event[3];
			occupied += change;
			sky[(light >> 4) & 15] += change;
			block[(light >> 12) & 15] += change;
			int state = (int) event[1];
			int biome = (int) event[2];
			int[] vote = null;
			for (int[] v : votes) {
				if (v[0] == state && v[1] == biome) {
					vote = v;
					break;
				}
			}
			if (vote == null) votes.add(new int[] {state, biome, change});
			else vote[2] += change;
		}
		return result;
	}

	private static int highest(int[] counts) {
		for (int i = counts.length - 1; i >= 0; i--) {
			if (counts[i] > 0) return i;
		}
		return 0;
	}
}
