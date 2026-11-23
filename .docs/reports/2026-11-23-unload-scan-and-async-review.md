# Cell unload scan optimization and thread ownership

## Owner-thread changes

The legacy 42.22.2 `ServerMap` used a set to avoid repeated searches in
`releventNow`. The agent records positions for native relevance appends and
checks those positions on recurring requests and eviction decisions. The
original list still determines the answer. A positive hint must resolve to
the identical cell at that position. A miss uses the original linear search.
This also covers public list replacement, removal, insertion and set
operations, including direct edits outside the instrumentation. Frame reset
drops the hints. Only the canonical server map on its owner thread uses them.
See the [legacy ServerMap](../../42.22.2/src/zombie/network/ServerMap.java)
and [agent hook](../../src/main/java/cloud/apocalipse/pz/agent/features/optimization/streaming/CellRelevanceModule.java).

The legacy bucket code could use either a simulation index or a using-player
index to locate an entity before vanilla swap removal. The agent previously
maintained both positions but only used the simulation position to remove the
native array entry. The server's general ISO object bucket has the using-player
index and may have no simulation index. The removal hook now reuses the
validated position already available during using-player index maintenance.
The native array mutation stays between the original checks and the bucket-bit
clear and listener callbacks. The global engine removal index is a separate
optimization that was already in the agent.
See the [legacy bucket](../../42.22.2/src/zombie/entity/EntityBucket.java),
[agent bucket hook](../../src/main/java/cloud/apocalipse/pz/agent/features/optimization/entity/EntityDueIndex.java)
and [position maintenance](../../src/main/java/cloud/apocalipse/pz/agent/features/optimization/entity/UsingPlayerIndex.java).

`map.cell.relevanceScansSkipped` counts verified positive hints. It includes
renewal and eviction checks and is flushed on map preupdate.
`entities.removal.usingPlayerBucketIndexed` counts actual native removals
that use the using-player index when the simulation index is absent. It can
include removals outside streaming. The existing entity simulation switch
`-Dapocbr.optimization.entitySimulation.enabled=false` disables the latter
along with that module's other hooks.

## Dedicated-server measurements

Windows, Java 25, and the original 42.22.2 JAR with SHA-256
`3C4A7C5F149D784758C6FB5F8FA85F3A21D572E38BC081400E89035F8120D287`.
The `players30` fixture uses 30 synthetic positions, 45 cells and 2,880
chunks per cycle. The table uses the five saved reload cycles after fresh
generation. Phase durations are inclusive.

| Measurement | Baseline | Both changes | Bucket change only, current default |
| --- | ---: | ---: | ---: |
| Cell unload, median ms per warm cycle | 414.063 | 358.112 | 409.718 |
| Chunk detach, median ms per warm cycle | 405.393 | 346.937 | 401.723 |
| Map postupdate, median ms per warm cycle | 511.015 | 445.700 | 483.812 |
| Relevance, aggregate ns per warm request | 45.3 | 60.1 | 43.8 |

All three servers passed the fixture and shut down cleanly. Both changed
runs recorded 40,485 indexed bucket removals across received snapshots,
including three from the oracle. The combined run also recorded 281,881
indexed relevance hits, including one from the oracle. The default removes
40,482 native bucket scans in the recorded workload. Its 1% median unload
difference is small enough that this sequence does not establish a reliable
wall-time improvement. The combined timing varied more than the isolated
change and cannot establish causation. The relevance port is opt-in because
it raised request cost in the matched workload. Its flag is
`-Dapocbr.optimization.cellRelevance.enabled=true`.

The oracles used original game objects on the dedicated-server thread. They
compared relevance with the native list after direct insertion, replacement
and removal. They exercised the transformed `EntityBucket.updateMembership`
with only a using-player index, verified the tail swap, and removed the
relocated tail and final member. The standard fixture verified generation,
relevance renewal, exact cell and chunk detachment counts, worker save and
release, and reload after save drain. Connected clients, immediate reload
while writes are pending, and failure injection are outside this fixture.

Artifacts are under `target/server-integration`. The baseline is
`run-10200931292320606223`, the combined run is
`run-2002740168485119714`, and the bucket-only run is
`run-9440034974640636710`. The final build passed the unit and integration
suites. Its isolated child confirmed that the relevance hook was disabled.
Use the command below for the combined comparison. Omit the relevance setting
to use the current default.

```powershell
mvn verify -Pserver-integration "-Dpz.it.streaming=true" "-Dpz.it.streamingProfile=players30" "-Dpz.it.heap=6g" "-Dapocbr.optimization.cellRelevance.enabled=true" "-Dpz.it.streamingBaseline=target/server-integration/run-10200931292320606223"
```

## Async safety review

