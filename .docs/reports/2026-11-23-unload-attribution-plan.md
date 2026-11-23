# Unload attribution and worker decision

## Implemented integration telemetry

Enable `-Dpz.it.unloadBreakdown=true` with the opt-in isolated streaming
fixture. The probe executes with the packaged production agent and the original
42.22.2 game JAR. It instruments the existing call paths in the test child and
writes local artifacts. Cell lifetime, scheduling, callbacks, save requests and
the schema-3 emitter use their existing production behavior.

| Artifact | What it answers |
| --- | --- |
| `unload-postupdate.csv` | Which map postupdates contain unloads, their total wall time and owner-thread CPU time, and how many cells and chunks were detached together. CPU is `-1` if unavailable. |
| `unload-cell.csv` | Which coordinates dominate a postupdate or repeat across warm cycles. Each row gives cell start and elapsed time. |
| `unload-phases.csv` | Where elapsed time accumulated in each unload-bearing postupdate. It includes invocation count, inclusive time, self time, max call and measured search work. |
| `unload-clock-utc.txt` | Approximate UTC anchor for the monotonic offsets. Use this to align postupdates with a JVM recording. The postupdate ordinal is local to the fixture. |
| `streaming.csv` | Independent phase counts, chunk worker save/write and release, and elapsed time until detachment and save drain. |

The top frame is `ServerMap.postupdate`. It includes the LOS suspension and
resume, each `ServerCell.Unload`, and save-completion drain. Unload breaks down
into chunk detachment, vehicle save capture, save enqueue, and cell self time.
Detachment includes the population passes and save request, collision and
pathfinding calls, square weather and geometry cleanup, room and zone removal,
moving objects, ordinary objects, corpse handling and adjacency. For ordinary
objects the probe separates dispatch, the base callback, entity/meta
unregistration, engine and bucket removal, static and processing lists,
container items, sound, lights, overlays, generator bookkeeping and the
hot-save flag. Subclass code, virtual calls outside the targeted sites,
loops, field writes, timing overhead and pauses can remain in a parent’s
self time. A large residual is a candidate for finer attribution or sampling.

Sum self times across stages for a postupdate. Compare inclusive times only
within their call hierarchy. For example, `OBJECT_DISPATCH`, `OBJECT_BASE`,
and `ENTITY_UNREGISTER` can contain one another. Worker time uses a separate
lane and may overlap owner time. A slow postupdate can explain part of a slow
server tick. Use production slow-tick telemetry or a JVM recording for the
rest of that tick.

`searchWork` has the following stage-dependent units:

- `ENGINE_ARRAY_SEARCH`, `BUCKET_ARRAY_SEARCH` and `OTHER_ARRAY_SEARCH`
  count candidate comparisons from the native array’s matched index or its
  size on miss. Timing includes the native removal body.
- `GENERATOR_COUNT` and `ROOM_EXIT_LOOKUP` count candidate reads at the
  native loop access sites. Zero reads with a nonzero call count means the
  native loop found no candidates to read.
- Room, static updater, item sound and moving list stages report list slots
  offered. Java can stop early on a hit, and ordered removal can shift
  elements after the hit. Treat these numbers as search bounds, with the
  elapsed time accounting for the entire call.
- Offloaded serialization, pending-save lookup and recycling are outside
  the owner attribution tree. The existing fixture gives save/write/release
  totals. Queue wait, monitor wait and worker CPU need independent evidence.

## Integration scenarios

Run the same JAR, workload, flags, heap and host conditions on both sides.
Keep the timings diagnostic. The attribution assertions check that exclusive
times reconcile with the postupdate wall interval, counts match the
independent streaming probe, and all chunks detach, enqueue, persist and
release. A run with the global entity removal index disabled also verifies
that the probe counts native scan work.

