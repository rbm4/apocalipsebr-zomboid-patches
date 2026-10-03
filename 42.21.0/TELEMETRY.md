# Server telemetry (schema 2)

This extends the existing server NDJSON telemetry in the 42.21.0 patch tree.
Compile against the intended build before deployment; compilation against a local
JAR does not establish compatibility with a differently versioned live server.
The payload includes the runtime game version to remove ambiguity in captures.

Existing properties remain available. Output defaults to `apocbr-telemetry.ndjson`.
JVM settings: `-Dapocbr.telemetry.intervalMs=30000`,
`-Dapocbr.telemetry.path=/path/to/apocbr-telemetry.ndjson`, and
`-Dapocbr.telemetry.lua.enabled=true` for optional Lua event timings.
Emission happens at the end of an outer server loop; disk writes remain on the
bounded asynchronous writer queue. `dropped` counts lost telemetry records.

## Interpretation

- `intervalMs`: actual elapsed reporting window.
- `tick`: completed simulation cycles. `avgMs` includes worker waiting and packet
  processing in the outer iteration that ran simulation, but excludes preceding
  packet-only iterations. It is elapsed time, not CPU time.
- `overBudgetTicks`: recorded cycles exceeding 100 ms.
- `loop`: all outer iterations, including packet-only iterations and their sleep.
  `totalMs` excludes telemetry emission; `telemetry.emit` reports that cost in the
  following reporting window.
- `phases`: calls, totalMs, average per call, and maximum per call. Names describe
  measured call sites. Timings are inclusive: never sum all phases as though they
  were independent. Lua timings also overlap their containing Java phase and may
  overlap other Lua events through nesting.
- `counters`: interval totals, reset after emission. Missing counters mean no
  observations yet. Packet drained counts include packets subsequently dropped;
  `packets.dropped` reports drops by the normal-queue overload branch only.
- `world`: end-of-window gauges. `loadedCells` is the engine list size, including
  cells pending activation; `pendingCells` is the loading list size. Zombies and
  movingObjects are active cell list sizes, not virtual population totals.
  `movingObjectsByClass` groups that same moving-object set by full Java class
  name, sampled once per emission (including animals separately from players).
- `jvm`: used/committed/max heap bytes and GC collection count/time deltas. GC time
  comes from MXBeans; it is not a precise sum of stop-the-world pauses.

## Phase relationships

`network.zombies.awaitAndSend` contains `network.zombies.workerWait` and
`network.zombies.send`. `map.postupdate` can also contain these zombie phases,
plus `network.zombies.auth`, `map.cell.update`, `map.cell.unload`,
`map.losSuspendWait`, and `map.saveCompletions`.

`map.preupdate` contains `map.cell.integrate`, `map.cell.loadVehicles`,
`map.losSuspendWait`, and `map.saveAll` when those operations occur.
`simulation.update` contains the IngameState world simulation and its Lua events.
Other top-level phases cover incoming networking, collision state, vehicles,
RCON, player relevance/chunk downloads, player networking, social systems,
backups, and asynchronous filesystem/world-map updates. Smaller uninstrumented
operations and idle sleep can leave a remainder in `loop.totalMs`.

`chunks.newIntegrated` and `chunks.existingIntegrated` classify chunks using the
engine's `isNewChunk()` (the addZombies flag), immediately before grid integration.
They count integration attempts, not successful background generation jobs or
unique coordinates. `cells.integrated` counts completed cell integrations;
`cells.unloaded` counts completed unload calls. These metrics do not directly
measure background disk read, world generation, or recalculation worker duration.

## Diagnosing a fresh save

Capture several windows while players explore, then while the same players stay
in already visited areas. Compare new chunk integrations and pending cells with
`map.preupdate`/`map.cell.integrate` time. A simultaneous fall in new integrations,
map integration time, and tick overruns supports generation/streaming as a cause.
Large `map.cell.update` totals instead suggest ongoing loaded-world work.
Large zombie worker wait/send totals point toward zombie synchronization.
High GC deltas need a JVM recording to distinguish pauses from concurrent work.

