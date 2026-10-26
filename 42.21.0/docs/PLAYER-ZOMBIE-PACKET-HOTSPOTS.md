# Player updates and zombie packet preparation: 42.21.0 investigation

Source inspection: 2026-10-05. Target: dedicated server, existing patched source plus exact 42.21.0 vanilla dependencies. The investigation below is retained as design context. The implementation status and validation are recorded here; the runtime patch is present in the working tree.

## Measured starting point

Snapshot seq 24 spans 30,042 ms and 253 ticks with 32 players. Human-player moving-object updates consume 4,772.759 ms (18.865 ms/tick); zombie packet preparation consumes 2,370.944 ms (9.371 ms/tick). Player batch maximum is 499.794 ms. These are elapsed timings, not exclusive CPU profiles. Internal attribution and allocation rates are not yet measured.

The player timer covers `preupdate()`, `frameStep()` and `update()` together. Its phase calls count bucket batches; the attempt counter reports 7,995 individual player chains. Packet preparation times `zombie.zombiePacket.set(zombie)` inside the global zombie loop, separately from ownership reassessment. Serialization, relay selection, incoming packets and network sends are outside that timer.

## Actual player call flow

`MovingObjectUpdateSchedulerUpdateBucket.update()` -> `IsoPlayer.preupdate()` -> inherited character/object preupdate; `ECSEntity.frameStep()` -> a traversal of that character's ECS components; `IsoPlayer.update()` -> `updateInternal1()` -> `updateInternal2()`.

The server creates network players with `remote = true`. Their live branch calls `updateRemotePlayer()` (LOS scheduling/result consumption, network position application, square membership updates and movement Lua events), then proximity checks, movement-rate calculation and endurance. When the branch returns true, `updateInternal1()` calls `IsoGameCharacter.update()`. That inherited update runs health/thermoregulation, stats, state machine, inventory item updates and other character work. The direct LOS call in `updateInternal1()` is guarded by `!remote`; ordinary server players instead reach it through `ServerLOS.updateLOS()`.

### 1. Full vehicle traversal on every live remote-player update

Sources: [IsoPlayer](src/zombie/characters/IsoPlayer.java), lines 2272-2273; [IsoGameCharacter](decompiled/zombie/characters/IsoGameCharacter.java), line 14348.

`checkIsNearVehicle()` traverses `currentCell.getVehicles()` until a vehicle satisfies `DistTo(this) < 3.5F`. It does this even when the player is not sneaking. In this player branch its return value is discarded; its only state write sets `nearWallCrouching` when sneaking. The preceding wall check already handles resetting that state.

With 7,995 player attempts and an ending population of 1,084 vehicles, scanning every vehicle would mean approximately 8.67 million distance checks. This is a workload estimate, not an observed counter: dead-player early exits, nearby early matches and changing populations reduce or change it.

First change: skip this call in the dedicated-server player branch when not sneaking, preserving the base method's return contract for other callers. Second change: query nearby vehicles through spatial membership, retaining the exact original distance test. Track vehicle movement, addition/removal, unload and reuse. Do not substitute a different distance metric without verifying equivalence. Wall checking itself visits a bounded number of adjacent squares and is a lower-priority algorithmic target.

### 2. Thermoregulation has a shared persistent clothing cache

Sources: [Thermoregulator](decompiled/zombie/characters/BodyDamage/Thermoregulator.java), lines 43-45, 666 and 989; [BodyDamage](decompiled/zombie/characters/BodyDamage/BodyDamage.java), line 2113.

`itemVisualsCache` is static, but the thermal nodes and their clothing lists belong to each Thermoregulator. `updateClothing()` compares the current character's visuals against the shared cache. Updating character B after character A normally fails the identity comparison even if B's equipment has not changed. It clears B's node clothing lists and rebuilds clothing-to-body-part membership, then overwrites the shared cache; A's next update normally misses again.

This is a concrete cache ownership problem, not proof that thermoregulation consumes most of the measured 18.865 ms. Ordinary living, non-god-mode server players reach thermoregulation through BodyDamage.Update; it is not client-only.

Make the persistent cache instance-owned. The temporary static scratch buffers are a separate threading concern; preserve the current execution assumptions or make them instance-owned too if concurrency requires it. Preserve the existing identity/size invalidation initially, and separately audit insulation/coverage changes that do not replace visual identities. Add a clothing rebuild counter to verify that unchanged equipment stops rebuilding across players. Retain real-time heat/health integration rather than broadly throttling it.

