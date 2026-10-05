# Unload and simulation algorithm review: sequences 327–336

Reviewed 2026-10-05 for the 42.21.0 dedicated server. This is an investigation;
no runtime code, update cadence, gameplay, JVM settings or deployment changed.
Source authority: current `src/zombie` overrides, otherwise this version's
`decompiled/zombie`. The deployed revision has not been matched to the repository.
Existing documents contain historical implementation/deployment statements;
presence in current source is the implementation check used here.

## Capture coverage and health

The supplied attachment contains ten JSON records and two non-JSON separator
lines (lines 2 and 9). The telemetry summarizer accepted sequences 327–336 with
no duplicate records. These are contiguous reporting windows totaling 300.142 s,
2,865 simulation ticks and 30–31 online players. Sequence 316 from the earlier
attachment is a separate comparison, not part of this aggregate.

Weighted mean outer simulation-bearing tick: 56.576 ms; actual throughput:
9.545 ticks/s; 193 ticks exceeded 100 ms (6.74%). Sequences 327–335 together
average 51.083 ms/tick. Sequence 336 is a distinct degraded window. Telemetry
loss is zero; the normal-queue overload counter reports 181 dropped packets,
including 158 in sequence 336. This counter does not cover every network loss.

| Sequence | ticks/s | Tick mean ms | Tick max ms | >100 ms ticks | Simulation ms/tick | Unload ms/tick |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 327 | 9.899 | 50.035 | 346.093 | 13 | 33.46 | 2.38 |
| 328 | 9.866 | 48.427 | 531.729 | 11 | 31.97 | 3.95 |
| 329 | 9.891 | 44.525 | 354.303 | 4 | 30.64 | 2.27 |
| 330 | 9.860 | 60.854 | 286.651 | 29 | 39.58 | 2.70 |
| 331 | 9.847 | 52.467 | 510.484 | 12 | 34.63 | 2.82 |
| 332 | 9.699 | 59.960 | 318.156 | 29 | 36.42 | 4.44 |
| 333 | 9.865 | 47.204 | 359.558 | 11 | 32.25 | 3.14 |
| 334 | 9.966 | 47.772 | 264.298 | 12 | 31.14 | 2.82 |
| 335 | 9.893 | 48.722 | 336.552 | 10 | 32.92 | 2.66 |
| 336 | 6.666 | 129.766 | 1891.601 | 62 | 69.57 | 12.57 |

All phase rows below have ten-window coverage. Timers are inclusive elapsed
measurements, not exclusive CPU. Do not sum parent and child rows.

| Phase | Total ms / 300.142 s | ms / simulation tick | Parent/interpretation |
| --- | ---: | ---: | --- |
| simulation.update | 103610.651 | 36.164 | statex.update; includes server networking/bookkeeping and Lua as well as gameplay |
| map.postupdate | 29368.735 | 10.251 | unload, cell update, zombie auth/sending, LOS suspension |
| map.preupdate | 11208.047 | 3.912 | integration and possible LOS suspension |
| simulation.movingObjects.update | 48444.53 | 16.909 | inside cell/world/simulation |
| simulation.movingObjects.update.vehicle | 23967.211 | 8.366 | child of moving updates |
| simulation.movingObjects.update.player | 20021.539 | 6.988 | child of moving updates |
| simulation.isoObjects | 9132.032 | 3.187 | registered-object scan plus selected update bodies |
| simulation.players.health | 4760.56 | 1.662 | inside player base |
| simulation.players.soundStress | 2068.366 | 0.722 | inside player stats; discovery included |
| simulation.designationZones | 1641.707 | 0.573 | inside world; periodic zone rebuilds |
| map.cell.unload | 10555.501 | 3.684 | child of map.postupdate |
| map.chunk.unloadSquares | 9496.11 | 3.315 | child of unload |
| map.chunk.unloadSquares.isoObjects | 5678.724 | 1.982 | child of square unload |
| map.chunk.unloadSquares.detach | 2448.145 | 0.855 | child of square unload |
| map.cell.integrate | 10101.956 | 3.526 | child of map.preupdate |
| map.losSuspendWait | 2952.811 | 1.031 | shared between map pre/post callers; waiting |

