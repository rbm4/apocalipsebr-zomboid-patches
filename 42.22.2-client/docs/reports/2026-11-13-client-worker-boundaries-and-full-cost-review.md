# Client worker boundaries and full cost review

Investigated 2026-11-13 for 42.22.2-client. Re-evaluates the unchanged
[schema 7 capture](2026-11-13-schema-7-capture-analysis.md), SHA-256
`4465060f83d259535cb344732edf1181f02288a78b832caf01687e3ce4ba5f49`.
Owning [network telemetry](../features/2026-11-13-client-network-telemetry.md) and
[main-thread instrumentation](../features/2026-11-12-client-main-thread-telemetry.md).
This is a source/measurement review, not an async implementation or deployment.

## A worker is viable after separating ownership

A single worker can serialize preparation of animal, vehicle and zombie records.
It still overlaps the main thread's simulation, rendering, streaming and other
packet handlers. Running the existing complete parse methods on that worker
does not establish exclusive ownership of the objects they touch.

The packet-stage timing label PARSE conceals integration in all three cases.
The measured 584.531 ms animal, 213.012 ms full-vehicle and 151.301 ms zombie
PARSE totals cannot yet be partitioned into pure decoding versus initialization,
resource work, live mutations or waits. They are not predicted async savings.

### Animals

AnimalUpdatePacket.parse does ordinary updates, full-state replies and deletion.
The ordinary branch gets an existing animal's cached AnimalPacket and changes
its network AI. Deletion removes an animal and alters hutch/nest membership.
Full-state processing constructs an IsoAnimal when missing, calls
AnimalInstanceManager.add **before** animal.load, then attaches it to world,
hutch or trailer state and changes AI.

AnimalInstanceManager's IsoObjectID uses ConcurrentHashMap. That is useful for
the registry operations, but not a lock for nested actor state. Publishing an
animal before load completes would let concurrent registry readers observe a
partially initialized animal if the current method were simply moved to a worker.

IsoAnimal.load itself reads the live meta-grid animal-zone handler, changes
position/stats and resolves definitions, creates/loads a Lua mod-data table,
calls init(breed), and reconstructs genome/state. init loads animation sets,
initializes states/sprite parts/visuals and uses global randomness/definitions.
Thus even a not-yet-attached animal does not make this entire loader a proven
worker-private decoder. A safe detached decoder must avoid engine Lua and native
initialization or establish a separately tested ownership contract for them.

Relevant exact-build sources: AnimalUpdatePacket.java:119,
IsoAnimal.java:223/1385/1499, AnimalInstanceManager.java:38, IsoObjectID.java:13.

### Vehicles

VehicleFullUpdatePacket.parse resolves a live vehicle and square. Existing
vehicles clear/load a cached tempVehicle and then change live transforms and
position. New vehicles are loaded, attached to chunk.vehicles, added to the
world and registered with VehicleManager. The same parser applies authorization,
passengers and Bullet.setOwnVehiclePhysics.

The temporary vehicle path is a promising isolation/algorithm target, but it is
not proven safe merely because its BaseVehicle reference is temporary.
BaseVehicle.load invokes superclass loading and VehiclePart.load. Parts load
items, containers and Lua mod data; shared registries/readers must be audited.
The native physics update and live registration belong to the integration stage.

Relevant sources: VehicleFullUpdatePacket.java:46, BaseVehicle.java:2740,
VehiclePart.java:608. The worker's private packet/scratch data cannot substitute
for ownership of the live vehicle/controller/world state.

### Zombies

ZombieSynchronizationPacket.parse changes the client send timer and invokes
NetworkZombieSimulator.receivePacket. parseZombie uses shared zombiePacket
scratch, VirtualZombieManager.choices, zombie creation/pooling, the client ID
registry, live position/visual/AI/state changes, and world moving/zombie lists.
Some missing zombies are registered before the subsequent initialization steps.
ThreadLocal scratch would address only scratch ownership; it would not isolate
these live effects from main-thread traversal and updates.

Relevant sources: ZombieSynchronizationPacket.java:20,
NetworkZombieSimulator.java:141/151. Never infer a native/world threading
contract from a method being named parse rather than update.

### Practical design

Use an owned byte payload and bounded preparation queue. A dedicated worker
decodes explicit field schemas into detached records, preserving full-state
sections and variable-length boundaries. Publish the completed plan, then resolve
current IDs/squares and integrate on the main owner, with connection generation
and entity-lifetime validation. Keep create/update/delete and reliable dependency
order. Parallel ordinary updates must not overtake pending full creation.

Lua/native/world initialization stays on the owner unless independently isolated
and validated. If creation/initialization dominates, a decoder worker alone will
not remove the stall; consistent bounded integration or cheaper reconstruction
may matter more. A global lock around complete parsing would make participating
main readers wait, and uncoordinated readers would still race.

## Sustained cost ranking

Same 5755 frames, mean 20.997 ms of owner work. These are wall timings and are
not GPU execution measurements. Parent phases overlap their children.