| Scenario | Purpose and acceptance |
| --- | --- |
| Four-cell smoke | Check scope coverage, square and adjacency counts, object callbacks, native array searches with the index disabled, saved reload and clean shutdown. |
| `players30`, simultaneous release | Compare a detailed run with a probe-off control. Check per-postupdate cell counts, max cell time and top exclusive phases. Repeat in alternating order to distinguish probe overhead and run-to-run variation. |
| `vehicles72`, staggered release | Check whether spikes follow batch size, vehicle capture, save enqueue, population passes, or a few dense cells. Use the existing vehicle worker timings in a separate lane. |
| Dense object and live-actor fixture | Add repeatable containers/items, static updaters, generator-powered objects, corpses and tracked zombies/animals in specified cells. Verify object and actor counts and compare work per object and per cell. Exercise multiple cells sharing a room edge, a population region or a vehicle-related registry. |
| Immediate reload with a pending save | Hold the save worker at a deterministic test gate, release and demand the same coordinate, and verify that the newest bytes win. Track queue wait, individual save attempts, write failure and retry, completion, pool release and shutdown. Set a gate timeout and release it on every exception path. |
| Active LOS with connected players | Exercise the LOS barrier under actual readers and compare player-visible world, spawn, vehicle and inventory state after turnover. Measure owner wall versus CPU and use JVM lock, park, execution and GC pause events to explain the largest gaps. |

Any later worker prototype should have a serial oracle. Compare map publication,
room and neighbor links, object update membership, actors and vehicle authority,
and saved bytes after the same unload and reload. Add test gates after capture,
before commit and before reuse. Force rapid re-entry, redundant requests, a stale
worker result, a full queue, worker failure and shutdown with work in flight.
Assert owner-thread execution for live mutations, exactly-once commit and
generation validation before publishing a result. Limit the gates to the test
child and use timeouts to surface a blocked fence as a test failure.

The first three scenarios use the existing streaming harness. The dense,
pending-save, connected and worker-oracle scenarios are test designs. The current fixture
drains writes before reloading and does not drive a real player connection.
Its normal postupdate scope does not capture failed cell integration unloads
from preupdate or removals processed during later simulation phases.

Start a diagnostic smoke run with:

```powershell
mvn verify -Pserver-integration "-Dpz.it.streaming=true" "-Dpz.it.unloadBreakdown=true" "-Dapocbr.optimization.entityRemoval.enabled=false"
```

For representative hot-path measurements, keep the default production switches
and select `-Dpz.it.streamingProfile=players30` or `vehicles72`. The
instrumented run calls a timer around small callbacks, so use the control run
for latency and the detailed run for attribution and work counts.

## First runtime checks

The four-cell smoke run passed against the original 42.22.2 JAR on Windows
with the global engine removal index disabled. Each of six cycles detached
four cells and 256 chunks, then drained and reloaded successfully. The five
warm cycles offered the following search work and elapsed time.

| Search | Calls | Measured work | Total wall |
| --- | ---: | ---: | ---: |
| Native global entity array removal | 4,170 | 866,685 comparisons | 0.60 ms |
| Native bucket array removal | 4,170 | 866,685 comparisons | 0.53 ms |
| Room square removal | 9,605 | 533,845 list slots offered | 1.46 ms |
| Generator powerable-object count | 685 | 2,415 candidate reads | 0.56 ms |

The same warm cycles spent 181 ms inclusive in ordinary object dispatch,
including 88 ms self in the base object method. Chunk detachment ran for
329 ms inclusive, with 69 ms self. These results measure the instrumented
four-cell workload. The broad residuals include profiling overhead and
cannot be assigned to a particular callback from these totals alone.
The raw run is `target/server-integration/run-16483898819448985441`.

## 30-position run with default optimizations

The instrumented fixture passed all six load/unload cycles and clean shutdown
using the original 42.22.2 JAR. Every warm unload-bearing postupdate detached
45 cells and 2,880 chunks. The five warm cycles visited 1,099,890 non-null
squares and ran 1,616,055 ordinary object teardown dispatches.

| Measurement | Five warm batches combined | Slowest warm postupdate |
| --- | ---: | ---: |
| Map postupdate wall | 3,174.68 ms | 781.55 ms |
| Owner thread CPU | 3,109.38 ms | about 781.25 ms |
| Cell unload inclusive | 3,166.29 ms | 779.23 ms |
| Ordinary object dispatch inclusive | 2,044.61 ms | 501.39 ms |
| Base object callback self | 972.73 ms | 229.70 ms |
| Chunk detachment self | 545.58 ms | 127.98 ms |
| Remaining native bucket scans | 24.49 ms | 6.01 ms |
| Room square list removals | 9.38 ms | 2.15 ms |
| Same-square generator list count | 3.38 ms | 0.92 ms |
| Save enqueue | 5.35 ms | 2.34 ms |

