# Horizon Server LODs

A server-side Fabric mod for Minecraft 26.3. It lets [Horizon](https://github.com/horizon-rust/horizon)
players see far terrain (LODs) well past their render distance.

The far terrain comes from chunks your server has already generated, so it matches your real
world. It works no matter what generated the world: vanilla, a datapack like Continents, or a
world generation mod.

Players on the vanilla client, or on Horizon builds without far terrain, won't notice anything.

## Install

1. Put `horizon-server-lods-<version>.jar` and Fabric API in the server's `mods/` folder.
2. Start the server. It creates `config/horizon-lod.properties`.
3. In Horizon, go to **Video Settings** and turn on **LOD Distance**. Far terrain fills in as it
   arrives.

**Good to know:**

- Only chunks that already exist are sent. Pregenerate the area you want visible, for example
  with [Chunky](https://modrinth.com/plugin/chunky).
- Far terrain is read from the saved world. Builds and other changes show up after the server
  saves (autosave, or `/save-all`).
- The F3 screen shows the server brand as `horizon` for every player. That's expected (see below).

## How it works

**No client mod needed.** The mod speaks Horizon's built-in far-terrain protocol (`lod_net`
version 1, the same one Horizon's own server uses). A stock Horizon client draws the far terrain
on its own.

**Why the brand says `horizon`.** Horizon clients only identify themselves to a server whose
brand is exactly `horizon`. The mod sets that brand so it can find the Horizon players as they
join.

**Light on the server.**
- Chunks are read on low-priority background threads, straight from the region files, so far
  terrain never slows down normal chunk loading or saving.
- Each player has a limit on requests per second, tiles at once, and bytes per tick.
- Players who pick **Reduced** far detail get cheaper, lower-detail tiles.

**Nothing written to disk.** Finished tiles are kept in memory and reused until one of their
chunks changes, so a second player or a rejoin costs almost nothing.

**Updating from 0.4?** 0.4 kept a cache on disk (a `horizon-lod/` folder in each dimension's
folder, about 8 KB per chunk). Updating turns it off. Stop the server, then delete those folders.
To keep using it instead, set `summary-cache=true`.

**Tick stats (optional).** Clients that ask on `faux:tick_stats` get the server's MSPT and tick
rate once a second. Fauxclient's MiniHUD shows these.

## Settings

All settings live in `config/horizon-lod.properties`. The defaults suit most servers.

| Setting | Default | What it does |
|---|---|---|
| `distance-chunks` | 512 | How far far terrain reaches, in chunks (max 1024) |
| `threads` | 2 | Background threads that read chunks |
| `tiles-in-flight-per-player` | 8 | Tiles being prepared for one player at once |
| `tiles-per-second-per-player` | 32 | Average tiles a player can request per second (short bursts up to 4×) |
| `max-queued-per-player` | 2048 | Requests a player can have waiting |
| `bytes-per-tick-per-player` | 65536 | Data sent to one player per tick, before compression |
| `cached-chunks-per-level` | 2048 | Chunk summaries kept in memory, per dimension |
| `reply-cache-mb` | 64 | Memory for finished tiles, shared by all players (0 = off) |
| `summary-cache` | false | Keep the 0.4 disk cache in each dimension's `horizon-lod/` folder |
| `summarize-loaded-chunks-per-tick` | 4 | With `summary-cache` on: chunks to cache per tick as players load them (0 = off) |
| `send-tick-stats` | true | Send MSPT and tick rate to clients that ask |

## Building from source

Needs JDK 25.

```sh
./gradlew build          # output: build/libs/horizon-server-lods-<version>.jar
./gradlew runServer      # starts a test server in run/ with the mod
```

The network format is documented in [PROTOCOL.md](PROTOCOL.md). On the client side, far terrain
is handled by Horizon itself (`crates/horizon-client/src/lod_net.rs`), and `faux:tick_stats` by
Fauxclient's `faux_minihud_extra` bundle.

## License

[MIT](LICENSE).

`Columns.java`, `Tiles.java`, `HorizonLod.java` and `LodPayload.java` are based on Horizon's
MIT-licensed `lod.rs` and `lod_net.rs`. Horizon's notice (Ludovic Marie and the Horizon
contributors) is kept in the licence and in those files.

This is an unofficial mod, not affiliated with Mojang, Microsoft or the Horizon project. It is
built against Minecraft and Fabric API, but includes neither.