| Boundary | Average/frame | Interpretation |
| --- | ---: | --- |
| World cell rendering | **8.373 ms** | Parent for the cell renderer; 39.9% of frame work |
| Tile drawing | 7.409 ms | Nested within cell rendering |
| Unassigned tile-pass remainder | **about 3.180 ms** | Derived residual; exact callee unresolved |
| Translucent objects | **2.992 ms** | Current visibility/object/item/outline rendering, includes ordering helper |
| Chunk rendering | 0.802 ms | Includes 0.726 ms/frame rebaking; do not add rebaking again |
| Moving-object update | **2.325 ms** | Nested inside WORLD_CELL update, not a rendering timer |
| Moving-object post-update | **1.431 ms** | Separate scheduler post-update boundary |
| UI rendering | **1.937 ms** | Includes frame handoff/waits; separate from UI update |
| Lighting phase | **1.442 ms** | Main lighting boundary; excludes the separately nested cell-lighting probe |
| World physics | **1.048 ms** | WorldSimulation.update |
| OnTick dispatch | **0.998 ms** | Rare severe callback stalls coexist with modest mean |
| UI update | 0.709 ms | Separate from UI rendering |
| NETWORK | **0.266 ms** | Specific bursts matter, but only 1.27% of total frame work |

The moving update and post-update boundaries total 3.756 ms/frame without adding
their WORLD_CELL/UPDATE_WORLD parents. WORLD_CELL itself averages 3.111 ms,
which already contains the 2.325 ms scheduler update. These averages describe
measured owner paths, not guarantees that their complete bodies can be offloaded.

The tile residual subtracts the distinct measured chunk, corpses, items, moving
object rendering, translucent floor/object and fog methods from CELL_DRAW_TILES.
Source call-site review shows these methods occur within performRenderTiles and
are separate siblings for this subtraction. It is a residual including probe/
loop overhead and the remaining operations, not a direct timing of one function.
It is about 15.1% of frame work and roughly as large as translucent objects.

Source places these unseparated candidates in that remainder: FBO manager/frame
work, corpse/item renderer updates, player/corpse/mannequin shadows, puddles,
water/shore, opaque render events, animated attachments, flies, highlighting,
rain splashes, shadow submission, visibility polygon work, markers and outlines.
The current telemetry cannot assign the 3.18 ms between those callees. In
particular, it cannot yet prove shadow or visibility polygon work dominates.

Translucent ordering catch-up costs only 21.637 ms over the whole capture,
0.00376 ms/frame. The entire ordering helper costs 0.1506 ms/frame. Neither
explains the full 2.992 ms translucent pass. Texture main mip selection is zero.
Further tuning of those already-small preparation costs is not the largest
measured sustained opportunity.

## Large-stall ranking

- Building reconciliation: 253.050 and 272.123 ms in frames 32 and 2889;
  removal/creation dominate, and the latter frame is CPU-active with negligible
  network cost. This is independent of the three packet parsers.
- BirdSoundSystem OnTick: 188.905/186.855 ms in frames 346/395, versus 31.25 ms
  sampled frame CPU each. Later callbacks reach 110.231/126.679 ms. Investigate
  nested sound/zone/emitter calls and waits; wall timing does not establish that
  equivalent Lua computation can be moved to a worker.
- Item rendering: 113.801 ms with 112.620 ms render-context wait in frame 39.
  The counters associate the delay but do not identify its issuing operation.
- Network: animal dispatch 115.415 ms and later 56.343 ms; zombie synchronization
  107.429 ms; full vehicle update 33.111 ms. Their PARSE stages include the live
  integration detailed above.
- Rebaking/chunk rendering can still burst (frame 38: 93.289/94.317 ms aggregate),
  despite modest averages. Render-context wait in that frame is 71.435 ms, so the
  complete duration is not equivalent pure CPU work to move to a worker.

The aggregate GC pause counters are only 2 ms and cannot explain these stalls.
Concurrent-cycle timings are not stop-the-world pause durations. No sample here
establishes an exact GPU bottleneck or scheduler cause.

## Next measurement and implementation boundary

The next useful probe pass should cover, together:

1. The unassigned tile-pass operations, plus translucent merge/visibility/object/
   item/outline drawing, with candidate/rendered counts and render-wait callers.
2. Moving update/post-update costs by object category and physics collision work;
   do not attribute all movement to the loaded-zombie count.
3. Animal full-state/ordinary/delete branches, constructors, field decode, load,
   initialization and attachment; analogous new/existing vehicle and zombie paths.
4. Building removal/creation substeps and workload sizes; BirdSoundSystem nested
   paths and off-CPU correlation as independent concerns.

Then implement a detached worker decoder for the measured pure preparation
portion, with a defined owner integration boundary. The existing capture is
enough to reject wholesale unmodified-parser offload and to prioritize these
boundaries, but not enough to claim exactly how much of each parser is safely
parallelizable. No runtime source, installed file or mod was changed in this review.