### 3. Recursive inventory discovery runs every character update

Source: [IsoGameCharacter](decompiled/zombie/characters/IsoGameCharacter.java), lines 9859 and 9955-9978.

All non-zombie characters recursively visit every inventory entry, including nested containers. Only entries implementing IUpdater receive `update()`: DrainableComboItem, HandWeapon, Radio and WeaponPart in this build. Thus ordinary loot is repeatedly traversed to discover a smaller update subset. HandWeapon additionally updates its attachment list; Radio's actual device update is guarded out on dedicated servers, although inventory traversal still visits it.

A maintained updater index could eliminate discovery scans, but it must track nested container mutations, transfers, loads and direct collection writes and preserve mutation/order semantics. HandWeapon attachments must remain represented through their owning weapon unless separately proven safe. Do not remove inactive drainables wholesale: their update handles temperature/cooking and other behavior beyond activation. Some item methods already implement their own minute cadence.

Inventory weight calculation is another uncached traversal (`getInventoryWeight`, line 11997), potentially including nested bag contents through item weights. Count actual calls before deciding whether weight caching belongs in the first patch. Weight invalidation must include use/ammo/content/equipment changes, not only adding or removing items.

### 4. LOS completion still copies/scans every zombie

Sources: [IsoPlayer](src/zombie/characters/IsoPlayer.java), line 5980; [ServerLOS](src/zombie/network/ServerLOS.java), lines 31, 149-177 and 195.

The current server LOS patch already uses an identity lookup and an approximately 2,000 ms scheduling interval. Main-thread consumption runs only for a ready/current worker result. It also processes seen-room squares and invokes metadata behavior before calling player.updateLOS(). Consequently, this is not a full zombie scan every player tick in the current normal path.

On completion, `updateServerZombieAuthorityLOS()` creates `new ArrayList<>(cell.getZombieList())`, traverses all zombies and then rejects dead/ineligible, wrong-floor and distant candidates. Surviving entries can call lead-aggro processing, spotted logic and ZombieControl sends.

Use a spatial query matching the current floor/radius and preserve snapshot/order and callback mutation safety. The LOS visibility window is bounded, but retain all current target/aggro and visibility tests. Reuse an appropriate indexed view only after confirming its lifecycle and ordering contracts. Do not directly iterate the mutable list solely to remove the allocation. Instrument room/metadata application separately from zombie candidate discovery and spotted callbacks; either can contribute to completion spikes.

### 5. Lower-priority or conditional work

`updateMechanicsItems()` scans the mechanics history every update while nonempty, allocates a removal list and repeatedly obtains calendar time. Its expiry threshold is one game day. Capture time once and consider an expiry schedule or periodic check with explicit expiry-latency semantics; a simple iterator removal avoids the temporary list without adding delay.

`calculateStats()` calls the CalculateStats Lua hook and sound-related stress logic. `WorldSoundManager.getStressFromSounds()` scans the global sound list for stress-producing nearby sounds. This is another player-times-global-list candidate; measure sound population and candidates before building an index, preserving its Manhattan distance/radius semantics.

Fitness.update already gates substantial work to ten in-game minutes. Numerous sounds/UI helpers have server guards or trivial bodies. Avoid treating every visible method invocation as expensive or removing a sound helper that also deactivates an exhausted item. Movement, combat, health, endurance and authoritative network state require preservation of their current timing.

## Actual zombie packet preparation flow

[NetworkZombiePacker](src/zombie/popman/NetworkZombiePacker.java), line 188: every tick traverses the zombie list; ownership update is followed unconditionally by `zombiePacket.set(zombie)` even when ownership reassessment was skipped.

[ZombiePacket.set](decompiled/zombie/network/packets/character/ZombiePacket.java), line 257 -> [NetworkZombieAI.set](decompiled/zombie/characters/NetworkZombieAI.java), line 167 -> boolean flags, animation state, prediction and extrapolation history; then NetworkZombieMind.set, walk/grapple fields and update-bit calculation.

At a constant 5,025 zombies for 253 ticks, this would produce approximately 1.27 million preparations. Endpoint population is not an exact preparation count; add that counter. The six existing connection workers select relay candidates; they do not execute this global packet preparation. Increasing their number therefore does not directly address this timer.

### 1. Duplicate walk-type conversion

