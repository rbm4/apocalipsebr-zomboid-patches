# GEM / stateGem optimization report

## Context

Telemetry snapshot before the `IsoPlayer.updateLOS()` dedicated-server fast path showed:

- `gameState.avgMs 727`, `gameState.maxMs 8138.04`
- `stateGem.avgMs 311.79`, `stateGem.maxMs 6962.99`
- `stateMoveUpdate.avgMs 224.03`, `stateMoveUpdate.maxMs 1130.89`
- `stateZombiePopulationMain.avgMs 42.23`, `stateZombiePopulationMain.maxMs 1224.45`

`stateGem` is the ApocBR telemetry label around `GameEntityManager.Update()` in `IngameState.update()`.

Entrypoint chain:

```text
GameServer main loop
  -> IngameState.update()
    -> GameEntityManager.Update()
      -> engine.update()
      -> EntitySimulation.update()
      -> for each owed simulation tick: engine.updateSimulation()
      -> release delayed meta entities
```

## Main failure mode

`EntitySimulation.update()` computes owed simulation ticks from elapsed game time:

```java
long elapsed = currentTimeMillis - lastTimeStamp;
simulationTicksThisFrame = (int)(elapsed / 100L);
```

`GameEntityManager.Update()` then runs all simulation systems once per owed tick:

```java
for (int i = 0; i < simulationTicks; i++) {
    engine.updateSimulation();
}
```

This means a server stall can cause GEM to run multiple full entity-simulation passes in one later frame. If each pass scans large buckets, the catch-up work can create another stall. This is likely the main reason `stateGem.maxMs` reached almost 7 seconds.

## Recommended patch order

## Refined optimization policy

Preserve responsiveness for player-action-driven systems. Crafting start/stop/manual requests, output creation, failed callbacks, fuel cancellation, and resource mutations caused by player interaction should not be delayed behind coarse throttles.

Prefer this order:

1. Algorithmic short-circuit when a system has no work.
2. Dirty/active/requested gates before scanning or doing recipe/resource work.
3. Convert repeated catch-up ticks into one elapsed-time update where the logic is time-progress based.
4. Phase or throttle only idle/background maintenance systems.
5. Drop or cap catch-up debt under overload when the alternative is a multi-second server stall.

The goal is not to slow gameplay systems globally. The goal is to stop server lag from replaying the same broad bucket scans several times in one frame.

### 1. Cap GEM simulation catch-up per frame

Priority: critical
Risk: low to medium

Add a server-side cap to simulation ticks consumed by `GameEntityManager.Update()`, with a configurable default.

Suggested default:

```text
apocbr.gem.maxSimulationTicksPerFrame=1
```

Alternative default if gameplay drift is too visible:

```text
apocbr.gem.maxSimulationTicksPerFrame=2
```

Expected benefit:

- Prevents one lag spike from forcing multiple complete GEM simulation scans in the next tick.
- Converts catastrophic catch-up stalls into gradual simulation drift under overload.
- Should directly reduce `stateGem.maxMs`.

Open design choice:

- Either drop excess simulation ticks by advancing `lastTimeStamp`, or preserve limited debt.
- For server stability, dropping excess debt is preferred. Crafting/fluid/resource simulation can tolerate small temporal loss better than all clients disconnecting.

Preferred refined design:

- Do not blindly replay `engine.updateSimulation()` N times.
- Add an elapsed simulation step concept, for example "requested ticks this frame".
- Consume at most one broad GEM simulation pass per server frame.
- Let systems that can safely jump time use the requested tick count as a multiplier.
- Systems that react to player actions still run every consumed pass and process requests immediately.

Candidate API shapes:

```java
EntitySimulation.getRequestedSimulationTicksThisFrame()
EntitySimulation.getConsumedSimulationTicksThisFrame()
EntitySimulation.getEffectiveGameSecondsThisFrame()
```

