# NPC collision scratch race

Status: targeted scratch isolation implemented; live-server validation pending.
Target: 42.21.0 dedicated server, async and synchronous NPC startup modes.
Related: [NPC worker](../features/2026-10-26-npc-async-worker.md) and
[live-access inventory](../reports/2026-10-26-npc-async-snapshot-map.md).

## Evidence and cause

The October 26 log at 23:06:27 reports `NoSuchElementException` in
`ArrayDeque.pop -> PointPool.alloc -> LineClearCollideMain.isNotClearOld`.
The NPC call chain is physiology, movement snapshot, prediction distance,
walking-edge inspection, then `PolygonalMap2.lineClearCollide`. The worker stops
after the exception. The trace does not identify the competing caller.

The exact `decompiled/zombie/pathfind/PointPool.java` baseline checks `isEmpty()`
and then pops an unsynchronized deque. `PolygonalMap2` previously routed queries
through one collision helper shared with native world callers. Its working
collections and geometry can be corrupted by concurrent calls even without an
exception.

## Implemented ownership and compatibility

`src/zombie/pathfind/PolygonalMap2.java` now caches one `LineClearCollideMain` per
calling thread through a private `ThreadLocal`. All four direct accesses use it:
debug line query, public line query, collision-point query and standing query.
Each helper owns its point pool, point list, perpendicular vector, bounding
rectangles, vehicle polygon, polygon vector arrays and Liang-Barsky arrays.
No lock, worker, queue or per-query helper allocation is introduced. Scratch
capacity is retained in each calling thread's thread-local entry and reused;
it is not passed across threads. Same-thread recursive queries remain unsupported.

`src/zombie/pathfind/LineClearCollideMain.java` uses the exact target baseline,
with an instance-owned result vector replacing all five writes to
`PolygonalMap2.temp`. The public static field remains for binary compatibility.
The returned vector is borrowed until the next collision-point call on the same
helper/thread. Callers retaining results must copy them.

Collision flags, formulas, point recycling and method signatures are preserved.
Exceptions propagate as before; worker failure, cancellation and shutdown
semantics are unchanged. Both classes must be deployed together.

## Nested audit and remaining limits

- `PointPool.pool` and `LiangBarsky.p/q` are instance-owned and isolated with the helper.
- The active `isNotClear` delegates to `isNotClearOld`. The Clipper alternative
  and its shared `collideWithObstaclesPoly` are not activated or made thread-safe.
- Helper rectangles are constructed directly; the static `VehicleRect` pool
  is not used by these scratch operations.
- `VehiclePoly.init(VehiclePoly)` copies into the helper's own polygon. However,
  live `BaseVehicle.getPoly/getPolyPlusRadius` can rebuild shared vehicle caches,
  reaching `VehiclePoly.init(BaseVehicle, float)` and its static vector/quaternion
  scratch. That live-cache path remains outside this fix.
- Squares, doors, floors, chunk membership, vehicle lists/polygons and debug
  rendering remain shared live state. Full native pathfinding async safety is
  not established by this patch.

## Validation

- `python tools/test_pathfind_collision_threads.py`: passed. It compiles the
  actual patched map/helper against the game JAR. Four threads verify distinct
  helpers and nested scratch objects, cached reuse, independent result storage,
  80,000 pooled supercover queries and intersection calculations. Source guards
  cover all four routing sites and removal of global-vector writes. This does
  not initialize a world or test native collision queries end to end.
- `python tools/test_npc_simulation_worker.py`: passed default async, sync and
  explicit async JVMs (31, 26 and 31 assertions). Failure-path stack traces are
  deliberately injected by that fixture.
- `./42.21.0/patchApocalipseBr.ps1 -DryRun`: passed against the installed exact
  game JAR with JDK 25; 301 sources compiled, producing 891 classes. No deployment.

Runtime verification must repeat NPC/zombie combat and concurrent collision
queries while doors, moving vehicles and chunk streaming change the world,
then verify shutdown and absence of worker failures. No speedup is established.
