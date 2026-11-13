# Schema 5 capture: resizing removed, cached translucent ordering, remaining stalls

Subsequent user report and mitigation: [translucent flicker during ordering refresh](../issues/2026-11-12-async-translucent-flicker.md).

Analyzed 2026-11-12. Owning implementation:
[deferred textures and asynchronous translucent ordering](../features/2026-11-12-client-deferred-textures-and-async-translucency.md).
Previous measurement: [schema 4 capture](2026-11-12-schema-4-capture-analysis.md).

Input `apocbr-client-telemetry.ndjson`, SHA-256:
`2de14fb9859e4f5f6a885b8433bdd446fcbcfc551f466f58f24aec937169e5d5`.
The file contains eleven old schema 4 windows followed by 28 schema 5 windows,
whose sequence restarts at 1. This report analyzes **only schema 5**. The input
path may be overwritten/appended later; conclusions apply to this hash.

## Capture scope

6,956 frames over 140.079 seconds of reporting intervals. Frame work totals
139.157 seconds: average 20.005 ms, maximum 1137.949 ms. There are 92 frames at
least 50 ms; 24 occur in the first window. After the first window, 6,887 frames
average 19.476 ms, with 68 slow frames. No dropped windows or write errors.

This is not a controlled old/new benchmark: duration, route and scene differ;
new zoom values include 1.0/1.75/2.0, versus 2.0 in the old capture. Loaded-zombie
window samples decline from 22 to about 5–6. The similar overall mean (old
20.187 ms, new 20.005 ms) does not establish an overall FPS improvement or loss.
Phase timings are nested owner-thread wall times, not GPU timings.

## Deferred textures meet their immediate target

- 127 preprocessed images; 1,248 successful dedicated-executor submissions.
  Submission count includes already-small images that do not need preprocessing.
- Zero FALLBACK, UNSUPPORTED, FAILED, CANCELLED_RESULTS, SETTINGS_RELOADS and
  OVERSIZED observations. Oversized handling and cancellation were not exercised
  by this capture and cannot be validated from those zeros.
- 13.579 seconds aggregate worker work; 2.494 ms aggregate successful capacity
  admission wait. Worker samples include pre-entry work and are not frame-time
  savings or a matched image/frame attribution.
- 853 main-thread size-limit calls, **zero actual resizes**. Total size-limit
  time is only 0.304 ms, maximum invocation 0.020 ms. TEXTURE_MIP_SELECT,
  allocation/copy/disposal and mip-generation work are zero on the main thread.
- Main texture adoption totals 4.168 ms, versus 244.226 ms in the previous
  capture, with the same caveat about unmatched workloads.

The previous 150.8 ms main-thread resize burst is absent. The JVM still reports
a 3 GiB maximum heap; the implementation did not increase heap settings. The
native-image preprocessing budget is separate from that heap limit.

## Translucent ordering: reuse is effective; the draw phase remains

4,509 submitted and completed ordering jobs, 938,101 reused results, zero
PENDING, QUEUE_FULL or FAILED observations. About 99.52% of reported
reuse/submission events consume a cached result. Worker processing totals just
43.331 ms (about 9.61 microseconds per completed job). Main owner-side
validation/capture/admission totals 949.004 ms, or 0.1364 ms/frame. Its maximum
invocation is 0.254 ms across 942,442 calls.

The worker is keeping up. Zero PENDING does not mean no visual skips: initial
SUBMITTED calls also skip that chunk-level pass. Worker counters are writer-time
samples; counts need not match main-thread phase counts exactly. Telemetry does
not establish whether the permitted brief omissions were visually acceptable.

CELL_TRANSLUCENT_OBJECTS still averages **2.9678 ms/frame**, about 37.7% of world
cell-rendering time and 14.8% of all frame work. Its maximum invocation is
25.764 ms. The old average was 3.4719 ms/frame; the difference is descriptive,
not a controlled causal 14.5% improvement claim.