Simulation takes 34.52% of reporting-window elapsed time. That does not establish
34.52% CPU utilization. Vehicles and players account for 90.80% of moving-update
elapsed time, and 42.46% of the simulation parent. They are the largest measured
target, but their broad chain timers do not identify the expensive inner method.
Vehicle chain mean is 0.04514 ms across 530,968 attempts; player chain mean is
0.22694 ms across 88,224 attempts. Batch `calls` are not object attempts.

## Are hitches clustering?

There is evidence for both recurring work and periodic/broad stalls.

- Simulation remains 30.64–39.58 ms/tick in sequences 327–335; periodic hitches
  alone cannot explain all of that recurring total.
- EveryTenMinutes takes 306.137, 291.365, 413.517, 320.924 and 294.251 ms in
  sequences 327/329/331/333/335. Sequence 331 also has 42.408 ms in EveryHours.
  GameTime includes these callbacks. The seven EveryTenMinutes callbacks are
  aggregated; these records cannot identify farming or any other individual mod.
- Sequence 336 has no measured EveryTenMinutes event, yet simulation peaks at
  1421.456 ms, map.postupdate at 779.060 ms, cell unload at 353.553 ms, LOS
  suspension at 499.571 ms, vehicle batches at 290.074 ms, player batches at
  309.856 ms and designation-zone update at 239.383 ms.
- Its per-attempt vehicle cost is approximately 0.08643 ms versus 0.04190 ms in
  335; player cost is 0.46641 versus 0.19878 ms. There are fewer attempts in 336.
  Endpoint vehicle populations are similar (703 versus 697). This supports a
  broad slowdown beyond merely doing more scheduled chains; changing workload
  mix, CPU contention, preemption, allocation stalls or blocking remain hypotheses.

Each max is an independent maximum with no tick ID/timestamp. They cannot be
added or assumed to belong to the same tick. The current data cannot prove that
several expensive operations coincided, or count how often each child exceeded a
threshold. Nor can a single maximum explain all of a window's cost distribution.

ZGC reports concurrent major-cycle times of roughly 10–14 seconds per window;
all reported pause-bean totals are zero at the available millisecond resolution.
Sequence 336 has 15,207 ms aggregate GC time and ends at 10.799 GiB used heap.
These are not 15 seconds of stop-the-world pause or evidence of a memory leak.
Use an actual JVM recording to distinguish CPU, blocking, allocation and pauses.

## Unload: what is already optimized, and what remains

`IsoChunk.removeFromWorld` retains its square/object traversal and virtual
removeFromWorldToMeta calls. The recent production-hotspot work added stage
timing and counters; it did not replace that main object-removal algorithm.
Existing lifecycle indexes do benefit some callees, so unload is not wholly
untouched: registered-process membership checks use sets, moving-object lifetime
removal is indexed, and selected entity-family buckets have indexed removals.

479 cells unload in this capture, involving 2,153,373 non-null squares and
3,409,394 IsoObject removal attempts. IsoObject removal accounts for 59.80% of
unloadSquares; detach accounts for 25.78%. These are the useful measured bounds
for the following candidates, not predicted savings.

### U1. Global engine entity removal: concrete O(N) -> O(1) candidate

Call path: `IsoChunk.java:3275` -> vanilla `IsoObject.java:4752` -> patched
`GameEntity.java:495` -> `GameEntityManager.java:235` -> vanilla
`EngineEntityManager.java:151`.

The engine owns an unordered Array and an entity membership set. Its removal
still calls `entities.removeValue(entity, true)`, which linearly searches the
global array before swap removal (`entity/util/Array.java:282`). Bucket indexes
do not eliminate this separate search. Only registered objects pass the manager
guard: 3.4 million IsoObject attempts are not 3.4 million engine removals.

