# ApocBR patch port report: 42.20.1 -> 42.20.3

Generated from local comparison of:

- Old stable vanilla base: `42.20.1/decompiled`
- Old stable patched source: `42.20.1/src`
- New vanilla base: `42.20.3/decompiled`

## Executive summary

- `42.20.1/src` contains 55 patched Java files.
- 44 patched files have an unchanged vanilla base in `42.20.3`; these are safe to copy directly.
- 3 files are ApocBR-only support classes absent from both vanilla bases; these are safe to copy directly, then compile-check their call sites.
- 4 patched files sit on a changed `42.20.3` vanilla base but three-way merge cleanly. Do not direct-copy them; rebase or apply the patch hunks so the new vanilla changes are preserved.
- 5 patched files conflict against the `42.20.3` vanilla base and require manual rebase.

## Whole vanilla tree delta

Across `42.20.1/decompiled` -> `42.20.3/decompiled`:

- Same Java files: 3017
- Modified Java files: 58
- Added Java files: 3
- Removed Java files: 1
- Old total: 3076
- New total: 3078

Added in 42.20.3:

- `zombie/network/packets/ChunkNotReadyPacket.java`
- `zombie/popman/PoolCaps.java`
- `zombie/util/CappedConcurrentQueue.java`

Removed in 42.20.3:

- `zombie/network/packets/character/PlayerVisitedPacket.java`

Modified areas most relevant to our patches:

- `zombie/iso/*`: chunk/grid-square pool and unload/cache behavior changed.
- `zombie/network/*`: max-player/slot handling, login flow, packet/request behavior changed.
- `zombie/pathfind/nativeCode/*`: pathfind task pool cap changed.
- `zombie/vehicleNetworkSound/server/Manager.java`: connection array size changed.
- `zombie/gameStates/IngameState.java`: PoolCaps hook and FPS graph visibility logic changed.

## Safe to direct-copy

These `42.20.1/src` files can be copied as-is to `42.20.3/src` because the corresponding `42.20.3/decompiled` vanilla file is byte-identical to `42.20.1/decompiled`.

- `zombie/ai/ZombieGroupManager.java`
- `zombie/characters/ecs/ECSComponent.java`
- `zombie/characters/ecs/ECSEntity.java`
- `zombie/characters/IsoZombie.java`
- `zombie/characters/NetworkPlayerAI.java`
- `zombie/core/skinnedmodel/advancedanimation/AnimationSet.java`
- `zombie/entity/ComponentContainer.java`
- `zombie/entity/components/crafting/CraftLogicSystem.java`
- `zombie/entity/components/crafting/DryingLogicSystem.java`
- `zombie/entity/components/crafting/FurnaceLogicSystem.java`
- `zombie/entity/components/crafting/MashingLogicSystem.java`
- `zombie/entity/components/fluids/FluidContainerUpdateSystem.java`
- `zombie/entity/components/resources/LogisticsSystem.java`
- `zombie/entity/components/resources/ResourceUpdateSystem.java`
- `zombie/entity/components/spriteconfig/SpriteConfig.java`
- `zombie/entity/EngineEntityManager.java`
- `zombie/entity/EntityBucket.java`
- `zombie/entity/GameEntity.java`
- `zombie/entity/InventoryItemSystem.java`
- `zombie/entity/UsingPlayerUpdateSystem.java`
- `zombie/inventory/ItemContainer.java`
- `zombie/iso/IsoMovingObject.java`
- `zombie/iso/LosUtil.java`
- `zombie/iso/objects/IsoDeadBody.java`
- `zombie/LoadGridsquarePerformanceWorkaround.java`
- `zombie/Lua/Event.java`
- `zombie/Lua/MapObjects.java`
- `zombie/MovingObjectUpdateScheduler.java`
- `zombie/MovingObjectUpdateSchedulerUpdateBucket.java`
- `zombie/network/BodyDamageSync.java`
- `zombie/network/id/ObjectIDManager.java`
- `zombie/network/id/ObjectIDType.java`
- `zombie/network/packets/RequestItemsForContainerPacket.java`
- `zombie/network/packets/SyncItemActivatedPacket.java`
- `zombie/network/ServerChunkLoader.java`
- `zombie/network/ServerLOS.java`
- `zombie/pathfind/LineClearCollideMain.java`
- `zombie/pathfind/nativeCode/PathfindNative.java`
- `zombie/pathfind/PolygonalMap2.java`
- `zombie/popman/NetworkZombieList.java`
- `zombie/popman/NetworkZombieManager.java`
- `zombie/popman/NetworkZombiePacker.java`
- `zombie/popman/ZombieCountOptimiser.java`
- `zombie/popman/ZombiePopulationManager.java`

