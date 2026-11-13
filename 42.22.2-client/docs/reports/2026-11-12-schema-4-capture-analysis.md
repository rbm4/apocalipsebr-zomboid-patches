# Schema 4 client capture analysis

Implemented follow-up: [deferred textures, asynchronous translucent ordering and building lookups](../features/2026-11-12-client-deferred-textures-and-async-translucency.md).

Measured 2026-11-12. Owning guide: [texture workers and client probes](../features/2026-11-12-texture-workers-and-client-probes.md).

Input: `apocbr-client-telemetry.ndjson`, SHA-256
`b9d9ab59098e0f298fab4d2ebf33932c628765e351bd679b8c017d0230864598`.
This input path is reused; the findings apply only to this hash.

## Capture

Eleven schema 4 windows, 2,686 frames, 55.070 seconds of reporting intervals.
Average frame work 20.187 ms, maximum 382.106 ms, 76 frames at least 50 ms.
No dropped windows or write errors. Timings are main-thread wall time, not GPU
execution time; parent/child phases overlap and must not be added together.

The first two windows contain 182 frames averaging 55.060 ms and 70 slow frames.
The remaining 2,504 frames average 17.653 ms with six slow frames. The last five
windows average 16.146 ms, with two slow frames. Initial bursts dominate the
slow-frame count, but intermittent stalls persist.

## Texture worker: functioning, partial coverage

Worker counters show 86 preprocessed images, 37 fallbacks, 8.732 seconds of
aggregate worker work, and zero failures/cancelled results/settings reloads.
The first worker sample includes pre-entry work: these counts cannot establish
a matched offload percentage or seconds saved from measured frames. Zero
counters do not establish gameplay cancellation/reload correctness.

The main thread performed 691 size-limit calls but only six actual resizes,
spending 232.592 ms in size limiting. Frame 28 still spends 150.775 ms there
(150.332 ms mip selection), producing a 182.796 ms frame. The largest individual
resize is 44.110 ms, 1024x1024 to 256x256.

The previous schema 3 capture had 19 resizes and 695.618 ms in size limiting.
This is consistent with useful offloading, but the captures are not a controlled
same-route/load comparison and cannot establish a causal improvement percentage.

Source: `ApocBRTexturePreprocessor.prepare` falls back for missing data/APNG,
oversized extra-memory estimates, occupied worker slots or unavailable memory
permits. FALLBACK does not distinguish those causes. Record reason, dimensions
and admission state before changing limits. A bounded deferred worker queue is
a possible later solution for eligible images; it needs explicit ownership,
cancellation, memory accounting and completion-order validation.

## Largest stall: building reconciliation

Frame 280: 382.106 ms wall, 375 ms sampled CPU. Nested region measurements:

| Boundary | Time |
| --- | ---: |
| World regions | 362.564 ms |
| Region swap scope | 362.537 ms |
| Reset references | 7.012 ms |
| Process buildings | **355.518 ms** |

The culprit is now localized to `DataRoot.clientProcessBuildings`, delegating
to `WorldRegionToMetaGrid.clientProcessBuildings`. It removes/recreates buildings
around changed cells, combines levels, inserts into the meta grid, marks chunks
dirty and updates lighting/squares. Moving the entire method to a worker would
cross live-state ownership boundaries.

Next split removal, building construction, overlap checks, level combination,
meta-grid insertion and square/lighting invalidation, with changed-cell,
building and region counts. REGION_SWAP counts the enclosing check scope, not
actual swaps; there are two building-processing calls in this capture.

## Sustained cost: translucent-object rendering

| Phase | Mean per frame | Share of frame work |
| --- | ---: | ---: |
| Scene rendering | 6.521 ms | 32.3% |
| World cell rendering | 6.091 ms | 30.2% |
| Tile drawing | 5.178 ms | 25.7% |
| Translucent objects | **3.472 ms** | **17.2%** |
| Chunk-level rebaking | 0.611 ms | 3.0% |

Translucent objects account for about 57% of cell rendering; maximum invocation
43.825 ms. This is a larger sustained target than dirty-chunk rebaking.

Source: `FBORenderCell.renderOneLevel_Translucent` merges cached item/object/
cutaway square lists using repeated `ArrayList.contains`, sorts them, checks
visibility, then renders objects/items/outlines. Linear duplicate checks are a
concrete source candidate, not a proven bottleneck. Measure merge/deduplication,
sorting, visibility and rendering separately, with candidate/unique square and
object counts. Any caching must preserve changing cutaways and draw order.
Live traversal/render submission is not directly worker-safe.

## Other stalls

- Frame 37: 336.118 ms total, NETWORK 244.884 ms. Packet counts/types and handler
  timings are still needed to separate heavy individual work from catch-up load.
- Frame 75: 163.843 ms total, CELL_ITEMS 88.688 ms and render-context wait
  87.190 ms in the same frame. This suggests a synchronous rendering/resource
  dependency; current probes do not prove which item operation issued the wait.
- Frame 1827: 82.801 ms total, OnTick 67.418 ms, specifically
  `BirdSoundSystem.lua:onTick:690` at 66.796 ms. The matching installed source is
  `3822608465/mods/Restless-Waters/common/media/lua/client/Birds/BirdSoundSystem.lua`
  in the local game's workshop directory; its declaration matches line 690.
  Five other observations range 5.165–10.964 ms. Sampled main-thread CPU for the
  long frame is zero: callback wall time does not prove equivalent Lua computing
  time, and scheduling/native waits remain possible. Its sequence/emitter updates
  execute before its game-minute early return. Separate these from biome/zone
  processing. `Main.lua:onTick:177` also reaches 18.932 ms, but basename alone
  does not identify its originating mod.

ZGC pause collectors report only 1 ms aggregate; concurrent cycle times are much
larger and are not stop-the-world duration. GC/scheduler contention cannot be
excluded, but the data do not explain the CPU-active region spike as a GC pause.

## Next priorities

1. Split translucent-list preparation and drawing, and count workload; inspect
   repeated membership checks as a possible recurring cost.
2. Split region building reconciliation and count changed cells/regions/buildings
   before choosing detached planning or owner-thread budgeting.
3. Classify texture fallbacks and correlate with main-thread resizes before
   adjusting admission or designing bounded deferred preprocessing.

Also isolate Restless Waters callback substeps and attribute network/item waits
when addressing those stalls. This investigation changes documentation only.
