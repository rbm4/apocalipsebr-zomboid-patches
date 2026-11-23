# 42.22.2 vehicle simulation and streaming pressure

Snapshot 362 recorded a 6,843.6 ms slowest tick. Its top-level simulation,
map preupdate and map postupdate consumed 4,328.7 ms, 1,856.0 ms and 562.1 ms
respectively. The map preupdate cost was almost entirely cell integration.
Within simulation, moving-object update took 2,248.9 ms. Item updates,
ISO-object updates and moving-object postupdate also consumed hundreds of
milliseconds. Lua time overlaps these phases. This data locates multiple
stalls in one tick. It does not identify a vehicle-specific callback or
show that a filesystem read ran on the simulation thread.

## Source and measurements

| Path | Exact 42.22.2 boundary | Fixture evidence |
| --- | --- | --- |
| Dedicated vehicle load | `ServerMap.ServerCell.loadVehicles()` calls `VehiclesDB2.loadChunkMain`, which queues `QueueLoadChunk`. On dedicated servers, the `ServerPlayersVehicles` thread drains that queue and performs the SQLite query and decode. It shares a loop with player DB work and sleeps 500 ms between passes. `processMain` applies only a completion whose chunk still exists and owns that request. | Enqueue, worker job, SQL store, per-row decode and main-thread application have separate timings. The SQL timer contains row decoding. |
| Alternate vehicle load | `VehiclesDB2.loadChunk(IsoChunk)` reaches the other SQL overload synchronously on its caller. The exact `WorldStreamer` call site is in its non-dedicated path. | The diagnostic reports the direct overload separately when it runs on the owner. |
| Vehicle unload | `updateVehicle` captures/serializes vehicle state before queuing the storage job. The worker writes the captured buffer. | Capture/enqueue and worker write have separate timings. Cell unload remains inclusive. Queue wait from enqueue to worker start still needs its own timestamp. |
| Legacy parked vehicles | The legacy scheduler permits a narrow idle subset at SIXTEENTH cadence. Occupancy, mechanics use, engine state, alarms, sirens, towing, movement and animals can require full cadence. Legacy vehicle-parts routing reduces inactive device scans and retains callback-aware ordering. | Measure steady native vehicle callback and part-update attempts before porting. Idle, active and transitions need separate scenarios. |
| Physics | The exact legacy `WorldSimulation` delta adds preparation and Bullet substep telemetry. | Three timers cover inclusive physics update, Java preparation and the existing native Bullet call. Native step calls count substeps. |
| Vehicle relay | Vanilla `VehiclePhysicsPacket.processServer` checks authority and timestamp before applying the vehicle state and relaying to relevant clients. The audited migration diff contains no replacement of this packet handler. | The playerless test measures `VehicleManager.serverUpdate` entry points. Connected packet relay requires a separate driver. |

Source references are the [vanilla vehicle database](../../42.22.2/decompiled/zombie/vehicles/VehiclesDB2.java),
[server map](../../42.22.2/decompiled/zombie/network/ServerMap.java),
[legacy scheduler](../../42.22.2/src/zombie/MovingObjectUpdateScheduler.java),
[legacy vehicle parts](../../42.22.2/src/zombie/vehicles/VehicleParts.java)
and [vanilla vehicle packet](../../42.22.2/decompiled/zombie/network/packets/vehicle/VehiclePhysicsPacket.java).
The legacy vehicle PostgreSQL backend changes the store used by the existing
vehicle pathways. It is distinct from the standalone chunk-blob PostgreSQL
replay and does not establish a root-agent migration.

## Original-JAR pressure run

The opt-in `vehicles72` profile ran on Windows with unmodified 42.22.2. The
initial generation covered 72 cells and 4,608 chunks. It observed 212 distinct
vehicles reaching `BaseVehicle.update` during a 100-tick hold. Three seeded warm
cycles each reloaded 54 previously generated cells and 3,456 chunks, held them
for 100 ticks, then withdrew relevance in batches of nine. They observed 141,
142 and 160 distinct vehicles during the holds.