ApocBR-only support classes, also safe to direct-copy:

- `zombie/ApocBRServerTelemetry.java`
- `zombie/ApocBRTelemetrySampler.java`
- `zombie/entity/MetaSimulationThrottle.java`

## Rebase, but low risk

These files have upstream vanilla edits in 42.20.3, but the 42.20.1 ApocBR patch merges cleanly in a three-way merge. Do not overwrite the whole file with the 42.20.1 patched version.

| File | 42.20.3 vanilla change | ApocBR patch surface | Recommendation |
| --- | --- | --- | --- |
| `zombie/gameStates/IngameState.java` | Adds `PoolCaps.onLoadingFinished()` and changes FPS graph display outside `Core.debug`. | Telemetry timings and server-side `SearchMode`/`RenderSettings` skip. | Rebase patch hunks onto 42.20.3 and keep PoolCaps/FPS changes. |
| `zombie/iso/IsoCell.java` | Lazily allocates client `gridSquares[playerIndex]` arrays via `getOrCreateGridSquares`. | Telemetry timings in update paths. | Rebase cleanly; preserve lazy allocation. |
| `zombie/iso/IsoChunk.java` | Replaces `loadGridSquare` queue with `CappedConcurrentQueue`, renames `discardSquares` to `unlinkSquares`, uses `softClear`, removes chunk from load queue. | Incremental `removeFromWorld`, unload telemetry, local border recalc helper, load-grid-square loop optimization. | Rebase cleanly; keep upstream queue/unlink changes. |
| `zombie/iso/IsoWorld.java` | Copies `IsoChunk.loadGridSquare` through `copyTo` before iteration. | Boolean decompile cleanup and telemetry timings. | Rebase cleanly; keep `copyTo` pattern for the new capped queue. |

## Manual rebase required

These conflict under a three-way merge and should not be copied directly.

### `zombie/iso/IsoGridSquare.java`

42.20.3 vanilla changed:

- `isoGridSquareCache` changed from `ConcurrentLinkedQueue<IsoGridSquare>` to `CappedConcurrentQueue<IsoGridSquare>(16384)`.
- Import changed from `java.util.concurrent.ConcurrentLinkedQueue` to `zombie.util.CappedConcurrentQueue`.

ApocBR patch changes:

- Server LOS lighting slot handling.
- Thread-local `Vector2` scratch buffers for concurrent LOS calculations.
- `lighting.length`-based reset.
- Allocates all `ServerLOS.ServerLighting` slots.
- Adds direct-player `checkRoomSeen(IsoPlayer)` overload.

Rebase guidance:

- Keep the 42.20.3 `CappedConcurrentQueue` import and `isoGridSquareCache` field.
- Apply ApocBR LOS/thread-local/checkRoomSeen changes on top.
- Direct-copying the 42.20.1 file would revert Indie Stone's new capped grid-square cache.

### `zombie/network/GameServer.java`

42.20.3 vanilla changed:

