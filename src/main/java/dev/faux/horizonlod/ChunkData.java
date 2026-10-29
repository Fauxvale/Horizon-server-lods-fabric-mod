package dev.faux.horizonlod;

import com.mojang.serialization.Codec;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.Registry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A saved chunk's blocks, biomes and light, as network ids (the ids the server's chunk packets
 * use), read from its NBT ({@code SerializableChunkData}'s format) without building a chunk.
 */
final class ChunkData {
	final int minY;
	final int sections;
	final boolean hasSky;
	/** Per section: 4096 block state ids (y, z, x order), or null for all air. */
	final int[][] states;
	/** Per section: 64 biome ids. */
	final int[][] biomes;
	final byte[][] sky;
	final byte[][] block;

	private ChunkData(int minY, int sections, boolean hasSky) {
		this.minY = minY;
		this.sections = sections;
		this.hasSky = hasSky;
		states = new int[sections][];
		biomes = new int[sections][];
		sky = new byte[sections][];
		block = new byte[sections][];
	}

	int state(int x, int y, int z) {
		int section = (y - minY) >> 4;
		if (section < 0 || section >= sections || states[section] == null) return 0;
		return states[section][((y & 15) << 8) | (z << 4) | x];
	}

	int biome(int x, int y, int z) {
		int section = Math.clamp((y - minY) >> 4, 0, sections - 1);
		int[] cells = biomes[section];
		return cells == null ? 0 : cells[(((y & 15) >> 2) << 4) | ((z >> 2) << 2) | (x >> 2)];
	}

	/** Sky light in the high nibble, block light in the low: Horizon's {@code Chunk::light}. */
	int light(int x, int y, int z) {
		int section = (y - minY) >> 4;
		if (section < 0) return 0;
		if (section >= sections) return hasSky ? 0xf0 : 0;
		int index = ((y & 15) << 8) | (z << 4) | x;
		int s = sky[section] == null ? (hasSky ? 15 : 0) : nibble(sky[section], index);
		int b = block[section] == null ? 0 : nibble(block[section], index);
		return (s << 4) | b;
	}

	private static int nibble(byte[] data, int index) {
		return (data[index >> 1] >> ((index & 1) << 2)) & 15;
	}

	private static final Codec<List<BlockState>> PALETTE = BlockState.CODEC.listOf();

	/** Reads the block states and biome ids of a level's chunks, caching biome ids. */
	static final class Decoder {
		private final Registry<Biome> biomeRegistry;
		private final int minY;
		private final int sections;
		private final boolean hasSky;
		private final Map<String, Integer> biomeIds = new HashMap<>();

		Decoder(Registry<Biome> biomes, int minY, int height, boolean hasSky) {
			this.biomeRegistry = biomes;
			this.minY = minY;
			this.sections = height >> 4;
			this.hasSky = hasSky;
		}

		/** The chunk, or empty when it isn't fully generated. */
		synchronized Optional<ChunkData> decode(CompoundTag root) {
			String status = root.getStringOr("Status", "");
			if (!status.equals("minecraft:full") && !status.equals("full")) return Optional.empty();
			ChunkData chunk = new ChunkData(minY, sections, hasSky);
			int bottom = minY >> 4;
			for (Tag entry : root.getListOrEmpty("sections")) {
				if (!(entry instanceof CompoundTag section)) continue;
				int index = section.getByteOr("Y", (byte) 0) - bottom;
				if (index < 0 || index >= sections) continue;
				section.getCompound("block_states").ifPresent(states -> chunk.states[index] = blockStates(states));
				section.getCompound("biomes").ifPresent(biomes -> chunk.biomes[index] = biomes(biomes));
				section.getByteArray("SkyLight").filter(a -> a.length == 2048).ifPresent(a -> chunk.sky[index] = a);
				section.getByteArray("BlockLight").filter(a -> a.length == 2048).ifPresent(a -> chunk.block[index] = a);
			}
			return Optional.of(chunk);
		}

		private int[] blockStates(CompoundTag container) {
			// Vanilla's own palette codec: since 26.1 a block without properties is saved as its
			// bare name, in a list mixed with {Name, Properties} compounds.
			Tag paletteTag = container.get("palette");
			if (paletteTag == null) return null;
			List<BlockState> palette = PALETTE.parse(NbtOps.INSTANCE, paletteTag).result().orElse(List.of());
			int[] ids = new int[palette.size()];
			boolean allAir = true;
			for (int i = 0; i < ids.length; i++) {
				ids[i] = Block.BLOCK_STATE_REGISTRY.getId(palette.get(i));
				allAir &= palette.get(i).isAir();
			}
			if (ids.length == 0 || allAir) return null;
			return unpack(ids, container.getLongArray("data").orElse(null), 4096, 4);
		}

		private int[] biomes(CompoundTag container) {
			ListTag palette = container.getListOrEmpty("palette");
			int[] ids = new int[palette.size()];
			for (int i = 0; i < ids.length; i++) {
				String name = palette.getStringOr(i, "minecraft:plains");
				ids[i] = biomeIds.computeIfAbsent(name, n -> {
					Biome biome = biomeRegistry.getValue(Identifier.tryParse(n));
					return biome == null ? 0 : biomeRegistry.getId(biome);
				});
			}
			if (ids.length == 0) return null;
			return unpack(ids, container.getLongArray("data").orElse(null), 64, 0);
		}

		/** A paletted container's packed indices ({@code SimpleBitStorage}: entries never span longs). */
		private static int[] unpack(int[] palette, long[] data, int size, int minimumBits) {
			int[] values = new int[size];
			if (palette.length == 1 || data == null) {
				java.util.Arrays.fill(values, palette[0]);
				return values;
			}
			int bits = Math.max(minimumBits, 32 - Integer.numberOfLeadingZeros(palette.length - 1));
			int perLong = 64 / bits;
			long mask = (1L << bits) - 1;
			for (int i = 0; i < size; i++) {
				int word = i / perLong;
				if (word >= data.length) break;
				int index = (int) ((data[word] >>> ((i % perLong) * bits)) & mask);
				values[i] = index < palette.length ? palette[index] : palette[0];
			}
			return values;
		}
	}
}