The slowest warm postupdate evicted all 45 cells. The slowest individual
cell in it took 23.81 ms. The measured owner CPU was close to the wall
interval, within the resolution of the platform counter. About 64% of that
postupdate’s wall time fell inside ordinary object dispatch. The native
bucket path made 81,613,225 comparisons across the warm batches. At 24.49 ms
total it accounted for under 1% of their combined postupdate time.
The room and generator scans measured even less in this workload.

The detailed probe measured 572 ms median warm unload. The earlier
probe-off fixture with the same production switches measured 410 ms.
These are separate runs. The gap motivates a matched overhead check
and a low-overhead sample of the base object callback and chunk loop
residuals. The observed search times give no evidence for significant
gains from threading those searches. An isolated playerless workload
with no pending-save reload cannot settle connected-player LOS, save
contention or worker correctness.

Raw artifacts are under `target/server-integration/run-4488379137281995429`.
The earlier probe-off comparison is `run-9440034974640636710` from the
[unload scan report](2026-11-23-unload-scan-and-async-review.md).

## Lower-overhead unload sampling

Use `-Dpz.it.unloadSampling=true` with the streaming fixture to record JFR
execution samples, native method samples, and monitor, park, sleep and garbage
collection events. The integration child publishes one JFR event for each
unload-bearing map postupdate. Each marker carries owner CPU time and native
cell and chunk counts. The runner cross-checks them against the existing
streaming measurements and requires an owner execution sample in the native
cell unload stack. The child uses wall-clock event correlation and one CPU
interval per postupdate.

Add `-Dpz.it.unloadSearchWork=true` to also time native
`zombie.entity.util.Array.removeValue` identity searches. A hit compares
through the matched index and a miss inspects the current array. The probe
takes a timestamp around each native call without instrumenting the comparison
loop. The count includes the remaining native buckets and other native
array scans within cell unload. It excludes indexed removals, non-identity
searches, and JDK lists. Existing detailed instrumentation attributes
those other sites. A native `removeValue` elapsed interval includes both
search and the removal when it finds a match.

```powershell
mvn verify -Pserver-integration "-Dpz.it.streaming=true" "-Dpz.it.streamingProfile=players30" "-Dpz.it.heap=6g" "-Dpz.it.unloadSampling=true" "-Dpz.it.unloadSearchWork=true"
```

Run a probe-off control with both settings omitted. For sampling alone omit
the search setting. Leave `pz.it.unloadBreakdown` off for overhead and stack
attribution runs. Compare the warm cycles on the same game and agent. The
separate control measures latency, while wall-time sums and sample counts
from the recorder answer attribution questions.

`unload-sampling.jfr` retains the recording after the isolated host exits.
`unload-sampling-windows.csv` contains aligned intervals and search totals.
`unload-sampling-events.csv` contains owner and selected worker samples
overlapping an unload-bearing postupdate, with full recorded stacks and
bytecode indexes. Java and native samples use separate event types. They
have different sampling periods, so analyze them separately. Duration
events contain overlap with the selected interval. JVM GC time, owner wait
time and worker execution can overlap. The event CSV covers map postupdate
rather than the full save drain or whole game tick.

### 30-position sampled results

The sampled run `target/server-integration/run-10269093679950324280` passed
all six streaming cycles on the original 42.22.2 JAR. All five warm eviction
postupdates detached 45 cells and 2,880 chunks. With production optimization
defaults, the native identity probe counted 40,695 calls and 81,613,635
comparisons in those warm batches. Native search and removal took 27.76 ms
of their combined 1,535.86 ms of postupdate wall time, about 1.8%. The typical
batch made 16.32 million comparisons and spent 5.52 ms in those methods.

| Owner Java execution samples, five warm batches | Samples |
| --- | ---: |
| All owner execution samples within the measured windows | 575 |
| Object teardown, excluding the separately classified native array path | 423 |
| Of those object samples, in the NPC object-lifetime hook | 191 |
| Remaining chunk detachment | 82 |
| Of those chunk samples, in adjacency unlink or square soft-clear | 47 |
| Animal population removal | 37 |
| Native array search call stacks | 14 |

