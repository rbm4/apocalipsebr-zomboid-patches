# Production hot spots: first optimized 42.21.0 snapshots

Target: 42.21.0 dedicated server. Evidence: production telemetry sequences 127
and 128 supplied in the chat, each covering 30.002 seconds. Both snapshots have
35 online players and 352 loaded cells. Sequence 128 ends with 3,079 zombies,
251 animals, 803 vehicles and 662 giblets. Counts are endpoint populations, not
counts of objects updated throughout the interval.

## What the measurements establish

| Metric | Earlier snapshot | Sequence 127 | Sequence 128 |
|---|---:|---:|---:|
| Simulation calls | 300 | 266 | 300 |
| simulation.update total ms | 12,783.353 | 12,227.073 | 12,134.852 |
| simulation.update average ms | 42.611 | 45.966 | 40.450 |
| simulation.update maximum ms | 129.964 | 513.751 | 134.533 |
| Tick rate | unavailable | 8.866 | 9.999 |
| Tick average ms | unavailable | 79.038 | 64.642 |
| Tick maximum ms | unavailable | 839.953 | 248.061 |
| Ticks over 100 ms | unavailable | 38 / 266 | 28 / 300 |
| Collector time ms | unavailable | 10,455 | 1,055 |

Sequence 128 has a 5.1% lower average simulation cost than the earlier snapshot,
with about 649 ms less simulation wall time per window. This is an observational
comparison; the earlier snapshot lacks equivalent workload and GC measurements.
Sequence 127 processes fewer ticks and has a higher simulation cost per call.
Neither pair establishes a controlled before/after speedup or runtime correctness.

The entity index selected 318,194 candidates and skipped 1,745,933 visits in
sequence 128: 84.6% of potential candidate visits were avoided. Entity simulation
costs 217.681 ms, 1.8% of simulation.update. This establishes that the index is
active, without equating visits avoided to CPU time saved. Fluid updates account
for 203.736 ms of the entity simulation time; furnace/mashing/resource costs are
small in this workload. Animal synchronization costs 125.133 ms, including 20.780
ms of coverage preparation. More work here has little whole-frame upside now.

## Ranked investigation targets

| Priority | Measured sequence 128 cost | Source finding | Next decision |
|---|---|---|---|
| 1: moving-object update | 4,977.958 ms; 16.593 ms/frame; 41.0% of simulation | Bucket dispatch calls setCurrentSimulationLevel, preupdate, frameStep, update in that order | Use class timings and actual attempt counts to select the costly class before changing its algorithm |
| 2: streaming and GC | map.postupdate 4,688.305 ms; unload 1,764.765 ms; square removal 1,669.059 ms | Unload removes objects, converts world entities to meta, detaches squares, then queues persistence | Identify removal stage and collector; use allocation/GC profiling if stalls remain |
| 3: entity frame work | 1,213.539 ms; 4.045 ms/frame | UsingPlayerUpdateSystem scans all IsoObject entities; InventoryItemSystem scans its inventory bucket; each system is followed by mutation queue processing | Separate system traversal from queued mutations before building an active-membership index |
| 4: miscellaneous update | 1,274.187 ms; 4.247 ms/frame | UpdateStuff includes GameTime.update, time-driven Lua events, scripts, rain and meta work | Separate periodic game-time callbacks from steady work |
| 5: IsoObjects | 982.558 ms; 3.275 ms/frame | 1,189,171 checks versus 132,239 update attempts; IDs depend on current square/list position | Identify lookup fallback reasons before extending caches or phase membership |
| 6: postupdate/animation | 923.163 ms; 3.077 ms/frame | animation.inline directly wraps scheduler.postupdate | Use class breakdown; count these overlapping parent/child measurements once |

Map work is outside simulation.update. Integration/unload-related
rows must not be summed as independent CPU costs: cell integration already
includes its measured stages. In sequence 128 there are 60 integrated and 57
unloaded cells, with 3,840 existing chunks integrated and zero new chunks. Stable
loaded-cell population does not imply stable membership; this interval has
considerable streaming turnover. It does not alone prove avoidable churn.