For the first patch, a simple cap is enough. Later patches can migrate systems from repeated ticks to effective elapsed-time updates.

### 2. Split `stateGem` telemetry by internal phase

Priority: critical
Risk: low

Current `stateGem` is too broad. Add timings around:

- `gemEngineUpdate`
- `gemEntitySimulationUpdate`
- `gemSimulationTicks`
- `gemEngineUpdateSimulation`
- `gemDelayedMetaRelease`

Also record:

- requested simulation ticks
- consumed simulation ticks
- dropped/deferred simulation ticks

Expected benefit:

- Confirms whether the spike is catch-up tick count, a specific system, or entity add/remove operations.
- Gives us a stable before/after for the catch-up cap.

### 3. Remove or disable empty `LogisticsSystem` work

Priority: high
Risk: low

`LogisticsSystem.updateSimulation()` scans the `Resources` bucket and performs no meaningful work:

```java
if (resources != null && !resources.isValid()) {
}
```

Options:

- Do not register `LogisticsSystem` on dedicated server.
- Or make `updateSimulation()` return immediately on server until real logic exists.

Expected benefit:

- Removes one full `Resources` bucket scan per GEM simulation tick.
- Especially valuable when catch-up tries to run multiple simulation ticks.

This is a pure short-circuit, not a gameplay throttle. The current method has no side effect.

### 4. Throttle broad resource/fluid/crafting bucket scans

Priority: high
Risk: medium

Systems scanning entity buckets every simulation tick:

- `ResourceUpdateSystem`
- `FluidContainerUpdateSystem`
- `CraftLogicSystem`
- `DryingLogicSystem`
- `FurnaceLogicSystem`
- `MashingLogicSystem`

Existing mitigation:

- `MetaSimulationThrottle.shouldSkip(entity)` skips 9/10 meta entities.

Remaining problem:

- Active/non-meta world entities still scan every owed simulation tick.
- Catch-up multiplies all scans.

Recommended approach:

- Add per-system frame phases for inactive/idle entities.
- Keep active/running/requested entities immediate.
- For meta entities, keep or increase the current 10-phase throttle.

Candidate rules:

- `CraftLogicSystem`: full update only if running, start/stop requested, automatic check requested, or inputs dirty. Idle clean automatic entities can skip.
- `FluidContainerUpdateSystem`: skip entirely when no precipitation and no petrol decay candidate; phase rain catchers when raining.
- `ResourceUpdateSystem`: skip resources if not dirty and no non-empty auto-decay energy resource.
- `MashingLogicSystem`: update running mashers from world-age delta; idle entities only need start-request checks.
- `DryingLogicSystem` and `FurnaceLogicSystem`: avoid slot verification every tick; verify only when slot count/resource ids change, resources dirty, or slot cache missing.

Avoid broad throttling for active player-facing crafting. The preferred patch is an active/idle split:

- active or requested entities run now
- idle clean entities skip or phase
- long-running progression uses elapsed time rather than replayed ticks

### 5. Cache furnace/drying slot verification

Priority: medium-high
Risk: medium

`DryingLogicSystem.verifyDryingLogicSlots()` and `FurnaceLogicSystem.verifyFurnaceSlots()` walk input/output slots every simulation tick before actual work.

Optimization:

- Cache last input/output resource ids and slot count in the logic object or system-side weak/cache map.
- Re-run verification only when:
  - input group size changes
  - output group size changes
  - resource ids changed
  - inputs/outputs dirty
  - slot size is zero

Expected benefit:

- Cuts repeated per-slot setup work for idle stations.

Risk:

- Need preserve behavior when scripts/entities mutate resource groups dynamically.

### 6. Reduce Lua callback pressure inside craft updates

Priority: medium
Risk: medium

`CraftLogicSystem.updateCraftLogic()` calls:

```java
logic.onUpdate(craftData);
craftData.luaCallOnUpdate();
```

This happens for every in-progress craft data on every GEM simulation tick.

Optimization options:

