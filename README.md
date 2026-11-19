# SteelExtractor — seed extraction service

This branch turns the extractor into a service. For any world seed, it generates every chunk
in a rectangle and writes two files:

- `chunk_stage_hashes.json`: MD5 hashes of each chunk's blocks after `TERRAIN` and `FEATURES`, plus its light data after `LIGHT`
- `biome_hashes.json`: an MD5 per chunk of its biomes, sampled at 4×4×4 resolution

The registry/fixture extractors (blocks, items, packets, …) live on `master`. They don't depend
on the seed, so run them there.

## Usage

```sh
scripts/extract.sh --seed 13579 [--seed 42 ...] --area minX,minZ,maxX,maxZ \
                   [--tile 10] [--dims minecraft:overworld,minecraft:the_nether] [--out output]
```

- `--area` is in **chunk** coordinates, inclusive. `-50,-50,49,49` covers 100×100 chunks.
- Each seed is a separate job, and jobs run sequentially. A job:
  1. deletes `run/world`
  2. sets `level-seed` in `run/server.properties`
  3. starts the headless server
  4. exits when extraction is done
- Output for each seed goes to `<out>/<seed>/steel-core/test_assets/` (plus `server.log`).
  Copy the files into Steel's `steel-core/test_assets/`.

The script just sets these env vars before `./gradlew runServer --args=nogui`, so you can also
set them yourself:

| Env var | Meaning | Default |
|---|---|---|
| `STEEL_EXTRACTOR_AREA` | `minX,minZ,maxX,maxZ`, chunk coords, inclusive | required |
| `STEEL_EXTRACTOR_TILE_SIZE` | Tile edge length in chunks | `10` |
| `STEEL_EXTRACTOR_DIMENSIONS` | Comma list of dimension ids | all three |
| `STEEL_EXTRACTOR_OUTPUT_DIRECTORY` | Output root | `steel_extractor_output` |
| `STEEL_EXTRACTOR_EXIT_ON_COMPLETE` | Stop the server when done | off |

## Tile semantics

The area is split into `tile × tile` tiles, processed in x-then-z order. Each tile goes through
these steps, and Steel's test harness must reproduce them to get the same hashes:

1. Generate the tile's chunks to `TERRAIN`. TERRAIN doesn't depend on neighbours, so it is
   hashed whenever a chunk inside the area reaches it, even if an earlier tile pulled it in.
2. Generate them to `FEATURES`, in x/z order. Once all of them are done, capture the feature
   hashes.
3. Generate `FEATURES` for the 2-chunk ring around the tile. Then generate the tile plus a
   1-chunk ring to `LIGHT`, wait for the light engine to go idle, and capture the light hashes.

**Adjacent tiles are not independent.** Step 3 runs features on chunks belonging to the next
tiles, and those features write into the current tile's edge chunks. So FEATURES and LIGHT
hashes reflect *this exact schedule*, applied over the whole area in tile order, and not an
isolated tile.

A single isolated tile (an area of exactly `tile × tile`) behaves exactly like one of the old
10×10 fixture clusters and gives byte-identical hashes.

Output is streamed one tile at a time, so memory stays flat. The JSON is compact. Chunks are
ordered tile by tile, so look them up by `x`/`z` rather than relying on their position in the
array.

## Cost

Runtime grows linearly with the chunk count, and the world's region files on disk grow with
it too (the world is deleted when the next job starts). Every completed tile logs elapsed time
and an ETA. Run a small area first to measure your throughput before starting a large one.