Zombie authorization (1,969.813 ms) and packet preparation (1,285.416 ms) are
additional server-loop costs outside simulation.update. They remain relevant to
tick capacity even though this report focuses on simulation and streaming.

## Algorithm candidates and mutation contracts

### Moving objects

The bucket already maintains indexed membership, phase scheduling, tombstones and
lifetime checks. Callback removal can invalidate another object during the same
traversal. A rewrite must keep current traversal order, lifetime checks, deferred
compaction and perObjectMultiplier restoration, including exceptions. Measuring
actual callback attempts avoids confusing endpoint population with scheduled work.

If zombies dominate, inspect their server AI/state, collision and pathfinding
calls; if vehicles dominate, inspect active/idle vehicle paths. Giblet counts grew
from 432 to 662, but their cost is not established. Their vanilla update consumes
RNG, emits blood splats, calls inherited update and queues removal at rest. A
blanket cosmetic skip changes observable simulation and random-number flow.

### Entity frame work

UsingPlayerUpdateSystem.update iterates engine.getIsoObjectBucket().getEntities(),
checks validity and clears a using player that is dead, on a different level or
outside the +/-10 tile bounds. Most entities may have no using player, making an
active-using-player index a plausible candidate if the new timing is substantial.
Before implementation, cover every using-player assignment, registration/removal,
validity transition and pooled lifetime. Preserve bucket ordering and cleanup in
the frame where it occurs; applying the meta throttle here delays lock release.

InventoryItemSystem unregisters entities whose equip parent is missing or dead.
An equipped-membership index would need equip-parent and death lifecycle hooks.
EngineEntityManager.updateOperations drains component/entity queues until both are
empty. Moving this drain to the end of all systems changes what the next system
sees. The new operation scopes retain the original drain after each system.

### Chunk streaming

IsoChunk.removeFromWorld walks squares across its min/max levels. Within each
square the order is environmental cleanup, moving-object removal, IsoObject
removeFromWorldToMeta, static moving-object removal, adjacency detach and softClear.
ServerCell.Unload then persists vehicle state and enqueues the unloaded save job.

Do not queue/reorder world-to-meta conversion separately from square detachment,
change live list iteration into snapshot iteration, or reuse chunks before save
ownership is released. These are contract-sensitive changes. The new stage
metrics identify which part merits a deeper algorithm/lifecycle review. They
measure main-thread enqueue/persistence calls, not completion of asynchronous disk
saves. Allocation attribution is still unmeasured; use a JVM allocation profile
rather than deriving allocated bytes from heap-used differences.

### IsoObject lookup cache

The cache rebuilt zero times in sequence 127 and once, for eight entries, in 128.
This demonstrates little observed adoption, not a measured cache speedup. It may
reflect small lists, raw backing-array exposure or custom equality. Raw exposure
must disable caching because external writes do not notify the list. Optional
lookup counters now distinguish the reasons. Persistent object due buckets still
require complete coverage of square changes and positional ID changes.

## New telemetry added in this investigation

Existing replacement classes were extended; no new vanilla overrides were needed.
No simulation policy, serialization, throttle interval or callback order changed.

- simulation.movingObjects.update.<kind> and postupdate.<kind>, where kind is
  zombie, vehicle, animal, player, giblet or other. Subclasses inherit the nearest
  known category. Timings include the complete dispatched callback chain.
- movingObjects.updateAttempts.<kind> and postupdateAttempts.<kind>. An attempt is
  counted even if a callback throws; reused zombies and skipped lifetimes are not
  counted. Dividing totalMs by attempts gives mean cost per attempted chain.
- simulation.entities.frameSystem.<SystemClass>, frameOperations and
  frameSystemOperations separate frame callbacks, entity/component mutations and
  pending system changes. simulationOperations and simulationSystemOperations
  measure corresponding drains in the simulation pass.
- simulation.misc.gameTime, scripts, rain and meta identify selected
  UpdateStuff children. Existing misc children remain unchanged.
