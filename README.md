# Horizon Server LODs

A server-side Fabric mod for Minecraft 26.3 that sends [Horizon](https://github.com/horizon-rust/horizon)
clients far terrain (LODs) from the chunks the server has already generated. Whatever generated
them, vanilla, a datapack like Continents or a world generation mod, the far terrain is the
server's own world, not a guess from the seed.

Players with the vanilla client, or with Horizon builds that don't support it, notice nothing.

## Install

1. Put `horizon-server-lods-<version>.jar` and Fabric API in the server's `mods/` folder.
2. Start the server. It writes `config/horizon-lod.properties`.
3. In Horizon, turn on LOD Distance (Video Settings). Far terrain fills in as tiles arrive.

Only chunks that exist are sent, so pregenerate the area you want visible (for example with
[Chunky](https://modrinth.com/plugin/chunky)). Chunks are read from disk, so changes show in far
terrain after the server saves them (autosave, or `save-all`).

## Fast far terrain

- **Seed terrain first.** The server sends Horizon its seed and its noise and density-function
  JSON, so worldgen datapacks like Continents are included. Horizon draws far terrain from the
  seed straight away. Then, for each tile, it asks the server's summary cache only (no world
  reads), and the columns of chunks players have loaded replace the seed's: builds and the real
  terrain show there. The server's full tiles are only asked for where the seed can't draw:
  `send-seed=false`, worldgen written in Java code, or a dimension without a vanilla noise preset.
  Anyone with a Horizon client can read the seed; set `send-seed=false` to keep it private.
- **Summary cache.** Each chunk's far-terrain summary is kept in the level's folder,
  `dimensions/<namespace>/<level>/horizon-lod/`. Chunks players load are summarized in the
  background, a few per tick (`summarize-loaded-chunks-per-tick`), and again after they unload
  and are saved, so builds reach players who come later. A summary is made again whenever the
  world saves its chunk again (the region file's timestamp changes). It takes about 8 KB per
  chunk (about 7.5 GB for a 1000 × 1000-chunk area). Deleting the folder is safe (stop the server
  first); it fills again as players play.
- **On the player's side,** Horizon keeps the tiles it gets in
  `server-lod/<server address>/<world id>/<level>/` in its game folder, and uses them on the next
  visit without asking. Chunks the player loads replace their own tiles. Deleting that folder
  clears it. The world id is in `<world>/horizon-lod/world-id`; a new world gets a new one.

## Settings (`config/horizon-lod.properties`)

| Key | Default | |
|---|---|---|
| `distance-chunks` | 512 | Far terrain is sent up to this many chunks from the player |
| `threads` | 2 | Threads reading and summarizing chunks |
| `tiles-in-flight-per-player` | 8 | Tiles in the works per player at once |
| `max-queued-per-player` | 2048 | Requests a player may have waiting |
| `bytes-per-tick-per-player` | 65536 | Tile bytes per player per tick, before network compression (about a seventh after it) |
| `cached-chunks-per-level` | 2048 | Chunk summaries kept in memory per dimension |
| `summary-cache` | true | Keep chunk summaries on disk (`horizon-lod/` in each dimension's folder) |
| `send-seed` | true | Send Horizon clients the seed and worldgen JSON; they draw far terrain from it, with the summary cache's columns over it |
| `summarize-loaded-chunks-per-tick` | 4 | Chunks players load summarized into the summary cache per tick, in the background (0: off) |
| `send-tick-stats` | true | Horizon clients that ask get the server's MSPT and tick rate once a second (Fauxclient's MiniHUD shows them) |

## Build

Needs JDK 25.

```sh
./gradlew build          # build/libs/horizon-server-lods-<version>.jar
./gradlew runServer      # a test server in run/ with the mod
```

The channel's format is in [PROTOCOL.md](PROTOCOL.md). The client side is Fauxclient's
`faux-remote-lod` bundle (from horizon-remote-lod's `crates/horizon-client/src/server_lod.rs`).

## License

[MIT](LICENSE). The far-terrain column summaries (`Columns.java`) follow Horizon's `lod.rs`,
whose MIT notice (Ludovic Marie and the Horizon contributors) is kept in the licence. Unofficial:
not affiliated with Mojang, Microsoft or the Horizon project. Built against Minecraft and Fabric
API, neither of which is included.