The sample counts are statistical shares, and the nested rows overlap their
parents. In particular, 179 samples attributed to the NPC hook had
`HashMap.getNode` as the leaf in `NpcObjectLifetime.revision`. The agent
advises the base `IsoObject.removeFromWorld` and calls `changed` at entry.
That method reaps weak keys, calls the synchronized revision lookup, and writes
the updated revision. The source constructs separate lookup and retained keys.
These costs occur on the native object teardown path even during the
playerless streaming fixture. This identifies an existing agent extension
to measure in isolation before changing native unload or threading it.
See [the hook](../../src/main/java/cloud/apocalipse/pz/agent/features/npc/NpcModule.java#L139)
and the [current replacement for that historical tracker](../features/npc-engine.md#furniture-token-lifetime).

The slowest warm postupdate measured 463.11 ms wall and about 328.13 ms owner
CPU. A 120.65 ms GC pause overlapped this window. The median warm postupdate
was 276.50 ms wall and 265.63 ms owner CPU. One pause accounts for most of
the peak wall/CPU gap, while the more typical windows are primarily
owner-thread execution. The recording ties that pause to the window.
It does not identify which allocations triggered collection.

The sequential probe-off control in `target/server-integration/run-3872875650622040374`
used the same game and agent hashes, spawn fixture, seed and heap. Both runs
passed unload, save drain, reload and shutdown. The common streaming timers
measured 275.78 ms median warm unload with JFR and native search counting and
241.75 ms with both disabled. The 34 ms difference includes runtime variation
as well as instrumentation. Use the control as the latency reference and the
recording for attribution. There were no owner monitor-enter, park, or sleep
events at the configured 1 ms threshold in the sampled warm windows.

## Draft production telemetry

Keep existing schema-3 phase names. Add lightweight per-tick counters for
evicted cells, detached chunks, populated squares, ordinary object callbacks,
moving and static callbacks, plus the worst cell’s elapsed time. Add coarse
owner wall timers for detachment, vehicle capture, save enqueue and the
existing LOS barrier. Correlate them with the existing slow tick sample and
the map postupdate parent. No coordinate labels in interval aggregates.
Use only bounded sampled detail for slow cells and keep recording optional.

Measure persistence queue depth, oldest age, enqueue lock-acquisition time,
pending lookup candidate count, serializer wall and CPU, write wall, and
release-to-reuse delay in their respective owners. Attribute owner enqueue
time separately from save worker time. For repeated scans, promote only the
few site counters shown to matter and report opportunities, comparisons,
list sizes or early exits as distinct metric units. Attribute later
process-list drains to their actual simulation phases so their cost remains
visible after an unload.

## How the data informs threading

| Result | Decision |
| --- | --- |
| High wall time with little owner CPU and a matching park, monitor or GC event | Address the verified wait or pause. More teardown threads have little CPU work to divide and may increase contention. |
| High owner CPU in repeated searches or membership maintenance | Reduce redundant work at the existing owner boundary. Compare exact candidates and per-cell time with the matched control. |
| High owner CPU in pure derivation with small immutable inputs | Consider same-tick parallel preparation with one serial commit. Measure partitioning, fence wait, imbalance and commit time. The optimistic bound is serial time plus proven parallelizable time divided by effective workers. Reject the experiment if snapshot and synchronization costs consume that headroom. |
| High owner CPU in live callbacks, population transitions or graph edits | Those paths need single ownership and preserved ordering. A map pause alone cannot establish safety among workers that share rooms, adjacent chunks, indexes, Lua or JNI state. Require explicit subsystem ownership and a deterministic commit contract before testing parallel mutation. |
| High detached work or save handoff latency | Work from immutable inputs or exclusively owned detached data. Preserve per-coordinate save order, immediate reload visibility, pool lifetime, bounded queue depth and shutdown. Exercise re-entry and failure before relying on asynchronous execution. |

Track active cell and chunk counts, simulation CPU, tick tails, worker backlog,
retained memory and save drain alongside any future experimental arm. A design
that lengthens live cell residency can lower one unload spike and still make
server throughput worse. Keep the measured owner-thread detach as the control.
