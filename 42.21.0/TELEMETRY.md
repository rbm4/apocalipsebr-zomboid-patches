# Server telemetry (schema 3)

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

## Nested phase output

Schema 3 replaces the schema 2 flat `phases` array with a nested object. Each
dot-separated phase name becomes a path: `simulation.vehicles.update.checks`
is emitted at `phases.simulation.vehicles.update.checks`. Shared prefixes appear
once, sibling keys are sorted, and redundant full `name` strings are omitted.
Output remains compact NDJSON (one JSON object per line).

For example (formatted here for readability):

```json
{
  "schemaVersion": 3,
  "phases": {
    "simulation": {
      "vehicles": {
        "update": {
          "calls": 100,
          "totalMs": 20.000,
          "avgMs": 0.200,
          "maxMs": 0.500,
          "checks": {
            "calls": 100,
            "totalMs": 5.000,
            "avgMs": 0.050,
            "maxMs": 0.100
          }
        }
      }
    }
  }
}
```

Measured parents retain their own `calls`, `totalMs`, `avgMs`, and `maxMs`
alongside child objects. Unmeasured prefixes only group children; they receive
no synthetic totals. Phases with no calls in a window are omitted, even if they
were measured in earlier windows. Phase-name segments must not use the reserved
timing field names (`calls`, `totalMs`, `avgMs`, `maxMs`).

The tree represents naming prefixes, not proven timing containment. Existing
phase names and measurement boundaries are unchanged; consult the relationships
below before calculating shares or summing timings. Counters and Lua event
output retain their existing layouts. Consumers must check `schemaVersion`:
read the flat array for schema 2, or recursively traverse the object for schema 3
and join its keys with dots to recover the original phase names. Historical
schema 2 records may share an appended NDJSON file with new schema 3 records.

Run `python tools/test_telemetry_output.py` to verify the production emitter with
isolated engine stubs, including measured parents with children, unmeasured
prefixes, deterministic ordering, escaping, and resets across reporting windows.

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

## Zombie ownership scheduling and coverage

`network.zombies.auth` remains the inclusive frame phase. It includes persistent
player coverage reconciliation, zombie ownership checks, and packet preparation
for every active zombie. Packet preparation and send cadence are unchanged.

The child phases are:

- `network.zombies.authGrid`: reconciliation of player coverage and periodic
  cleanup of scheduling entries for zombies no longer in the active object set.
- `network.zombies.ownership`: sum of per-zombie ownership-check durations for
  this pass, including cooldown/cadence early exits. One call is recorded per pass;
  its max is a pass aggregate, not a maximum individual zombie duration.
- `network.zombies.packetPrepare`: sum of packet preparation durations for the
  same pass, likewise recorded once per pass.

Stable owned zombies reassess on staggered 100 ms slots with at most 500 ms
between due times. This is a wall-clock schedule: actual processing can be later
if server ticks are delayed. Target, grapple, owner, or online-ID changes bypass
the stable cadence. Ownerless zombies, invalid/disconnected owners, and death
also bypass it. Vanilla's two-second transfer cooldown remains for valid living
owners, including target/grapple changes during that cooldown. Invalid owners
and death bypass the transfer cooldown. The debug ownership-rotation option
retains its original behavior. Scheduling entries are pruned approximately once
per second when their zombie leaves the active object set.

The 64-tile player auth grid persists between ticks. A lightweight pass over the
connection/player slots detects changed coverage endpoints, relevance ranges,
alive/eligible state, slot replacement and disconnects. Unchanged candidates and
bucket lists are retained. Changed coverage alone is unlinked/relinked. Exact
distance comparisons continue reading current player positions. Candidate order
follows connection/player-slot order, including connection reorder events, to
preserve the existing ownership selection and distance hysteresis.

Ownership groups now use identity-indexed, doubly linked membership: removal is
constant time and iteration preserves insertion order for authorization hashes.
Request queues retain their existing LinkedList/FIFO implementation. Transfers,
ground-death cleanup, disconnect reassignment and invalid-ID predicate removal
all update the ownership index under the existing ownership lock. Empty groups
are released when ownership is removed or disconnected.

Interval counters:

