# Follow-up review 54: zombie native path-request argument

Created2026-10-28. Status: **approved/applied; full source build passed, runtime unverified**. Review53 is applied.
See the [subsystem tracker](../features/2026-10-27-subsystem-port-tracker.md),
[candidate diff](2026-10-28-merge-54-zombie-path.diff),
[verification record](2026-10-28-merge-54-zombie-path.json) and
[build checkpoint](2026-10-28-build-check.md).

## Contract and proposed change

The copied [ServerZombiePath](../../src/zombie/apocalipse/zombies/ServerZombiePath.java)
uses itself as a dedicated custom Mover, keeping requests outside the zombie's
native AI slot. Its native addRequest call now needs a trailing boolean named
avoidFarmingPlants in [target PathfindNative](../../decompiled/zombie/pathfind/nativeCode/PathfindNative.java).

Pass false explicitly. In [old PathFindRequest.init](../../../42.21.0/decompiled/zombie/pathfind/nativeCode/PathFindRequest.java),
avoidFarmingPlants started false and became true for player movers; this separate
ServerZombiePath mover is not a player. Target
[PathFindRequest.init](../../decompiled/zombie/pathfind/nativeCode/PathFindRequest.java)
accepts the explicit value, and target
[PathFindBehavior2.update](../../decompiled/zombie/pathfind/PathFindBehavior2.java)
initially requests avoidance through IsoPlayer.isPlayer, with a false retry if
avoidance fails. False preserves this custom route's previous non-player policy.

Only append the argument to the native backend call. Keep the Java backend,
separate mover, node limits, generation/incarnation validation, callback copying,
traversal checks, cancellation and main-thread result pump unchanged. In particular,
this does not grant zombie thumping/climbing/crawling abilities to the custom mover.
Git normalized the applied file to CRLF; candidate equivalence was verified
modulo line endings.

## Verification and decision

The candidate applies cleanly. All303 explicit target Java sources, with only this
candidate overlaid on approved sources, compile successfully against the identified
42.22/world251 JAR using Java25 and the bundled PostgreSQL/Hikari libraries:
**exit0, zero errors,895 class files**. The JSON records the temporary compiler log.
No source in decompiled was modified and no generated class was deployed.

No extra test was added for this single compatibility argument. Evidence for the
value comes from old defaults and exact target callers; the full compilation
verifies the signature. Native path DLL execution, actual plant avoidance, routes,
NPC gameplay and lifecycle/thread runtime remain untested. Compilation is not
proof that all subsystems are viable in a running server.

Decision: applied on2026-10-28 after user approval. The actual approved303-source
tree compiled successfully afterward (895 class files). The requested
[thread-local audit](2026-10-28-pathfinding-thread-local-audit.md) confirms the
previous collision helper/vector fix is preserved; the four-thread regression
passed80000 queries. Continue event-control
metadata, List-cache and runtime/deployment-cleanup work individually afterward.
Historical-data migration planning and disabled vehicle PostgreSQL gap repairs
retain their user-deferred status. No installed game files, live database or
production configuration changed.
