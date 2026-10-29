# The `horizon:lod` channel, version 4

One custom payload channel, both directions, in the play phase. Every message starts with a
message type byte. Numbers are Minecraft's: VarInt, big-endian `i16`/`u16`/`f32`, strings as a VarInt
byte length then UTF-8.

## Client to server

| Type | Name | Fields |
|---|---|---|
| 0 | `HELLO` | VarInt version (4; a version 2 or 3 client gets the same `HELLO` with its version, a version 1 client version 1's and no `WORLDGEN`) |
| 1 | `REQUEST` | string level (e.g. `minecraft:overworld`), VarInt count (at most 1024), then per tile: VarInt id, VarInt x, VarInt z, VarInt stride |

From version 3, type 2 `REQUEST_KEPT` has `REQUEST`'s fields: those tiles are answered from the
server's summary cache only (chunks players have loaded), never reading the world, with status 3
or 4.

From version 4, type 3 `TICKS` is one byte: 1 to get `TICK_STATS` once a second, 0 to stop. A
server with `send-tick-stats=false` ignores it.

The client also sends `minecraft:register` with `horizon:lod`, so Fabric knows it reads the channel.
A server without the mod ignores both.

A tile is 16 x 16 columns, starting at block x, z, one column every `stride` blocks (a power of two).
Stride 1 at a chunk's corner is that whole chunk.

## Server to client

| Type | Name | Fields |
|---|---|---|
| 0 | `HELLO` | VarInt version, VarInt distance (chunks the server sends far terrain to). From version 2: string world id, byte seed flag, then `i64` seed when the flag is 1, then VarInt count of (string level, string noise settings) for the levels with a noise generator |
| 1 | `TILE` | VarInt id, status byte, then for status 0 the 256 columns, x fastest |
| 2 | `WORLDGEN` | VarInt count, then per entry: string bundle (`noises` or `densities`), string id, string JSON (the entry as a datapack holds it) |
| 3 | `WORLDGEN_END` | none: the last message after `HELLO`, after the `WORLDGEN` messages |
| 4 | `TICK_STATS` | From version 4, every 20 ticks to players who sent `TICKS` 1: `f32` MSPT (the average tick time of the last 100 ticks, as `/tick query` gives it), `f32` tick rate, byte sprinting, byte frozen |

After `HELLO`, the server sends every entry of its `worldgen/noise` and `worldgen/density_function`
registries in `WORLDGEN` messages of about 256 KB, then `WORLDGEN_END`. With `send-seed=false`,
the seed flag is 0 and only `WORLDGEN_END` follows. The world id is a UUID kept in the world's
`horizon-lod/world-id`, so clients keep tiles per world.

For `REQUEST_KEPT`: status 3, 32 bytes of mask (bit `i % 8` of byte `i / 8`: column `i` is the
summary cache's), then the 256 columns (the others have no spans); status 4, the cache has none of
them. The client keeps its seed terrain where the mask is clear.

Status: 0 ok, 1 none of the tile's chunks is generated, 2 refused (another level than the
player's, past the distance, a bad stride, or the queue is full).

A column is a VarInt span count, then spans from the bottom up: `i16` min y, `i16` max y
(exclusive), VarInt block state (the network id chunk packets use), VarInt biome (the server's
biome registry id), `u16` light (sky light in bits 4 to 7, block light or the block's emission in
bits 12 to 15). Gaps between spans are air.

Columns are Horizon's far-terrain columns (`lod.rs`, `Column::from_chunk`): runs of blocks that
draw as solid, leaves and fluids. For a stride up to 16, each column is the stride x stride
footprint reduced to one (`Column::reduced`: at each height the most common material where at
least half is occupied); for a wider stride, the column at the sample point.

Every request is answered once, in order. The server reads chunks as its own loading does (from
disk or saves in progress, datafixed), never generates one, and sends a byte budget per player
per tick.