- `zombies.auth.cadenceSkipped`: stable checks deferred to their next due time.
- `zombies.auth.transferCooldownSkipped`: checks blocked by the transfer cooldown.
- `zombies.auth.reassessed`: ownership selection attempts, not owner changes.
- `zombies.auth.candidatesVisited`: player candidates examined in local auth cells.
- `zombies.auth.ownerChanged`: actual non-dead owner transfers.
- `zombies.auth.coveragePlayersChecked`: player slots checked by reconciliation,
  including empty slots.
- `zombies.auth.coverageChanged`: candidate creation/removal or changed coverage;
  zero is expected with unchanged eligibility, ranges and coverage boundaries.

`python tools/test_zombie_auth_algorithms.py` runs production manager, coverage,
and ownership-index sources against isolated engine fixtures. It checks cadence
boundaries, transfer cooldowns, target/grapple/owner changes, debug rotation,
death/disconnect cleanup, identity removal and stable order, coverage reuse,
range/eligibility changes, and randomized conservative coverage queries. Runtime
validation remains necessary for combat, grappling, vehicle passengers, split
screen, reanimated players, disconnect/reconnect and large ownership migrations.

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

Perception and bucket traversal retain active-lifetime insertion order rather
than re-adopting world HashSet order each frame. Server animal perception uses
persistent 16-tile spatial buckets for zombies, querying only candidates within
10 tiles (inclusive). Vanilla zombie stress/flee reactions are bounded by this
radius. Animals with `fleeZombies=false` skip zombie queries; human-player
candidates remain unrestricted because their detection includes distant branches.
Existing height, square, visibility, ghost/grapple and death checks remain.

Zombie bucket membership is reconciled once per frame that needs perception,
after the scheduler has awaited zombie workers. This traverses the preclassified
target list once, not once per animal; no grid is rebuilt. Only zombies crossing
bucket boundaries are relocated. Target insertion invalidates the refresh stamp;
removal immediately unlinks the exact spatial slot, including pooled re-entry.
Queries keep active-lifetime target order and use reusable candidate buffers.
Clients retain their original perception traversal.

Server perception clears `spottedChr` and decrements `lastAlerted` once per
perception cycle, using the animal's scheduler-adjusted game multiplier. This is
an intentional correction: vanilla performed both operations for every target,
so distant population affected cooldowns and could erase a nearby threat. All
qualifying local candidates still receive their individual stress/flee reactions;
public `spotted` calls outside this server traversal preserve vanilla bookkeeping.
No additional perception throttle is introduced: calm animals update every 16
frames, and active animals every 2 frames under the existing scheduler predicates.

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
- `simulation.animals.perceptionGrid`: position reconciliation, nested in the first
  perception query of a frame (or a subsequent query after target insertion).
- `simulation.animals.sounds`: server animal sound dispatch.

`movingObjects.classified` now counts successful active-world insertions in the
interval, including re-entry of pooled objects. It is no longer a sum of repeated
frame visits: zero is expected in stable windows. `movingObjects.activityChecked`
counts scheduling checks on preclassified non-zombies. Scheduler added/removed/
level-change counters and `animals.perceptionCandidatesVisited` remain interval
totals. Scheduler removal counts now also include lifecycle queue removals.

`animals.perceptionGridChecked` counts zombie positions examined during grid
maintenance; `animals.perceptionGridRelocated` counts initial spatial registration
and bucket crossings. `animals.perceptionBucketCandidatesVisited` counts zombies
examined in local buckets before the circular distance filter.
`animals.perceptionCandidatesVisited` now counts local zombie and unrestricted
human candidates returned to animal perception. Add grid checks and bucket visits
when comparing total lookup work with earlier whole-target traversal counters;
their definitions differ. These counters do not count distinct objects.

