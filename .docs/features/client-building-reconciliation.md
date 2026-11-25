# Client building reconciliation

Set `-Dapocbr.optimization.clientBuildingReconciliation.enabled=true` alongside
`-javaagent` to enable the synchronous reconciliation optimization at startup.
The switch defaults to false and is independent of the client chunk-map cap.
Remove the argument or set it to false and restart to restore vanilla behavior.

## Original game path

In 42.22.2, `IsoRegions.update()` hands off the worker buffer on the world
thread, resets cached region references, and calls `DataRoot.clientProcessBuildings()`
in local and multiplayer client sessions. The dedicated-server path skips
that call. `DataRoot` passes the changed cells to `WorldRegionToMetaGrid` and
consumes them after successful reconciliation.

The agent ports the [synchronous algorithms](../../42.22.2-client/docs/features/2026-11-12-client-deferred-textures-and-async-translucency.md#building-reconciliation-algorithms)
from the 42.22.2 client patch. The Byte Buddy module transforms the original
`WorldRegionToMetaGrid` at class load:

- An iterator stack and a visited set preserve depth-first ordering, the
  enclosure and roof eligibility checks, and already visited regions.
- An insertion-ordered set backs `allWorldRegions` during creation, reassignment
  and invalidation. The existing per-building assignment lists retain their
  order and duplicate behavior.
- A primitive long set tracks dirty chunks. The key encodes both signed
  coordinates, then mixes the bits for hashing. Reads retain vanilla's
  coordinate conversion and marks require no per-coordinate object allocation.

The original nonempty-pass reset, pool ownership, building and room
publication, and square, lighting and cutaway invalidation stay on the world
thread. The sets use the lifetime of the original reconciliation collections.
The module introduces no worker or file writes. The legacy async geometry
plan and bulk commit are a separate optimization. Transformation checks the
expected private fields, signatures and collection access sites, with Byte
Buddy reporting a transformation error if those anchors differ.

## Validation

- `mvn -Dtest=BuildingReconciliationHookTest,ClientChunkMapHookTest package`
  passed on Windows with the original 42.22.2 game JAR. SHA-256
  `3C4A7C5F149D784758C6FB5F8FA85F3A21D572E38BC081400E89035F8120D287`.
- The forked fixture covers enabled, disabled and default startup, including
  depth-first ordering, cycles, excluded neighbors, a 12,000-region chain,
  reassignment ordering, signed chunk keys, and normal and failed resets
  through the original `DataRoot` entry point.
- The shaded artifact passed the same fixture using the original game archive
  and an isolated probe classpath. Both client switches were enabled together
  in the packaged smoke run.

Live acceptance remains open for building edits, room/lighting transitions,
and frame-time comparisons during streaming. The legacy worker capture timing
includes geometry planning and commit changes from a different implementation.
