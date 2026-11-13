# Sprite-heavy multiplayer client capture

Analyzed 2026-11-12. Input: `apocbr-client-telemetry.ndjson` in this directory,
schema 1, sequences 1-14, target 42.22.2. User describes login into a scene with
many sprites and few zombies. Scene density, camera movement, zoom, weather and
mod list are not recorded by this schema. Metric boundaries are defined in the
[telemetry guide](../features/2026-11-12-client-main-thread-telemetry.md).

Historical input SHA-256:
`443c41343c92012eccc930fa98c43504c5ba9bb87673729af0f5f9e4b2d15d9e`.
The user later replaced that filename with the schema-2 run analyzed
[separately](2026-11-12-schema-2-capture-analysis.md); these findings refer to the
original schema-1 records, not the current file contents.

## Findings

The capture demonstrates substantial main-thread wall-time stalls with a small
loaded zombie population. It identifies both persistent rendering-related cost
and intermittent update/asset/network spikes. It does not identify the exact
sprite, callback, subsystem, lock, GPU operation or allocation responsible.

- 67.444 seconds across 14 reporting windows; 2,741 measured frames.
- 24.374 ms mean frame work; 505.795 ms maximum.
- 91 frames at/above the 50 ms threshold (3.32%); histogram shows 25 above 100 ms
  and four above 250 ms. 75.12% exceed the 16.666667 ms histogram boundary.
- Combined histogram p95 upper bound is 50 ms; p99 upper bound is 100 ms.
  These are bucket bounds, not precise percentile estimates.
- 69 retained worst-frame records; 22 slow frames are omitted because the first
  two windows exceeded the 16-sample retention limit. Aggregate histograms and
  threshold counts still include every frame.
- All sequences contiguous; droppedWindows=0 and writeErrors=0. Writer sample
  lag is at most 6 ms in these records.
- Window-end loaded zombie counts range 24-33; moving-object counts 96-115.
  Neither is a visible-sprite or draw-call count.

Frame work sums to 66.809 seconds, 99.06% of reporting-window duration. Cadence
sums to 66.837 seconds across 2,740 samples. Most elapsed time is therefore inside
the measured frame boundary rather than unmeasured pacing/invoke-queue gaps in
this run. This is wall time: CPU execution, GC, waits and descheduling are not
separated. Count/elapsed gives 40.64 main-thread frames/s, not display-present FPS.

## Where frame time goes

| Phase | Mean ms/frame | Share of frame work | Maximum ms |
| --- | ---: | ---: | ---: |
| RENDER_PREPARE | 11.656 | 47.82% | 130.921 |
| LOGIC, inclusive | 11.194 | 45.93% | 490.864 |
| LIGHTING | 1.494 | 6.13% | 71.329 |
| STATE_UPDATE, inside LOGIC | 9.179 | 37.66% | 486.404 |
| UI_UPDATE, inside LOGIC | 0.950 | 3.90% | 11.948 |
| NETWORK, inside LOGIC | 0.369 | 1.51% | 144.180 |
| FILE_TRANSACTIONS, inside LOGIC | 0.293 | 1.20% | 252.532 |

Do not add the LOGIC child rows to LOGIC. The remaining outer frame phases are
small. Small FINISH_ANIMATION does not clear all animation work: animation can
execute inside gameplay/render preparation. Small QUEUED_LUA does not clear Lua:
OnTick and other synchronous Lua callbacks execute inside other measured phases.

## Login period versus later frames

| Segment | Frames | Mean work ms | Frames/s by elapsed time | Slow frames >=50 ms |
| --- | ---: | ---: | ---: | ---: |
| First two windows, 10.073 s | 175 | 57.455 | 17.37 | 54 |
| Remaining 57.371 s | 2,566 | 22.118 | 44.73 | 37 |
| Final five windows, 22.265 s | 1,190 | 18.200 | 53.45 | 2 |