Verification: `python tools/test_moving_object_algorithms.py` exercises production
Set/index/scheduler/bucket sources using isolated engine fixtures. The tests cover
bulk and iterator mutations, same-cardinality replacements, duplicate additions,
pooled re-entry, zero frame reclassification, removal during callbacks, activity
changes, cell reset, threaded frame transitions, spatial boundary crossings,
negative coordinates, inclusive radius, global humans, and pooled spatial re-entry.
Randomized spatial queries are compared with an exhaustive distance reference;
a sparse 30,000-zombie fixture verifies one movement pass for 100 local queries.
Run the normal 42.21.0 patch
script with `-DryRun` for game-JAR compilation. Live validation remains necessary
for large herds, vehicle starts/stops/towing, animal alert/lure/hook changes, mass
despawn/chunk unload, density-independent alert cooldowns, local zombie stress and
fleeing, distant human detection, and threaded animation if enabled. Fixture timing is not a
server benchmark.

## Simulation work selection and child timings

The server entity systems now obtain candidates through persistent family-bucket
indexes. Meta phase membership is maintained at bucket insertion/removal and
component changes; ID load/reset also refreshes it. Selected candidates retain
the current engine bucket order, including its swap-removal behavior. The index
also removes indexed bucket members by their known position. Group order is
sorted only after mutation; a merge of selected groups preserves bucket order
without sorting all selected entities on every pass. The existing
`MetaSimulationThrottle` still computes each entity's effective elapsed ticks.
It runs every ten simulation ticks by default, not necessarily ten server frames.
Loaded entities retain their original cadence. Drying-craft detachment candidates
remain checked every simulation pass before throttling. Unknown entity subclasses
retain the exhaustive checks. Client and single-player family views are unchanged.

`ProcessIsoObject` retains its mutable list traversal, ID-derived phase checks,
trap/generator exceptions and per-object multiplier. Square object-list `indexOf`
queries now reuse an identity-position index on server lists of at least eight
entries when the queried type uses Object identity equality. Every supported list
mutation invalidates that cache; duplicates retain their first position and custom
equality retains vanilla lookup. Exposing the backing array disables caching until
array reallocation, because retained raw arrays can be changed without list calls.
This reduces repeated square scans; it does not remove the world-object frame scan.

Animal synchronization uses persistent 64-tile connection coverage for changed
animal routing. Both connect-area rectangles and all four relevance positions are
covered, including connections with no live player object. Exact `RelevantTo`,
screen-distance, timer and extra-update checks still run in the sender. Candidate
order follows the original changed-ID iteration order. Overlapping coverage is
deduplicated per animal/connection; disconnects and coverage changes unlink old
entries. Oversized or unrepresentable coverage uses a bounded full-candidate
fallback. Packet batches retain the 150-animal limit and reliable cadence.
Overflow batches now use independent input sets: vanilla recursively passed the
packet's own pending set into a call that cleared it, losing overflow updates.
Packet declarations and serialization are unchanged.

Lua callbacks retain the public ArrayList field and backed-view mutation behavior.
Dispatch skips the post-callback linear membership check only when no structural
or replacement mutation occurred. Mutated lists retain the vanilla check and loop
adjustment, including duplicate callbacks and self-removal. The server also skips
the client-only zombie vocal traversal, skips zero/fulfilled zombie culling quotas
without consuming additional RNG, and counts building room tiles using list sizes.
Fake-dead corpse checks reject ineligible or out-of-annulus players before LOS,
while retaining current player traversal, final visibility checks, and all decay,
random skeleton trials and reanimation timing.

New inclusive phases:

