# Horizon Server LODs

A server-side Fabric mod for Minecraft 26.3 that sends [Horizon](https://github.com/horizon-rust/horizon)
clients far terrain (LODs) from the chunks the server has already generated. Whatever generated
them, vanilla, a datapack like Continents or a world generation mod, the far terrain is the
server's own world, not a guess from the seed.

Players with the vanilla client, or with Horizon builds that don't support it, notice nothing
in game. Every player's F3 screen shows the server brand as `horizon` (see below).

## Install

1. Put `horizon-server-lods-<version>.jar` and Fabric API in the server's `mods/` folder.
2. Start the server. It writes `config/horizon-lod.properties`.
3. In Horizon, turn on LOD Distance (Video Settings). Far terrain fills in as tiles arrive.

Only chunks that exist are sent, so pregenerate the area you want visible (for example with
[Chunky](https://modrinth.com/plugin/chunky)). Chunks are read from disk, so changes show in far
terrain after the server saves them (autosave, or `save-all`).

## How it works

- **Horizon's own protocol.** Since 0.5 the mod speaks Horizon's built-in far-terrain protocol
  (`lod_net` version 1, the one Horizon's own dedicated server speaks), so a stock Horizon client
  draws the server's far terrain with no client mod. Horizon clients only identify themselves to
  a server whose brand is exactly `horizon`, so the mod sets the server brand to `horizon` (F3
  shows it to every player), and offers far terrain to the clients that answer as Horizon while
  they join.
- **Light on the server.** Tiles are read and summarized on low-priority threads (`threads`),
  straight from the region files with a read-only reader, not through the server's own chunk IO
  queue, so far terrain never waits behind (or holds up) the game's chunk loads and saves. Only
  chunks from an older Minecraft version (which need datafixing) go through the server's reader.
  Each player is held to a request rate (`tiles-per-second-per-player`, also sent to the client
  so it paces itself), a few tiles at once and a byte budget per tick. Players who choose
  Reduced far detail get corner samples, which cost less to make.
- **No disk storage by default.** Finished tiles are kept in memory (`reply-cache-mb`) until one
  of their chunks is saved again, so another player, or a rejoin, costs a few lookups.
  Nothing is written to the world. The 0.4 summary cache (`horizon-lod/` in each dimension's
  folder, about 8 KB per chunk) is still read and written with `summary-cache=true`; updating
  from 0.4 turns it off once, and those folders, and `<world>/horizon-lod/`, can be deleted
  (stop the server first).
- **Server tick time.** Clients that ask on `faux:tick_stats` get the server's MSPT and tick
  rate once a second (Fauxclient's MiniHUD shows them; `send-tick-stats`).

## Settings (`config/horizon-lod.properties`)

| Key | Default | |
|---|---|---|
| `distance-chunks` | 512 | Far terrain is sent up to this many chunks from the player (at most 1024) |
| `threads` | 2 | Threads reading and summarizing chunks |
| `tiles-in-flight-per-player` | 8 | Tiles in the works per player at once (sent to the client) |
| `tiles-per-second-per-player` | 32 | Tiles a player may ask for each second, on average (sent to the client; bursts of four times that) |
| `max-queued-per-player` | 2048 | Requests a player may have waiting |
| `bytes-per-tick-per-player` | 65536 | Tile bytes per player per tick, before network compression |
| `cached-chunks-per-level` | 2048 | Chunk summaries kept in memory per dimension |
| `reply-cache-mb` | 64 | Memory for finished tiles, shared by all players (0: off) |
| `summary-cache` | false | Keep chunk summaries on disk (`horizon-lod/` in each dimension's folder), as 0.4 did |
| `summarize-loaded-chunks-per-tick` | 4 | With `summary-cache`, chunks players load summarized per tick in the background (0: off) |
| `send-tick-stats` | true | Answer `faux:tick_stats` with the server's MSPT and tick rate once a second |

## Build

Needs JDK 25.

```sh
./gradlew build          # build/libs/horizon-server-lods-<version>.jar
./gradlew runServer      # a test server in run/ with the mod
```

The wire format is in [PROTOCOL.md](PROTOCOL.md). The client side of far terrain is Horizon
itself (`crates/horizon-client/src/lod_net.rs`); `faux:tick_stats` is read by Fauxclient's
`faux_minihud_extra` bundle.

## License

[MIT](LICENSE). The far-terrain column summaries and tile replies (`Columns.java`, `Tiles.java`,
`HorizonLod.java`, `LodPayload.java`) follow Horizon's MIT-licensed `lod.rs` and `lod_net.rs`, whose
notice (Ludovic Marie and the Horizon contributors) is kept in the licence and in those files. Unofficial:
not affiliated with Mojang, Microsoft or the Horizon project. Built against Minecraft and Fabric
API, neither of which is included.