Maintain an identity entity->position map. On removal, obtain the position,
perform the existing removeIndex and update the swapped tail's position. This
retains exactly the current unordered-array result and avoids O(global entities)
per removal. Preserve removal flags, bucket notifications, engine listeners,
delayed operations, component migration, pooled lifetime and removeAll semantics.
The mutable array is private; the engine exposes an immutable wrapper.

This is the strongest directly verified unload algorithm candidate. Add counters
for actual engine removals and search entries, and aggregated timing for engine
removal versus component/meta migration before claiming its share of 5678.724 ms.
It can also benefit simulation-time entity/component removals, but those drain
timers are small in this capture; do not attribute all gains to simulation.update.

### U2. Powered-object removal repeats same-square scans

`IsoObject.java:4779` calls `IsoChunk.removeObjectPoweredByGenerator`, then
`IsoChunkLevel.java:108`. The level counts other powerable objects by scanning
the complete square object list for each removal. On a square with K eligible
objects among S objects this is O(K*S), potentially quadratic in a dense square.

A mutation-aware per-square powerable membership/count can answer the same
exclude-current-object predicate in expected O(1). It must reflect sprite/property
changes, list mutations, object moves and re-entry; unload does not simply remove
each object from the square list. Skipping this bookkeeping for the whole chunk
requires stronger proof about what virtual removal callbacks observe. Keep the
existing result and order first. Measure eligible calls and entries examined;
ordinary sparse squares may offer little benefit.

### U3. Cleanup and detach: limited algorithmic headroom

`IsoRoom.removeSquare` linearly removes from room square lists and searches exits.
Position indexing can remove search but ordered ArrayList removal still shifts
entries; swap removal is not automatically equivalent. Cleanup is only 545.67 ms
total, so this ranks below U1. Zone.removeSquare is empty in the exact vanilla base
class; avoid inventing a costly base-zone removal algorithm.

`disconnectFromAdjacentChunks` touches boundary-square links. `getAdjacentSquare`
and `setAdjacentSquare` are direct array accesses; softClear clears fixed fields
and eight nav entries. This is already O(1) per square. Cache repeated neighbor
reads and share stage boundary timestamps as small constant-cost improvements,
but a wholesale O(1) chunk detach would omit observable per-square state changes.
Keep object callbacks before detach, save ownership and pool reuse ordering.

The current stage instrumentation performs ten nanoTime reads per non-null
square, approximately 21.53 million reads over this capture (71.7k/s). Reusing a
single timestamp at adjacent stage boundaries would reduce that to six reads per
square without losing stages. Actual timing overhead is unmeasured; it is not
evidence that detach's 2448 ms is all profiler overhead.

Vehicle persistence and save enqueue are just 134.679 and 110.892 ms total.
These timers do not measure background save completion. They are low-priority
targets compared with IsoObject removal and LOS waits.

## Simulation: new opportunities and unresolved previous proposals

### S1. Sound discovery still dominates candidate discovery work

`ServerSoundStressIndex.java:25` rebuilds from the entire public sound list on
each new frame or list revision; only afterward does it query stress sounds.
Counter totals: 252,127,748 discovery entries versus 19,624 selected candidate
visits. Discovery counts repeated observations, not unique sounds. The counter
does not distinguish one rebuild/frame from multiple revision rebuilds/frame.
The entire timer costs 2068.366 ms (0.722 ms/tick), with a 71.861 ms max in 336.

Maintain stress-eligible membership and spatial entries at sound add/remove/pool
release, so queries use stress sounds rather than rediscovering all sounds. Keep
ordered accumulation and duplicate-list semantics, negative radius behavior and
the bounded large-radius fallback. Public writes to stresshumans/radius/coordinates
and list mutation require notification or a conservative compatibility path.
Do not just remove the frame guard: it currently reconciles unnotified field edits.
Add build count, full-list size, stress membership changes and eligible count.

