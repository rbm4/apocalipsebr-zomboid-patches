# Schema 3 capture: confirmed texture preprocessing, regions and cell rendering

Analyzed 2026-11-12. Input apocbr-client-telemetry.ndjson now contains only schema
3, windows 1-13. SHA-256:
`9f586c3cde98e755d4e33ad6b3a806880f34c74fd0d42ce57a7f00777416c606`.
Capture approximately 17:41:57-17:42:59 America/Fortaleza. This replaces the input
filename used by the historical schema-2 run; it is not the same session.
See [probe definitions](../features/2026-11-12-client-main-thread-telemetry.md),
[schema 2 analysis](2026-11-12-schema-2-capture-analysis.md) and the
[worker-offload assessment](2026-11-12-client-probe-schema-3-texture-offload.md).

## Conclusions supported by the capture

1. **CPU image preprocessing is a confirmed main-thread loading-hitch target.**
   19 scaled images correspond to ~696 ms in limitMaxSize. Almost all that time
   lies in the mip-selection/preprocessing path, not allocation, copying, asset
   notification or enqueueing the GPU upload.
2. **The large unmeasured world-update spike is now localized to IsoRegions.**
   Two initial frames spend 446.478 and 352.089 ms in WORLD_REGIONS. Other newly
   measured emitter/hutch/fog/helicopter/init phases are small in this capture.
3. **Persistent scene cost is primarily cell rendering.** WORLD_RENDER_CELL
   averages 11.867 ms/frame and accounts for 95.83% of SCENE_WORLD_RENDER.
4. **A separate OnTick stall exists later.** Frame 945 spends 299.603 ms inside
   UPDATE_LUA_TICK, with only 46.875 ms CPU across the entire 324.294-ms frame.
   This requires per-callback or stack evidence; it is not identified as a
   particular mod or necessarily CPU-intensive Lua execution.
5. **Synchronous render-context waits do happen.** One invocation reaches
   120.138 ms. This is distinct from ready-slot handoff waiting, which remains
   small in aggregate. The current data do not identify the waiting caller.

## Capture overview

- 62.709 seconds, 2,043 frames, mean frame work 30.369 ms, maximum 532.249 ms.
- 135 frames >=50 ms (6.61%); histogram has 28 frames above 100 ms and eight
  above 250 ms. Combined p95 upper bound 100 ms; p99 upper bound 250 ms.
- 83 retained worst-frame records; 52 slow frames omitted by per-window limits.
- Frame wall time 62.043 s; reported main-thread CPU 60.219 s (~97.06%). CPU
  measurements remain coarse and are unsuitable for tiny per-frame differences.
- No dropped windows or writer errors. Largest writer-sample lag 25 ms.
- All window-end render contexts: 1920x1080, primary zoom 2.0. Loaded zombie
  counts at window ends range 20-28. These are not visible-sprite counts.

First two windows average 98.203 ms/frame (102 frames, 77 slow); the remaining
52.680 seconds average 26.804 ms/frame (1,941 frames, 58 slow). All 19 observed
resizes happen in those first two windows. No scaled images appear afterward,
despite later stalls and a sustained rendering cost. This is a loading-period
optimization opportunity, not a measured explanation of every ongoing stutter.

## Texture result: the CPU half is now identified

| Measurement | Calls | Total ms | Maximum per call ms |
| --- | ---: | ---: | ---: |
| All file handleResult | 834 | 710.729 | 56.485 |
| Texture setImageData | 621 | 700.187 | 56.478 |
| Texture limitMaxSize | 621 | 695.618 | 56.407 |
| Mip selection/preprocessing branch | 19 | 693.348 | 56.324 |
| Selected-buffer allocation | 19 | 0.820 | 0.098 |
| Selected-buffer copying | 19 | 0.485 | 0.065 |
| Source-image disposal | 19 | 0.286 | 0.034 |
| Upload enqueue | 621 | 0.646 | 0.090 |
| Asset success notification | 621 | 1.357 | 0.196 |

limitMaxSize accounts for 97.87% of total file-result time. Its mip-selection
branch accounts for 99.67% of the limitMaxSize total. There are 621 limit calls
but only 19 actual resizes; the no-resize path is cheap. Image-data file-task
callbacks total 702.489 ms, versus 6.229 ms mesh callbacks.

The nested ImageData generation probe reports 396.239 ms and premultiplication
396.020 ms. These overlap: generation premultiplies child mip levels and the
caller also preprocesses the base level. Do not add both totals to the enclosing
693.348 ms. This identifies CPU image/mipmap/alpha work, not the asynchronous
PNG decode or GPU texture upload.

