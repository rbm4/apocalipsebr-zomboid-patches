# simulation.update algorithm review

Target: 42.21.0 dedicated server. Reviewed 2026-10-03 against this version's
`decompiled/zombie` and current `src/zombie` replacements, including uncommitted
patches. The original review is a source investigation, not a live profile.

Implementation update: the subsequently authorized first patch batch adds indexed
meta selection, mutation-aware square-list lookup caching, conservative animal
connection routing with lossless iterative overflow batches, the Lua stable-list
fast path, corpse LOS prefiltering and smaller culling/building/vocal fixes. Child
timings and work counters are documented in [TELEMETRY.md](TELEMETRY.md).

The world-object frame scan remains because public square/list mutations can change
the ID-derived phase without a scheduling notification. Its square-position lookup
is accelerated while raw-array and custom-equality paths retain exhaustive lookup.
Full corpse deadline scheduling is deferred: deeper inspection found per-frame
random skeleton trials and continuously updated animal deathAge mod data, plus
iteration-sensitive shared rot-stage duration in mixed human/animal passes. A
simple deadline-only rewrite would change behavior. Fixed Bullet integration and
Java preparation cadence remain unchanged and now have separate timing. Fishing
expiry and virtual-animal lifecycle rewrites also remain candidates for fresh
profiling. The original investigation and recommendations below are retained.

Two copied decompiler artifacts needed compile-only repairs: IsoWorld mixed
boolean startup variables with later integer player coordinates; IsoDeadBody's
reanimation-delay switch omitted its zero-delay default. The latter default was
confirmed against the installed JAR's javap bytecode (case 1 and default both
retain the initial zero value). No decompiled baseline file was edited.

## What the supplied measurement establishes

The supplied older snapshot reports 300 calls, 12,783.353 ms total, 42.611 ms
average, and 129.964 ms maximum. Assuming an actual 30,000 ms reporting interval,
this is 42.61% of the window and 10 simulation calls per second. The maximum
parent call alone exceeds the documented 100 ms tick budget. It does not identify
which subsystem was expensive, establish CPU utilization, or measure the savings
from changes made after that snapshot.

`src/zombie/network/GameServer.java:1040` measures `statex.update()`.
`decompiled/zombie/gameStates/IngameState.java:1340` shows the actual boundary:

```text
IngameState.updateInternal
  OnTickEvenPaused; debug/file checks
  IsoWorld.update
    VehicleManager.serverUpdate; WorldSimulation (Bullet physics)
    hutches; emitters; climate
    IsoCell.update
      moving-object scheduler start / synchronization
      items; ProcessIsoObject; moving objects; static updaters
      lifecycle additions/removals; corpses; fishing
    collision contacts; moving-object postupdate/animation
    buildings; databases; virtual animals
    designation zones
  GameEntityManager.Update
  AnimalController -> AnimalSynchronizationManager
  radio; UpdateStuff (clock, sounds, fire, population, pathfinding, etc.)
  OnTick; ambient managers
  transaction/action/ping managers
```

Two attribution details:

- Vehicle networking also runs inside `IsoWorld.updateInternal` at
  `decompiled/zombie/iso/IsoWorld.java:3009`. The separate `vehicles.update`
  measurement covers the later GameServer call only. `VehicleManager.serverUpdate`
  has an update limiter, so two call sites do not imply two full passes every tick.
- `src/zombie/MovingObjectUpdateScheduler.java:49` can join previous animation;
  line 72 calls `awaitWorkers` before lifecycle/activity timing begins. These waits
  can contribute to this parent but escape its moving-object child phases.
  GameServer already awaits zombie workers before simulation, so the later wait
  is normally already satisfied. Do not assume it explains the historical total.

## Recommended algorithmic work

Priority below reflects verified avoidable work and implementation tractability,
not a measured ranking of live milliseconds.

### 1. Select due meta entities without scanning all meta entities

Evidence: `src/zombie/entity/components/fluids/FluidContainerUpdateSystem.java:37`,
`components/resources/ResourceUpdateSystem.java:33`, and the craft/furnace/mashing
systems iterate their entire component-family bucket, validate each entity, then
call `MetaSimulationThrottle.shouldSkip`.

The existing patch removes repeated simulation passes
(`src/zombie/entity/GameEntityManager.java:93`) and batches elapsed ticks. However,
throttling still saves update bodies rather than candidate traversal. The interval
is ten **100 ms simulation ticks**, not necessarily ten server frames
(`src/zombie/entity/MetaSimulationThrottle.java:4`).

Use persistent phase buckets per system for meta members, alongside the existing
loaded-entity members. Select only phases crossed between startTick and endTick;
when an interval is crossed more than once, visit each due entity once and pass
its aggregate owed ticks. Expected selection work changes from O(loaded + meta)
to O(loaded + due meta + membership changes) per system pass. With uniform phases
and one simulation tick advanced, due meta is approximately meta/10; this is a
candidate-visit reduction, not a claim of 10x faster entity simulation.