Separately, `WorldSoundManager.java:454` repeatedly removes expired entries from
an ArrayList. E expirations among N entries can cause O(E*N) shifting. Stable
linear compaction can preserve remaining order and life decrement semantics while
reducing removal to O(N). Audit pool release, public-list mutation visibility and
query invalidation; this is not permission to change sound lifetime. The whole
worldSounds timer costs 1436.36 ms, so it is a secondary bounded opportunity.

### S2. Animal designation zones: synchronized periodic rebuilds and O(N) dedup

Vanilla `DesignationZone.java:230` checks all zones behind one 2500 ms boundary.
`DesignationZoneAnimal.java:162` scans each fully streamed zone's complete area,
rebuilds animals/corpses/food/roof/water lists, resolves connected zones and
reattaches animals. Candidate duplicate checks use ArrayList.contains, including
nearWaterSquares at line 234 and connected-zone traversal at line 59. This can
become quadratic in accumulated candidates and repeats overlapping scans.
The metric costs 1641.707 ms, with a 239.383 ms peak in 336.

First preserve the same list order while adding identity membership sets for
duplicate tests. Keep the original traversal and the point at which candidates
are admitted; use sets only to answer membership. Then evaluate persistent
zone topology and static square-content views, maintained by zone edits, streaming,
roof/water/object mutations. Continue dynamic animal reconciliation at its current
cadence. No staggered schedule or reduced update frequency is proposed.
Add zone count, area/squares checked, dedup comparisons, topology traversal and
reattachment timing before identifying which part produced the peak.

### S3. Vehicle chains are the largest unresolved broad timer

No BaseVehicle override exists in this version's src tree. Its update still runs
inherited moving-object work, physics-state reconciliation, animal cargo updates,
sounds, parts, lights, passengers and crop checks. This costs 8.366 ms/tick.
Important exclusions: `IsoCell.vehicles` is already a HashSet subclass and
`VehicleParts.getPartById` already uses a map. Neither needs an O(N)->O(1) fix.

`BaseVehicle.java:7925` scans every part on the idle battery path to find active
radios/lights. `VehicleParts.java:395` scans all parts for full updates, while
updatePart performs signal-device work before its minute-dependent Lua callback.
Maintain an ordered subset for idle active-device/light parts, and potentially
separate signal/headlight membership from callback-bearing parts for full updates.
Observe light/device/item/script/load mutations and battery/lightbar dependencies.
Minute callback boundaries, device updates and original iteration/callback order
must remain identical. This replaces discovery with O(relevant parts), not O(1)
when multiple parts actually require work. Carried animals can mutate state inside
their update callbacks and cannot simply be omitted.

Add aggregate child timings for inherited update, animal cargo, transform/square
reconciliation, parts/battery, sounds and remaining bookkeeping. Count total,
visited and selected parts. Visual-looking code needs a dedicated-server and
ServerGUI audit; updateSounds also emits gameplay world sounds and must remain.
No whole-idle-vehicle skip, physics-step change or blanket cosmetic removal.

### S4. ProcessIsoObject due views: still pending, with a real mutation obstacle

`IsoCell.java:2233` scans all registered objects and calls getEntityNetID to
select due phases. 13,629,070 checks produce 1,519,724 attempted updates. Timer:
9132.032 ms (3.187 ms/tick). The existing square index cache does not remove
the scan; vanilla `IsoObject.java:6096` derives IDs using the current square-list
position, with several getObjectIndex calls in the original implementation.

Persistent due and always-update views remain a candidate from the earlier review.
They must track exposed process-list mutations and square-list reorder/replacement,
raw-array exposure, floor handling, subclass ID behavior, moves and pooled re-entry.
Preserve callback-sensitive traversal and current phase/multiplier values. Begin
with notifying eligible tracked lists and conservative fallback for exposed raw
arrays/unknown behavior, rather than freezing mutable IDs. Optional square lookup
counters can identify cache misses, but a low rebuild count alone cannot diagnose
why caching was unused. The entire 3.187 ms/tick is not an estimated saving:
selected object update bodies remain necessary.

