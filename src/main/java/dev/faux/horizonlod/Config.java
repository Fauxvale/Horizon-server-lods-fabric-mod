package dev.faux.horizonlod;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** {@code config/horizon-lod.properties}, written with the defaults the first time. */
final class Config {
	private static final Logger LOG = LoggerFactory.getLogger("horizon-lod");

	/** Far terrain is sent for chunks up to this many chunks from the player. */
	final int distance;
	/** Threads reading and summarizing chunks. */
	final int threads;
	/** Tiles in the works per player at once. */
	final int inFlight;
	/** Requests a player may have waiting; more are refused. */
	final int maxQueued;
	/** Bytes of tiles sent to a player per tick (20 ticks a second). */
	final int bytesPerTick;
	/** Chunks' columns kept in memory per level. */
	final int cachedChunks;
	/** Chunk summaries are kept in each level's folder ({@code horizon-lod/}). */
	final boolean summaryCache;
	/** The world's seed is sent to Horizon clients, which draw far terrain from it. */
	final boolean sendSeed;
	/** Chunks players load summarized into the summary cache per tick, in the background (0: none). */
	final int summarizePerTick;
	/** Horizon clients that ask get the server's tick time and rate once a second ({@code TICK_STATS}). */
	final boolean sendTickStats;

	private Config(Properties p) {
		distance = get(p, "distance-chunks", 512, 1, 4096);
		threads = get(p, "threads", 2, 1, 16);
		inFlight = get(p, "tiles-in-flight-per-player", 8, 1, 256);
		maxQueued = get(p, "max-queued-per-player", 2048, 16, 65536);
		bytesPerTick = get(p, "bytes-per-tick-per-player", 65536, 1024, 16 << 20);
		cachedChunks = get(p, "cached-chunks-per-level", 2048, 0, 1 << 20);
		summaryCache = Boolean.parseBoolean(p.getProperty("summary-cache", "true").trim());
		sendSeed = Boolean.parseBoolean(p.getProperty("send-seed", "true").trim());
		summarizePerTick = get(p, "summarize-loaded-chunks-per-tick", 4, 0, 256);
		sendTickStats = Boolean.parseBoolean(p.getProperty("send-tick-stats", "true").trim());
	}

	static Config load(Path dir) {
		Path file = dir.resolve("horizon-lod.properties");
		Properties p = new Properties();
		if (Files.exists(file)) {
			try (Reader r = Files.newBufferedReader(file)) {
				p.load(r);
			} catch (IOException e) {
				LOG.warn("Horizon LOD: could not read {}, using the defaults", file, e);
			}
		}
		Config config = new Config(p);
		// Written again when settings are missing (a file from an older version).
		if (!Files.exists(file) || !p.containsKey("summary-cache") || !p.containsKey("send-seed")
				|| !p.containsKey("summarize-loaded-chunks-per-tick") || !p.containsKey("send-tick-stats")) config.save(file);
		return config;
	}

	private void save(Path file) {
		Properties p = new Properties();
		p.setProperty("distance-chunks", Integer.toString(distance));
		p.setProperty("threads", Integer.toString(threads));
		p.setProperty("tiles-in-flight-per-player", Integer.toString(inFlight));
		p.setProperty("max-queued-per-player", Integer.toString(maxQueued));
		p.setProperty("bytes-per-tick-per-player", Integer.toString(bytesPerTick));
		p.setProperty("cached-chunks-per-level", Integer.toString(cachedChunks));
		p.setProperty("summary-cache", Boolean.toString(summaryCache));
		p.setProperty("send-seed", Boolean.toString(sendSeed));
		p.setProperty("summarize-loaded-chunks-per-tick", Integer.toString(summarizePerTick));
		p.setProperty("send-tick-stats", Boolean.toString(sendTickStats));
		try {
			Files.createDirectories(file.getParent());
			try (Writer w = Files.newBufferedWriter(file)) {
				p.store(w, "Horizon Server LODs: far terrain for Horizon clients");
			}
		} catch (IOException e) {
			LOG.warn("Horizon LOD: could not write {}", file, e);
		}
	}

	private static int get(Properties p, String key, int fallback, int min, int max) {
		try {
			return Math.clamp(Long.parseLong(p.getProperty(key, Integer.toString(fallback)).trim()), min, max);
		} catch (NumberFormatException e) {
			LOG.warn("Horizon LOD: {} is not a number, using {}", key, fallback);
			return fallback;
		}
	}
}
