# Schema 2 capture: scene rendering and image-result bursts

Analyzed 2026-11-12. Input: apocbr-client-telemetry.ndjson, schema 2 only,
13 contiguous windows, sequences 1-13. SHA-256:
`2c2fa5f2978e608172a075543359d1fb2a9a36873086167ea5a92fa38d59243c`.
Capture interval approximately 17:12:05-17:13:07 America/Fortaleza.
The input file replaced the earlier schema-1 capture; this is a different run.
That filename was subsequently replaced again; the
[schema-3 follow-up](2026-11-12-schema-3-capture-analysis.md) describes its newer
contents. The SHA-256 above identifies this historical schema-2 run.
See [metric definitions](../features/2026-11-12-client-main-thread-telemetry.md)
and the historical [schema-1 analysis](2026-11-12-sprite-heavy-client-capture.md).

## Main findings

1. Rendering cost lies primarily in **RENDER_SCENE**, not the measured frame-queue
   handoff. That narrows the next investigation to renderframe/IsoWorld.render,
   including scene work, synchronous OnPostRender callbacks and EndFrame.
2. Main-thread file-completion cost is almost entirely **FileTask_LoadImageData**
   callbacks. A loading burst spans many callbacks, not one 248-ms task.
3. Normal aggregate frame work is predominantly main-thread CPU time. The largest
   initial scene stall is different: 841 ms wall versus only 141 ms CPU in the
   entire frame, indicating substantial off-CPU time somewhere within scene work.
4. ZGC's large recorded collection times are concurrent-cycle counters, not
   evidence of multi-second stop-the-world pauses. Measured pause-time deltas
   after the first sample are all zero milliseconds at MXBean resolution.
5. One ~410-ms hole remains inside UPDATE_WORLD outside its current child probes.
   This prevents assigning the largest update spike to moving objects or physics.

## Capture overview

- 62.001 seconds of reporting windows; 2,156 measured frames.
- 61.525 seconds frame wall time; 59.281 seconds reported main-thread CPU time.
- Mean work 28.537 ms/frame, mean CPU 27.496 ms/frame; maximum work 841.483 ms.
- 98 frames >=50 ms (4.55%); 18 above 100 ms and four above 250 ms.
- Combined histogram p95 upper bound 50 ms; p99 upper bound 100 ms.
- 85 retained worst frames; 13 slow frames omitted by per-window retention limits.
- No dropped windows, output errors or task-type overflow. Writer sample lag 0-5 ms.
- All window-end render contexts: 1920x1080, primary-player zoom 2.0. Window-end
  loaded zombie counts 22-34. Context does not measure visible sprites or draw calls.

Main-thread CPU sums to 96.35% of frame wall time across the capture. This supports
a predominantly CPU-execution cost in aggregate, not a pervasive ready-slot wait.
It does not measure GPU or worker CPU usage. Every retained frame CPU sample is
a multiple of 15.625 ms, so small per-frame differences and CPU > wall in some
short records are timer-granularity effects, not accurate fine-scale accounting.

## Sustained cost distribution

| Phase | Mean ms/frame | Share of frame wall time | Maximum ms |
| --- | ---: | ---: | ---: |
| RENDER_PREPARE | 14.514 | 50.86% | 753.265 |
| RENDER_SCENE, inside render preparation | 11.175 | 39.16% | 740.811 |
| RENDER_UI, inside render preparation | 2.820 | 9.88% | 27.554 |
| RENDER_HANDOFF, nested in rendering | 0.0327 | 0.115% | 9.199 |
| RENDER_READY_SLOT_WAIT, inside handoff | 0.0009 | 0.003% | 0.026 |
| STATE_UPDATE, inside LOGIC | 9.813 | 34.39% | 422.925 |
| UPDATE_WORLD, inside STATE_UPDATE | 8.055 | 28.23% | 419.282 |
| WORLD_CELL, inside UPDATE_WORLD | 4.267 | 14.95% | 46.762 |
| MOVING_UPDATE, inside WORLD_CELL | 3.284 | 11.51% | 44.412 |
| MOVING_POSTUPDATE, after cell update | 1.791 | 6.28% | 13.322 |
| WORLD_PHYSICS, inside UPDATE_WORLD | 1.701 | 5.96% | 15.681 |
| UPDATE_LUA_TICK, inside STATE_UPDATE | 1.059 | 3.71% | 12.164 |
| LIGHTING, outer frame phase | 1.728 | 6.05% | 78.248 |

Rows overlap; do not sum parent and child values. UI rendering and UI update are
different phases (UI_UPDATE averages another 1.079 ms inside LOGIC).
Moving update plus postupdate averages 5.075 ms/frame, meaningful but not all
zombie AI: scheduler work includes other moving objects and postupdate behavior.

The ready-slot maximum is only 26 microseconds. Monitor acquisition accounts for
most of the maximum 9.199-ms handoff. This largely removes the measured sprite
queue handoff from the primary sustained-bottleneck candidates for this run. It
does not exclude other render-context/native waits inside RENDER_SCENE.

## Large stalls and what remains unresolved