| Phase | Measured work |
| --- | --- |
| `simulation.world` | IsoWorld update, including cell, physics and auxiliary world work |
| `simulation.cell` | IsoCell update, including scheduling, items, IsoObjects and corpses |
| `simulation.isoObjects` | ProcessIsoObject phase filtering and update bodies |
| `simulation.items`, `simulation.staticUpdaters`, `simulation.spottedRooms` | Respective cell processing passes |
| `simulation.corpses`, `simulation.fishing` | Corpse and fishing update calls |
| `simulation.entities` | Entire GameEntityManager.Update call |
| `simulation.entities.frame`, `simulation.entities.simulation` | Engine frame and simulation passes |
| `simulation.entities.system.<ClassName>` | Individual simulation-system calls; excludes subsequent queued engine operations |
| `simulation.animals.sync`, `simulation.animals.syncCoverage` | Animal synchronization and its coverage/routing child |
| `simulation.physics` | Full WorldSimulation call, including native data readback |
| `simulation.physics.prepare`, `simulation.physics.step` | Java vehicle preparation and native Bullet step, one call per fixed substep |
| `simulation.vehicles.network` | VehicleManager call inside IsoWorld; separate from the outer `vehicles.update` call |
| `simulation.contacts`, `simulation.hutches`, `simulation.climate` | Collision contact, hutch and climate updates |
| `simulation.buildings`, `simulation.databases`, `simulation.animals.virtual`, `simulation.designationZones` | Auxiliary world passes |
| `simulation.animation.inline`, `simulation.animation.wait` | Inline postupdate and joins of optional previous animation work |
| `simulation.misc` | IngameState.UpdateStuff, including the following population/sound/fire phases |
| `simulation.worldSounds`, `simulation.fire`, `simulation.zombies.virtual`, `simulation.zombies.population` | Respective UpdateStuff manager calls |
| `simulation.collision.main`, `simulation.pathfinding.nativeMain`, `simulation.pathfinding.javaMain`, `simulation.lootRespawn` | Respective main-thread manager calls; only the selected pathfinding implementation executes |
| `simulation.zombies.cullPrepare` | Scheduler-start culling preparation, preceding lifecycle/activity timings |
| `simulation.radio`, `simulation.onTick`, `simulation.managers` | Radio, OnTick dispatch and transaction/action/ping manager updates |

With optional threaded world work, buildings/databases/virtual-animal timings can
overlap cell work. Animation waits can also be measured outside this parent at
other callers. Do not sum these phases indiscriminately or equate their elapsed
durations with main-thread CPU use. Physics step calls can substantially exceed
simulation.update calls; fixed 10 ms integration and step count are unchanged.

Interval work counters:

- `entities.simulationCandidates`, `entities.simulationSkippedByIndex`: selected
  candidates and omitted members per indexed system pass, including repeated
  observations of one entity across systems. Drying cleanup candidates can still
  be rejected by the original throttle after their cleanup check.
- `entities.simulationIndexSorted`: group entries sorted after membership/order
  mutations; stable selected groups contribute zero.
- `isoObjects.checked`, `isoObjects.updateAttempts`,
  `isoObjects.alwaysUpdateAttempts`: actual list visits and attempted updates;
  always-update attempts are a subset of all attempts.
- `isoObjects.squareIndexRebuilt`, `isoObjects.squareIndexEntries`: cache rebuilds
  and entries indexed, including lookups outside ProcessIsoObject or simulation.
- `animals.sync.coverageChanged`: changed coverage rectangles, not connections.
- `animals.sync.coverageCandidatesVisited`: spatial bucket entries plus fallback
  entries examined before overlap deduplication and exact relevance filtering.
- `animals.sync.candidatePairs`: deduplicated connection/animal pairs routed to
  sender checks, including conservative false positives.
- `animals.sync.packets`, `animals.sync.updated`: server batches sent and update
  IDs included in those batches; update counts can repeat across connections.
- `corpses.checked`: complete corpse-registry entries copied for decay processing.
- `physics.substeps`: fixed Bullet steps, not outer simulation frames.

`python tools/test_simulation_algorithms.py` exercises production entity buckets,
meta identity/throttle/index, Lua dispatcher, mutable square lists, animal coverage
and synchronization manager. It compares entity selection/order/owed ticks and
callback mutations with exhaustive traversal, tests pooled re-entry, backed views,
raw arrays and custom equality, compares randomized coverage with the exact vanilla
relevance predicate, verifies packet overflow/requests/deletions/urgent timers, and
compares the extracted production corpse predicates with vanilla. Scale fixtures
check 30,000 meta members, zero stable callback membership searches and sparse
30,000-animal routing across 100 connections. These are algorithm fixtures, not
live server performance measurements. Run the normal patch script with `-DryRun`
for real game-JAR compilation. Live checks still cover chunk unload/reload,
craft/resource transitions, mass corpse decay and fake-dead wakeups, large herd
sync, split-screen/reconnect, and optional threaded animation/world work.


## Production hot-spot breakdown (sequences 127/128 follow-up)

