# Original 42.22.2 chunk integration and detachment

`ServerMap.preupdate` publishes loaded cells while the server LOS barrier is
held. `ServerCell.RecalcAll2` handles the perimeter, sets square properties
dirty, integrates chunks with `IsoChunk.doLoadGridsquare` and finishes
room-level bookkeeping. `ServerCell.loadVehicles` subsequently queues vehicle
database reads. Unload runs from map postupdate or failed integration and
detaches each chunk before vehicle save capture, save queue publication, and
removal of the chunk from the cell.

## What executes on the world owner

| Entry point | Work in execution order |
| --- | --- |
| `doLoadGridsquare` | Set hot-save guard. Handle meta animal zones, vehicle spawn, zone/ranch stories, regrowth and procedural population. Update chunk state, ragdolls, and corpse counts. Register vehicles. Walk the levels to add objects and static movers, count tree and water tiles, run erosion and map-object/Lua callbacks. Register with global objects, native collision/population, Java or native pathfinding, generators and loot. Resolve buildings and run building randomizers. Do a second pass for overlays and initial container fill, notify neighboring chunks, answer pending client object-state requests, clear the hot-save guard and emit the Lua chunk event. |
| `removeFromWorld` | Remove the chunk from the load queue and set the hot-save guard. Unregister collision and population, request a coalesced population-cell save, queue pathfinding removal and clear collision. Walk live squares and clear weather, water, puddles, rooms and zones. Run moving, regular, and static-object lifecycle callbacks, unlink cross-chunk references, soft-clear the square and clear its chunk pointer. Handle remaining vehicles and corpse state, then restore the hot-save guard. |

The call-site probe measures only invocations inside these methods. Its totals
exclude file I/O and the work performed after worker handoffs. Per-call
timers add overhead, especially when timing very small callbacks. Interpret
their sums as attribution within an instrumented run, and retain the parent
chunk and cell totals for comparison.

## Baseline on the original JAR

The Windows `vehicles72` fixture exercised 4,608 first-generation chunks and
three saved reloads of 3,456 chunks each. The parent timers and selected
call-site timers below are inclusive. Worker execution is outside the table.

| Site | First generation | Median warm cycle |
| --- | ---: | ---: |
| `doLoadGridsquare` | 13,615 ms | 505 ms |
| Per-square erosion | 5,353 ms | 27 ms |
| Loot and overlay pass | 3,942 ms | 9 ms |
| Object registration callbacks | 877 ms | 230 ms |
| `MapObjects.newGridSquare` | 600 ms | No calls |
| Java pathfinding snapshot and enqueue | 190 ms | 47 ms |
| `removeFromWorld` | 1,424 ms | 534 ms |
| Object removal callbacks | 796 ms | 276 ms |
| Cross-chunk adjacency cleanup | 89 ms | 41 ms |
| Animal population removal | 76 ms | 15 ms |
| Zombie population removal | 82 ms | 9 ms |

Initial erosion creates square data and validates spawns. The overlay pass
can fill containers and run item-picker and overlay logic. On reload, the
saved erosion and overlay flags avoid most of that work. Per-object
registration and removal are the largest measured warm subphases. Those
callbacks run entity registration, container and item-process handling,
generator checks, and subclass-specific behavior. A smaller synchronous
change can remove repeated sprite property lookups while keeping the
callbacks. The unload adjacency change reduces reads without omitting any
required reverse-link clear.

## Synchronous changes

- The container hook runs during owner-thread chunk integration, on exact
  vanilla `IsoObject` instances with a sprite and no existing container.
  It skips the native body only when the sprite has neither the container
  flag nor the freezer property. Those two checks cover all possible writes
  in the original body for a plain object. Subclasses and container-bearing
  sprites use the original method. Scope resets on normal return and
  exceptions. The startup switch is
  `-Dapocbr.optimization.chunkContainers.enabled=false`.