The worst tick was 1,375.213 ms. Cell integration took 1,367.423 ms and vehicle
updates took 0.489 ms on that tick. In a later window, the worst tick was
241.887 ms, of which cell unload took 228.816 ms and vehicle updates took
3.263 ms.

| Warm-cycle measurement | Median across three cycles |
| --- | ---: |
| Chunk file-open wall time, summed on worker | 21,411.861 ms |
| Request through fully loaded wall time | 23,210.828 ms |
| Cell integration, summed on main | 668.522 ms |
| Cell unload, summed on main | 413.457 ms |
| Vehicle load enqueue, summed on main | 1.263 ms |
| Vehicle SQLite read and decode, summed on worker | 91.039 ms |
| Vehicle load completion, summed on main | 30.816 ms |
| Vehicle save capture and enqueue, summed on main | 19.757 ms |
| Vehicle storage writes, summed on worker | 962.575 ms |
| Vehicle update callbacks, summed across the 100 held ticks | 80.753 ms |

Each warm cycle recorded 3,456 vehicle load enqueues, SQLite worker calls and
main-thread completion callbacks. The direct SQLite read overload recorded
zero main-thread calls. The longest observed worker SQLite read was 150.153 ms
and the longest main-thread completion was 2.521 ms. Worker timings exclude
queue wait and can overlap main-thread timings. This playerless run recorded
zero part updates and zero native Bullet steps. It measures streamed, parked
vehicles and leaves active device, physics and connected relay costs open.
Maven reported zero failures across 135 unit and 263 integration cases,
including 3 and 110 skips, respectively.

### Next phase boundaries

- **Cell integration.** A warm cycle spent 368 ms of its 669 ms in
  `IsoChunk.doLoadGridsquare`. Split the rest of `RecalcAll2` into square
  creation, neighbor recalculation and room/zombie handling, then split
  `doLoadGridsquare` into its own native callbacks. The separate same-cell
  perimeter lookup trial did not demonstrate a reduction in the whole phase.
- **Cell unload.** In the same warm cycle, `IsoChunk.removeFromWorld` took
  391 ms of the 413 ms spent unloading cells. Subdivide detachment into
  native population/collision calls, entity callbacks and adjacency cleanup.
  The existing entity-removal index is a candidate for a repeat comparison
  at this larger footprint with unload and save-drain checks enabled.
- **Moving objects.** The parked cadence targets avoidable vehicle
  callbacks. Use scheduler start-frame timing and per-kind callbacks
  to distinguish bucket reconstruction from object updates before
  attempting a persistent object index. The latter changes membership
  lifetime and needs removal, reuse and insertion-order coverage.
- **Saved chunk files.** In the same warm cycle, the loader spent 21.4 s
  in file opens and 0.44 s reading bytes. This is worker wall time.
  Evaluate open latency independently from the main-thread integration
  and unload stalls.

## Additional workloads

- **Connected relay.** Use an actual authorized vehicle driver and recipients
  inside and outside relevance, then exercise position updates, towing and
  disconnect. Record incoming packets, successful authority checks, fan-out,
  encoded bytes, wall time and packet age. The game's fake client has vehicle
  speed presets, but the inspected source does not send vehicle physics
  packets. Basic player coverage and a physics-capable client are separate
  validation steps.
- **Zombie population.** Use native spawn entry points in bounded batches and
  confirm actual active counts at 2,000, 3,000 and 4,000. Measure zombie simulation
  and reclamation separately from packet preparation and relay with clients.
  Confirm actual giblet instances before attributing an `other` scheduler cost
  to them. Verify stale work and pool cleanup after unload.
- **Asynchronous work.** Keep native live-world mutation, game callbacks and
  physics under their existing owner. Start with detached byte I/O or packet
  selection whose results can be bounded and checked against world/cell
  generations. Use the pressure fixture to compare queue wait and tail latency
  with cancellation, unload/reload and shutdown. Native objects can become
  invalid during cell turnover, so moving the whole scheduler into a worker
  would require a different ownership contract.

A large wall-time interval does not distinguish CPU, JVM pauses, storage
latency and VM scheduling. Compare scheduler CPU, GC evidence and worker
phases in the same windows and keep the load budget and offered population
fixed when testing candidates.
