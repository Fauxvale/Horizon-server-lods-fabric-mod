package dev.faux.horizonlod;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Finished tile replies (status onwards, without the request's id) in memory, least recently used
 * first out once they pass a byte budget. A reply is good while the save timestamps of the chunks
 * it read are the same ({@code version}): replaces keeping chunk summaries on disk.
 */
final class ReplyCache {
	record Key(String level, int x, int z, int stride, boolean approximate) {}

	private record Entry(long version, byte[] body) {}

	private final long budget;
	private long bytes;
	private final LinkedHashMap<Key, Entry> entries = new LinkedHashMap<>(256, 0.75f, true);

	ReplyCache(long budget) {
		this.budget = budget;
	}

	synchronized byte[] get(Key key, long version) {
		Entry entry = entries.get(key);
		return entry != null && entry.version == version ? entry.body : null;
	}

	synchronized void put(Key key, long version, byte[] body) {
		if (budget <= 0 || body.length > budget) return;
		Entry old = entries.put(key, new Entry(version, body));
		if (old != null) bytes -= old.body.length;
		bytes += body.length;
		Iterator<Map.Entry<Key, Entry>> it = entries.entrySet().iterator();
		while (bytes > budget && it.hasNext()) {
			bytes -= it.next().getValue().body.length;
			it.remove();
		}
	}
}
