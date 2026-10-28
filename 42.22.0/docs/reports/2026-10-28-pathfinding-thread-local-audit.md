# Pathfinding collision scratch isolation:42.22.0 verification

Created2026-10-28. Status: **prior targeted fix preserved; regression test passed**.
See the [subsystem tracker](../features/2026-10-27-subsystem-port-tracker.md),
[previous failure/fix](../../../42.21.0/docs/issues/2026-10-26-npc-collision-scratch-race.md)
and [applied path API adaptation](2026-10-28-merge-54-zombie-path.md).

The earlier failure was PointPool.alloc popping an unsynchronized deque through
LineClearCollideMain during NPC movement/prediction collision queries. Target
[PolygonalMap2](../../src/zombie/pathfind/PolygonalMap2.java) and
[LineClearCollideMain](../../src/zombie/pathfind/LineClearCollideMain.java) remain
byte-identical to their patched42.21 counterparts. No scratch isolation was lost.

The map caches ThreadLocal<LineClearCollideMain>. All four helper boundaries use
lccMain.get(): debug query, public line-clear query, collision-point query and
standing query. Each helper owns its point pool, vectors, rectangles, vehicle
polygon, nested vector arrays and Liang-Barsky arrays. Its collidePoint result
replaces all old writes to the public PolygonalMap2.temp vector. That public field
remains for compatibility; the fixed helper does not write it. A borrowed result
must be copied before another query on the same helper/thread. Same-thread recursive
queries remain unsupported.

The inspected bot callers route through these boundaries: ServerHumanMovement
blocked/prediction segment checks, HumanTraversal walking-edge/standing checks,
ServerHumanAttackLine and ServerZombieNativeAdapter.lineBlocked. The custom
ServerZombiePath owns requests separately and does not reuse native AI scratch;
review54 only passes the new avoidFarmingPlants argument.

[Fixture](../../../tools/test_pathfind_collision_threads.py) now accepts
--version42.22.0 (with a separating space; default remains42.21.0). The target run
compiled the actual map/helper against the identified game JAR and passed four
threads,80000 pooled supercover queries, intersection checks, independent nested
scratch/results and cached helper reuse. Source guards cover all four routing
sites and absence of global result writes. The full approved303-source tree also
compiled successfully, producing895 class files.

## Scope and residual shared state

This confirms the previous targeted fix, not that every pathfinding class is
thread-local. PathFindBehavior2 still declares static tempVector2,tempVector2_2,
tempVector3f_1 and pointOnPath, just as the old patch did. It also retains a static
debug-point pool. They were not converted by the earlier collision fix. No broad
conversion was applied during this verification; reentrant/thread ownership of
any newly worker-reachable native behavior must be audited separately.

Live square/door/chunk collections and vehicle polygon caches remain shared world
state. Vehicle cache rebuilds can reach static geometry scratch outside the fixed
helper. The alternate Clipper collision path is not made safe by this patch. Tests
do not run a world, native path DLL, vehicle streaming, full NPC lifecycle or real
client. Preserve these limitations from the previous issue; ThreadLocal scratch
does not by itself make concurrent native world access safe.
