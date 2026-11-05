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
	/**
	 * Chunk summaries are kept on disk in each level's folder ({@code horizon-lod/}). Off by
	 * default: replies are kept in memory instead ({@code reply-cache-mb}); a folder left by an
	 * earlier version can be deleted.
	 */
	final boolean summaryCache;
	/** Tiles a second a player may ask for, on average (sent to the client in the Offer). */
	final int tilesPerSecond;
	/** Memory for finished replies, shared by all players and levels. */
	final int replyCacheMegabytes;
	/** Chunks players load summarized into the summary cache per tick, in the background (0: none). */
	final int summarizePerTick;
	/** Horizon clients that ask get the server's tick time and rate once a second ({@code TICK_STATS}). */
	final boolean sendTickStats;

	private Config(Properties p) {
		distance = get(p, "distance-chunks", 512, 1, Tiles.MAX_DISTANCE);
		threads = get(p, "threads", 2, 1, 16);
		inFlight = get(p, "tiles-in-flight-per-player", 8, 1, 256);
		maxQueued = get(p, "max-queued-per-player", 2048, 16, 65536);
		bytesPerTick = get(p, "bytes-per-tick-per-player", 65536, 1024, 16 << 20);
		cachedChunks = get(p, "cached-chunks-per-level", 2048, 0, 1 << 20);
		summaryCache = Boolean.parseBoolean(p.getProperty("summary-cache", "false").trim());
		tilesPerSecond = get(p, "tiles-per-second-per-player", 32, 1, 1024);
		replyCacheMegabytes = get(p, "reply-cache-mb", 64, 0, 4096);
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
		boolean migrated = p.containsKey("send-seed");
		// A file from 0.4 or earlier (it has send-seed): its summary-cache=true was the old default,
		// written out for every server. Far terrain no longer needs it, so it goes off once.
		if (p.containsKey("send-seed")) {
			p.remove("send-seed");
			if (Boolean.parseBoolean(p.getProperty("summary-cache", "false").trim())) {
				p.setProperty("summary-cache", "false");
				LOG.info("Horizon LOD: summary-cache is now off (replies are kept in memory); the horizon-lod folders in the world can be deleted");
			}
		}
		Config config = new Config(p);
		// Written again when settings are missing (a file from an older version).
		if (!Files.exists(file) || !p.containsKey("summary-cache") || migrated
				|| !p.containsKey("summarize-loaded-chunks-per-tick") || !p.containsKey("send-tick-stats")
				|| !p.containsKey("tiles-per-second-per-player") || !p.containsKey("reply-cache-mb")) config.save(file);
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
		p.setProperty("tiles-per-second-per-player", Integer.toString(tilesPerSecond));
		p.setProperty("reply-cache-mb", Integer.toString(replyCacheMegabytes));
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
