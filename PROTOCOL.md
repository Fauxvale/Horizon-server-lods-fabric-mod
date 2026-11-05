# Far terrain: Horizon's `horizon:lod`, version 1

Since 0.5 this mod speaks Horizon's own far-terrain protocol, as Horizon's dedicated server does
(Horizon `crates/horizon-client/src/lod_net.rs`, `lan/conn.rs`, `net.rs`). Numbers are
big-endian; VarInt as Minecraft's; strings are a VarInt byte length then UTF-8. The 0.4 protocol
(version 4: `HELLO`, `REQUEST`, `REQUEST_KEPT`, `TILE`, `WORLDGEN`) is gone.

## Handshake (configuration phase)

1. The client sends `minecraft:brand` `"vanilla"`.
2. The server sends `minecraft:brand` `"horizon"` (this mod sets the server brand).
3. A Horizon client, seeing exactly `"horizon"`, sends a second `minecraft:brand` `"horizon"`.
4. When the client answers `SelectKnownPacks`, a client whose last brand is `"horizon"` gets the
   **Offer**, a configuration-phase custom payload on `horizon:lod`:

   | Field | Type | |
   |---|---|---|
   | version | VarInt | 1 |
   | distance | VarInt | chunks from the player at most (`distance-chunks`, ≤ 1024) |
   | in_flight | VarInt | requests waiting at once at most (`tiles-in-flight-per-player`) |
   | per_second | VarInt | tiles a second at most (`tiles-per-second-per-player`) |

## Request (play phase, client to server, `horizon:lod`, no type byte)

| Field | Type | |
|---|---|---|
| id | VarInt | the client's, echoed in the reply |
| x, z | i32, i32 | the tile's corner, in blocks |
| stride | VarInt | blocks between columns: a power of two |
| approximate | bool | the player chose Reduced far detail |

A tile is 16 × 16 columns from (x, z), one every `stride` blocks. It is served when the stride is
a power of two no larger than `distance` rounded up to a power of two (at most 1024), x and z are
multiples of 16 × stride, and the tile's nearest point is within (distance + 8) × 16 blocks of the
player. Requests past the player's rate (a token bucket of `per_second`, bursts of 4 ×) or queue
are refused. Nobody is disconnected for a bad request; it is ignored.

## Reply (play phase, server to client, `horizon:lod`)

```text
VarInt id
u8 status                     0: a tile, 1: refused (the reply ends here)
VarInt biomes (≤ 256), then each biome's name (String, e.g. "minecraft:plains")
VarInt 256, then 256 columns, z outer, x inner:
  bool surface
    [i32 height, VarInt state, bool water [i32 height, VarInt state], VarInt biome]
  VarInt spans, then from the bottom up:
    i16 min, i16 max (exclusive), VarInt state, VarInt biome, u16 light
```

Biomes are indices into the reply's own name list; block states are network ids (the ids chunk
packets use); light has sky light in bits 4 to 7 and block light (or the block's emission) in
bits 12 to 15. Gaps between spans are air. Spans go up within −2048..4096, a tile holds at most
16384 spans and a reply at most 512 KiB: a tile past that is refused.

The surface is the top non-fluid span's top face, with the top fluid span above it if any (lod.rs
`Column::from_chunk`); a column of only fluid has an air floor at the level's bottom.

Columns are Horizon's far-terrain columns (`lod.rs`, `Column::from_chunk`): runs of blocks that
draw as solid, leaves and fluids. For a stride up to 16 (and not approximate), each column is the
stride × stride footprint reduced to one (`Column::reduced`: at each height the most common
material where at least half is occupied); otherwise, the column at the sample point. Ungenerated
chunks give empty columns; the server never generates one.

# `faux:tick_stats` (play phase)

Client to server: one byte, 1 to get the server's tick stats once a second, 0 to stop
(ignored with `send-tick-stats=false`). Server to client, every 20 ticks:

| Field | Type | |
|---|---|---|
| mspt | f32 | the average tick time of the last 100 ticks, as `/tick query` gives it |
| rate | f32 | the target tick rate |
| sprinting | u8 | 1 while `/tick sprint` runs |
| frozen | u8 | 1 while `/tick freeze` holds |