See [PRODUCTION-HOTSPOTS.md](PRODUCTION-HOTSPOTS.md) for source findings,
production measurements, optimization constraints and validation.

Moving-object callback chains now have fixed `simulation.movingObjects.update`
and `.postupdate` children for `zombie`, `vehicle`, `animal`, `player`, `giblet`
and `other`. The corresponding `movingObjects.updateAttempts.<kind>` and
`postupdateAttempts.<kind>` counters count attempted chains, including throws.
Phase calls describe per-class **bucket batches**; divide totalMs by attempt
counts to estimate mean object cost. Class dispatch retains the existing order,
mutation checks and scheduling. Animation inline includes postupdate.

Entity frame callbacks appear under `simulation.entities.frameSystem.<Class>`.
`frameOperations` and `simulationOperations` measure entity/component queue drains
at their original post-system points. `frameSystemOperations` and
`simulationSystemOperations` measure pending system registration changes.
`simulation.misc.gameTime`, `.scripts`, `.rain` and `.meta` provide
selected children of UpdateStuff; gameTime includes periodic Lua event execution.

`map.chunk.unloadSquares` now contains `cleanup`, `moving`, `isoObjects`,
`staticObjects` and `detach` children, aggregated per chunk, not per square.
`chunks.unloadedSquares`, `.unloadedIsoObjectAttempts` and
`.unloadedStaticObjectAttempts` report non-null squares and actual object-loop
visits. Existing `.unloadedMovingObjects` reports starting moving-list entries.
Incomplete exceptional removal does not flush these aggregate child metrics.
`map.chunk.unloadVehiclePersistence` and `.unloadSaveEnqueue` measure subsequent
ServerCell.Unload calls; they do not measure asynchronous save completion.

`jvm.collectors` adds per-collector `name`, `count` and `totalMs` interval deltas.
This is additive to existing `gcCount`/`gcMs`; collector time can include concurrent
work and is not a direct stop-the-world pause measurement. Bean readings occur
sequentially, so collection completions at window boundaries can cause slight
aggregate/per-collector differences.

Optional JVM flag `-Dapocbr.telemetry.squareLookups.enabled=true` adds counters
`isoObjects.squareLookup.rawArrayExposed`, `.smallList`, `.customEquality`,
`.cacheEligible` and `.listEntries` for server IsoObject-list `indexOf(Object)`
queries. Reasons are mutually exclusive in that order; listEntries sums queried
list sizes, not comparisons. Diagnostic counters are disabled by default to avoid
counter overhead on each lookup. Other lookup overloads are not instrumented.


## Using-player active index

The dedicated-server `UsingPlayerUpdateSystem` now traverses entities whose
using-player field is non-null. Membership is updated at bucket admission/removal
and every vanilla assignment (setter, receive packets, synchronization and reset).
The first indexed pass bootstraps the existing bucket once; later frames do not
rescan idle entries. Vanilla distance bounds, level/death checks, validity guards,
packet sends, callback order and per-frame cleanup cadence are retained. Client
update remains a no-op and single-player traversal stays exhaustive.

The index tracks current bucket positions, including swap removal, and queries
live ordered successors. Callbacks activating a later entry are observed in the
same pass; activation of an already-passed entry waits until the next frame.
Classes overriding `getUsingPlayer` or `isValidEngineEntity` remain candidates
every frame to preserve getter behavior and assignments outside the base setter.
Owner links are removed when the entity leaves a bucket; no static entity map
retains old engine lifetimes. The index uses O(N) membership storage, one O(N)
bootstrap, O(log A) active membership changes and O(A log A) per-frame traversal,
where A includes conservative override candidates.

`entities.usingPlayer.bucketEntriesAtStart` sums full bucket sizes before passes;
`candidatesVisited` counts actual indexed visits (including exception attempts);
`fallbackCandidatesVisited` is the subset with overridden accessors;
`cleared` counts completed cleanup calls;
`indexBootstrapped` counts members visited during one-time index creation; and
`indexMembershipChanges` counts selection admissions/removals, not position moves.
All names have the `entities.usingPlayer.` prefix. Compare these with
`simulation.entities.frameSystem.UsingPlayerUpdateSystem`; bucket entries minus
visits estimates avoided checks only when membership stays stable during a pass.

