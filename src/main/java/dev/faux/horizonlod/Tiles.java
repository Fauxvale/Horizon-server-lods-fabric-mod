package dev.faux.horizonlod;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Answers Horizon clients' tile requests from the chunks already generated: read as the server
 * reads them (from disk or its pending saves), off the server thread, never generating one. Each
 * player has a queue, a few tiles in the works and a byte budget per tick.
 */
final class Tiles {
	private static final Logger LOG = LoggerFactory.getLogger("horizon-lod");
	static final int TILE_OK = 0;
	/** None of the tile's chunks is generated. */
	static final int TILE_EMPTY = 1;
	/** Outside the distance, another level's, or the queue is full. */
	static final int TILE_REFUSED = 2;
	/** A {@code REQUEST_KEPT} answer: a mask of the columns the summary cache has, then all 256. */
	static final int TILE_KNOWN = 3;
	/** A {@code REQUEST_KEPT} answer: the summary cache has none of the tile's columns. */
	static final int TILE_UNKNOWN = 4;
	/** Chunks waiting to be summarized in the background, per level at most. */
	private static final int MAX_PENDING = 65536;
	/** Tries for a chunk not saved yet (no region timestamp), a minute apart. */
	private static final int TRIES = 5;
	/** Horizon's fixed column bottom (chunk.rs {@code MIN_Y}). */
	private static final int HORIZON_MIN_Y = -64;

	/** A tile; {@code kept}: answered from the summary cache only ({@code REQUEST_KEPT}). */
	record Request(int id, int x, int z, int stride, boolean kept) {}

	private static final class Player {
		final ArrayDeque<Request> queue = new ArrayDeque<>();
		final ConcurrentLinkedQueue<byte[]> ready = new ConcurrentLinkedQueue<>();
		final AtomicInteger working = new AtomicInteger();
		String level = "";
	}

	private final Config config;
	private final ExecutorService workers;
	private final Map<UUID, Player> players = new ConcurrentHashMap<>();
	private final Map<ServerLevel, Level> levels = new ConcurrentHashMap<>();
	private volatile byte[] kinds;
	/** Chunks summarized from the summary store, and read from the level (logged each minute). */
	private final AtomicLong kept = new AtomicLong(), read = new AtomicLong();
	private long logged, loggedAt;
	/** Loaded chunks to summarize, per level: chunk -> {due time, tries}. */
	private final Map<ServerLevel, LinkedHashMap<Long, long[]>> pending = new ConcurrentHashMap<>();
	private final ExecutorService summarizer;
	private final AtomicInteger summarizing = new AtomicInteger();

	Tiles(Config config) {
		this.config = config;
		AtomicInteger count = new AtomicInteger();
		workers = Executors.newFixedThreadPool(config.threads, task -> {
			Thread thread = new Thread(task, "Horizon LOD " + count.incrementAndGet());
			thread.setDaemon(true);
			thread.setPriority(Thread.MIN_PRIORITY);
			return thread;
		});
		summarizer = Executors.newSingleThreadExecutor(task -> {
			Thread thread = new Thread(task, "Horizon LOD summarizer");
			thread.setDaemon(true);
			thread.setPriority(Thread.MIN_PRIORITY);
			return thread;
		});
	}

	/**
	 * A chunk a player's game loaded or unloaded: summarized into the summary cache in the
	 * background after {@code delay} ms (an unloaded chunk is saved meanwhile), so far terrain shows
	 * it to players who come later.
	 */
	void loaded(ServerLevel level, int x, int z, long delay) {
		if (!config.summaryCache || config.summarizePerTick == 0) return;
		LinkedHashMap<Long, long[]> chunks = pending.computeIfAbsent(level, l -> new LinkedHashMap<>());
		synchronized (chunks) {
			long key = ChunkPos.pack(x, z);
			long due = System.currentTimeMillis() + delay;
			long[] entry = chunks.get(key);
			if (entry != null) {
				entry[0] = Math.max(entry[0], due);
			} else if (chunks.size() < MAX_PENDING) {
				chunks.put(key, new long[] {due, 0});
			}
		}
	}