Largest retained image: 1024x1024 -> 256x256, effective maxSize=256, flags=132,
56.408 ms in the detached texture context (tiny finalizer overhead differs from
the phase's 56.407 ms). A retained 512x512 -> 256x256 image takes 19.985 ms.

Frame 26 spends 255.451 ms overall: TEXTURE_LIMIT_SIZE 194.091 ms and
TEXTURE_MIP_SELECT 193.590 ms. Largest individual resize is 55.708 ms, so several
resizes combine in that frame. Frame 27 similarly spends 119.798 ms in scaling.

This is enough evidence to prototype the CPU preprocessing offload described in
the assessment: prepare an exclusively owned image on the existing file worker,
then publish a digestible result to main. Preserve the exact pixel transformation,
settings/load identity validation, cancellation disposal and bounded memory.
GPU creation stays on the render context. The measured scaling slice is a target
for moving out of the main frame, not a guaranteed reduction in total processing
time or a prediction of steady-state FPS improvement.

## Regions: missing update cost identified

| Frame | Total work ms | WORLD_REGIONS ms | UPDATE_WORLD ms |
| --- | ---: | ---: | ---: |
| 16 | 532.249 | 446.478 | 456.899 |
| 33 | 469.670 | 352.089 | 384.026 |

WORLD_REGIONS totals 804.555 ms, overwhelmingly concentrated in these two
initial frames; after ten seconds it averages ~0.003 ms/frame. The source path
is IsoRegions.update, which can swap the worker DataRoot, reset cached region
references across loaded chunk squares, process changed buildings, update its
worker bridge and update logging. This is region/building topology processing,
not merely the Lua/mod concept of geographic regions.

Source tracing identifies candidates but does not prove which sub-operation took
446 ms. Separate cached-reference invalidation, clientProcessBuildings, buffer
swap and worker/logger housekeeping next. Frame 16 is CPU-heavy (531.250 ms CPU
versus 532.249 ms wall, subject to coarse CPU-counter resolution).

## Cell rendering: next sustained optimization investigation

| Phase | Mean ms/frame | Share of frame wall | Maximum ms |
| --- | ---: | ---: | ---: |
| RENDER_PREPARE | 15.882 | 52.30% | 269.696 |
| SCENE_WORLD_RENDER | 12.384 | 40.78% | 244.641 |
| WORLD_RENDER_CELL | 11.867 | 39.08% | 243.326 |
| RENDER_UI | 2.800 | 9.22% | 17.324 |
| WORLD_RENDER_WEATHER | 0.334 | 1.10% | 30.705 |
| SCENE_LUA_POST_RENDER | 0.026 | 0.08% | 0.217 |
| SCENE_END_FRAME | 0.002 | 0.01% | 0.090 |

Rows overlap. The earlier ambiguity between world rendering and OnPostRender/
EndFrame is resolved in this run: cell rendering dominates that scene branch.
IsoCell.render selects FBORenderCell.renderInternal when fboRenderChunk is enabled,
otherwise the legacy path. The capture does not record that selector, so the next
probes should expose active renderer, chunk rebakes/dirty reasons, static tile
preparation, dynamic characters/objects and relevant waits. It still does not
prove raw sprite count or GPU draw cost is the specific cause.

After ten seconds WORLD_RENDER_CELL still averages 10.991 ms/frame despite no
resizing. Frame 32 records 243.326 ms cell rendering and 120.285 ms cumulative
synchronous render-context wait (one call's maximum 120.138 ms). Those timings
are recorded across the frame, not as a caller stack; do not subtract all wait
time from cell rendering without establishing where the invocation occurred.
Ready-slot waits total only 20.652 ms across the entire capture, versus 289.364 ms
in synchronous render-context waits. They are separate mechanisms.

## Independent late Lua stall

Frame 945, window 8, has 324.294 ms frame work, UPDATE_LUA_TICK 299.603 ms,
UPDATE_WORLD 5.859 ms, and no file callbacks or scaling. Entire-frame CPU is
46.875 ms. This is consistent with substantial off-CPU time during the event,
but does not identify whether a callback waited on I/O, locks, native code or
something else. Per-callback timing/identity and stacks are needed. Another
window's OnTick maximum is 95.073 ms. Average OnTick is 1.323 ms/frame.

## GC interpretation and limits

After the first unavailable baseline, ZGC counters report 1,415 ms minor-cycle
and 14,302 ms major-cycle time; pause collectors report 0 ms minor pauses and
1 ms major pauses in total. Those cycle durations are not STW freeze duration.
Initial-window effects and allocation stalls are not excluded by these aggregate
measurements. Peak sampled heap is 99.48% of the 3-GiB maximum. Worker-side
preprocessing must account for transient mip memory, not simply add concurrency.

No source or runtime setting was changed during this analysis. A different
camera/load sequence and added probe overhead prevent treating differences from
earlier captures as measured regressions or gains. The existing CPU-scaling
algorithm remains unchanged, and no offload has yet been deployed.