NetworkZombieAI.set converts the `zombieWalkType` animation variable to WalkType (line 175). ZombiePacket.set then overwrites packet.walkType from `chr.getWalkType()` (line 266). No code between these assignments uses the first value in the inspected call chain. Both conversions linearly search 13 enum values through `values()` and case-insensitive comparisons.

The inspected source search finds ZombiePacket.set as the direct caller of NetworkZombieAI.set(ZombiePacket). The similarly named PlayerPacket call targets NetworkPlayerAI instead. Removing the redundant conversion is therefore a concrete candidate; still preserve the public method's contract for external/mod callers, for example through an internal preparation variant, or explicitly justify that compatibility tradeoff before implementation.

### 2. Enum lookup and temporary objects

NetworkVariables.ZombieState.fromString lowercases and searches 41 enum entries through `values()`; WalkType.fromString searches 13. The source expresses a fresh values-array clone for each call, although JIT optimization may eliminate some allocations. Use immutable cached enum arrays first to preserve exact case/null/default behavior without the clones. A lookup map or per-zombie last-string cache can remove the search too, but must preserve current string matching and defaults. Do not introduce per-call lowercase allocation or assume locale changes are irrelevant to ZombieState's existing conversion.

NetworkZombieAI allocates a Vector2 for the final direction update and another in moving extrapolation branches. Direction X/Y accessors already exist; use those or an AI-owned scratch vector after auditing reentrancy. NetworkZombieVariables allocates a ShortFlags wrapper to immediately extract a short. A primitive flag encoding helper could avoid that temporary while preserving the public ShortFlags API and all bit ordinals. Allocation savings must be profiled: escape analysis may already eliminate some objects.

### 3. Conditional path-length traversal

WalkToward preparation when `getPath2() == null` calls PathFindBehavior2.getPathLength() (line 754), which can walk remaining nodes and take a square root per segment. Packet logic only tests whether the resulting distance exceeds five.

A threshold-specific helper can stop after the accumulated nonnegative length exceeds five, retaining the same length metric. A suffix-length cache is more involved because paths/indexes mutate. This branch is conditional; when a path is exposed as getPath2 it uses the next path point instead. Count branch frequency and visited nodes before predicting savings.

### 4. Preparing only needed packets is promising but requires separation of effects

The global preparation happens before connection selection, so it prepares zombies that might not be included in any outgoing synchronization packet. Potential design: union selected/requested zombies across connections, prepare each once on the main thread and share the resulting snapshot for serialization. Cover ownership-hash changes, which can add requests during send, as well as relay recipients and the 300-entry cap.

However, set is not a pure serializer: it updates `zombie.realState`, prediction history, `isClimbing`, and `thumpSent`. Zombie.preupdate consumes thumpSent. Incoming owner packets also populate the cached ZombiePacket, and server preparation recomputes it afterwards. Retaining the incoming packet unchanged needs a separate correctness audit.

Separate required per-tick network-AI bookkeeping from demand-driven field preparation first. Preserve owner-transfer/grapple/target/death transitions and packet format. Do not call set concurrently on the same zombie from connection workers. The current awaitWorkers/next-frame lifecycle and mutable packet serialization also mean that deferred selection/preparation must be designed with an explicit frame lifetime, rather than adding a generic dirty flag.

## Recommended first implementation sequence

1. Add aggregated internal timings/counters: player preupdate/frameStep/updateInternal2/base update; remote-player LOS schedule/room application/zombie perception; near-vehicle candidates; BodyDamage/thermoregulation clothing rebuilds; recursive inventory entries/updaters; packet preparations, enum conversions and prediction branches/path nodes. Separate totals from nested children and avoid per-object logging/high-cardinality phase names.
2. Fix persistent thermal clothing cache ownership; skip non-sneaking server near-vehicle calls; remove verified redundant conversion while retaining standalone caller contracts; reduce enum/vector/flag temporary work. These have concrete source evidence without changing tick cadence.
3. Use timings to prioritize spatial vehicle/LOS queries versus inventory updater indexing. Verify lifecycle and callback mutation behavior against the real 42.21 JAR and server scenarios.
4. Consider demand-driven packet preparation after separating side effects; throttle day-scale housekeeping separately. Do not broadly throttle player updates or parallelize live mutable zombie preparation.

No exact milliseconds saved are claimed. Static inspection identifies avoidable work, but the existing telemetry cannot yet rank its internal CPU cost or explain the 499.794 ms player batch conclusively. The implementation described below changes runtime code in the working tree. Nothing has been deployed.


