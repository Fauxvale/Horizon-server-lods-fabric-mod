package dev.faux.horizonlod;

import com.google.gson.JsonElement;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerTickRateManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunctions;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The {@code horizon:lod} channel: Horizon clients ask for far terrain, the server sends it. */
public final class HorizonLod implements ModInitializer {
	private static final Logger LOG = LoggerFactory.getLogger("horizon-lod");
	static final int VERSION = 4;
	static final int HELLO = 0;
	static final int REQUEST = 1;
	/** Client to server, from version 3: tiles answered from the summary cache only. */
	static final int REQUEST_KEPT = 2;
	/** Client to server, from version 4: a byte, 1 to get {@code TICK_STATS}, 0 to stop. */
	static final int TICKS = 3;
	static final int TILE = 1;
	static final int WORLDGEN = 2;
	static final int WORLDGEN_END = 3;
	/** Server to client, from version 4: the server's tick time and rate, once a second. */
	static final int TICK_STATS = 4;
	/** Ticks between {@code TICK_STATS} messages. */
	private static final int TICK_STATS_INTERVAL = 20;
	/** A {@code WORLDGEN} message's entries stop once it is this long (payloads go to 1 MiB). */
	private static final int WORLDGEN_BYTES = 256 * 1024;
	/** Tiles in one request at most. */
	private static final int MAX_REQUESTS = 1024;

	private Tiles tiles;
	/** The {@code HELLO} and {@code WORLDGEN} messages of this server's world, made when it starts. */
	/** {@code HELLO} for version 2 to 4 clients (the same but for the version). */
	private volatile byte[] hello2, hello3, hello4;
	/** Players who asked for {@code TICK_STATS}. */
	private final Set<UUID> tickPlayers = ConcurrentHashMap.newKeySet();
	private volatile List<byte[]> worldgen = List.of();