- Adds `PoolCaps`.
- Replaces public `MAX_PLAYERS = 512` pattern with `NO_FREE_SLOT = -1`.
- `SlotToConnection` is now sized `255`.
- `UdpEngine` is constructed with `255`.
- Sends `ServerOptions.getInstance().getMaxPlayers()` instead of hardcoded `512`.
- `getFreeSlot()` is now private.
- `addConnection` handles no-free-slot by sending `AccessDenied`/`ServerFull` and force-disconnecting.
- Adds `PoolCaps.onLoadingFinished()` and `PoolCaps.updateServerCaps()`.
- Revision string changed from `77d7d5d0d7` to `70207f62e0`.

ApocBR patch changes:

- Starts `ApocBRTelemetrySampler`.
- Adds detailed telemetry around main-loop queues, packet handling, login/player connect phases, tick sections, and world tick logging.
- Adds `getApocBRPacketTypeName`.

Rebase guidance:

- Keep all 42.20.3 slot/max-player/PoolCaps/login-full-server behavior.
- Reapply telemetry around the new 42.20.3 login/connect structure.
- Watch `SlotToConnection` and vehicle/network arrays that may still assume 512 slots.
- Direct-copying the 42.20.1 file would regress player-slot limits and server-full handling.

### `zombie/network/ServerMap.java`

42.20.3 vanilla changed:

- Removes the old cancelled-cell cleanup scans over `loadedCells` and `ServerCell.loaded2`.
- Keeps only the `toLoad`/`tempCells`/`loaded` processing loops, with loop variable cleanup.

ApocBR patch changes:

- Reduces save-cell worker threads from 4 to 1.
- Adds extensive server-map pre/post/unload telemetry.
- Optimizes unload/update sections.
- Adds `ServerCell.getGridSquareLocal`.
- Fixes a decompile bug in `load2` by assigning `sq = ServerMap.instance.getGridSquare(...)`.
- Uses local grid-square lookups for border recalculation.

Rebase guidance:

- Start from 42.20.3 `ServerMap.java`.
- Reapply telemetry and worker-thread change around the new simplified cancellation/loading loops.
- Keep the ApocBR `sq = ...` bug fix and local border lookup optimization.
- Do not restore the removed cancelled-cell cleanup loops unless testing proves 42.20.3 still needs them.

### `zombie/pathfind/nativeCode/ChunkUpdateTask.java`

42.20.3 vanilla changed:

- `ObjectPool<ChunkUpdateTask>` now has an explicit cap: `new ObjectPool<>(ChunkUpdateTask::new, "ChunkUpdateTask.pool", 2048)`.

ApocBR patch changes:

- Adds stale-chunk guard before `PathfindNative.updateChunk(...)`.
- Skips native update if `PathfindNative.activeChunkLoadIds` does not match the task `loadId`.

Rebase guidance:

- Keep the 42.20.3 pool cap.
- Reapply the stale-chunk guard in `execute()`.
- Direct-copying 42.20.1 would remove the new pool cap.

### `zombie/vehicleNetworkSound/server/Manager.java`

42.20.3 vanilla changed:

- `connections` array size changed from `512` to `256`.

ApocBR patch changes:

- Adds `apocbr.vehicleSoundUpdateIntervalTicks` throttle.
- Adds null/index guards in `updateConnection`.

Rebase guidance:

- Keep the 42.20.3 `Connection[256]`.
- Reapply throttle and guard logic.
- Confirm the intended max connection slot with the new `GameServer`/`UdpEngine` `255` limit.

## Suggested port order

1. Copy the 47 direct-copy files into `42.20.3/src`.
2. Rebase the 4 clean-merge changed-base files.
3. Manually rebase the 5 conflict files, prioritizing `GameServer`, `ServerMap`, `IsoGridSquare`, `ChunkUpdateTask`, then vehicle sound.
4. Compile.
5. Smoke-test server startup, login/full-server rejection, chunk load/unload, LOS, pathfind queue backlog, and vehicle sound updates.