- Only call Lua update every N simulation ticks for long-running recipes.
- Or call every tick only for recipes that explicitly need per-tick Lua behavior.
- Preserve start/create/failed callbacks immediately.

Risk:

- Mods may rely on `OnUpdate` cadence for visual/progress side effects.
- Dedicated server likely needs less frequent update callbacks than clients.

Safer alternative:

- Advance `elapsedTime` by effective elapsed time.
- Keep start/create/failed callbacks immediate.
- Call Lua `OnUpdate` once for the aggregated server frame, not once per replayed catch-up tick.
- Do not throttle manual start/stop/request handling.

### 7. Make `InventoryItemSystem` and `UsingPlayerUpdateSystem` incremental

Priority: medium
Risk: low

Both already run once per second, but each scans the whole bucket when they run.

Current systems:

- `InventoryItemSystem`: scans equipped inventory item entities and unregisters those with no valid equip parent.
- `UsingPlayerUpdateSystem`: scans all iso entities and clears stale `usingPlayer`.

Optimization:

- Process a capped number per run with a cursor.
- Keep the one-second interval.
- Reset cursor safely if bucket size shrinks.

Expected benefit:

- Avoids once-per-second spikes when many entities are loaded.

### 8. Review delayed meta release bursts

Priority: medium
Risk: low

`GameEntityManager.Update()` drains the entire `delayedReleaseMetaEntities` queue in one frame.

Optimization:

- Cap releases per frame.
- Keep draining over future ticks.

Expected benefit:

- Prevents entity unload/load churn from creating a single large GEM tail spike.

## Subsystem notes

### `GameEntityManager.Update()`

Composes:

- `engine.update()`
- `EntitySimulation.update()`
- repeated `engine.updateSimulation()`
- delayed meta release drain

Main target:

- Cap catch-up simulation ticks.

### `EntitySimulation`

Uses 100 ms simulation ticks. `getGameSecondsPerTick()` returns 2.4 game seconds. Under server lag, `simulationTicksThisFrame` can grow and multiply GEM simulation work.

Main target:

- Add max-tick cap and explicit dropped/deferred tick telemetry.

### `UsingPlayerUpdateSystem`

Updater system, not simulation updater. Runs every 1000 ms, scans all iso-object entities, clears `usingPlayer` when the player is too far/dead.

Quick win:

- Cursor/capped scan.

### `InventoryItemSystem`

Updater system, not simulation updater. Runs every 1000 ms, scans inventory item entities, unregisters invalid equipped item entities.

Quick win:

- Cursor/capped scan.

### `MetaEntitySystem`

Not an updater. Used by GEM save/load paths for meta entities.

Related risk:

- Large meta save/load/offload activity can expand bucket sizes and delayed releases.

Quick win:

- Cap delayed meta releases in `GameEntityManager.Update()`.

### `LogisticsSystem`

Simulation updater. Currently scans `Resources` entities but has no implemented behavior.

Quick win:

- Disable/remove on dedicated server.

Recommended patch:

- Make `updateSimulation()` return immediately.
- Or avoid registering it in `GameEntityManager.Init()` until real logistics logic exists.

### `ResourceUpdateSystem`

Simulation updater. Scans `Resources` entities and applies auto-decay to non-empty energy resources.

Quick win:

- Skip entities with no dirty resources and no candidate auto-decay energy resource.
- Consider phased processing for idle active entities.

Algorithmic notes:

- The only observed mutation is energy auto-decay.
- Current code subtracts `energyCapacity * 0.05F` per simulation tick.
- If we aggregate catch-up ticks, this can be represented in one pass.
- For exact repeated-tick behavior, use repeated subtraction capped by available amount, or an equivalent calculated amount if the resource semantics allow it.
- Do not scan resources that cannot contain non-empty auto-decay energy.

### `FluidContainerUpdateSystem`

Simulation updater. Scans fluid containers for petrol decay and rain filling. Sync limiter is 1000 ms, but scan still happens every simulation tick.