	/** Starts the background summaries that are due, a few per tick. */
	private void summarize(long now) {
		for (Map.Entry<ServerLevel, LinkedHashMap<Long, long[]>> entry : pending.entrySet()) {
			LinkedHashMap<Long, long[]> chunks = entry.getValue();
			Level levelState = levels.computeIfAbsent(entry.getKey(), this::level);
			synchronized (chunks) {
				var it = chunks.entrySet().iterator();
				while (it.hasNext() && summarizing.get() < config.summarizePerTick) {
					Map.Entry<Long, long[]> chunk = it.next();
					long[] state = chunk.getValue();
					if (state[0] > now) continue;
					it.remove();
					long key = chunk.getKey();
					summarizing.incrementAndGet();
					try {
						summarizer.execute(() -> {
							try {
								// Not saved yet: tried again later.
								if (!levelState.summarize(ChunkPos.getX(key), ChunkPos.getZ(key)) && state[1] + 1 < TRIES) {
									synchronized (chunks) {
										chunks.putIfAbsent(key, new long[] {System.currentTimeMillis() + 60_000, state[1] + 1});
									}
								}
							} catch (RuntimeException e) {
								LOG.debug("Horizon LOD: summarizing chunk failed", e);
							} finally {
								summarizing.decrementAndGet();
							}
						});
					} catch (java.util.concurrent.RejectedExecutionException e) {
						summarizing.decrementAndGet();
						return;
					}
				}
			}
		}
	}

	void hello(ServerPlayer player) {
		players.computeIfAbsent(player.getUUID(), id -> new Player());
	}

	void forget(ServerPlayer player) {
		players.remove(player.getUUID());
	}

	void request(ServerPlayer player, String level, Iterable<Request> requests) {
		Player state = players.get(player.getUUID());
		if (state == null) return;
		synchronized (state) {
			state.level = level;
			for (Request request : requests) {
				if (state.queue.size() >= config.maxQueued) {
					state.ready.add(refused(request.id));
				} else {
					state.queue.add(request);
				}
			}
		}
	}