RENDER_PREPARE averages 19.824 ms in the first segment, 11.099 ms after that and
8.662 ms in the final segment. STATE_UPDATE falls from 23.045 to 8.233 and then
7.409 ms. FILE_TRANSACTIONS averages only 0.025 ms after the first ten seconds;
NETWORK averages 0.160 ms in that later period. Login/asset warm-up is consistent
with the data, but camera/scene changes and GC can also contribute to improvement.
There is no controlled stationary comparison in the file.

## Specific measured stalls

| Frame | Total work ms | Main measured contributor(s) |
| --- | ---: | --- |
| 18 | 505.795 | STATE_UPDATE 486.404 ms |
| 26 | 330.063 | FILE_TRANSACTIONS 252.532 ms |
| 135 | 299.752 | STATE_UPDATE 282.630 ms |
| 25 | 293.857 | FILE_TRANSACTIONS 141.691 + NETWORK 113.106 ms |
| 9 | 200.080 | NETWORK 144.180 ms |
| 167 | 159.632 | RENDER_PREPARE 130.921 ms |
| 22 | 132.401 | LIGHTING 71.329 + RENDER_PREPARE 40.397 ms |
| 1585 | 128.121 | STATE_UPDATE 117.734 ms, well after initial login |

This is not one exclusively rendering-related stutter. Means also hide important
intermittent sources: asset-result handling has only a 1.20% total share but
contains a quarter-second stall.

## Source interpretation and next measurement boundaries

1. **Split RENDER_PREPARE first.** GameWindow.renderInternal includes lighting
   pre-update, object picking and states.render. IngameState.renderInternal
   includes per-player scene rendering, offscreen composition, text/UI rendering
   and Core.EndFrameUI. The latter calls RenderThread.Ready, which reaches
   SpriteRenderer.pushFrameDown and waitForReadySlotToOpen. Consequently this
   metric includes possible producer/render-thread backpressure. Separate scene
   preparation/chunk baking, text/UI, and ready-slot waiting before attributing
   its total to sprite traversal, draw calls or GPU time.
2. **Split STATE_UPDATE.** Time IsoWorld.update, chunk handoff/integration,
   cell/moving-object work, entity managers, synchronous Lua events, model update
   and remaining managers. The 486/283/118 ms spikes cannot be assigned to zombie
   AI from this capture. A small population does not exclude expensive per-object
   behavior, mod callbacks or global pauses.
3. **Instrument file completions by task type.** Exact-version
   FileSystemImpl.updateAsyncTransactions checks completed futures, invokes
   task.handleResult and task.done on the calling thread, spins on an admission
   lock, inserts pending jobs by priority and submits work. Its time is not a
   measurement of worker disk-read duration. Record task type/completion count,
   callback time, lock wait and queue depths to identify the 252 ms completion
   spike. Do not assume PNG decoding itself occurred on main.
4. **Add GC collector names and phase/CPU evidence.** Current schema sums all
   GarbageCollectorMXBeans, including concurrent-cycle accounting where exposed.
   It reports 57 collections and 10,779 ms of collection-time deltas after the
   first sample; windows 2 and 3 report 4,720 and 5,084 ms. These are NOT established
   stop-the-world pause totals. Add per-collector measurements and correlate GC
   logs/JFR pause events before assigning frame stalls to collection.
5. **Add rendering workload context.** Visible tiles/sprites, dirty/rebaked chunk
   levels, render submissions, zoom/resolution and render-thread durations will
   make the user's sprite-heavy scenario testable. These counts are absent now.

## Memory pressure signal

Writer JVM samples show a 3,072 MiB heap limit, with 2,848-2,960 MiB used
(92.71-96.35%). This makes allocation/GC behavior a material investigation target.
It does not prove a leak, an undersized heap, or a specific collector problem:
live-set size, allocation rate, collector names and pause events are absent.
The writer samples are close to each window end but not synchronized to each
individual stalled frame. No JVM-setting change is justified as a measured fix
from this file alone.

## Assessment

The first capture already supports targeted instrumentation: rendering/preparation
or render handoff is the largest aggregate phase; gameplay update causes the
largest individual stalls; asset callbacks and network application contribute
large login-period spikes. Broad zombie multithreading is not supported as the
first response to this scene. No optimization or runtime setting was changed as
part of this analysis.