`python tools/test_using_player_algorithms.py` compares the exact vanilla system
with production bucket/index/system code and extracted production GameEntity
mutation methods. It covers randomized membership/assignment transitions,
cleanup ordering, boundary/NaN values, packet ownership, client/SP behavior,
reset/reuse, callback activation/removal/failure, override fallback and coexistence
with the meta index. The 30,000-member / 35-active fixture verifies 10,500 visits
across 300 frames, instead of 9,000,000 full-scan visits; this is not a live timing
benchmark. Live checks should exercise crafting-lock release on death, movement,
level changes, chunk unload/reload and reconnect.


## Player update and zombie packet preparation optimizations

The human moving-object update timer includes preupdate, ECS frameStep and the
complete player/character update. New `simulation.players.*` phases split:
`preupdate`, `frameStep`, `internal`, `base`, `health`, `stats`, `inventory`,
`thermalClothing`, `nearVehicle`, `vehicleGrid`, `los`, `losRooms`, `losZombies`,
`losGrid` and `soundStress`. Health/stats/inventory are nested inside base;
thermalClothing is inside health; LOS/proximity are inside internal; room and
zombie LOS application are inside los. Do not sum children with their parents.
Phase calls are method invocations; use totalMs divided by tick.count to compare
cost per tick. Player proximity is now queried only when needed for sneaking.

`players.inventory.discoveryEntries` and `indexRebuilt` count lazy route builds;
`routesVisited` counts containers/updaters; `updateAttempts` counts item callbacks.
`fallbackEntries` and `mutationFallback` show external lists and mutation-during-
callback paths. `players.thermal.clothingRebuilt` should stay low for unchanged
clothing across multiple players. `players.vehicles.gridEntriesChecked` and
`candidatesVisited` distinguish shared vehicle maintenance from nearby checks.
`players.los.gridEntriesChecked` and `bucketCandidates` do the same for zombie
LOS candidates. `players.soundStress.discoveryEntries` and `candidatesVisited`
separate sound discovery from stress evaluation.

Zombie preparation timing changed location: `network.zombies.packetBookkeeping`
is inside the auth pass and retains per-tick prediction/state/thump work for
all zombies. `network.zombies.packetPrepare` now times selected zombie field
preparation in the main-thread sending path, nested under send. Compare the sum
of bookkeeping and preparation to the old full packetPrepare total, and avoid
adding preparation twice to send. `zombies.packet.bookkeeping`, `prepared`,
`preparationReused` and `pathNodesVisited` report work and sharing. `prepared`
can exceed unique zombies in a frame when an incoming packet invalidates the
cache. The existing connection workers still select relay candidates; they do
not prepare live packet fields.

See [implementation and validation](PLAYER-ZOMBIE-PACKET-HOTSPOTS.md) and run
`python tools/test_player_packet_algorithms.py` for focused regression fixtures.

## Unload, sound, animal-zone and vehicle follow-up

Implemented 2026-10-05 following sequences 327–336. See
[review and implementation status](UNLOAD-SIMULATION-ALGORITHM-REVIEW.md) and
[vehicle follow-up candidates](VEHICLE-UPDATE-FOLLOWUP.md).