| Frame | Work ms | CPU ms | Attribution |
| --- | ---: | ---: | --- |
| 38 | 841.483 | 140.625 | RENDER_SCENE 740.811 ms; handoff 0.023 ms. Large off-CPU component, not the measured queue wait. |
| 22 | 515.860 | 531.250 | UPDATE_WORLD 419.282 ms, but named world children sum to only ~8.782 ms. File results add 70.889 ms. CPU sample is coarse. |
| 25 | 458.307 | 406.250 | FILE_HANDLE_RESULT 248.106 ms, NETWORK 69.421 ms, LIGHTING 78.248 ms. Largest individual file callback only 35.734 ms. |
| 32 | 277.534 | 281.250 | NETWORK 179.342 ms and RENDER_SCENE 56.094 ms. |
| 894 | 140.257 | 140.625 | Later spike: RENDER_SCENE 78.515 ms, UPDATE_WORLD 39.034 ms; no file callback and handoff 0.025 ms. |

RENDER_SCENE wraps IngameState.renderframe, whose renderFrameInternal includes
RenderSettings/progress handling, IsoWorld.render, ModelOutlines, legacy postrender,
synchronous Lua OnPostRender and Core.EndFrame. It is not pure sprite traversal
or GPU execution. Separate these calls next. Within IsoWorld.render, distinguish
cell/chunk rendering, dynamic objects, weather and render-context interactions.
Use JFR/stack evidence to explain the off-CPU part of frame 38; these timings
cannot decide whether it was allocation, native synchronization or another wait.

Frame 22 has ~410.500 ms of UPDATE_WORLD outside WORLD_PHYSICS, WORLD_CLIMATE,
WORLD_CELL, WORLD_CONTACTS, WORLD_REMAINDER, MOVING_POSTUPDATE and WORLD_WORKER_WAIT.
The source still has unmeasured hutch/fog/helicopter work, emitter ticking/removal,
world sound/group/collision pre-update, region/halo work, recorder hooks and
DesignationZone update. First-time class initialization can also be charged to
whichever call triggers it. WORLD_REMAINDER names only updateThread, not every
unmeasured part of IsoWorld.update. Add explicit boundaries; do not assign this
hole to a particular subsystem from the current data.

## Confirmed image-result burst

| Completed task type | Result callbacks | Result total ms | Largest callback ms |
| --- | ---: | ---: | ---: |
| FileTask_LoadImageData | 680 | 587.941 | 52.434 |
| FileTask_LoadMesh | 246 | 5.681 | 0.929 |
| FileTask_LoadWorldMapBinary | 14 | 1.633 | 1.200 |
| FileTask_LoadPackImage | 2 | 0.046 | 0.029 |

Image-data callbacks account for approximately 98.76% of measured result-callback
time. Window 1 processes 664 callbacks across all types and observes a queue
backlog peak of 515 at admission points. Admission-lock waits and executor
submission are negligible. The expensive work is main-thread result application.

The exact source path is FileTask.handleResult -> TextureIDAssetManager's callback
-> TextureID.setImageData -> limitMaxSize, followed by queuing GPU texture creation
through RenderThread.queueInvokeOnRenderContext. limitMaxSize may build mip levels
and copy buffers when flags/dimensions require downscaling. Background
FileTask_LoadImageData.call decodes ImageData separately. The capture proves
the callback category, not that downscaling consumed all its time. Add timings
for limitMaxSize versus remaining asset completion, dimensions/size-limit flags
and callback count per frame before selecting an offload or pacing change.

## GC and memory: corrected interpretation

After the first unavailable baseline, collector deltas sum to:

| Collector | Reported count delta | Collection time delta ms |
| --- | ---: | ---: |
| ZGC Minor Cycles | 13 | 1,636 |
| ZGC Minor Pauses | 42 | 0 |
| ZGC Major Cycles | 2 | 6,457 |
| ZGC Major Pauses | 7 | 0 |

The window with 5,054 ms aggregate collection time contains 4,889 ms major-cycle
time and 165 ms minor-cycle time; its pause collectors report 0 ms. Do not treat
the aggregate as frozen-game duration or sum cycle and pause counts as one kind
of event. Zero milliseconds does not mean no pauses: submillisecond effects are
below the counter's reporting resolution. Initial-window deltas are unavailable,
so this does not exclude GC-related effects during the worst login frame. ZGC
allocation stalls/CPU competition also require separate logs/events.

Heap limit is 3,072 MiB. First sample is 3,068 MiB (99.87%); subsequent samples
range 2,824-2,932 MiB (91.93-95.44%). Memory pressure remains relevant, but a leak,
allocation stall or benefit from a larger heap is not established by these data.

## Temporal behavior and next actions

First two windows average 59.093 ms/frame; subsequent windows average 25.921 ms.
The seventh window regresses to 39.711 ms with 21 slow frames, including the
CPU-heavy frame 894. The final full window averages 16.116 ms; the final partial
window averages 13.694 ms. Warm-up is not the only pattern: substantial scene
cost and later spikes remain. Camera movement/scene content were not logged.

Next priorities: split RENDER_SCENE's world render / OnPostRender / EndFrame;
measure the UPDATE_WORLD remainder; split image callback downscaling/application;
then add per-type moving-object and chunk-rebake workload counters where those
new timings lead. Record native/GC/JFR stacks for large off-CPU stalls.
No optimization or probe-code change was applied during this analysis. Comparing
this run's mean with the previous run does not quantify probe overhead or a
performance regression because the runs are not controlled equivalents.
