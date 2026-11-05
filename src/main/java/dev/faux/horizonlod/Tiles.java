/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Faux
 * Copyright (c) 2026 Ludovic Marie and the Horizon contributors
 * Part of Horizon Server LODs. Follows Horizon's lod.rs and lod_net.rs
 * (https://github.com/horizon-rust/horizon, MIT); see LICENSE.
 */
package dev.faux.horizonlod;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Answers Horizon clients' far tiles (lod_net version 1, see PROTOCOL.md) from the chunks already
 * generated, read straight from the level's region files on low-priority threads, never generating
 * one. Each player has a token bucket, a queue, a few tiles in the works and a byte budget per tick.
 *
 * <p>Finished replies are kept in memory, keyed by the save timestamps of the chunks they read, so
 * the same tile for another player (or the same player after a rejoin) costs a few lookups until
 * one of its chunks is saved again. Chunk summaries are kept on disk only with
 * {@code summary-cache=true} (the 0.4 format, still read and written).
 */
final class Tiles {
	private static final Logger LOG = LoggerFactory.getLogger("horizon-lod");

	/** Reply statuses. */
	static final int TILE_OK = 0;
	static final int TILE_REFUSED = 1;
	/** Columns on a tile's side. */
	static final int SIDE = 16;
	/** The coarsest stride a client asks for (lod_net {@code MAX_DISTANCE}). */
	static final int MAX_DISTANCE = 1024;
	/** Chunks past the distance a tile may be: the player moved since asking (lod_net {@code SLACK}). */
	private static final int SLACK = 8;
	/** The client's limits on a reply (lod_net {@code MAX_SPANS}, {@code MAX_REPLY}, {@code MAX_BIOMES}). */
	private static final int MAX_SPANS = 16 * 1024;
	private static final int MAX_REPLY = 512 * 1024;
	private static final int MAX_BIOMES = 256;
	private static final int MAX_BIOME_NAME = 256;
	/** Heights a span or surface may reach (lod_net {@code HEIGHTS}). */
	private static final int MIN_HEIGHT = -2048, MAX_HEIGHT = 4096;
	/** Chunks waiting to be summarized in the background, per level at most. */
	private static final int MAX_PENDING = 65536;
	/** Of those, the ones holding a copy of the chunk taken at unload: each holds its sections in memory. */
	private static final int MAX_SNAPSHOTS = 512;
	/** Tries for a chunk not saved yet (no region timestamp), a minute apart. */
	private static final int TRIES = 5;
	/** Horizon's fixed column bottom (chunk.rs {@code MIN_Y}). */
	private static final int HORIZON_MIN_Y = -64;

	/** A lod_net {@code Request}. */
	record Request(int id, int x, int z, int stride, boolean approximate) {
		static Request read(Wire.Reader r) {
			Request request = new Request(r.varint(), r.i32(), r.i32(), r.varint(), r.u8() != 0);
			if (!r.done()) throw new IllegalArgumentException("trailing bytes");
			return request;
		}

		/** lod_net {@code Request::allowed}: an aligned whole tile within the distance. */
		boolean allowed(int distance, double px, double pz) {
			if (distance <= 0) return false;
			// distance.next_power_of_two().min(MAX_DISTANCE)
			int coarsest = Math.min(distance == 1 ? 1 : Integer.highestOneBit(distance - 1) << 1, MAX_DISTANCE);
			if (stride < 1 || stride > coarsest || Integer.bitCount(stride) != 1) return false;
			int size = stride * SIDE;
			if (Math.floorMod(x, size) != 0 || Math.floorMod(z, size) != 0) return false;
			double dx = Math.max(Math.max(x - px, px - x - size), 0);
			double dz = Math.max(Math.max(z - pz, pz - z - size), 0);
			double reach = (double) (distance + SLACK) * 16;
			return dx * dx + dz * dz <= reach * reach;
		}
	}

	/** A chunk waiting for its background summary: when, tries, and its copy if one was taken. */
	private static final class Pending {
		long due;
		int tries;
		ChunkData.Snapshot snapshot;

		Pending(long due, int tries, ChunkData.Snapshot snapshot) {
			this.due = due;
			this.tries = tries;
			this.snapshot = snapshot;
		}
	}

	private static final class Player {
		final ArrayDeque<Request> queue = new ArrayDeque<>();
		final ConcurrentLinkedQueue<byte[]> ready = new ConcurrentLinkedQueue<>();
		final AtomicInteger working = new AtomicInteger();
		/** Token bucket: requests it may still make, refilled at {@code tiles-per-second-per-player}. */
		double tokens;
		long refilled = System.nanoTime();

		Player(double burst) {
			tokens = burst;
		}
	}

	private final Config config;
	private final ExecutorService workers;
	private final Map<UUID, Player> players = new ConcurrentHashMap<>();
	private final Map<ServerLevel, Level> levels = new ConcurrentHashMap<>();
	private volatile byte[] kinds;
	/** Chunks from the summary store, read from the level, and tiles served from memory (logged each minute). */
	private final AtomicLong kept = new AtomicLong(), read = new AtomicLong(), reused = new AtomicLong();
	private long logged, loggedAt;
	/** Loaded chunks to summarize into the summary store, per level (only with {@code summary-cache=true}). */
	private final Map<ServerLevel, LinkedHashMap<Long, Pending>> pending = new ConcurrentHashMap<>();
	private final AtomicInteger snapshots = new AtomicInteger();
	private final ExecutorService summarizer;
	private final AtomicInteger summarizing = new AtomicInteger();
	private final ReplyCache replies;

	Tiles(Config config) {
		this.config = config;
		this.replies = new ReplyCache((long) config.replyCacheMegabytes << 20);
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

	private boolean summarizes() {
		return config.summaryCache && config.summarizePerTick > 0;
	}

	/** A chunk a player's game loaded: summarized into the summary store in the background. */
	void loaded(ServerLevel level, int x, int z) {
		if (summarizes()) queue(level, x, z, 0, null);
	}

	/**
	 * A chunk unloading, on the server thread: with the summary store on, a copy of its sections
	 * (cheap: paletted containers and nibble arrays) is summarized in the background once the
	 * world has saved it, without reading it back from disk.
	 */
	void unloaded(ServerLevel level, LevelChunk chunk) {
		if (!summarizes()) return;
		ChunkData.Snapshot snapshot = null;
		if (snapshots.get() < MAX_SNAPSHOTS) {
			snapshot = ChunkData.Snapshot.of(level, chunk);
			snapshots.incrementAndGet();
		}
		queue(level, chunk.getPos().x(), chunk.getPos().z(), 15_000, snapshot);
	}

	private void queue(ServerLevel level, int x, int z, long delay, ChunkData.Snapshot snapshot) {
		LinkedHashMap<Long, Pending> chunks = pending.computeIfAbsent(level, l -> new LinkedHashMap<>());
		synchronized (chunks) {
			long key = ChunkPos.pack(x, z);
			long due = System.currentTimeMillis() + delay;
			Pending entry = chunks.get(key);
			if (entry != null) {
				entry.due = Math.max(entry.due, due);
				if (snapshot != null) {
					if (entry.snapshot != null) snapshots.decrementAndGet();
					entry.snapshot = snapshot;
				}
			} else if (chunks.size() < MAX_PENDING) {
				chunks.put(key, new Pending(due, 0, snapshot));
			} else if (snapshot != null) {
				snapshots.decrementAndGet();
			}
		}
	}

	/** Starts the background summaries that are due, a few per tick. */
	private void summarize(long now) {
		for (Map.Entry<ServerLevel, LinkedHashMap<Long, Pending>> entry : pending.entrySet()) {
			LinkedHashMap<Long, Pending> chunks = entry.getValue();
			Level levelState = levels.computeIfAbsent(entry.getKey(), this::level);
			synchronized (chunks) {
				var it = chunks.entrySet().iterator();
				while (it.hasNext() && summarizing.get() < config.summarizePerTick) {
					Map.Entry<Long, Pending> chunk = it.next();
					Pending state = chunk.getValue();
					if (state.due > now) continue;
					it.remove();
					long key = chunk.getKey();
					summarizing.incrementAndGet();
					try {
						summarizer.execute(() -> {
							boolean done = false;
							try {
								done = levelState.summarize(ChunkPos.getX(key), ChunkPos.getZ(key), state.snapshot);
							} catch (RuntimeException e) {
								LOG.debug("Horizon LOD: summarizing chunk failed", e);
								done = true;
							} finally {
								summarizing.decrementAndGet();
							}
							// Not saved yet: tried again later, keeping its copy.
							if (!done && state.tries + 1 < TRIES) {
								synchronized (chunks) {
									if (!chunks.containsKey(key)) {
										state.due = System.currentTimeMillis() + 60_000;
										state.tries++;
										chunks.put(key, state);
										return;
									}
								}
							}
							if (state.snapshot != null) snapshots.decrementAndGet();
						});
					} catch (java.util.concurrent.RejectedExecutionException e) {
						summarizing.decrementAndGet();
						return;
					}
				}
			}
		}
	}

	void forget(ServerPlayer player) {
		players.remove(player.getUUID());
	}

	private double burst() {
		return 4.0 * config.tilesPerSecond;
	}

	/** A Request from the network: admitted against the player's token bucket and queue. */
	void request(ServerPlayer player, Request request) {
		Player state = players.computeIfAbsent(player.getUUID(), id -> new Player(burst()));
		synchronized (state) {
			long now = System.nanoTime();
			state.tokens = Math.min(burst(), state.tokens + (now - state.refilled) / 1e9 * config.tilesPerSecond);
			state.refilled = now;
			if (state.tokens < 1 || state.queue.size() >= config.maxQueued) {
				state.ready.add(refused(request.id));
				return;
			}
			state.tokens -= 1;
			state.queue.add(request);
		}
	}

	/** On the server thread, each tick: start queued tiles and send finished ones. */
	void tick(MinecraftServer server) {
		long now = System.currentTimeMillis();
		if (now - loggedAt > 60_000) {
			long total = kept.get() + read.get() + reused.get();
			if (total != logged) {
				LOG.info("Horizon LOD: {} chunks from the summary store, {} read from the world, {} tiles reused from memory",
						kept.get(), read.get(), reused.get());
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
			while (state.working.get() < config.inFlight) {
				Request request;
				synchronized (state) {
					request = state.queue.poll();
				}
				if (request == null) break;
				if (!request.allowed(config.distance, player.getX(), player.getZ())) {
					state.ready.add(refused(request.id));
					continue;
				}
				Level levelState = levels.computeIfAbsent(level, this::level);
				state.working.incrementAndGet();
				workers.execute(() -> {
					try {
						state.ready.add(levelState.reply(request));
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

	static byte[] refused(int id) {
		return new Wire.Writer().varint(id).u8(TILE_REFUSED).bytes();
	}

	private Level level(ServerLevel level) {
		Registry<Biome> biomes = level.registryAccess().lookupOrThrow(Registries.BIOME);
		ChunkData.Decoder decoder = new ChunkData.Decoder(biomes, level.getMinY(), level.getHeight(),
				level.dimensionType().hasSkyLight());
		MinecraftServer server = level.getServer();
		java.nio.file.Path dimension = DimensionType.getStorageFolder(level.dimension(), server.getWorldPath(LevelResource.ROOT));
		SummaryStore store = new SummaryStore(dimension, server.getWorldData().getLevelName(), level.dimension(), config.summaryCache);
		return new Level(level, decoder, store, new RegionReader(dimension.resolve("region")), biomes);
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
			boolean keep = !state.isAir()
					&& (state.isSolidRender()
							|| state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)
							|| BuiltInRegistries.BLOCK.getKey(block).getPath().endsWith("_leaves")
							|| fluid
							|| nearlyFull(BuiltInRegistries.BLOCK.getKey(block).toString()));
			boolean watery = !keep && !state.isAir() && state.getFluidState().is(FluidTags.WATER);
			k[id] = (byte) ((keep || watery ? Columns.KEPT : 0)
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
		private final RegionReader regions;
		private final Registry<Biome> biomes;
		private final Map<Integer, String> biomeNames = new ConcurrentHashMap<>();
		/** The level's region timestamps, and the kept summaries. */
		final SummaryStore store;

		Level(ServerLevel level, ChunkData.Decoder decoder, SummaryStore store, RegionReader regions, Registry<Biome> biomes) {
			this.level = level;
			this.decoder = decoder;
			this.store = store;
			this.regions = regions;
			this.biomes = biomes;
			int capacity = config.cachedChunks;
			cache = java.util.Collections.synchronizedMap(new LinkedHashMap<>(capacity, 0.75f, true) {
				@Override
				protected boolean removeEldestEntry(Map.Entry<Long, Summary> eldest) {
					return size() > capacity;
				}
			});
		}

		/** A chunk's 256 columns. */
		private Columns.Column[] columns(ChunkData data) {
			byte[] k = kinds();
			int water = Block.BLOCK_STATE_REGISTRY.getId(Blocks.WATER.defaultBlockState());
			Columns.Column[] result = new Columns.Column[256];
			for (int i = 0; i < 256; i++) result[i] = Columns.fromChunk(data, k, water, i & 15, i >> 4);
			return result;
		}

		/** Chunk x, z's saved NBT: from its region file directly, else the server's own reader. */
		private Optional<CompoundTag> readChunk(int x, int z) {
			RegionReader.Read r = regions.read(x, z);
			if (r.result() == RegionReader.Result.FOUND) return r.tag();
			// Datafixing, an external chunk, a torn read, or not on disk yet: the server's way.
			return level.getChunkSource().chunkMap.readChunk(new ChunkPos(x, z)).join();
		}

		/** A chunk's 256 columns, or empty when it isn't generated. */
		private Optional<Columns.Column[]> chunk(int x, int z, int timestamp) {
			long key = ChunkPos.pack(x, z);
			// Not in the level's region files: never generated (or so new it isn't saved yet,
			// shown once it is). Not kept, so a chunk saved later is read then.
			if (timestamp == 0) return Optional.empty();
			// Kept in memory since the world last saved the chunk.
			Summary cached = cache.get(key);
			if (cached != null && cached.timestamp == timestamp) return cached.columns;
			// A summary made since the world last saved the chunk (summary-cache=true only).
			Optional<Optional<Columns.Column[]>> summary = store.read(x, z, timestamp);
			if (summary.isPresent()) {
				kept.incrementAndGet();
				cache.put(key, new Summary(timestamp, summary.get()));
				return summary.get();
			}
			read.incrementAndGet();
			Optional<CompoundTag> tag = readChunk(x, z);
			Optional<Columns.Column[]> columns = tag.flatMap(decoder::decode).map(this::columns);
			cache.put(key, new Summary(timestamp, columns));
			if (tag.isPresent()) store.write(x, z, timestamp, columns);
			return columns;
		}

		/**
		 * Summarizes chunk x, z into the summary store unless it has it already, from its copy
		 * when one was taken; false when the world hasn't saved the chunk yet.
		 */
		boolean summarize(int x, int z, ChunkData.Snapshot snapshot) {
			int timestamp = store.timestamp(x, z);
			if (timestamp == 0) return false;
			Summary cached = cache.get(ChunkPos.pack(x, z));
			if (cached != null && cached.timestamp == timestamp) return true;
			if (store.read(x, z, timestamp).isPresent()) return true;
			if (snapshot != null) {
				Optional<Columns.Column[]> columns = Optional.of(columns(decoder.fromSnapshot(snapshot)));
				cache.put(ChunkPos.pack(x, z), new Summary(timestamp, columns));
				store.write(x, z, timestamp, columns);
			} else {
				chunk(x, z, timestamp);
			}
			return true;
		}

		/** A Request's reply: from memory while none of its chunks was saved again, else sampled. */
		byte[] reply(Request request) {
			int[] stamps = new int[256];
			long version = 1;
			long lastChunk = Long.MIN_VALUE;
			int lastStamp = 0;
			for (int i = 0; i < 256; i++) {
				int cx = (request.x + (i & 15) * request.stride) >> 4;
				int cz = (request.z + (i >> 4) * request.stride) >> 4;
				long chunk = ChunkPos.pack(cx, cz);
				// Neighbouring samples share a chunk at small strides: one lookup for the run.
				int stamp = chunk == lastChunk ? lastStamp : store.timestamp(cx, cz);
				lastChunk = chunk;
				lastStamp = stamp;
				stamps[i] = stamp;
				version = version * 1_000_003L + stamp;
			}
			ReplyCache.Key key = new ReplyCache.Key(level.dimension().identifier().toString(),
					request.x, request.z, request.stride, request.approximate);
			byte[] cached = replies.get(key, version);
			if (cached != null) {
				reused.incrementAndGet();
				return withId(cached, request.id);
			}
			byte[] body = tile(request, stamps);
			if (body == null) return refused(request.id);
			replies.put(key, version, body);
			return withId(body, request.id);
		}

		/** The reply after its id (status and columns), or null when the client would refuse it. */
		private byte[] tile(Request request, int[] stamps) {
			Columns.Column empty = new Columns.Column();
			Columns.Column[] samples = new Columns.Column[256];
			// Up to a chunk wide, the sample's footprint; wider, or Reduced far detail, the column
			// at its corner.
			int width = request.stride <= 16 && !request.approximate ? request.stride : 1;
			for (int i = 0; i < 256; i++) {
				int wx = request.x + (i & 15) * request.stride;
				int wz = request.z + (i >> 4) * request.stride;
				Optional<Columns.Column[]> chunk = chunk(wx >> 4, wz >> 4, stamps[i]);
				samples[i] = chunk.isEmpty() ? empty : Columns.reduced(chunk.get(), wx & 15, wz & 15, width, HORIZON_MIN_Y);
			}
			return encode(samples);
		}

		/** lod_net {@code encode_reply} after the id: status, biome names, 256 columns with their surfaces. */
		private byte[] encode(Columns.Column[] columns) {
			byte[] k = kinds();
			List<Integer> palette = new ArrayList<>();
			Map<Integer, Integer> local = new java.util.HashMap<>();
			java.util.function.IntUnaryOperator index = biome -> local.computeIfAbsent(biome, b -> {
				palette.add(b);
				return palette.size() - 1;
			});
			int spans = 0;
			Wire.Writer body = new Wire.Writer().varint(columns.length);
			for (Columns.Column c : columns) {
				spans += c.count;
				if (spans > MAX_SPANS) return null;
				// lod.rs Column::from_chunk: the top solid face, with the fluid above it if any.
				int surface = -1;
				int waterSpan = -1;
				for (int i = c.count - 1; i >= 0; i--) {
					int kind = c.state[i] < k.length ? k[c.state[i]] & 0xff : 0;
					if ((kind & Columns.FLUID) != 0) {
						if (waterSpan < 0) waterSpan = i;
					} else {
						surface = i;
						break;
					}
				}
				if (surface >= 0) {
					body.u8(1).i32(c.max[surface]).varint(c.state[surface]);
					if (waterSpan >= 0) body.u8(1).i32(c.max[waterSpan]).varint(c.state[waterSpan]);
					else body.u8(0);
					body.varint(index.applyAsInt(c.biome[surface]));
				} else if (waterSpan >= 0) {
					// Only fluid: an air floor at the level's bottom under it, as lod.rs does.
					body.u8(1).i32(level.getMinY()).varint(0);
					body.u8(1).i32(c.max[waterSpan]).varint(c.state[waterSpan]);
					body.varint(index.applyAsInt(c.biome[waterSpan]));
				} else {
					body.u8(0);
				}
				body.varint(c.count);
				int floor = MIN_HEIGHT;
				for (int i = 0; i < c.count; i++) {
					if (c.min[i] < floor || c.max[i] > MAX_HEIGHT || c.min[i] >= c.max[i]) return null;
					floor = c.max[i];
					body.i16(c.min[i]).i16(c.max[i]).varint(c.state[i]).varint(index.applyAsInt(c.biome[i])).i16(c.light[i]);
				}
			}
			if (palette.size() > MAX_BIOMES) return null;
			Wire.Writer w = new Wire.Writer().u8(TILE_OK).varint(palette.size());
			for (int biome : palette) w.string(biomeName(biome));
			w.raw(body.bytes());
			byte[] reply = w.bytes();
			// Plus at most five bytes of id.
			return reply.length + 5 > MAX_REPLY ? null : reply;
		}

		private String biomeName(int id) {
			return biomeNames.computeIfAbsent(id, i -> {
				Biome biome = biomes.byId(i);
				Identifier key = biome == null ? null : biomes.getKey(biome);
				String name = key == null ? "minecraft:plains" : key.toString();
				return name.length() <= MAX_BIOME_NAME ? name : "minecraft:plains";
			});
		}
	}

	/** {@code body} (status onwards) after the request's id. */
	private static byte[] withId(byte[] body, int id) {
		byte[] head = new Wire.Writer().varint(id).bytes();
		byte[] out = new byte[head.length + body.length];
		System.arraycopy(head, 0, out, 0, head.length);
		System.arraycopy(body, 0, out, head.length, body.length);
		return out;
	}
}
