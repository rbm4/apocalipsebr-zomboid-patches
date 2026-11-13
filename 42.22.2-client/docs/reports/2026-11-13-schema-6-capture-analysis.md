# Schema 6 capture: inexpensive continuity, startup network/building stalls

Analyzed 2026-11-13 for 42.22.2-client. Owning implementation:
[deferred textures and translucent ordering](../features/2026-11-12-client-deferred-textures-and-async-translucency.md).
Related issue: [translucent refresh flicker](../issues/2026-11-12-async-translucent-flicker.md).
Previous measurement: [schema 5 capture](2026-11-12-schema-5-capture-analysis.md).

Input: `apocbr-client-telemetry-schema6.ndjson`, SHA-256
`0eb2783afbbd30cc857de6830284339ead293f0a56e734695d9d7eb626f1334a`.
All 18 windows are schema 6, sequence 1–18. This report describes that hash.

## Capture scope

4,174 frames over 90.504 seconds of reporting intervals. Frame work totals
89.896 seconds, mean 21.537 ms, maximum 1100.309 ms; 56 frames are at least
50 ms. There are no dropped windows or write errors.

The first window has 29 slow frames. After it, 4,108 frames average 20.643 ms,
with 27 slow frames and a 116.319 ms maximum. After the first two windows,
3,918 frames average 20.363 ms, with 17 slow frames and a 76.594 ms maximum.
After the first five windows, only four of 3,336 frames reach 50 ms. The last
five windows average 18.397 ms, with two slow frames.

Zoom is sampled at 1.0 in the first window and 2.0 thereafter; loaded-zombie
samples range 5–19. This is a different workload from schema 5. Its old mean
20.005 ms versus new 21.537 ms is not a controlled regression measurement.
Phase timings nest; do not sum parent and child totals. CPU samples are coarse
and can exceed an individual frame's wall time at their measurement granularity.

## Continuity cost is very small

| Worker/owner observation | Total |
| --- | ---: |
| Submitted/completed ordering jobs | 3,673 / 3,673 |
| Ready worker result reuse | 672,313 |
| Owner catch-up preparations | 3,667 |
| Owner catch-up merge/sort time | **19.544 ms** |
| Worker merge/sort time | 22.730 ms |
| Continuity-only cached reuse | 0 |
| Pending / queue-full / failures | 0 / 0 / 0 |

Catch-up accounts for about 0.54% of reported reuse/catch-up events, averaging
5.33 microseconds each or **0.00468 ms per measured frame**. Its work is only
0.0217% of total frame work. Main validation/capture/admission (including catch-up)
totals 706.034 ms, or 0.1692 ms/frame; the maximum invocation is 0.306 ms.

The absence of CONTINUITY_REUSED does not mean the fix was unused: nearly every
new snapshot needed owner catch-up immediately, then its ready worker result
was reused on later frames. The worker and owner therefore duplicate much of
the preparation for changed snapshots. At this measured cost, cached reuse is
the important mechanism; this pass is not a substantial worker-compute saving.
These counters are writer samples, so their totals need not align exactly with
main-frame phase call counts.

Schema 6 supplies a complete current list instead of skipping a changed pass.
The capture confirms the catch-up path is active and inexpensive. It cannot
establish whether the user's visible flicker disappeared, or measure residual
vanilla cutaway transitions; visual verification remains necessary.

## Texture resizing remains off the owner

115 successful worker resizes, 1,138 executor submissions, zero fallback,
unsupported, oversized, failure, cancelled-result or settings-reload events.
Not every submitted image needs resizing. Aggregate worker work is 5.541 seconds,
with 2.030 ms of successful admission waits. Worker samples include pre-entry
work and cannot be interpreted as measured frame-time savings.

741 owner size-limit calls performed **zero actual resizes**, taking 0.314 ms
total (maximum 0.018 ms). Main mip selection and buffer allocation/copy/disposal
remain zero. Texture adoption totals 3.676 ms. This capture does not stress
oversized admission or cancellation. Maximum JVM heap remains 3 GiB.

## Largest hitch: network plus building reconciliation in the same frame

Frame 28 takes **1100.309 ms**, with 1062.5 ms sampled CPU:

- NETWORK: **700.272 ms**.
- WORLD_REGIONS: 345.263 ms, including reference reset 7.317 ms and
  REGION_PROCESS_BUILDINGS **337.912 ms**.
- Within building reconciliation: removal 130.591 ms, creation 150.504 ms,
  predefined-building filtering 15.511 ms, combination 4.716 ms, insertion
  20.653 ms and square update 15.165 ms.
- Render-context wait in this frame: zero.

Network plus region building work explains about 94% of the frame's wall time.
Unlike the earlier off-CPU RegionManager event, this is predominantly CPU-active
owner work. NETWORK still wraps the entire GameClient.update, not a measured
packet type. See the [network async assessment](2026-11-12-client-network-async-assessment.md).
Per-type packet timings and separation from streaming/outgoing maintenance are
needed to select an asynchronous decoding target.

Frame 63 adds another 249.330 ms hitch: building reconciliation 189.261 ms,
including removal 73.174 ms and creation 90.250 ms. Eight reconciliation calls
total 548.648 ms; the two large calls account for 527.173 ms. Across all calls,
removal totals 210.776 ms and creation 251.713 ms, together about 84.3%.
These remain the boundaries to subdivide before offloading or changing commits.
Workload counts are still absent, so old/new maxima do not prove algorithmic gains.

## Rendering and other remaining costs

| Phase | Mean per measured frame |
| --- | ---: |
| Scene rendering | 9.084 ms |
| World cell rendering | 8.705 ms |
| Tile drawing | 7.737 ms |
| Translucent objects | **3.333 ms** |
| Lighting phase | 1.816 ms |
| Chunk-level rebaking | 0.743 ms |

World cell rendering accounts for 40.4% of frame work; translucent objects for
15.5%. Catch-up merge/sort is only about 0.0047 ms/frame, so it does not explain
the 3.333 ms translucent phase. Split current square/object visibility, cutaway,
item/outline traversal and draw submission before moving more work to workers.

Frame 237 takes 116.319 ms, with 76.859 ms aggregate rebaking and 64.099 ms
aggregate render-context wait. These scopes overlap; they cannot be added.
The counter association does not prove which rebake operation issued every wait.
Frame 32 also has 124.646 ms in LIGHTING inside a 204.174 ms frame.
Frame 757 later has NETWORK 60.082 ms inside a 75.168 ms frame, so network bursts
are not exclusively an initial-entry event.

The recorded RegionManager OnTick sample is 5.472 ms; the prior second-long
callback does not recur here. Changing its tick interval may reduce frequency,
but this unmatched capture does not prove that the underlying slow-call cause
was fixed. BirdSoundSystem reaches 20.629 ms; total OnTick maximum is 21.186 ms.
ZGC pause collectors report only 1 ms aggregate, which does not explain the
large CPU-active startup hitch.

## Next work supported by the capture

1. Add GameClient update subphases and bounded per-packet-type count/bytes/time/
   backlog probes. Choose a worker target from measured pure decoding work.
2. Split building removal/creation into room/meta-cell operations, ID rebuilding,
   graph traversal, room rectangles, pool work and bounds, with workload counts.
3. Instrument actual translucent traversal/drawing. Preserve the continuity fix;
   ordering computation itself is too small to justify further worker tuning.

Analysis and documentation only. No runtime sources, installed files or mods changed.
