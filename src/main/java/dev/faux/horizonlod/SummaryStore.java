package dev.faux.horizonlod;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Chunk summaries (their 256 columns) kept in the level's folder, {@code horizon-lod/}, in region
 * files like the level's own: a chunk is read from the world once, then from here until the world
 * saves it again. A summary holds the timestamp the level's region file gave the chunk when it was
 * made; a different one there now means the chunk changed, and it is made again.
 */
final class SummaryStore {
	private static final Logger LOG = LoggerFactory.getLogger("horizon-lod");

	/**
	 * The summaries' format: summaries of another are made again. 2: water-holding blocks draw as
	 * water; 3: nearly full blocks (powder snow, mud, paths) are kept.
	 */
	private static final int FORMAT = 3;

	private final Path regions;
	/** Null when summaries aren't kept ({@code summary-cache=false}): only the timestamps are read. */
	private final RegionFileStorage storage;
	private final ExecutorService writer;
	private final Map<Long, Header> headers = new ConcurrentHashMap<>();

	/** A level region file's chunk timestamps, as of its modification time. */
	private record Header(long modified, int[] timestamps) {}

	SummaryStore(Path dimension, String levelName, ResourceKey<net.minecraft.world.level.Level> key, boolean keep) {
		this.regions = dimension.resolve("region");
		this.storage = keep
				? new RegionFileStorage(new RegionStorageInfo(levelName, key, "horizon_lod"), dimension.resolve("horizon-lod"), false)
				: null;
		this.writer = Executors.newSingleThreadExecutor(task -> {
			Thread thread = new Thread(task, "Horizon LOD store");
			thread.setDaemon(true);
			thread.setPriority(Thread.MIN_PRIORITY);
			return thread;
		});
	}

	/** The timestamp the level's region file gives chunk x, z; 0 when it has none. */
	int timestamp(int x, int z) {
		int rx = x >> 5, rz = z >> 5;
		Path file = regions.resolve("r." + rx + "." + rz + ".mca");
		long key = ChunkPos.pack(rx, rz);
		try {
			if (!Files.exists(file)) {
				headers.remove(key);
				return 0;
			}
			long modified = Files.getLastModifiedTime(file).toMillis();
			Header header = headers.get(key);
			if (header == null || header.modified != modified) {
				header = new Header(modified, readTimestamps(file));
				headers.put(key, header);
			}
			return header.timestamps[(x & 31) + (z & 31) * 32];
		} catch (IOException e) {
			return 0;
		}
	}

	private static int[] readTimestamps(Path file) throws IOException {
		int[] timestamps = new int[1024];
		try (RandomAccessFile f = new RandomAccessFile(file.toFile(), "r")) {
			if (f.length() < 8192) return timestamps;
			f.seek(4096);
			for (int i = 0; i < 1024; i++) timestamps[i] = f.readInt();
		}
		return timestamps;
	}

	/**
	 * The kept summary of chunk x, z, made when the level's region file gave it {@code timestamp}:
	 * empty when there is none or it is older, an empty column array for a chunk that wasn't full.
	 */
	Optional<Optional<Columns.Column[]>> read(int x, int z, int timestamp) {
		CompoundTag tag = tag(x, z);
		if (tag == null || tag.getIntOr("timestamp", 0) != timestamp || tag.getIntOr("format", 1) != FORMAT) return Optional.empty();
		return parse(tag);
	}

	/** A summary read whatever its timestamp: its columns (null if none), and whether its format is old. */
	record Kept(Columns.Column[] columns, boolean stale) {}

	/** The kept columns of chunk x, z, however old (but in this format). */
	Kept readAny(int x, int z) {
		CompoundTag tag = tag(x, z);
		if (tag == null) return new Kept(null, false);
		if (tag.getIntOr("format", 1) != FORMAT) return new Kept(null, true);
		return new Kept(parse(tag).flatMap(columns -> columns).orElse(null), false);
	}

	private CompoundTag tag(int x, int z) {
		if (storage == null) return null;
		try {
			synchronized (storage) {
				return storage.read(new ChunkPos(x, z));
			}
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	private static Optional<Optional<Columns.Column[]>> parse(CompoundTag tag) {
		if (tag.getBooleanOr("unfinished", false)) return Optional.of(Optional.empty());
		Optional<byte[]> data = tag.getByteArray("columns");
		if (data.isEmpty()) return Optional.empty();
		try {
			Wire.Reader r = new Wire.Reader(data.get());
			Columns.Column[] columns = new Columns.Column[256];
			for (int i = 0; i < 256; i++) columns[i] = Columns.Column.read(r);
			return Optional.of(Optional.of(columns));
		} catch (IllegalArgumentException e) {
			return Optional.empty();
		}
	}

	/** Keeps chunk x, z's summary (empty for a chunk that wasn't full), on the store's thread. */
	void write(int x, int z, int timestamp, Optional<Columns.Column[]> columns) {
		if (storage == null) return;
		CompoundTag tag = new CompoundTag();
		tag.putInt("timestamp", timestamp);
		tag.putInt("format", FORMAT);
		if (columns.isEmpty()) {
			tag.putBoolean("unfinished", true);
		} else {
			Wire.Writer w = new Wire.Writer();
			for (Columns.Column column : columns.get()) column.write(w);
			tag.putByteArray("columns", w.bytes());
		}
		try {
			writer.execute(() -> {
				try {
					synchronized (storage) {
						storage.write(new ChunkPos(x, z), tag);
					}
				} catch (IOException | RuntimeException e) {
					LOG.warn("Horizon LOD: could not keep the summary of chunk {} {}", x, z, e);
				}
			});
		} catch (java.util.concurrent.RejectedExecutionException e) {
			// Stopping.
		}
	}

	/** Finishes the writes sent so far and closes the files. */
	void close() {
		writer.shutdown();
		try {
			writer.awaitTermination(30, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		if (storage == null) return;
		try {
			synchronized (storage) {
				storage.close();
			}
		} catch (IOException e) {
			LOG.warn("Horizon LOD: could not close the summary store", e);
		}
	}
}