- The adjacency hook preserves vanilla perimeter classification and all
  eight ordered directions. It reads each neighbor once and clears both
  reverse representations when that neighbor belongs to a different chunk.
  Interior squares reach the existing no-op outcome without floating-point
  coordinate conversion. Extreme coordinates and other threads keep the
  original method. The startup switch is
  `-Dapocbr.optimization.chunkAdjacency.enabled=false`.

Telemetry counts skipped container bodies and interior adjacency no-ops
per chunk. The integration fixture alternates the native and optimized
paths across the same chunk coordinates, then checks the real streamed
lifecycle. See [test instructions](../validation/server-integration.md#chunk-integration-and-detachment-profiling).

Each change defaults on. The final paired run used the original 42.22.2
JAR on Windows and matched 6,912 warm chunk integrations and detachments
per arm. The container arm offered 771,022 calls with the hook bypassed and
771,027 with it active. Both adjacency arms offered 500,422 calls.

| Changed site | Work skipped in optimized arm | Vanilla wall time per offered call | Optimized wall time per offered call |
| --- | ---: | ---: | ---: |
| Container body | 740,270 | 95.1 ns | 66.8 ns |
| Interior adjacency operation | 282,626 | 168.2 ns | 111.8 ns |

The test required faster normalized wall time for both sites and checked
plain, regular, existing, and freezer-only containers, 288 interior
reverse links, and 129 cross-chunk reverse links. It also completed
generation, saved reloads, unloads, save drain, and graceful shutdown.
The rollback smoke run also passed against the original JAR with both
startup switches off and verified that the hook calls were absent.
These timings support the individual optimizations. The fixture is
playerless and does not assess whole tick latency or connected client
state delivery.

Source references are [IsoChunk](../../42.22.2/decompiled/zombie/iso/IsoChunk.java),
[IsoObject](../../42.22.2/decompiled/zombie/iso/IsoObject.java),
[IsoGridSquare](../../42.22.2/decompiled/zombie/iso/IsoGridSquare.java),
and [ServerChunkLoader](../../42.22.2/decompiled/zombie/network/ServerChunkLoader.java).

## Asynchronous boundaries

- `ServerChunkLoader` already owns separate load, recalc and save workers. The
  load path parses data into a chunk before publication, while the save path
  receives it after detachment. The save worker's ability to serialize ownership
  transferred by the engine does not imply a live chunk can be shared safely.
- `PolygonalMap2.addChunkToWorld` copies square properties, movement costs and
  slopes on the owner before it enqueues the detached task. The Java pathfinding
  worker consumes that copy. Its unload task already contains just coordinates
  and the owning map. Separating input capture from pure computation could be
  viable if measurement finds expensive derivation after a small, immutable
  copy. Detached work would still need ordered publication and a generation
  check when the same coordinates unload and reload.
- Chunk integration publishes objects and can run arbitrary mod Lua. Later
  stages depend on earlier mutations. Load and unload touch native collision
  and population, rooms, entity indexes, grid adjacency and pooled instances.
  These operations currently share the owner and the map/LOS lifecycle
  barriers. A general worker handoff for either whole method would race those
  observers or delay side effects past the original tick.
- Fresh erosion has a testable split between coordinate-based noise
  calculation and the owner-side validation and application that mutate
  square erosion data. Any future offload would need immutable erosion
  parameters and an epoch check. The overlay stage invokes the item picker,
  modifies containers, and may run Lua or send inventory changes. The
  current measurements do not justify sharing that state with a worker.
- An async prototype would need measured queue wait, a bounded outstanding
  budget and a test for load/unload/reload of the same coordinates while work
  is pending. Use generation-based rejection before publication and safe
  disposal of detached data on cancellation or shutdown. The current change
  keeps the original thread and call order.

The burst size is also a separate variable. `ServerMap.preupdate` pulls
new recalculation completions while it drains the current batch under the
LOS barrier. A synchronous budget for completing whole cells could bound
the per-tick publication burst. Such a policy needs a separate test for
increased time-to-visible, movement and cancellation, LOS resume, and
pending client state requests.