Maintain component add/remove, unload-to-meta/load-from-meta, ID assignment,
pooled lifetimes, and delayed engine operations. Preserve per-system entity order
and system priority; the engine processes queued operations after every system.
Craft/resource dirty state and effective tick counts must remain consistent.
The drying detached-world cleanup currently precedes the throttle; move it to
reliable lifecycle notification or preserve its existing validation cadence.

### 2. Replace ProcessIsoObject's full-list phase filter with persistent buckets

Evidence: `src/zombie/iso/IsoCell.java:2231` hashes every registered object's ID
every frame and only updates the selected tenth. Traps and generators are always
updated. The current moving-object indexes do not cover this separate list.

Maintain ten phase views plus an always-update view at registration/removal.
Selection becomes O(always + due + mutations), replacing O(all registered
objects) plus due update bodies. Cache the phase; relocate it if the entity ID
changes. Merge due and always entries in original active insertion order rather
than updating the two views in arbitrary order. Retain perObjectMultiplier,
callback removal behavior, and re-entry lifetime handling.

The list and removal set have public getters (`IsoCell.java:2813`, `:4588`), so
indexing only addToProcessIsoObject is insufficient: exposed mutations must also
be observed. Server-only adoption should preserve the client path.

### 3. Separate corpse deadlines from nearby fake-dead checks

Evidence: `decompiled/zombie/iso/objects/IsoDeadBody.java:1590` copies the complete
DeadBody registry into tempBodies and checks every corpse every frame when
corpse removal is enabled. Rotting/removal use world-age thresholds. Eligible
fake-dead corpses additionally scan server players (`:1783`, `:1804`) and perform
LOS checks before the distance filter.

Schedule rot-stage/removal transitions by game-time deadline (heap or timing
wheel). Maintain a separate ready fake-dead set and spatially query players within
four tiles, then apply the exact visibility and distance predicates. Existing
distance eligibility is the 2-to-4-tile annulus, not all players within four tiles.
This replaces O(corpses) polling, with worst-case O(ready fake corpses * players),
by due transitions plus local perception candidates.

Preserve prompt wakeup checks, ordinary static-updater reanimation, manually
changed rot stages, player-corpse exemptions, animal bodies, option changes,
world-time rollback, registry removal, chunk unload/load and pooled identities.
Do not simply throttle updateBodies as a whole. Bursts of simultaneous expirations
still need processing; deadlines remove idle scans rather than that actual work.

### 4. Route animal updates to relevant connections

Evidence: `decompiled/zombie/popman/animal/AnimalSynchronizationManager.java:75`
visits every connected client; `:117` traverses the entire changed-animal set
for each client before relevance and send-cadence filtering. Cost is O(connections
* changed animals), even when clients are in distant areas.

Use persistent spatial connection coverage to route each changed animal to
candidate clients, then retain the exact RelevantTo predicate. Add due schedules
only after preserving the current extra-update checks: these can detect changes
before the 800/1000 ms timer is due. Preserve requests, deleted IDs, reliable
cadence, split-screen, ownership-sensitive packet state and the 150-animal limit.
Use explicit iterative packet batches in place of recursive pending processing,
with independent buffers and the existing packet contract.

Expected benefit is largest with many clients and geographically separated herds.
When all clients see the same herd, real fan-out remains unavoidable. Packet
preparation cannot simply be shared across clients because setAnimalPacket takes
the connection as an argument.

### 5. Reduce repeated Java vehicle work around fixed Bullet steps

Evidence: `decompiled/zombie/core/physics/WorldSimulation.java:81` still executes
10 ms fixed physics substeps. Each substep traverses every loaded vehicle in
updateVehiclePhysics (`:105`) before Bullet.stepSimulation. Meta-system delta-time
changes do not alter this loop. Vehicle data are also read back after stepping.

At 10 server updates per second, approximately ten physics steps per update are
expected if the physics delta tracks 100 ms; this is an estimate, not a capture.
Instrument the actual substep count and Java/native durations first.

Keep fixed physics integration. Investigate caching surrounding-chunk eligibility
until vehicle position/chunk coverage changes, and separating impulse work into
dirty entries. Server guards already suppress substantial impulse application
(`decompiled/zombie/vehicles/BaseVehicle.java:3598`, `:3642`); chunk checks may be
the more relevant Java cost. Any active/sleeping vehicle subset requires correct
wakeup on contacts, towing, authority changes and chunk availability. Moving
checks out of substeps needs proof that boundary crossings cannot be missed.
Replacing the fixed steps with one large delta would change collision behavior.

### 6. Remove quadratic callback membership checks

