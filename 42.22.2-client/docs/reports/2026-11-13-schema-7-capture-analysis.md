# Schema 7 capture: entity loading inside packet parsing

Analyzed 2026-11-13 for 42.22.2-client. Owning instrumentation:
[network update and packet-stage telemetry](../features/2026-11-13-client-network-telemetry.md).
Protocol context: [binary format and decoder pipeline](2026-11-13-client-binary-protocol-and-decode-pipeline.md).

Input `apocbr-client-telemetry-schema7.ndjson`, SHA-256
`4465060f83d259535cb344732edf1181f02288a78b832caf01687e3ce4ba5f49`.
All 25 windows are schema 7, sequence 1–25. Findings apply to that hash.

## Capture scope

5,755 frames over 121.607 seconds of reporting intervals, 120.839 seconds of
frame work. Mean work 20.997 ms, maximum 329.927 ms, 83 frames at least 50 ms.
The first two windows account for 56 slow frames. After them, 5,569 frames
average 19.898 ms with 27 slow frames. The last five windows average 19.349 ms
and have no frames reaching 50 ms. No dropped windows, write errors or type
overflow. These conditions differ from previous captures; no causal overall
speedup/regression percentage is established.

## Packet wrapper construction is not the measured problem

14,350 incoming main-loop dispatch attempts, 14,346 polled entries, approximately
5.867 MB of attempted payload bytes. Count differences can reflect replayed or
already queued work; payload sums exclude transport/type headers and are not
wire bandwidth. No recorded outer dispatch errors or coop delays. Maximum
sampled owner backlog is 314; maximum application-queue age is 589 ms, not ping.

Only **two explicit fresh packet instances** were constructed, both State
packets (windows 2 and 13). FACTORY totals **11.064 ms** across 12,260 modern
dispatches; its largest invocation is 0.087 ms. This includes cache lookups and
factory checks. It excludes connection-time eager cache construction and all
nested entity/field/asset allocations during parsing/application. It is not a
claim that the network subsystem allocated only two objects.

Even eliminating the entire measured factory stage would remove only about
0.0019 ms/frame, or 0.72% of NETWORK work in this capture. A protocol rewrite
motivated by wrapper creation is not supported by these measurements.

## Network time is primarily handler parsing

| Owner boundary | Total time | Mean per frame |
| --- | ---: | ---: |
| NETWORK | 1531.848 ms | 0.2662 ms |
| NET_DISPATCH_BATCH | 1369.732 ms | 0.2380 ms |
| Summed per-type dispatch | 1356.517 ms | 0.2357 ms |
| PARSE | 1076.212 ms | 0.1870 ms |
| APPLY | 142.267 ms | 0.0247 ms |
| VALIDATE | 42.918 ms | 0.0075 ms |
| LEGACY handler calls | 50.247 ms | 0.0087 ms |
| NET_STREAMING | 14.436 ms | 0.0025 ms |
| NET_OUTGOING_INVENTORY | 4.052 ms | 0.0007 ms |

NETWORK is 1.27% of all frame work, despite causing specific large spikes.
PARSE accounts for about 70.3% of NETWORK time. The batch-minus-handler gap is
13.215 ms over the whole capture and includes eligibility checks, list removal
and probe overhead. This capture does not implicate queue shifting as a major
cost, nor streaming/outgoing inventory as the observed network spike source.

## Expensive packet types

| Type | Attempts | Dispatch total | Largest dispatch | PARSE total |
| --- | ---: | ---: | ---: | ---: |
| AnimalUpdateUnreliable | 1081 | **595.436 ms** | **115.415 ms** | **584.531 ms** |
| VehicleFullUpdate | 84 | **213.597 ms** | **33.111 ms** | **213.012 ms** |
| ZombieSynchronizationUnreliable | 182 | **152.356 ms** | **107.429 ms** | **151.301 ms** |
| PlayerUpdateReliable | 3550 | 101.562 ms | 0.538 ms | 20.882 ms |
| VehicleUpdate | 4202 | 44.370 ms | 1.652 ms | 10.693 ms |

The first three account for about 70.9% of dispatch work and 88.2% of PARSE
work. Player updates have high volume but small individual cost; their APPLY
total is 64.197 ms. SyncClothing's APPLY totals 37.199 ms; ScoreboardUpdate has
30.851 ms in APPLY, including a 20.495 ms dispatch.

The phase name PARSE does **not** imply pure byte decoding. Exact-build source:

- AnimalUpdatePacket.parse removes animals/hutch associations, handles requested
  full animal data, constructs missing IsoAnimal instances, calls animal.load,
  attaches animals to the world/hutches/trailers, updates network AI, and handles
  ordinary updates/requests. Its small processClient only updates requested
  synchronization state. Expensive full reconstruction is therefore charged to
  PARSE, not APPLY. The packet does not record which subpath caused each spike.
- VehicleFullUpdatePacket.parse calls BaseVehicle.load. For an existing vehicle,
  it clears/loads a reusable temporary vehicle and then updates pose; for a
  missing vehicle it constructs, loads, attaches and registers it. It also applies
  authorization, physics and passenger state. The temp instance is cached, but
  serialized contents are still loaded. Avoiding unnecessary reconstruction of
  an existing vehicle is a source candidate, requiring exact buffer/layout and
  behavior analysis; skipping arbitrary bytes is not safe.
- ZombieSynchronizationPacket.parse changes the client send timer and invokes
  NetworkZombieSimulator.receivePacket. That iterates parseZombie, which can
  create/register missing zombies, populate visuals, AI and world state, or
  update existing entities. It is not a detached numeric decoder.