## Implementation and verification after the interrupted session

Implemented in the 42.21.0 working tree:

- Instance-owned thermal clothing cache and scratch buffers. The copied decompiler switch in getSimulationMultiplier was repaired against the original JAR bytecode: Default preserves the game-time multiplier; all other factors multiply that multiplier before simulationMultiplier.
- Non-sneaking dedicated-server players skip the unused near-vehicle query; remaining queries use spatial vehicle candidates and the exact original Manhattan threshold. Membership changes invalidate the view, normal scheduler vehicle movement relocates its entry, and new frames refresh coordinates.
- Server LOS completion shares a zombie spatial view across player queries in a frame, retains zombie-list ordering and the final eligibility/floor/distance/visibility tests, and preserves the existing two-second LOS scheduling. Zombie-list mutations and incoming zombie position updates invalidate the view. Extreme coordinates use bounded loops and unusually large views use a full snapshot fallback.
- Character inventory update discovery uses per-container sparse routes for nested containers and IUpdater entries. MutationTrackedArrayList tracks normal, bulk, iterator and backed-sublist changes. Rebuilds happen on mutation, child-before-parent order is retained, callback mutations resume the vanilla advancing-index traversal, and externally supplied plain lists retain a full-scan fallback and identity.
- Mechanics history scans only when the earliest exact expiry is crossed, on game-clock rollback, or after insertion/load. Expiry remains strictly greater than one game day; removal uses the entry iterator and creates no temporary removal list.
- Player sound-stress discovery shares an ordered spatial view of stress-producing sounds in a frame. It preserves Manhattan distance, floating-point accumulation order and negative-radius behavior. Large radii/small populations use a bounded scan of stress sounds.
- Zombie packet preparation is split into per-auth-frame prediction/state/thump bookkeeping and demand-driven packet fields. Selected zombies are prepared once across recipients; received packets invalidate the field cache, late requests prepare missing bookkeeping, and new frames clear the cache. This remains on the main thread and does not move mutable packet preparation to connection workers.
- Duplicate walk-type conversion is removed from the combined packet path; standalone NetworkZombieAI.set retains its original animation-variable walk-type contract. Cached enum arrays and per-AI last-string results preserve matching, defaults and locale-sensitive state conversion. Direction components avoid temporary vectors; primitive flags retain the public ShortFlags API and bit assignments. A threshold-specific path calculation stops once finite path length exceeds five.
- Added internal player timings and discovery/preparation counters. Inventory weight caching was not introduced: its item/ammo/equipment invalidation requirements were an unmeasured follow-up candidate in the investigation, not part of the concrete first patch. The implementation retains live weight calculations.

Verification:

- `python tools/test_player_packet_algorithms.py`: 44,355 assertions pass against production helpers and extracted production expiry/threshold methods. Coverage includes ordering and callback mutation, plain-list/public-field fallbacks, spatial negative/boundary/extreme coordinates, within-frame invalidation, sound-sum equivalence, packet cache lifetime/incoming/late requests, all 16,384 flag combinations against the vanilla encoder, locale-sensitive enum results, path decisions against the vanilla method, and exact expiry with clock rollback. Packet parse/write/copy source is also checked unchanged.
- `python tools/test_moving_object_algorithms.py`: 531 existing lifecycle/scheduler assertions pass.
- `python tools/test_using_player_algorithms.py`: 190,136 existing using-player index assertions pass.
- Full version dry-run compilation against the installed 42.21 game JAR succeeds: 74 Java sources and 247 class files. No game files are deployed.

Live multiplayer validation is still required for temperature/clothing changes, inventory transfers and item replacements, sneaking near moving vehicles, LOS with chunk unload/reload and teleports, and zombie ownership/grapple/thump transitions. These fixtures establish algorithmic invariants; they do not measure production CPU savings or replace a game-server test.

Spatial views have an auth/simulation-frame lifetime. Custom mods that directly change zombie or sound coordinates/eligibility in the middle of a shared-view frame, bypassing the normal update flow, need to invalidate that view: `cell.getPlayerSpatialQueries().invalidateZombies()` or `WorldSoundManager.instance.invalidateStressIndex()`. This is a remaining integration consideration for such mods.

Per repository instructions, the nine new full vanilla replacement classes were copied and committed separately as vanilla baselines before edits. Behavioral changes remain uncommitted for review; no push was performed.