| Stage | Ownership and ordering to preserve |
| --- | --- |
| Cell visibility and LOS | `ServerMap.postupdate` suspends LOS before eviction and resumes it in a finally block. The agent LOS barrier cancels work and waits for active readers to leave. Unload runs before the cell map entry is cleared. The barrier coordinates LOS readers while the tick thread owns the map and its publication. Failed integration also calls `Unload` under its map lifecycle. |
| Square, object and entity teardown | The tick thread changes rooms, zones, live object lists, entity bucket membership, cross-chunk reverse links and square references. Callbacks can mutate those same lists. For example, the moving-object loop checks membership after the callback and adjusts its cursor when the object has left. Concurrent mutation could invalidate the cursor or clear a square while a callback still uses it. Container, generator, corpse, and emitter teardown are part of these callbacks. Their side effects must finish in order before recycling. |
| Animal and zombie population | The owner invokes native population APIs and walks live moving-object lists before ordinary square teardown. Zombie virtualization also holds a save lock around selected work and can remove actors from the very list it scans. The Java map, animal state, the initial native chunk event, and other indexes have separate ownership requirements. JNI thread-safety and ordering against population jobs are unverified. A worker would need value-only input and a defined, ordered native-owner command API. |
| Collision and pathfinding | The vanilla `MapCollisionData.removeChunkFromWorld` body is effectively empty. The chunk still clears its own collision state. Java and native pathfinding already enqueue coordinate-based removals to their workers. Delaying the enqueue beyond an ensuing load risks removing the replacement pathfinding entry at the same coordinates. Preserve add/remove order and any owner-side commit checks. |
| Vehicles | Unload clears the chunk's vehicle-load request and captures vehicle state for the database queue. Vehicle load completion checks the current chunk and exact request identity on the server thread before attaching anything. SQL work is already off-thread. Moving live vehicle capture or detachment to a worker would race physics, simulation, and completion publication. |
| Chunk save and reuse | `SaveUnloadedTask` receives a detached chunk. The loader can resolve a newer pending save before going to disk. The task serializes and copies under its own monitor. The save worker publishes successful completion, and the owner drains it before transferring the chunk to `WorldReuser`. The reuser clears squares and returns eligible instances to pools. A reference retained across this boundary may point at a recycled incarnation. A save failure bypasses the normal completion path. Any redesigned handoff must define failure behavior, retry, shutdown, and what an immediate reload sees. |

The ownership analysis follows these call sites.
[map eviction and failed integration](../../42.22.2/decompiled/zombie/network/ServerMap.java),
[agent LOS barrier](../../src/main/java/cloud/apocalipse/pz/agent/features/optimization/los/ServerLosRuntime.java),
[chunk detachment](../../42.22.2/decompiled/zombie/iso/IsoChunk.java),
[zombie population](../../42.22.2/decompiled/zombie/popman/ZombiePopulationManager.java),
[animal population](../../42.22.2/decompiled/zombie/characters/animals/AnimalPopulationManager.java),
[Java pathfinding](../../42.22.2/decompiled/zombie/pathfind/ChunkRemoveTask.java),
[native pathfinding](../../42.22.2/decompiled/zombie/pathfind/nativeCode/ChunkRemoveTask.java),
[vehicle load and save](../../42.22.2/decompiled/zombie/vehicles/VehiclesDB2.java),
[chunk save queue and reload lookup](../../42.22.2/decompiled/zombie/network/ServerChunkLoader.java),
[square and chunk reuse](../../42.22.2/decompiled/zombie/iso/WorldReuserThread.java).

### Next concurrency candidate

`SaveChunkThread.findPendingChunkTask` scans the active task and the entire
pending queue under the same `taskLock` that unload uses to enqueue each chunk.
A long backlog can make reload lookup expensive and can delay the tick's
enqueue indirectly. The current streaming fixture waits for save drain, so
its measurements cannot establish the size of that effect. Measure queue
depth, pending lookup and enqueue wall time before choosing this change.

If it proves material, index the newest pending task per coordinate inside the
existing save-queue owner. Keep the FIFO write order and replacement by task
identity. Snapshot coordinates before publication. Define how the last good
buffer remains discoverable through completion, worker failure and same-key
reuse. The test needs to block the save worker deterministically, demand
the same coordinate, prove that newer in-memory bytes win, and exercise a
failed write, subsequent retry, pool reuse and shutdown. This isolates the
queue scan from the live-world lifecycle.

Whole-cell worker detachment would require equivalent ownership and
publication changes across every subsystem in the table. In particular,
removing cells from the map early leaves live registrations visible elsewhere,
while retaining them for a deferred unload increases live simulation work.
The current optimization applies during the original tick and retains the
engine's existing asynchronous save, vehicle and pathfinding handoffs.