These are sources under 42.22.2/decompiled/zombie, matching the target baseline.
Moving these complete parsers onto a worker would move live mutations and shared
cached state there. The useful boundary is detached field preparation followed
by explicitly owned entity integration, not replacing wrappers indiscriminately.

## Correlated network hitches

- Frame 35: 323.419 ms total, NETWORK 115.532 ms. Largest packet is
  AnimalUpdateUnreliable, 115.415 ms for 1340 payload bytes; its PARSE dominates.
  The same frame also has heavy rendering/lighting. A very small payload taking
  this long is consistent with expensive handler work, not evidence of bandwidth
  cost. Nested entity creation/loading remains unseparated.
- Frame 4: 166.965 ms total, NETWORK 119.645 ms. ZombieSynchronizationUnreliable
  accounts for 107.429 ms with 425 payload bytes.
- Frame 721, window 5: 72.770 ms total, NETWORK 56.371 ms. An animal update
  accounts for 56.343 ms, so animal spikes are not confined to the first window.
- Frame 2675, window 13: 82.349 ms total, NETWORK 33.367 ms. VehicleFullUpdate
  accounts for 33.111 ms and 6564 payload bytes.

The prior 700 ms NETWORK event does not recur. Probes did not change scheduling
or handlers; a different capture cannot establish that instrumentation removed it.

## Larger remaining stalls outside network

- Frame 32: 329.927 ms, building reconciliation 253.050 ms; removal 84.853 ms,
  creation 133.776 ms. Only two tiny incoming packets were dispatched that frame.
- Frame 2889, window 14: 301.882 ms, building reconciliation 272.123 ms;
  removal 114.592 ms, creation 131.834 ms. NETWORK is only 0.076 ms. This is a
  recurring later CPU-active region stall (296.875 ms sampled CPU).
- Frames 346 and 395: 212.835/207.841 ms, BirdSoundSystem.onTick
  188.905/186.855 ms, but only 31.25 ms sampled CPU each. Other bird samples
  reach 110.231 and 126.679 ms. These wall times localize the callback but do not
  establish equivalent Lua computing time; native waits/descheduling remain.
- Frame 39: 304.486 ms, item pass 113.801 ms and render-context wait 112.620 ms
  in the same frame. Nested scopes overlap and cannot be added or assigned to a
  particular render operation without caller instrumentation.

Sustained world cell rendering averages 8.373 ms/frame, translucent objects
2.992 ms/frame. These remain much larger recurring costs than average networking.
No RegionManager slow callback is retained in this capture; absence does not
establish that all underlying callback problems are resolved. ZGC pause
collectors report only 2 ms aggregate.

Texture workers resize 122 images with zero fallback or failure; main mip
selection remains zero. Translucent continuity catch-up totals 21.637 ms across
4689 preparations, with zero queue saturation/failures. No visual outcome can be
inferred from those counters alone.

## Next targeted work

### Animal visibility and relevance clarification

Follow-up source check on 2026-11-13: not seeing animals on camera does not imply
the animal packets were irrelevant. Both vanilla and the current server patch's
AnimalSynchronizationManager retain the final UdpConnection.RelevantTo check for
ordinary updates. The server patch adds a conservative candidate broad phase;
it does not intentionally replace the final relevance test with a global broadcast.

RelevantTo accepts either a connection loading rectangle or an axis-aligned
position range for any of the four connection slots. The animal sender passes
`(getRelevantRange() - 2) * 10`. The server's accepted handshake range is clamped
12–20 and converted to `range / 2 + 2`, giving ordinary position thresholds of
60–100 tiles; the connect-area rectangle can independently qualify an animal.
This tests neither camera visibility, aiming direction, occlusion nor exact
client loaded-square membership. isAnimalOnScreen is also only a Manhattan-distance
heuristic, controlling nominal 800 versus 1000 ms cadence, with extra-update
exceptions. It does not suppress all off-screen updates.

Requested full-state responses are collected separately without repeating that
distance test; an unknown normal update causes the client to request the animal.
Deletion IDs are copied to all fully connected recipients without relevance
filtering. Therefore a packet can contain requested/full-state data, ordinary
updates or global deletion notices; packet counts are not animal counts.
Full-state consistency checks require a loaded target square, an existing hutch,
or a known vehicle depending on location. Animal parsing includes hutches,
trailers and removal/hidden locations, not only animals visible in the scene.

The existing capture contains no animal species, coordinates, distances or section
counts. It cannot establish whether these specific packets concerned nearby unseen
animals, stale requested entities, or deletion notices, nor prove a relevance bug.
Extend animal subprobes with requested/updated/deleted record counts, new/existing
animals, species, relative distance and loaded-square/hutch/vehicle status before
changing relevance policy. No game or server behavior was changed by this check.

### Processing boundaries

1. Instrument animal requested/full-state versus ordinary-update paths: counts
   created/loaded/deleted/updated, field decode, constructor, load, attachment,
   AI integration and resource requests. This is the strongest measured network
   target for a detached decoder/consistent commit design.
2. Split full vehicle updates by existing/missing vehicle; measure load versus
   integration and investigate unnecessary temporary reconstruction.
3. Split zombie synchronization by new/existing entity and decode versus creation/
   update. Preserve spawn/update/delete ordering and connection generations.

Keep building removal/creation and actual rendering as independent priorities.
Only after those splits choose an async byte decoder, algorithm change or bounded
owner-side integration. This investigation changes documentation only.