Evidence: `src/zombie/Lua/Event.java:26` invokes each callback and then performs
`callbacks.contains(closure)`. For K distinct callbacks still registered, this
creates O(K^2) membership comparisons per event dispatch, independently of the
Lua callback bodies. OnTick and OnTickEvenPaused are inside the measured parent;
time events emitted by GameTime.update are also nested there.

Use a mutation-aware indexed callback list, or a mutation version allowing the
contains check to be skipped when no list mutation occurred. Preserve duplicate
registrations, self-removal, removal of other callbacks, additions during dispatch,
and the publicly exposed ArrayList API. The fast path can be O(K). This saves
dispatcher overhead; it does not fix expensive mod callbacks. Existing optional
Lua event telemetry measures aggregates, not individual mod attribution.

## Smaller or workload-dependent candidates

- **Zombie culling:** `decompiled/zombie/popman/ZombieCountOptimiser.java:19`
  looks up every sent zombie even when the excess quota is zero, and continues
  after fulfilling the quota. Skip zero quotas and stop the loop once fulfilled.
  Those paths already skip RNG because of short-circuiting, so this can remove
  lookups without changing RNG consumption. This runs before scheduler lifecycle
  timing and deserves its own measurement. Avoid extending this into changed
  culling behavior or duplicate-deletion semantics without a separate review.
- **Fishing expiry:** `decompiled/zombie/iso/FishSchoolManager.java:107` scans
  noise exclusions, chum entries and zoneCache every frame, but compares deadlines
  in integer game minutes. A minute-change fast path plus dirty notifications
  can eliminate unchanged checks; a deadline queue scales better for large
  caches. Preserve same-minute inserts, expired entries, option/time changes and
  direct external zone timestamp writes. Deadline equality is currently strict `>`.
- **Building bookkeeping:** `decompiled/zombie/iso/areas/IsoBuilding.java:128`
  walks ground-floor room tile lists each frame even though safescore remains
  zero in this version. Replace per-tile counting with room list sizes, or remove
  the unused calculation after bytecode confirmation. Preserve scoreUpdate and
  ScoreBuildingGeneral. JIT optimization and actual building population determine
  the live payoff; this is not evidence of a major bottleneck.
- **Virtual animals:** `decompiled/zombie/characters/animals/AnimalZones.java:284`
  rebuilds a chunk-set snapshot and copies chunk animal lists before deduplicated
  updates. Persistent membership and mutation-safe traversal could reduce this
  bookkeeping, but actual virtual-animal state work needs separate timing.
  Snapshotting currently protects concurrent chunk tracking and updates that move
  animals between chunks, so removing copies without lifecycle ownership is unsafe.
- **Server zombie vocals:** `src/zombie/iso/IsoCell.java:2292` traverses zombieList
  to call updateVocalProperties; the vanilla method returns through a server guard
  (`decompiled/zombie/characters/IsoZombie.java:3569`). A server early return can
  remove that traversal. JIT may already remove some work; benchmark before
  expecting a significant gain.

LogisticsSystem and MetaEntitySystem are not default recurring simulation costs:
their constructors disable simulation-updater membership. Empty-looking method
bodies alone are not a reason to prioritize them. PolygonalMap2 already has
vehicle dirty tracking in the current patch, and its path-search graph rebuild
is on its worker thread; do not attribute the whole worker cost to updateMain.
The native pathfinding option also selects a different runtime path.

## Measurement and implementation sequence

1. Obtain fresh windows from the current deployed patch set. Include actual
   intervalMs, runtime version, existing moving-object child phases, optional Lua
   aggregates, world populations and JVM GC deltas. Compare stable areas, herds,
   driving, corpse-heavy areas and exploration with similar player counts.
2. Add a bounded set of child timings: world/cell; ProcessIsoObject; items/static
   updaters; corpses; entity update and each simulation system; animal sync;
   physics Java prep/native step/readback; population/pathfinding main; virtual
   animals; culling prep; optional animation wait. Timers should surround whole
   passes, rather than adding a clock read to every member.
3. Add work counters: registered/due/skipped meta and IsoObjects; corpse counts
   and transitions; animal connection-candidate pairs; physics substeps and vehicle
   visits; culling candidates; fishing cache sizes. Counts distinguish reduced
   traversal from an unrelated population or workload change.
4. Use a JVM recording to separate sampled CPU, allocation, GC and waiting where
   elapsed timings leave ambiguity. Preserve the distinction between parent and
   inclusive child phases; subtract only children known to be disjoint and wholly
   inside that parent. Asynchronous postupdate can execute outside this boundary.
5. Start implementation with due-selection indexes for entities and IsoObjects,
   then let the fresh profile choose corpses, animal networking or physics next.
   The smaller guarded traversal fixes can be independently measured.

The authorized implementation above is in the working tree, with copied vanilla
baselines committed separately through tools/Copy-PZPatchBaseline.ps1. No runtime
deployment or benchmark-derived millisecond savings are claimed. Compilation and
lifecycle/mutation fixtures complement the live gameplay checks still required.