| Phase | Boundary and interpretation |
| --- | --- |
| `entities.removal` | EngineEntityManager.removeEntityInternal, including membership/bucket notifications and removal listeners; can run under unload, simulation or another caller |
| `entities.removal.array` | Indexed global-array removal inside entities.removal; excludes bucket/listener work |
| `simulation.players.soundStress.discovery` | Full sound-index reconciliation inside soundStress; normal incremental appends do not create a discovery timing |
| `simulation.worldSounds.expiry` | Server sound lifetime traversal and stable removal/compaction inside worldSounds |
| `simulation.designationZones.animalCheck` | One animal-zone check, including topology lookup, square reconciliation and reattachment |
| `simulation.designationZones.topologyValidate` | Geometry/list validation and spatial-view rebuilding when needed |
| `simulation.designationZones.topology` | Connected-zone traversal on a cache miss; excludes topologyValidate |
| `simulation.designationZones.reattach` | Trough/hutch animal reattachment inside animalCheck |
| `simulation.vehicles.update.checks` | Removed/membership/chunk checks preceding inherited vehicle update |
| `simulation.vehicles.update.inherited` | BaseVehicle's super.update call |
| `simulation.vehicles.update.animals` | Auth countdown and carried-animal update branch |
| `simulation.vehicles.update.routing` | Reliability/authority, trailer cargo and towing/reconnection bookkeeping preceding physics-state work |
| `simulation.vehicles.update.physicsState` | Existing physics-state branch: activation, engine/controller, transform/square reconciliation and crash handling; not the fixed Bullet integration timer |
| `simulation.vehicles.update.bookkeeping` | Impulse resets, sounds, breaking objects, world lights, alpha/passengers and lightbar checks |
| `simulation.vehicles.update.parts` | Existing parts-update or idle-battery-update branch |
| `simulation.vehicles.update.tail` | Bullet stats, overlays, remaining capacity/passenger/crop/important-area/trailer checks |
| `simulation.isoObjects.update.<Kind>` | Actual selected IsoObject.update bodies, aggregated by fixed class family per processing pass |

Topology helpers are also called by connected-zone getters outside the periodic
designationZones pass. Their labels identify the subsystem, not guaranteed
containment within that periodic parent. Do not calculate a periodic-parent share
without matching call context. Vehicle stages aggregate once per scheduler bucket
batch; phase max/calls describe batches, not an individual vehicle. Direct vehicle
updates outside a scheduler batch record individual stage calls. Timed callbacks
can contain other nested telemetry. Vehicle frameStep/preupdate and scheduler
bookkeeping remain outside these BaseVehicle.update children. No variable object
IDs or arbitrary subclass names are used as labels.

IsoObject fixed kinds: IsoThumpable, IsoCompost, IsoFeedingTrough, IsoStove,
IsoGenerator, IsoTrap, IsoBarbecue, IsoFireplace, IsoCarBatteryCharger,
IsoClothingWasher, IsoClothingDryer, IsoCombinationWasherDryer,
IsoStackedWasherDryer and other. Subclasses inherit the nearest known family.
Divide class totals by `isoObjects.updateAttempts.<Kind>` for mean attempted-body
cost, or by tick.count for tick impact; phase calls count nonempty class batches.

New counters:

- `entities.removal.indexed`: actual global-array removals. `arrayEntriesAtStart`
  sums the global-array sizes before those removals. This is potential population,
  not observed comparisons or a measured count of comparisons saved.
- `players.soundStress.incrementalAppends`: known appends incorporated without
  full rediscovery; includes non-stress sounds that leave candidate views intact.
  `indexRebuilt` counts full builds and `eligibleAtBuild` sums stress-entry counts
  at those builds. Existing discoveryEntries retains actual full-scan entries.
- `sounds.expiry.checked`, `removed`: visited list entries and removed entries;
  duplicates are separate entries. Life decrement and survivor/release order stay
  unchanged. Stable compaction replaces repeated array shifts.
- `zones.topology.geometryChecked`: zone-list entries checked for membership,
  ordering and public rectangle changes; `invalidated`: changed snapshots;
  `rebuilt`: connected-result builds; `cacheHits`: reused connected results;
  `spatialEntriesBuilt`: zone/bucket references constructed for changed geometry.
- `zones.check.squares`, `objects`: coordinate checks and live square-object visits
  during animal-zone reconciliation, not unique world objects.
- `isoObjects.skippedNoWork.IsoThumpable`: due exact-class server objects whose
  existing update body would have no observable work. These are excluded from
  actual updateAttempts and class-body timings. The existing phase schedule and
  always-update exceptions remain unchanged.

The sound view retains full reconciliation at the first query of a new world
frame, or after unknown list mutations/invalidation. Native addSound appends
extend the current view instead of invalidating it. Integrations editing existing
sound coordinates/radius/stress eligibility during a shared-view frame must call
`WorldSoundManager.instance.invalidateStressIndex()`; an unrelated native append
is no longer an incidental full rebuild. stressMod is read live at query time.