### S5. Inventory weight: documented follow-up, still uncached

The updater route index is present, but `IsoGameCharacter.java:12007` still sums
live item weights and tests equipped/fake-equipped status. Thermoregulator calls
getCapacityWeight at line 749; additional encumbrance paths also request weight.
Container getContentsWeight also sums items (`ItemContainer.java:2242`).
Memoization requires item/ammo/fluid/content weights, nested bags, attachment,
equipment and fake-equipment changes, and mod overrides. A list revision alone
does not establish unchanged weights. Even a per-tick cache is unsafe if callbacks
change weights inside that tick. Add weight calls/items visited/time before
choosing this over measured sound discovery. Health totals include much more
than inventory weight and cannot be attributed wholesale to it.

## Documentation status reconciled with source

| Earlier proposal | Current source status | Priority from these windows |
| --- | --- | --- |
| Meta due-selection indexes | Implemented | ECS overall only 0.832 ms/tick; global removal is a separate remaining issue |
| Square identity-position lookup cache | Implemented, conservative fallbacks | Full ProcessIsoObject scan still pending |
| Animal connection routing / overflow batches | Implemented | Not a leading timer here |
| Lua stable-list dispatcher fast path | Implemented | Expensive callback bodies remain; profile EveryTenMinutes individually |
| Corpse LOS prefilter | Implemented | Corpse total 0.134 ms/tick |
| Full corpse deadline scheduler | Deferred for RNG/deathAge/order constraints | Low priority; simple deadlines change behavior |
| Culling/building/server vocal small fixes | Implemented | Not leading measured costs |
| Using-player index | Implemented | Not a recurring bottleneck in these windows |
| Thermal/inventory routes/LOS/proximity/expiry indexes | Implemented | Weight totals and sound discovery remain candidates |
| Demand-driven zombie packet fields / enum/path helpers | Implemented | Outside simulation parent; don't compare selected prep alone to old prep |
| Physics preparation selection | Pending | simulation.physics only 0.007 ms/tick here; native fixed steps unchanged |
| Fishing expiry | Pending | 0.042 ms/tick; low priority |
| Virtual-animal lifecycle/snapshot work | Pending | 0.440 ms/tick; secondary |
| Unload stage algorithm replacement | No dedicated replacement found | Start with global engine-array removal |

The earlier documents are SIMULATION-OPTIMIZATION-REVIEW.md,
PRODUCTION-HOTSPOTS.md and PLAYER-ZOMBIE-PACKET-HOTSPOTS.md. Their investigation
sections describe proposals before the later implementation updates; reading
only the original candidate lists would incorrectly label several completed
changes as pending.

## Smallest additional evidence needed, without throttling

1. Maintain a bounded diagnostic ring of simulation-bearing tick records with
   monotonic tick ID/start, outer duration and fixed top-level phase durations.
   Retain threshold breaches and a small surrounding sample; record cell/chunk
   load/unload counts and periodic-event durations against the same tick ID.
   Emit asynchronously with a cap and drop counter. Independent window maxima
   cannot establish coincidence; this trace can. Avoid per-object log lines.
2. Split the dominant broad scopes with the bounded child timers above. Count
   actual global engine removals/search entries, sound rebuilds and selected parts.
   Do not count potential bucket size as actual removal attempts.
3. Pair a degraded capture with a JVM recording of the main thread and LOS workers
   to distinguish CPU from blocking/preemption/allocation. Preserve collector
   identity; concurrent cycle duration is not a pause measurement.
