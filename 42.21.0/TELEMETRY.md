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

## Moving-object algorithm patch

The 42.21.0 server scheduler now retains phase-bucket membership between frames.
One authoritative world-set pass still checks activity every frame, promotes or
demotes vehicles/animals immediately according to the existing predicates, and
populates shared animal/perception views. Stable members are not cleared and
reinserted. Direct removals that bypass the scheduler are reconciled when the
world pass detects missing members; cell changes reset retained membership.

Server removal uses identity lookups to the object's level and slot. Removed
slots become null until stable compaction at the next frame boundary. Update and
postupdate skip those slots, preventing self-removal from skipping the successor.
Optional threaded animation is joined before changing frame membership; scheduler
mutation and execution share a reentrant monitor. Normal client classification
and per-frame bucket rebuilding remain in place. Persistent server buckets retain
their existing order until members enter/leave/change level, rather than adopting
the HashSet's full iteration order again on every frame.

Server animal perception traverses shared zombies/human-player candidates rather
than every animal, vehicle, prop, or physics object. Candidates retain world-set
order at classification time. Existing height, square, visibility, ghost/grapple
checks, distances and spotted callbacks remain in place; no distance cutoff or
additional perception throttle is introduced. Removed targets are rejected by
current world membership. Normal immediate additions invalidate the view; size
increases also trigger a refresh. Mods replacing entries directly through the
exposed Set at equal cardinality during a frame should call
`ServerMovingObjectIndex.invalidate()`; otherwise those additions appear at the
next frame's classification pass.

Server animal sound work uses the shared animal view and is distributed every four
frames by ID. Set `-Dapocbr.animalSoundFrameMod=1` to restore its previous cadence,
or another positive interval to change this cosmetic-work throttle. Client sound
cadence and existing car/animal simulation frequencies are retained.

New inclusive phases:

- `simulation.movingObjects.classify`: index maintenance, dirty-bucket compaction,
  classification and membership reconciliation; excludes preceding zombie waits.
- `simulation.movingObjects.update`: scheduled moving-object updates.
- `simulation.movingObjects.postupdate`: scheduler postupdate/animation; can run
  outside the parent simulation timing with optional threaded animation.
- `simulation.animals.perception`: individual animal perception calls, nested in
  moving-object update. Do not add it to its parent as an independent cost.
- `simulation.animals.sounds`: server animal sound dispatch, including any index
  refresh required by immediate additions.

Counters `movingObjects.classified`, `movingObjects.schedulerAdded`,
`movingObjects.schedulerLevelChanges`, `movingObjects.schedulerRemoved`, and
`animals.perceptionCandidatesVisited` are interval totals. Classification sums
the world size per frame, not unique objects. Removed counts cover registered
members removed through the scheduler API; direct-set reconciliation is excluded.

Verification: `python tools/test_moving_object_algorithms.py` exercises production
scheduler/bucket/index sources using isolated engine fixtures. Run the normal
42.21.0 patch script with `-DryRun` for game-JAR compilation. Live scenarios still
needed: large herds, vehicle starts/stops and towing, animal alert/lure/hook changes,
mass despawn/chunk unload, threaded animation if enabled, and before/after
telemetry at comparable populations. Fixture timing is not a server benchmark.