Quick win:

- If no rain, skip rain-fill checks globally.
- Pre-gate by rain catcher / can-player-empty before expensive fluid/weather checks.
- Phase inactive containers.

Algorithmic notes:

- Petrol evaporation can use elapsed tick count in one pass.
- Rain filling already uses `EntitySimulation.getGameSecondsPerTick()` and can multiply by an effective elapsed-time value.
- Cache global weather values once per system update:
  - precipitation intensity
  - snow flag
- If there is no precipitation, avoid all outside/rain checks.
- Only sync when amount actually changed and the existing sync limiter allows it.

### `CraftLogicSystem`

Simulation updater. Scans craft-logic entities, updates running crafts, handles start/stop, and calls Lua update callbacks.

Quick win:

- Skip idle clean entities.
- Throttle `luaCallOnUpdate()` for long-running crafts on dedicated server.

Algorithmic notes:

- Manual start/stop and start-request handling must stay immediate.
- Running craft progress can advance by effective elapsed game seconds in one pass.
- `luaCallOnUpdate()` should be called once per server frame per active craft at most, not once per catch-up tick.
- Finish/create/failed callbacks must remain immediate when the aggregated elapsed time crosses completion.

### `DryingLogicSystem`

Simulation updater. Scans drying entities, verifies slots, updates fuel and drying slots.

Quick win:

- Cache slot verification.
- Skip idle clean entities.

Algorithmic notes:

- Slot progress currently increments by `+1` per simulation pass.
- Catch-up can be converted to `+effectiveTicks` for the currently active slot recipe.
- To avoid burst-producing multiple chained recipes in one lag frame, complete at most one recipe per slot per server frame unless we explicitly decide catch-up crafting should chain.
- Manual start/stop remains immediate.

### `FurnaceLogicSystem`

Simulation updater. Similar to drying, verifies slots and updates fuel/furnace recipes.

Quick win:

- Cache slot verification.
- Skip idle clean entities.

Algorithmic notes:

- Same model as drying:
  - cache slot verification
  - active/requested entities run immediately
  - slot/fuel progress can jump by effective ticks
  - avoid repeated slot recipe lookup when resources are clean and a recipe is already known

### `MashingLogicSystem`

Simulation updater. Uses world-age delta for fermentation progress.

Quick win:

- Since progress is already based on world-age delta, it should not need repeated catch-up simulation passes. It can tolerate lower update cadence.
- Skip idle entities without start request.

Algorithmic notes:

- This system is already mostly elapsed-time based via `GameTime.instance.getWorldAgeHours()`.
- Replaying multiple simulation ticks in the same frame mostly repeats the bucket scan after `lastWorldAge` has already been updated.
- Best patch: ensure it runs once per GEM update frame, not once per catch-up simulation tick.
- Start requests still need immediate processing.

## Proposed implementation checklist

1. Patch `EntitySimulation` / `GameEntityManager.Update()` to cap consumed simulation ticks per frame.
2. Add GEM internal telemetry and tick debt counters.
3. Disable `LogisticsSystem` server simulation work.
4. Add cursor-based scans to `InventoryItemSystem` and `UsingPlayerUpdateSystem`.
5. Add idle/dirty gates to `ResourceUpdateSystem` and `FluidContainerUpdateSystem`.
6. Add idle/dirty gates and slot verification caching to `FurnaceLogicSystem` and `DryingLogicSystem`.
7. Evaluate throttling `CraftLogicSystem.luaCallOnUpdate()` on dedicated server.
8. Cap delayed meta entity releases per frame.

## Recommended first patch

Implement the catch-up cap first. It is the smallest patch with the biggest expected impact on `stateGem.maxMs`.

Minimum behavior:

- Default max consumed simulation ticks per frame: 1.
- Drop excess debt under overload.
- Record requested, consumed, and dropped simulation ticks in telemetry.
