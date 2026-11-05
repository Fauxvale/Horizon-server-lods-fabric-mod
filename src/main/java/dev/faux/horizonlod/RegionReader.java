package dev.faux.horizonlod;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;

/**
 * Reads a level's saved chunks straight from its region files, read-only, on the LOD threads.
 * The server's own chunk IO ({@code ChunkMap.readChunk}) is one queue shared with its loads and
 * saves: far terrain read through it waits behind the game and makes the game wait behind it.
 * This never writes, never opens a file for writing, and leaves anything it can't read plainly (a
 * chunk from an older version that needs datafixing, one stored outside the region, a sector the
 * server is rewriting) to {@link Result#FALLBACK}, which the caller reads the server's way.
 */
final class RegionReader {
	/** A region file's sector size. */
	private static final int SECTOR = 4096;
	/** Bit 7 of the compression type: the chunk is in its own {@code c.x.z.mcc} file. */
	private static final int EXTERNAL = 0x80;
	/** The data version this server saves: an older chunk needs the server's datafixers. */
	private static final int DATA_VERSION = SharedConstants.getCurrentVersion().dataVersion().version();

	/** What a read found. */
	enum Result { FOUND, ABSENT, FALLBACK }

	record Read(Result result, Optional<CompoundTag> tag) {
		static final Read ABSENT = new Read(Result.ABSENT, Optional.empty());
		static final Read FALLBACK = new Read(Result.FALLBACK, Optional.empty());
	}

	private final Path regions;

	RegionReader(Path regions) {
		this.regions = regions;
	}

	/** Chunk x, z as the region file holds it now. */
	Read read(int x, int z) {
		Path file = regions.resolve("r." + (x >> 5) + "." + (z >> 5) + ".mca");
		if (!Files.exists(file)) return Read.ABSENT;
		try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
			ByteBuffer location = ByteBuffer.allocate(4);
			readFully(channel, location, 4L * ((x & 31) + (z & 31) * 32));
			int entry = location.getInt(0);
			if (entry == 0) return Read.ABSENT;
			long offset = (long) (entry >>> 8) * SECTOR;
			int sectors = entry & 0xff;
			ByteBuffer header = ByteBuffer.allocate(5);
			readFully(channel, header, offset);
			int length = header.getInt(0);
			int type = header.get(4) & 0xff;
			if ((type & EXTERNAL) != 0) return Read.FALLBACK;
			if (length <= 1 || length + 4 > sectors * SECTOR) return Read.FALLBACK;
			RegionFileVersion version = RegionFileVersion.fromId(type);
			if (version == null) return Read.FALLBACK;
			ByteBuffer body = ByteBuffer.allocate(length - 1);
			readFully(channel, body, offset + 5);
			CompoundTag tag;
			try (DataInputStream in = new DataInputStream(new BufferedInputStream(
					version.wrap(new ByteArrayInputStream(body.array()))))) {
				tag = NbtIo.read(in);
			}
			if (tag.getIntOr(SharedConstants.DATA_VERSION_TAG, 0) < DATA_VERSION) return Read.FALLBACK;
			return new Read(Result.FOUND, Optional.of(tag));
		} catch (NoSuchFileException e) {
			return Read.ABSENT;
		} catch (IOException | RuntimeException e) {
			// A torn read while the server rewrites the chunk, or a damaged file: its own reader decides.
			return Read.FALLBACK;
		}
	}

	private static void readFully(FileChannel channel, ByteBuffer buffer, long position) throws IOException {
		while (buffer.hasRemaining()) {
			int n = channel.read(buffer, position + buffer.position());
			if (n < 0) throw new IOException("region file ends early");
		}
	}
}