Other current averages: world cell rendering 7.8725 ms/frame, tile drawing
7.0125 ms/frame, scene rendering 8.1435 ms/frame, chunk rebaking 0.6124 ms/frame.
The new worker prepares ordering; current visibility/cutaway checks, object/item/
outline traversal and drawing still run on the owner. More ordering workers
would not address the dominant remaining work. Next separate those actual draw
subpasses and count squares/objects submitted; also attribute the unmeasured
remainder inside tile drawing rather than treating it all as sorting.

## Building reconciliation remains a burst

One observed reconciliation takes **383.906 ms** in frame 7:

| Stage | Wall time |
| --- | ---: |
| Remove old buildings | 155.344 ms |
| Create replacements | 180.285 ms |
| Filter predefined-building overlap | 22.501 ms |
| Combine levels | 6.492 ms |
| Insert into meta grid | 17.014 ms |
| Update squares | 1.141 ms |

Removal plus creation accounts for 87.4%. Reference reset is 7.302 ms;
WORLD_REGIONS totals 391.248 ms. The full frame is 719.992 ms, including
NETWORK 270.231 ms and 232.872 ms of cumulative render-context waits, with
484.375 ms sampled main-thread CPU. Waits overlap existing phase scopes and
must not be added to those totals. Current probes cannot assign every wait to a
particular building substep.

The algorithm changes did not eliminate this event. Old 355.518 ms versus new
383.906 ms is not evidence of regression without matching changed-cell/building/
region counts. Next split removal into meta-cell/room removal, pool release and
ID recalculation; split creation into graph traversal, room rectangles and
bounds. Count affected objects and label render-context wait callers. Detached
worker preparation may help the pure computation; live integration and render
dependencies need separate treatment.

## Largest hitches are not texture resizes

| Frame | Total | Measured dominant boundary |
| --- | ---: | --- |
| 2079, window 10 | 1137.949 ms | RegionManager_ClientTick.lua:OnTick:201, 1124.794 ms |
| 2, window 1 | 812.195 ms | NETWORK, 726.537 ms |
| 7, window 1 | 719.992 ms | Network + building reconciliation, as above |
| 2097, window 10 | 241.085 ms | MOVING_UPDATE, 228.220 ms |
| 34, window 1 | 133.544 ms | UI preparation 108.521 ms, ready-slot wait 102.690 ms |
| 5335, window 22 | 126.196 ms | BirdSoundSystem.lua:onTick:690, 112.486 ms |

Both frame 2079 and frame 2097 report zero sampled CPU. This localizes where
wall time was observed but does not prove a second of Lua computation or 228 ms
of moving-object CPU work. Descheduling, native waits and measurement limitations
remain possibilities. Frame 34 similarly has only 15.625 ms sampled CPU, so its
UI/ready-slot delay should not be interpreted as equivalent UI computation.

The matching installed 42.18 Apocalipsebr-regions source under workshop item
3706462687 declares OnTick at line 200, with its first counter instruction at
201. It calls zone checking and dispatches registered modules' entry/exit/tick
handlers. The measured callback includes all nested work: it does not identify
the expensive subscriber or zone query. The installed 42.14 file declares the
function at a different line. Isolate zone queries and registered-module calls,
and correlate another long event with thread stacks before changing policy.
BirdSoundSystem also repeats in numerous 8–18 ms observations; it remains a
separate intermittent target in Restless Waters.

ZGC pause collectors total only 2 ms in this new session. Larger concurrent
cycle durations do not represent stop-the-world pauses. GC/scheduler contention
cannot be excluded, but pause counters do not explain the second-long hitch.

## Next work justified by the evidence

1. Attribute RegionManager's nested callbacks and correlate long off-CPU events
   with thread stacks; do not blindly move live Lua/world work to a Java worker.
2. Split building removal/creation and render-wait callers, with workload counts,
   before designing detached planning and a consistent main-thread commit.
3. Probe current translucent draw/cutaway/item/outline work and remaining tile
   drawing boundaries. Keep the functioning ordering cache and texture queue.

Analysis/documentation only; no runtime code, installed files or mod files changed.