Zone connectivity and spatial views validate public geometry/order before reuse,
retain first-match overlap semantics and original connected-list order, and cap
cached connected references and spatial references independently at 65,536 each.
Oversized spatial coverage falls back to the original zone scan. Dynamic roof,
water/free-space, food, animal and corpse state is still reconciled at the original
2500 ms boundary. Caching that state without its missing invalidation hooks would
change observable results. Public list getters still return mutable ArrayLists.

The thumpable gate applies only to exact IsoThumpable instances on the server.
Settled unlit/no-fuel states skip no-op calls; lit fuel work becomes due at the
original strict `abs(gameMinutes-lastUpdateHours) > 10` boundary. Initial/reset
state and time rollback retain the original body; subclasses retain normal calls.
This does not throttle Food.update, item temperature/cooking, traps or generators.

Verification: `python tools/test_unload_simulation_algorithms.py` compares removal
order/lifecycle with the complete vanilla manager, connected results/spatial lookup
with vanilla traversal, expiry with the original loop, and the no-work predicate
with the original dedicated-server thumpable body. It also checks that vehicle
gameplay statements and the thumpable update body were left unchanged. Existing
player/packet and moving-object fixtures cover incremental sound sums/invalidation
and scheduler integration. Full 42.21 game-JAR dry-run compilation is required.
These are correctness fixtures, not production timing or allocation benchmarks.

## Synchronous parked-car follow-up, 2026-10-05

The scheduler permits SIXTEENTH when needPartsUpdate is the only former FULL
reason. All other activity guards remain. Parts keep the original game-minute
deadline and elapsed-minute callback arguments; signal work/callback delivery on
these cars can be delayed by up to the next scheduled call. This is a cadence
change, not solely an algorithm substitution. See VEHICLE-UPDATE-FOLLOWUP.md.

| Counter | Meaning |
| --- | --- |
| `vehicles.scheduler.fullChecks` | Vehicle classifications selecting FULL, summed over frames; not a population gauge or actual update count |
| `vehicles.scheduler.sixteenthChecks` | Vehicle classifications selecting SIXTEENTH, summed over frames |
| `vehicles.scheduler.partsOnlyChecks` | Subset of SIXTEENTH classifications with needPartsUpdate=true |
| `movingObjects.updateAttempts.vehicle.<LEVEL>` | Actual preupdate/frameStep/update chains attempted at FULL, HALF, QUARTER, EIGHTH or SIXTEENTH; includes failed attempts and matches the existing vehicle attempt parent |
| `movingObjects.postupdateAttempts.vehicle.<LEVEL>` | Actual postupdate attempts at that scheduler level; separate from update chains |
| `vehicles.idleParts.viewRebuilds` | Builds of the persistent ordered device/light capability index |
| `vehicles.idleParts.rebuildEntries` | All part entries inspected during those builds |
| `vehicles.idleParts.candidatesChecked` | Capability-bearing entries inspected by server idle traversal; active flags are read live |
| `vehicles.idleParts.updateAttempts` | updatePart calls selected by idle traversal, including calls with no Lua callback due; excludes the trailing lightbar battery call |
| `vehicles.squareQueries.reused` | Third physics-state square lookup reused from the same call's square/below lookup |
| `vehicles.modelParts.indexEntries` | Model entries indexed for first-identity parent lookup during server updateTransform; built lazily only when an attached-model parent lookup is needed |

Idle-part and model/square counters accumulate in the vehicle stage batch rather
than issuing telemetry map lookups per car. The original stages still contain the
same surrounding work; parts includes either full updates or the optimized idle
branch. Square reuse has no cross-frame cache. Model indexing preserves matrix
calculations; it does not skip unchanged transforms or optimize postupdate.

`python tools/test_vehicle_idle_algorithms.py` exercises production selectors and
creation/replacement hooks, compares ordered updates with vanilla under live
activation/list mutation/re-entry, checks elapsed-minute cooling and the actual
square-selection statements, and checks first-model indexing and batched counters.
The unload fixture's vehicle source contract now permits only the independently
tested square-query substitution inside update. The moving-object fixture checks
parts-only cadence and activity promotion. Validate mod callbacks, radio/light
activation, cooling, streaming/floor edits, towing and reconnect in multiplayer.