4. LOS suspension presently waits for full calcLOS passes to return slots.
   Workers check generation before/after the pass, not inside its nested square
   loops. Investigate a cooperative cancellation handshake before map mutation,
   with an explicit active-job barrier. Never detach while a worker can still
   access squares, and do not assume polling cancellation alone fixes the
   existing check/acquire lifecycle. This is a synchronization design review,
   not a proven O(1) substitute for necessary worker completion.

Implementation order: global entity removal index; sound discovery/expiration;
zone duplicate membership; vehicle child attribution and selective part views;
then ProcessIsoObject notifications/due views. Keep load integration profiling
visible: doLoadGridsquare and border recalculation total 5433.78 and 4451.61 ms,
but object callbacks/adjacency refresh ordering rule out arbitrary bulk skipping.
No milliseconds saved or live capacity increase are claimed before validation.

Validation performed for this investigation: supplied-file summarizer; independent
weighted/window arithmetic; exact-version call-site and collection inspection;
documentation/source reconciliation. No runtime tests or compilation were needed
because only this review document was added.

## Authorized implementation follow-up, 2026-10-05

The subsequent implementation request was applied to the 42.21.0 working tree:

- Global engine removal now uses identity positions and retains the original
  swap result, flags, delayed operations, bucket notifications and listeners.
  Added removal and array-child timers with actual removal/potential-size counters.
- Known native sound appends extend the current sparse stress view; unknown list
  mutations and new frames retain reconciliation for public field compatibility.
  Expiration uses stable linear compaction with unchanged lifetime semantics.
  This eliminates repeated discovery on normal appends, not every reconciliation
  scan. Same-frame direct field edits must use the existing invalidation API.
- Animal zones reuse geometry-validated connected results and bounded coordinate
  buckets, preserve overlap/list order, and use sets for rebuild/aggregation
  duplicate checks. Geometry changes, registry reordering/replacement and reset
  invalidate views. Dynamic square content still receives the same periodic scan:
  roof/free-space/water/item properties lack complete mutation notification.
- Vehicle update has eight fixed stage timings, flushed by scheduler bucket;
  gameplay statements are unchanged. Pending algorithms are recorded separately
  in VEHICLE-UPDATE-FOLLOWUP.md.
- ProcessIsoObject has fixed family body timings/attempt counters and an exact
  IsoThumpable no-work/deadline predicate. It skips selected calls only when the
  original dedicated-server body has no work; the existing fuel deadline and phase
  schedule remain. Unknown subclasses, traps and generators retain normal updates.

The user's ground-decay example is not governed solely by ProcessIsoObject:
IsoWorldInventoryObject.update registers its item in ProcessItems. Food.update
combines elapsed-hour aging with temperature, cooking-minute logic and egg checks.
Compost similarly combines elapsed-hour progression with worm aging, item mutation
and conversions. Neither was given a blanket frame throttle in this batch.
Their internal contracts must be separated before a cadence change can preserve
effects. New IsoObject family timings make later selection evidence-based.

Exact vanilla replacement baselines for EngineEntityManager, DesignationZoneAnimal,
BaseVehicle and IsoThumpable were copied with the repository baseline tool and
committed separately. Behavioral edits remain uncommitted; nothing was pushed
or deployed. No decompiled files or game JAR were changed.

Validation: unload/simulation differential fixture passes 1,495,716 assertions;
player/packet fixture passes 45,359; moving-object fixture passes 531. The new
fixture covers swap/listener re-entry, delayed operations/removeAll, public zone
geometry/order changes, overlapping spatial results and large-coverage fallback,
seeded/previous-zone traversal, duplicate sound lifetimes/release order/nulls,
strict fuel deadlines, rollback and no-fuel state. It verifies vehicle gameplay
statements and the original thumpable update body remain unchanged. Full dry-run
compilation produces 284 class files from 81 sources against the installed game
JAR. These checks do not establish production speedup or runtime multiplayer
compatibility; validate streaming/meta conversion, reconnect, zone edits, active
lights/devices and server GUI after deployment.