- map.chunk.unloadSquares.cleanup, moving, isoObjects, staticObjects and detach
  aggregate square stages per chunk. chunks.unloadedSquares counts non-null
  squares; unloadedIsoObjectAttempts and unloadedStaticObjectAttempts count actual
  loop visits. Existing unloadedMovingObjects counts starting list entries.
- map.chunk.unloadVehiclePersistence and unloadSaveEnqueue expose work following
  removeFromWorld in ServerCell.Unload.
- jvm.collectors lists name, count and totalMs deltas per GarbageCollectorMXBean.
  Existing gcCount/gcMs remain available. Collector time is not a guaranteed
  main-thread pause measurement; collector names help interpret concurrent work.
- Optional -Dapocbr.telemetry.squareLookups.enabled=true enables
  isoObjects.squareLookup.rawArrayExposed, smallList, customEquality, cacheEligible
  and listEntries. The first four partition instrumented indexOf(Object) queries;
  listEntries sums their list sizes, not actual comparisons. Other lookup overloads
  are excluded. Disabled by default to avoid per-lookup counter overhead.

Moving class timings are aggregated once per nonempty class per bucket invocation:
their phase calls/avgMs/maxMs describe batches, not individual objects or whole
frames. Chunk square stage phases similarly describe whole-chunk aggregates.
Individual maximum callback cost is not collected. Moving timers use two clock
reads per attempted chain, small invocation-local fixed arrays and fixed labels;
they do not acquire the telemetry map for every object. Optional animation workers
use separate local accumulators. GC pauses/blocking inside a timer remain included.
For chunk square stages, exceptional incomplete removals do not flush child totals;
the enclosing parent still captures its elapsed time. Timings are diagnostic and
need production overhead comparison after deployment.

## Validation and next production comparison

The 58-file server patch compiles against the installed game JAR with
patchApocalipseBr.ps1 -DryRun. Moving-object fixtures pass 531 lifecycle assertions;
simulation fixtures pass 31,657 assertions covering entity selection, Lua
mutations, animal routing, mutable square lists and corpse perception. No files
under decompiled were changed. The changes have not been deployed by this work.

Capture several consecutive windows under comparable player/population/streaming
load. Compare per-frame means, over-budget tick frequency and class attempt counts;
keep GC-heavy windows visible as a separate group. Look for a dominant moving
class, a dominant frame system or operation drain, and a dominant square-removal
stage. Run optional square lookup diagnostics for a short capture if adoption
remains near zero. To explain severe stalls and allocation sources, pair telemetry
with GC pause logs or a JVM recording; these counters alone cannot establish
stop-the-world duration or the allocating stack.


## Implemented follow-up: UsingPlayerUpdateSystem

The active-using-player candidate index is now implemented for the dedicated
server, with assignment hooks in GameEntity and positional membership maintenance
in EntityBucket. This follows the candidate described above; the production
sequences 127/128 predate this additional optimization. It remains uncommitted
and has not been deployed by this work.

The baseline tool copied exact 42.21.0 GameEntity and UsingPlayerUpdateSystem
sources and committed each vanilla baseline separately. Behavioral changes are
in those replacements, EntityBucket and new ServerUsingPlayerIndex. Registered
idle entities are excluded from recurring checks. The existing validity/distance/
death predicate, send behavior and update order remain intact; no throttling was
added. Network receive/reset writes notify the index directly without being
redirected through a setter that would send extra packets. Unknown accessor
behavior takes a conservative always-checked path.

A one-time O(N) bootstrap and O(N) member storage support subsequent O(A log A)
traversal in current bucket order. Entity removals unlink owner references and
adjust the swapped tail position; callbacks can add later candidates during the
current pass. Added using-player counters are defined in TELEMETRY.md and expose
selected candidates versus complete bucket sizes, including override fallbacks.

The dedicated differential fixture passed 190,136 assertions. It compiles the
production index/bucket/system and extracts production GameEntity mutation methods
into a minimal environment, comparing cleanup and callback order with the exact
vanilla system. The scale fixture processes only 35 active entities per frame
among 30,000 registered entities. Full 61-source game-JAR compilation also passes;
the established simulation fixture remains passing. Runtime time savings and
crafting-lock behavior still need production validation after deployment.
