# Schema 8: worker building planning and remaining client stalls

Capture: [schema 8 NDJSON](apocbr-client-telemetry-schema8.ndjson), analyzed
2026-11-13. Owning guide: [building worker and bulk commit](../features/2026-11-13-async-building-reconciliation.md).
Rendering context: [previous lifecycle investigation](2026-11-13-rendering-and-building-async-lifecycle.md).

## Coverage and limits

19 consecutive windows, 95.268 seconds, 4,426 frames, no dropped windows or write
errors. Weighted frame work averages 21.365 ms; 42 frames exceed the 50 ms slow
threshold; maximum 810.551 ms. After the first three windows, average work is
20.283 ms over 3,922 frames. This is work timing, not a direct FPS measurement.
1920x1080; window-end zoom changes from 1 to 2. Retained slow-frame zombie counts
range from 0 to 27: this does not validate a large horde workload.

The previous schema 7 capture had 253.050 and 272.123 ms building processing
frames. This capture's maximum is 59.843 ms, but input workloads differ and the
old capture lacks equivalent geometry counters. Treat this as a smaller observed
stall, not a controlled percentage improvement or proof of runtime equivalence.

## Building outcome

Six worker plans consumed 246.760 ms total: collection 125.944 ms and geometry
120.816 ms. Worker failures: zero. Main-thread synchronous planning calls: zero.
Worker counters are sampled independently and include possible pre-entry work;
they cannot be subtracted from individual frame durations.

There are five main-thread reconciliations, all in windows 2 and 3. Commit total
is 62.253 ms. Four small commits together account for 6.322 ms; the large commit
accounts for 55.931 ms. No later reconciliations were observed, so this does not
establish the cost of sustained cell-boundary streaming or repeated edits.

Frame 227 takes 117.537 ms. Its workload is 1,244 unique cell coordinates
(including empty neighbors), 8,735 region observations, and 23,355 planned
rectangles. It removes/adds 81 buildings, reindexes 36 meta cells, and marks 445
chunks dirty. These counters describe planning scope before authored-overlap
filtering, not 23,355 installed rectangles or 8,735 installed rooms.

| Frame 227 owner operation | ms |
| --- | ---: |
| Region swap including cached-reference reset | 68.222 |
| Cached-reference reset | 8.370 |
| Building processing including validation/commit | 59.843 |
| Bulk commit | 55.931 |
| Materialize engine definitions | 23.405 |
| Authored-building overlap filter | 15.709 |
| Remove | 6.108 |
| Insert | 3.672 |
| Combine levels | 2.734 |
| Global registry compaction | 1.860 |
| Final reindex | 1.145 |
| Retire pooled definitions | 0.150 |
| Refresh loaded squares | 0.115 |

Parent and child phases overlap. Materialization and filtering consume 39.114 ms,
about 70% of the commit. Reindexing and square refresh are small in this capture;
the latter must not be generalized to a dense loaded-square workload. The
worker/dedup/bulk approach is active, but publication still misses a frame budget.

Practical follow-up: measure candidate shapes/rooms/rectangles before and after
filtering, pool misses versus allocations, and filter query/rectangle-test counts.
Then consider rejecting candidates using worker geometry against an owner-captured
authored-building index before creating engine definitions. Retain revision
validation and main-thread pool/Lua/RNG ownership. This is a proposed next step,
not implemented here. The global registry is currently scanned even for no-op
commits; window 3 spends 2.159 of its 3.051 commit ms there, so skipping compaction
when no definitions retire is a small concrete candidate.

## Rendering and other stalls

| Phase | Weighted ms/frame | Meaning |
| --- | ---: | --- |
| Render prepare | 11.394 | Parent rendering phase |
| Tile drawing | 7.652 | Inside rendering |
| Translucent objects | 3.298 | Includes scans, state/alpha, items and draw work |
| Translucent ordering | 0.155 | Nested ordering helper |
| World cell update | 3.155 | Simulation, separate from rendering |
| Moving update / postupdate | 2.333 / 1.332 | Separate moving-object passes |
| Render UI | 2.128 | Inside rendering |

Approximately 3.143 ms/frame remains inside translucent objects outside ordering.
After the first 15 seconds, translucent objects still average 3.334 ms/frame.
Async ordering cannot explain/remove that remainder: instrument square filtering,
object scans/draws, alpha work, world items, southeast/outline scans and tree flushes
before choosing the next worker boundary. Preserve current rendering order and
per-player invalidations.

The ordering worker reports 3,731 submissions/completions, no failures/full queue,
and 3,729 owner catch-ups. Catch-ups cost only 18.813 ms total; worker work is
23.009 ms total. They are nearly as frequent as submissions but not a major
measured cost here. Reuse is 621,671; it does not mean all translucent draw work
was offloaded.

Frame 4,245 takes 130.464 ms, with item rendering 86.330 ms and render-context wait
85.202 ms (nested). Main-thread CPU is only 31.250 ms. Frame 4,246 similarly has
63.030 ms items and 62.623 ms context wait. Frame 224 has 72.620 ms total translucent
objects with 61.439 ms context wait. These show waits attributed inside rendering,
not equivalent amounts of CPU scanning. Current probes do not identify the exact
render-context task, upload, or driver operation. Add caller/task labels and
enqueue-to-execute/task-execution timings before blaming resizing or GPU saturation.
Per-invocation phase maxima differ from summed per-frame phase times.

Frame 959 takes 376.898 ms: RegionManager_ClientTick.lua OnTick line 201 alone
takes 363.861 ms; the frame CPU is 15.625 ms. Its wall-time stall is real, but
the low owner CPU suggests blocking/descheduling rather than proving 364 ms of
Lua computation. Capture inside that callback and its Java calls. Doubling its
interval has not eliminated the single-call stall.

Login frame 13 takes 810.551 ms, network 762.840 ms. Largest individual packet:
ZombieSynchronizationUnreliable 761.619 ms. AnimalUpdateUnreliable reaches
126.173 ms and VehicleFullUpdate 76.144 ms. Network averages 0.466 ms/frame across
the capture, illustrating startup bursts rather than steady domination. Packet
changes remain outside the requested rendering/building focus.

## Textures and JVM context

113 worker-preprocessed images, no main-thread fallback or worker failure, about
11.225 seconds worker time (writer samples can include pre-entry work). Capacity
wait totals 1.939 ms. This supports the deferred-resize path operating; it does
not attribute render-context stalls to a particular texture task.

The process still reports a **3 GiB maximum heap**, with sampled use 92.3%-97.3%.
It is not running with the larger heap contemplated earlier. This is a relevant
memory constraint, not proof of allocation stalls. ZGC cycle duration must not be
read as stop-the-world time: the 3,919/4,538 ms major-cycle durations appear under
cycle collectors, while pause collector times are separately reported. Current
window samples cannot assign GC or allocation pressure to an exact slow frame.

## Next measurement order

1. Label render-context tasks/callers and split translucent scan/draw/alpha/item
   costs. This addresses the repeated rendering cost and the late wait spikes.
2. Add building pre/post-filter and allocation/query counters; reduce engine
   materialization of candidates that will be rejected. Keep coherent publication.
3. Trace the RegionManager callback's blocking path separately, and compare the
   same route/zoom with the intended heap setting. No performance claim should
   mix changed heap, camera coverage and geometry workload without disclosure.

This analysis changes documentation only; no runtime or deployment changes.
