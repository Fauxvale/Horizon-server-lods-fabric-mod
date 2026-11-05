package dev.faux.horizonlod;

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
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.ServerTickRateManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Far terrain for Horizon clients over Horizon's own {@code horizon:lod} protocol (lod_net version
 * 1): the server says it is Horizon, offers far terrain to the clients that answer as Horizon
 * while they configure, then serves their tile requests. The server's tick time goes to clients
 * that ask on {@code faux:tick_stats}. See PROTOCOL.md.
 */
public final class HorizonLod implements ModInitializer {
	private static final Logger LOG = LoggerFactory.getLogger("horizon-lod");

	/** The server brand Horizon clients answer (net.rs: exactly {@code "horizon"}). */
	public static final String BRAND = "horizon";
	/** lod_net's version, first in an Offer. */
	static final int VERSION = 1;
	/** Ticks between {@code faux:tick_stats} messages. */
	private static final int TICK_STATS_INTERVAL = 20;

	private static volatile HorizonLod instance;

	private Config config;
	private volatile Tiles tiles;
	/** Players who asked for tick stats. */
	private final Set<UUID> tickPlayers = ConcurrentHashMap.newKeySet();

	/** Each configuring connection's last client brand. */
	private static final java.util.Map<Object, String> BRANDS =
			java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

	/** A configuring client's brand (its listener as the key). */
	public static void clientBrand(Object listener, String brand) {
		BRANDS.put(listener, brand);
	}

	/** Whether the configuring client said it is Horizon. */
	public static boolean isHorizon(Object listener) {
		return BRAND.equals(BRANDS.get(listener));
	}

	/** The Offer's bytes when far terrain is served, else null (the configuration mixin sends it). */
	public static byte[] offer() {
		HorizonLod mod = instance;
		if (mod == null || mod.tiles == null || mod.config.distance <= 0) return null;
		return new Wire.Writer()
				.varint(VERSION)
				.varint(mod.config.distance)
				.varint(mod.config.inFlight)
				.varint(mod.config.tilesPerSecond)
				.bytes();
	}

	@Override
	public void onInitialize() {
		instance = this;
		PayloadTypeRegistry.clientboundConfiguration().register(LodPayload.TYPE, LodPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(LodPayload.TYPE, LodPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(LodPayload.TYPE, LodPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(TickPayload.TYPE, TickPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(TickPayload.TYPE, TickPayload.CODEC);
		config = Config.load(FabricLoader.getInstance().getConfigDir());
		ServerLifecycleEvents.SERVER_STARTED.register(server -> tiles = new Tiles(config));
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			Tiles t = tiles;
			tiles = null;
			if (t != null) t.stop();
		});
		ServerChunkEvents.CHUNK_LOAD.register((level, chunk, generated) -> {
			Tiles t = tiles;
			if (t != null) t.loaded(level, chunk.getPos().x(), chunk.getPos().z());
		});
		ServerChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> {
			Tiles t = tiles;
			if (t != null) t.unloaded(level, chunk);
		});
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			Tiles t = tiles;
			if (t != null) t.tick(server);
			if (!tickPlayers.isEmpty() && server.getTickCount() % TICK_STATS_INTERVAL == 0) sendTickStats(server);
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			Tiles t = tiles;
			if (t != null) t.forget(handler.getPlayer());
			tickPlayers.remove(handler.getPlayer().getUUID());
		});
		ServerPlayNetworking.registerGlobalReceiver(LodPayload.TYPE, (payload, context) -> {
			Tiles t = tiles;
			if (t == null) return;
			try {
				t.request(context.player(), Tiles.Request.read(new Wire.Reader(payload.data())));
			} catch (IllegalArgumentException e) {
				LOG.debug("Horizon LOD: bad request from {}: {}", context.player().getName().getString(), e.getMessage());
			}
		});
		ServerPlayNetworking.registerGlobalReceiver(TickPayload.TYPE, (payload, context) -> {
			byte[] data = payload.data();
			boolean on = data.length == 1 && data[0] != 0 && config.sendTickStats;
			if (on) tickPlayers.add(context.player().getUUID());
			else tickPlayers.remove(context.player().getUUID());
		});
	}

	/**
	 * {@code faux:tick_stats}: the average tick time of the last 100 ticks in milliseconds (as
	 * {@code /tick query} gives it), the target tick rate, then sprinting and frozen.
	 */
	private void sendTickStats(MinecraftServer server) {
		ServerTickRateManager rate = server.tickRateManager();
		byte[] message = new Wire.Writer()
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
				ServerPlayNetworking.send(player, new TickPayload(message));
			}
		}
	}
}