	/** On the server thread, each tick: start queued tiles and send finished ones. */
	void tick(MinecraftServer server) {
		long now = System.currentTimeMillis();
		if (now - loggedAt > 60_000) {
			long total = kept.get() + read.get();
			if (total != logged) {
				LOG.info("Horizon LOD: {} chunks summarized from the summary store, {} read from the world", kept.get(), read.get());
				logged = total;
			}
			loggedAt = now;
		}
		summarize(now);
		for (Map.Entry<UUID, Player> entry : players.entrySet()) {
			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
			Player state = entry.getValue();
			if (player == null) continue;
			ServerLevel level = player.level();
			ChunkPos at = player.chunkPosition();
			while (state.working.get() < config.inFlight) {
				Request request;
				boolean here;
				synchronized (state) {
					request = state.queue.poll();
					here = level.dimension().identifier().toString().equals(state.level);
				}
				if (request == null) break;
				int size = 16 * Math.max(1, request.stride);
				int cx = Math.floorDiv(request.x + size / 2, 16);
				int cz = Math.floorDiv(request.z + size / 2, 16);
				boolean near = Math.max(Math.abs(cx - at.x()), Math.abs(cz - at.z())) <= config.distance;
				if (!here || !near || !validStride(request.stride)) {
					state.ready.add(refused(request.id));
					continue;
				}
				Level levelState = levels.computeIfAbsent(level, this::level);
				state.working.incrementAndGet();
				workers.execute(() -> {
					try {
						state.ready.add(request.kept ? levelState.keptTile(request) : levelState.tile(request));
					} catch (RuntimeException e) {
						LOG.warn("Horizon LOD: tile {} {} failed", request.x, request.z, e);
						state.ready.add(refused(request.id));
					} finally {
						state.working.decrementAndGet();
					}
				});
			}
			int budget = config.bytesPerTick;
			byte[] message;
			while (budget > 0 && (message = state.ready.poll()) != null) {
				net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player, new LodPayload(message));
				budget -= message.length;
			}
		}
	}

	void stop() {
		summarizer.shutdownNow();
		workers.shutdownNow();
		try {
			summarizer.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);
			workers.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		for (Level level : levels.values()) {
			level.store.close();
		}
	}

	private static boolean validStride(int stride) {
		return stride >= 1 && stride <= 1 << 16 && Integer.bitCount(stride) == 1;
	}

	private static byte[] refused(int id) {
		return new Wire.Writer().u8(HorizonLod.TILE).varint(id).u8(TILE_REFUSED).bytes();
	}

	private Level level(ServerLevel level) {
		ChunkData.Decoder decoder = new ChunkData.Decoder(
				level.registryAccess().lookupOrThrow(Registries.BIOME),
				level.getMinY(),
				level.getHeight(),
				level.dimensionType().hasSkyLight());
		MinecraftServer server = level.getServer();
		java.nio.file.Path dimension = DimensionType.getStorageFolder(level.dimension(), server.getWorldPath(LevelResource.ROOT));
		SummaryStore store = new SummaryStore(dimension, server.getWorldData().getLevelName(), level.dimension(), config.summaryCache);
		return new Level(level, decoder, store);
	}

	/** {@code lod::state_kinds}, as the server's blocks say. */
	private byte[] kinds() {
		byte[] k = kinds;
		if (k != null) return k;
		k = new byte[Block.BLOCK_STATE_REGISTRY.size()];
		for (int id = 0; id < k.length; id++) {
			BlockState state = Block.BLOCK_STATE_REGISTRY.byId(id);
			if (state == null) continue;
			Block block = state.getBlock();
			boolean fluid = block == Blocks.WATER || block == Blocks.LAVA;
			boolean kept = !state.isAir()
					&& (state.isSolidRender()
							|| state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)
							|| BuiltInRegistries.BLOCK.getKey(block).getPath().endsWith("_leaves")
							|| fluid
							|| nearlyFull(BuiltInRegistries.BLOCK.getKey(block).toString()));
			boolean watery = !kept && !state.isAir() && state.getFluidState().is(FluidTags.WATER);
			k[id] = (byte) ((kept || watery ? Columns.KEPT : 0)
					| (fluid || watery ? Columns.FLUID : 0)
					| (watery ? Columns.WATERY : 0)
					| (Math.min(state.getLightEmission(), 15) << 4));
		}
		kinds = k;
		return k;
	}

	/** A chunk's columns as of the timestamp the level's region file gave it. */
	private record Summary(int timestamp, Optional<Columns.Column[]> columns) {}

	/**
	 * lod.rs {@code nearly_full}: blocks that fill their cell but neither draw solid nor collide as a
	 * full block, drawn as blocks in far terrain (or powder snow leaves holes in mountains).
	 */
	private static boolean nearlyFull(String name) {
		return switch (name) {
			case "minecraft:powder_snow", "minecraft:dirt_path", "minecraft:farmland", "minecraft:honey_block" -> true;
			default -> name.endsWith("_stairs");
		};
	}

	/** One level's chunk columns, kept for tiles that share chunks. */
	private final class Level {
		private final ServerLevel level;
		private final ChunkData.Decoder decoder;
		private final Map<Long, Summary> cache;
		/** The level's region timestamps, and the kept summaries. */
		final SummaryStore store;

		Level(ServerLevel level, ChunkData.Decoder decoder, SummaryStore store) {
			this.level = level;
			this.decoder = decoder;
			this.store = store;
			int capacity = config.cachedChunks;
			cache = java.util.Collections.synchronizedMap(new LinkedHashMap<>(capacity, 0.75f, true) {
				@Override
				protected boolean removeEldestEntry(Map.Entry<Long, Summary> eldest) {
					return size() > capacity;
				}
			});
		}

		/** A chunk's 256 columns, or empty when it isn't generated. */
		private Optional<Columns.Column[]> chunk(int x, int z) {
			long key = ChunkPos.pack(x, z);
			// Not in the level's region files: never generated (or so new it isn't saved yet,
			// shown once it is). Not kept, so a chunk saved later is read then.
			int timestamp = store.timestamp(x, z);
			if (timestamp == 0) return Optional.empty();
			// Kept in memory since the world last saved the chunk.
			Summary cached = cache.get(key);
			if (cached != null && cached.timestamp == timestamp) return cached.columns;
			// A summary made since the world last saved the chunk.
			Optional<Optional<Columns.Column[]>> summary = store.read(x, z, timestamp);
			if (summary.isPresent()) {
				kept.incrementAndGet();
				cache.put(key, new Summary(timestamp, summary.get()));
				return summary.get();
			}
			read.incrementAndGet();
			Optional<CompoundTag> tag = level.getChunkSource().chunkMap.readChunk(new ChunkPos(x, z)).join();
			Optional<Columns.Column[]> columns = tag.flatMap(decoder::decode).map(data -> {
				byte[] k = kinds();
				int water = Block.BLOCK_STATE_REGISTRY.getId(Blocks.WATER.defaultBlockState());
				Columns.Column[] result = new Columns.Column[256];
				for (int i = 0; i < 256; i++) result[i] = Columns.fromChunk(data, k, water, i & 15, i >> 4);
				return result;
			});
			cache.put(key, new Summary(timestamp, columns));
			if (tag.isPresent()) store.write(x, z, timestamp, columns);
			return columns;
		}

		/**
		 * Summarizes chunk x, z into the summary cache unless it has it already; false when the
		 * world hasn't saved the chunk yet.
		 */
		boolean summarize(int x, int z) {
			int timestamp = store.timestamp(x, z);
			if (timestamp == 0) return false;
			Summary cached = cache.get(ChunkPos.pack(x, z));
			if (cached != null && cached.timestamp == timestamp) return true;
			if (store.read(x, z, timestamp).isEmpty()) chunk(x, z);
			return true;
		}

		/** Chunk x, z's columns from memory or the summary cache only, however old; null if neither has them. */
		private Columns.Column[] keptChunk(int x, int z) {
			Summary cached = cache.get(ChunkPos.pack(x, z));
			if (cached != null) return cached.columns.orElse(null);
			SummaryStore.Kept kept = store.readAny(x, z);
			// Made by an older version: made again in the background.
			if (kept.stale()) loaded(level, x, z, 0);
			return kept.columns();
		}

		/**
		 * A {@code REQUEST_KEPT} tile: only the columns whose chunks are in memory or the summary
		 * cache (chunks a player loaded), never reading the world. The client draws the rest from
		 * the seed.
		 */
		byte[] keptTile(Request request) {
			Columns.Column empty = new Columns.Column();
			Columns.Column[] samples = new Columns.Column[256];
			byte[] mask = new byte[32];
			boolean any = false;
			for (int i = 0; i < 256; i++) {
				int wx = request.x + (i & 15) * request.stride;
				int wz = request.z + (i >> 4) * request.stride;
				Columns.Column[] chunk = keptChunk(wx >> 4, wz >> 4);
				if (chunk == null) {
					samples[i] = empty;
					continue;
				}
				any = true;
				mask[i >> 3] |= (byte) (1 << (i & 7));
				int width = request.stride <= 16 ? request.stride : 1;
				samples[i] = Columns.reduced(chunk, wx & 15, wz & 15, width, HORIZON_MIN_Y);
			}
			Wire.Writer w = new Wire.Writer().u8(HorizonLod.TILE).varint(request.id);
			if (!any) return w.u8(TILE_UNKNOWN).bytes();
			w.u8(TILE_KNOWN).raw(mask);
			for (Columns.Column column : samples) column.write(w);
			return w.bytes();
		}

		/** A {@code TILE}: 16 x 16 columns from x, z, one every {@code stride} blocks. */
		byte[] tile(Request request) {
			Columns.Column empty = new Columns.Column();
			Columns.Column[] samples = new Columns.Column[256];
			boolean any = false;
			for (int i = 0; i < 256; i++) {
				int wx = request.x + (i & 15) * request.stride;
				int wz = request.z + (i >> 4) * request.stride;
				Optional<Columns.Column[]> chunk = chunk(wx >> 4, wz >> 4);
				if (chunk.isEmpty()) {
					samples[i] = empty;
					continue;
				}
				any = true;
				// Up to a chunk wide, the sample's footprint; wider, the column at its corner.
				int width = request.stride <= 16 ? request.stride : 1;
				samples[i] = Columns.reduced(chunk.get(), wx & 15, wz & 15, width, HORIZON_MIN_Y);
			}
			Wire.Writer w = new Wire.Writer().u8(HorizonLod.TILE).varint(request.id);
			if (!any) return w.u8(TILE_EMPTY).bytes();
			w.u8(TILE_OK);
			for (Columns.Column column : samples) column.write(w);
			return w.bytes();
		}
	}
}