	@Override
	public void onInitialize() {
		PayloadTypeRegistry.serverboundPlay().register(LodPayload.TYPE, LodPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(LodPayload.TYPE, LodPayload.CODEC);
		Config config = Config.load(FabricLoader.getInstance().getConfigDir());
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			tiles = new Tiles(config);
			hello2 = hello(server, config, 2);
			hello3 = hello(server, config, 3);
			hello4 = hello(server, config, 4);
			worldgen = config.sendSeed ? worldgen(server) : List.of();
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			if (tiles != null) tiles.stop();
			tiles = null;
		});
		ServerChunkEvents.CHUNK_LOAD.register((level, chunk, generated) -> {
			if (tiles != null) tiles.loaded(level, chunk.getPos().x(), chunk.getPos().z(), 0);
		});
		ServerChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> {
			if (tiles != null) tiles.loaded(level, chunk.getPos().x(), chunk.getPos().z(), 15_000);
		});
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (tiles != null) tiles.tick(server);
			if (!tickPlayers.isEmpty() && server.getTickCount() % TICK_STATS_INTERVAL == 0) sendTickStats(server);
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			if (tiles != null) tiles.forget(handler.getPlayer());
			tickPlayers.remove(handler.getPlayer().getUUID());
		});
		ServerPlayNetworking.registerGlobalReceiver(LodPayload.TYPE, (payload, context) -> {
			try {
				receive(context.player(), payload.data(), config);
			} catch (IllegalArgumentException e) {
				LOG.debug("Horizon LOD: bad message from {}: {}", context.player().getName().getString(), e.getMessage());
			}
		});
	}

	/**
	 * {@code HELLO} version 2: the distance, the world's id (its {@code horizon-lod/world-id},
	 * made the first time), its seed when shared, and each noise-generated level's noise settings.
	 */
	private static byte[] hello(MinecraftServer server, Config config, int version) {
		Wire.Writer w = new Wire.Writer().u8(HELLO).varint(version).varint(config.distance);
		w.string(worldId(server));
		if (config.sendSeed) {
			w.u8(1).i64(server.overworld().getSeed());
		} else {
			w.u8(0);
		}
		List<String[]> settings = new ArrayList<>();
		for (ServerLevel level : server.getAllLevels()) {
			if (level.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator noise) {
				noise.generatorSettings().unwrapKey().ifPresent(key -> settings.add(new String[] {
						level.dimension().identifier().toString(), key.identifier().toString()}));
			}
		}
		w.varint(settings.size());
		for (String[] entry : settings) w.string(entry[0]).string(entry[1]);
		return w.bytes();
	}

	/**
	 * {@code TICK_STATS}: the average tick time of the last 100 ticks in milliseconds (as
	 * {@code /tick query} gives it), the target tick rate, then sprinting and frozen.
	 */
	private void sendTickStats(MinecraftServer server) {
		ServerTickRateManager rate = server.tickRateManager();
		byte[] message = new Wire.Writer()
				.u8(TICK_STATS)
				.f32(server.getAverageTickTimeNanos() / 1_000_000f)
				.f32(rate.tickrate())
				.u8(rate.isSprinting() ? 1 : 0)
				.u8(rate.isFrozen() ? 1 : 0)
				.bytes();
		for (UUID id : tickPlayers) {
			ServerPlayer player = server.getPlayerList().getPlayer(id);
			if (player == null) {
				tickPlayers.remove(id);
			} else {
				ServerPlayNetworking.send(player, new LodPayload(message));
			}
		}
	}

	/** The world's id, kept in its folder: a world made again in the same folder gets a new one. */
	private static String worldId(MinecraftServer server) {
		Path file = server.getWorldPath(LevelResource.ROOT).resolve("horizon-lod").resolve("world-id");
		try {
			if (Files.exists(file)) {
				String id = Files.readString(file, StandardCharsets.UTF_8).trim();
				if (!id.isEmpty() && id.length() <= 64) return id;
			}
			String id = UUID.randomUUID().toString();
			Files.createDirectories(file.getParent());
			Files.writeString(file, id + "\n", StandardCharsets.UTF_8);
			return id;
		} catch (IOException e) {
			LOG.warn("Horizon LOD: could not keep the world id in {}", file, e);
			return "";
		}
	}

	/** {@code WORLDGEN}: every noise and density function of the server's registries, as JSON. */
	private static List<byte[]> worldgen(MinecraftServer server) {
		RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, server.registryAccess());
		List<String[]> entries = new ArrayList<>();
		collect(entries, "noises", server.registryAccess().lookupOrThrow(Registries.NOISE), NormalNoise.DIRECT_CODEC, ops);
		collect(entries, "densities", server.registryAccess().lookupOrThrow(Registries.DENSITY_FUNCTION), DensityFunctions.DIRECT_CODEC, ops);
		List<byte[]> messages = new ArrayList<>();
		int at = 0;
		while (at < entries.size()) {
			Wire.Writer body = new Wire.Writer();
			int count = 0;
			while (at < entries.size() && (count == 0 || body.size() < WORLDGEN_BYTES)) {
				String[] entry = entries.get(at++);
				body.string(entry[0]).string(entry[1]).string(entry[2]);
				count++;
			}
			Wire.Writer w = new Wire.Writer().u8(WORLDGEN).varint(count);
			w.raw(body.bytes());
			messages.add(w.bytes());
		}
		LOG.info("Horizon LOD: {} worldgen entries for Horizon clients' seed terrain", entries.size());
		return messages;
	}

	private static <T> void collect(List<String[]> entries, String bundle, Registry<T> registry, Codec<T> codec, RegistryOps<JsonElement> ops) {
		for (var holder : (Iterable<net.minecraft.core.Holder.Reference<T>>) registry.listElements()::iterator) {
			ResourceKey<T> key = holder.key();
			codec.encodeStart(ops, holder.value())
					.resultOrPartial(error -> LOG.debug("Horizon LOD: {} {} is not sent: {}", bundle, key.identifier(), error))
					.ifPresent(json -> entries.add(new String[] {bundle, key.identifier().toString(), json.toString()}));
		}
	}

	private void receive(ServerPlayer player, byte[] data, Config config) {
		Tiles t = tiles;
		if (t == null) return;
		Wire.Reader r = new Wire.Reader(data);
		switch (r.u8()) {
			case HELLO -> {
				int version = r.varint();
				if (version < 1) return;
				t.hello(player);
				if (version == 1) {
					byte[] reply = new Wire.Writer().u8(HELLO).varint(1).varint(config.distance).bytes();
					ServerPlayNetworking.send(player, new LodPayload(reply));
				} else {
					byte[] hello = version >= 4 ? hello4 : version == 3 ? hello3 : hello2;
					ServerPlayNetworking.send(player, new LodPayload(hello));
					for (byte[] message : worldgen) ServerPlayNetworking.send(player, new LodPayload(message));
					ServerPlayNetworking.send(player, new LodPayload(new Wire.Writer().u8(WORLDGEN_END).bytes()));
				}
				LOG.info("Horizon LOD: sending far terrain to {}", player.getName().getString());
			}
			case REQUEST, REQUEST_KEPT -> {
				boolean kept = data[0] == REQUEST_KEPT;
				String level = r.string(256);
				int count = r.varint();
				if (count < 0 || count > MAX_REQUESTS) throw new IllegalArgumentException("too many tiles");
				List<Tiles.Request> requests = new ArrayList<>(count);
				for (int i = 0; i < count; i++) {
					requests.add(new Tiles.Request(r.varint(), r.varint(), r.varint(), r.varint(), kept));
				}
				t.request(player, level, requests);
			}
			case TICKS -> {
				if (r.u8() != 0 && config.sendTickStats) {
					tickPlayers.add(player.getUUID());
				} else {
					tickPlayers.remove(player.getUUID());
				}
			}
			default -> {}
		}
	}
}
