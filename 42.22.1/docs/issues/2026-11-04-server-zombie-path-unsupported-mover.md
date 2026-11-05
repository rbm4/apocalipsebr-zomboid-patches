# ServerZombiePath rejected by the Java polygon pathfinder

Target: **42.22.1 server**. Status: **source fix and regression verified; live
deployment/crowd replay pending**. Reported during combat between many zombies
and the three NPCs. No live configuration or deployment is changed by this fix.

## Failure and cause

```text
PolygonalMap2.updateThread
IllegalArgumentException: unsupported Mover zombie.apocalipse.zombies.ServerZombiePath
PMMover.set -> PolygonalMap2.findPathHighLevelThenLowLevel
```

`ServerZombiePath` intentionally submits itself as a separate `Mover`, keeping
its request/cancellation slot distinct from native AI requests for the actual
`IsoZombie`. The current Java high-level pathfinder calls `PMMover.set(request)`
before searching. Vanilla PMMover accepts only `IsoAnimal`, `IsoPlayer` and
`IsoZombie`, so every such custom request fails classification.

The Java worker catches this exception and returns the request to its main-thread
result pump. An empty result invokes `Failed`; renewed pursuit can submit another
request and repeat the log. The supplied trace establishes path-request failures,
not JVM termination or proof that the worker thread died. Many active pursuers
increase the number of affected requests; a race or overload is not required to
produce this deterministic failure.

Version comparison on 2026-11-04: the supplied **42.21.0, 42.22.0 and 42.22.1**
decompiled `PMMover.java`, `PolygonalMap2.java` and Java `PathFindRequest.java`
are byte-identical across all three snapshots. In particular, the rejection and
high-level call to PMMover already exist in the local 42.21.0 baseline. This is
not evidence of a newly added 42.22.1 check. Prior successful gameplay could have
used the native backend, which accepts the generic mover. Both versions default
`PathfindNative.useNativeCode` / `Pathfind.UseNativeCode` to true; runtime settings
and prior backend logs are needed to establish why this run used Java fallback.
The code demonstrates mover capability classification; it does not document the
developers' historical intent for rejecting unknown implementations.

Additional runtime context: production already uses Java pathfinding, and prior
tests involved fewer zombies and one NPC. A backend change is therefore not
established and is not needed to explain different observations. Our patched
`ServerZombieController` can acquire zombies near eligible NPCs (or through
explicit NPC engagement), take server ownership and submit their pursuit paths.
`ServerZombieNativeAdapter.route` skips queued pathfinding when its straight
collision line is clear. Small/open encounters can consequently work on the same
Java backend without reaching PMMover; blocked routes in a larger encounter
expose the incompatible proxy. Counts alone do not prove why a route was needed;
acquisition/backend/route diagnostics are needed to reconstruct the old run.

The earlier [native request argument adaptation](../reports/2026-10-28-merge-54-zombie-path.md)
only added the required `avoidFarmingPlants=false` argument to the native backend.
Its compilation check did not exercise Java high-level mover classification.

## Narrow compatibility fix

`zombie.apocalipse.pathfind.WalkOnlyMover` is an explicit marker extending the
existing Mover contract. `ServerZombiePath` implements it without exposing or
unwrapping its live zombie body. The replacement `PMMover.set(PathFindRequest)`
recognizes the marker and uses the existing restrictive `MoverType.Animal`
classification with all crawl/thump/fence permissions disabled. The native
backend already serializes generic non-player/non-zombie movers as numeric type
0; Animal is the Java enum's zero category. Actual DLL path execution is not
claimed by the fixture.

The restriction matters: classifying the proxy as a normal zombie could grant
zombie-specific traversal preferences that its physical executor cannot perform.
The marker branch explicitly rejects inherited request crawl/thump flags, and
the normal setter resets crawling and both fence permissions before branching.
Ordinary animal/player/zombie branches retain their original logic. Unmarked
unsupported movers still throw; this is not a blanket catch/default fallback.

The proxy remains the request-map key and callback identity. It is not replaced
with the actual body. Generation, instance ID, online ID and life validation,
256-node result limits, copied pooled path results, same-floor/stair delegation,
cancellation, backend selection and main-thread result pumping remain unchanged.

Files:

- [PMMover.java](../../src/zombie/pathfind/PMMover.java): native classification override.
- [WalkOnlyMover.java](../../src/zombie/apocalipse/pathfind/WalkOnlyMover.java): immutable capability marker.
- [ServerZombiePath.java](../../src/zombie/apocalipse/zombies/ServerZombiePath.java): opt-in marker implementation.

Vanilla baseline: `42.22.1/decompiled/zombie/pathfind/PMMover.java`. The baseline
tool copied/hash-verified it and committed only that file as **132e1a8** before
the behavioral edit. Other working-tree features were preserved. No decompiled
source or original game JAR was edited.

## Thread/ownership contract

Request submission, cancellation and callback ownership remain with the existing
server zombie controller and main-thread result pump. PMMover classification runs
on the Java polygon worker and reads the marker type and existing request fields;
it performs no live actor lookup, body unwrap, Lua call or cross-thread factory
lookup. No new thread, queue, polling loop or packet contract is introduced.
Existing path worker/callback and native-world threading contracts are unchanged.

## Verification and delivery

`py -3 tools/test_server_zombie_path_mover.py` compiles the actual current
ServerZombiePath and both Java/native request initializers with explicit
world/backend doubles. It first uses the exact vanilla PMMover to reproduce the
reported exception, then substitutes the patched class. **155 assertions pass**:

- Native animal, player and zombie classification is preserved.
- Walk-only proxies clear recycled capabilities, including poisoned crawl/thump flags.
- Unmarked unknown movers remain rejected.
- Native AI and proxy requests coexist even for the same native body/instance ID.
- Proxy cancellation preserves native AI requests on both backends.
- Old generations, replaced instance/online IDs and dead bodies reject results.
- Callback output is copied before pooled paths are cleared.
- Cross-floor and invalid/oversized paths retain their existing rejection/delegation.
- A burst of 128 Java-fallback proxy requests completes without mover rejection.

This is production classification/request/callback regression coverage, not a
real-world polygon geometry, native DLL or live crowd benchmark. Compile through
the target `patchApocalipseBr.ps1 -BuildOnly` against the real game JAR. The actual
build passed on 2026-11-04: **327 sources / 946 class files**, retained under
`42.22.1/artifacts/build_20261104_225007/`; no deployment was performed. Deploy
the matching marker/proxy/PMMover classes together using the normal patch process
and restart before replaying the reported crowded battle. Verify Java fallback,
native mode, obstacle boundaries, ownership handoff and absence of repeated
unsupported-mover logs. No migration or client patch is required.

Related: [server-controlled zombie interactions](../../../42.21.0/docs/features/2026-10-22-npc-zombie-control.md),
[group patrol](../features/2026-11-04-npc-group-patrol.md),
[current documentation index](../README.md).