Do not identify a mod from an event aggregate alone: the Lua metrics time all
callbacks together. Live validation is still required for overhead, threading,
and the actual bottleneck. No scheduling, packet, save, or gameplay behavior is
intentionally changed by this instrumentation.

## Moving-object lifecycle indexes

The 42.21.0 server classifies an object on successful insertion into the active
`IsoCell.objectList`, using its immutable Java type only. This can occur inside a
constructor; subclass activity fields are not read until scheduling at a frame
boundary. Constructed objects that never enter the active set are not indexed.
`ServerMovingObjectSet` preserves the exposed Set API and observes direct add,
remove, iterator removal, addAll, removeAll, retainAll, removeIf and clear. Duplicate
additions create no extra entry. Each removal/re-addition creates a new active
lifetime, even when a pooled instance retains its identity or changes its ID.

Indexes are owned by each cell, with persistent views for schedulable non-zombies,
animals, and perception targets (zombies and human players). No full-world server
classification or reconciliation pass runs per frame. The scheduler checks only
preclassified schedulable members for activity/minimum-level changes, using the
existing vehicle and animal predicates. Server-GUI zombie updates run separately
only when that GUI is enabled. Client render/distance scheduling retains its
world pass. Telemetry's class histogram still samples the whole set once per
reporting window, not per frame.

Server buckets remain persistent. Removals use identity lookups and leave empty
slots until compaction at a frame boundary. Only affected type views/buckets are
compacted. Removed lifetime entries immediately become inactive; update and
postupdate reject them even if the same object is re-added before the next frame.
Lifecycle removal queues drain at frame/update/postupdate boundaries. Newly
inserted objects join scheduling at the next frame, while perception indexes
reflect insertion immediately. Optional threaded animation is joined before
changing frame membership; scheduler mutation/execution share a reentrant monitor.

Perception and bucket traversal now retain active-lifetime insertion order rather
than re-adopting world HashSet order each frame. Existing height, square,
visibility, ghost/grapple checks, distances and spotted callbacks remain; no
spatial cutoff or extra perception throttle is introduced. Perception views can
contain temporary empty slots until compaction; callers skip removed targets.
Direct same-cardinality Set replacements no longer require manual invalidation.

Server animal sound work is distributed every four frames by ID. Set
`-Dapocbr.animalSoundFrameMod=1` to restore its previous cadence. Client sounds and
existing car/animal simulation frequencies are retained.

Inclusive phases:

- `simulation.movingObjects.classify`: successful lifecycle insertion/classification
  work, measured at the Set boundary; may be inside chunk integration or spawning.
- `simulation.movingObjects.lifecycle`: removal-queue draining and dirty compaction
  at the frame boundary; excludes preceding zombie waits.
- `simulation.movingObjects.activity`: preclassified non-zombie scheduling checks
  and membership changes; no full-world type discovery.
- `simulation.movingObjects.update`: scheduled moving-object updates.
- `simulation.movingObjects.postupdate`: scheduler postupdate/animation; can run
  outside the parent simulation timing with optional threaded animation.
- `simulation.animals.perception`: individual perception calls, nested in object
  update. Do not add it to its parent as an independent cost.
- `simulation.animals.sounds`: server animal sound dispatch.

`movingObjects.classified` now counts successful active-world insertions in the
interval, including re-entry of pooled objects. It is no longer a sum of repeated
frame visits: zero is expected in stable windows. `movingObjects.activityChecked`
counts scheduling checks on preclassified non-zombies. Scheduler added/removed/
level-change counters and `animals.perceptionCandidatesVisited` remain interval
totals. Scheduler removal counts now also include lifecycle queue removals.

Verification: `python tools/test_moving_object_algorithms.py` exercises production
Set/index/scheduler/bucket sources using isolated engine fixtures. The tests cover
bulk and iterator mutations, same-cardinality replacements, duplicate additions,
pooled re-entry, zero frame reclassification, removal during callbacks, activity
changes, cell reset, and threaded frame transitions. Run the normal 42.21.0 patch
script with `-DryRun` for game-JAR compilation. Live validation remains necessary
for large herds, vehicle starts/stops/towing, animal alert/lure/hook changes, mass
despawn/chunk unload, and threaded animation if enabled. Fixture timing is not a
server benchmark.
