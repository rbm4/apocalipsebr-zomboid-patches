# ECS frameStep() and IsoMovingObject.separate() Optimization

## Scope

Investigated two hot per-entity, per-tick paths flagged as unnecessary overhead for
every `IsoMovingObject` (zombies especially, since the server ticks hundreds of them
every frame):

- `ECSEntity.frameStep()` / `ECSEntity.visitAllComponents()` in
  `zombie/characters/ecs/ECSEntity.java`
- `IsoMovingObject.separate()` in `zombie/iso/IsoMovingObject.java`

Status for `42.20.1`: the ECS fix, scheduler-bucket cast cleanup, and
`IsoMovingObject.separate()` squared-distance optimization are now implemented in
`src/` and compile in a dry run.

---

## Implemented: ECSComponent / ECSEntity frameStep cache

### Problem

`ECSEntity.frameStep()` called `visitAllComponents(ECSFrameStep.class, ECSFrameStep::frameStep)`,
which iterates every component in the entity's `HashMap<Class<? extends ECSComponent>, ECSComponent>`
and, for each one, calls `Type.tryCastTo(component, ECSFrameStep.class)` - effectively
`Class.isInstance()` + `Class.cast()` - plus a `Consumer` lambda dispatch for any match.

This runs for **every** `ECSEntity.frameStep()` call, every frame, for every scheduled
moving object. `CharacterInputComponent` is the only `ECSFrameStep` implementation in
the codebase, and zombies never carry one, so for every zombie this was 100% wasted
iteration + reflection-ish type-check work with zero possible matches.

### Fix

- `zombie/characters/ecs/ECSComponent.java`: added a `private final boolean ecsFrameStep`
  field computed once in the constructor path (`this instanceof ECSFrameStep`), mirroring
  the existing `ecsClass` cached-at-construction pattern already used by this class. Added
  a `public final boolean isECSFrameStep()` getter.
- `zombie/characters/ecs/ECSEntity.java`: `frameStep()` no longer calls
  `visitAllComponents(...)`. It now iterates `getECSComponentMapInternal().values()`
  directly and checks the cached `c.isECSFrameStep()` boolean, casting only on a hit:

```java
for (ECSComponent c : this.getECSComponentMapInternal().values()) {
    if (c.isECSFrameStep()) {
        ((ECSFrameStep)c).frameStep();
    }
}
```

This removes the `Class.isInstance()`/`Class.cast()` virtual dispatch and the `Consumer`
lambda indirection from the hot path. It does **not** eliminate the per-component loop
itself (still O(components per entity)) - doing that would require a per-entity cached
list/count of `ECSFrameStep` components, which needs a field on the entity, not the
component. The only concrete `ECSEntity` implementor is `IsoObject`, which would have
required patching that ~7100-line file (see "Why not also cache at the entity level"
below).

Files touched (patched full-file replacements, per this repo's compile-and-drop-in-`.class`
patching model - see root `README.md`):

- `src/zombie/characters/ecs/ECSComponent.java`
- `src/zombie/characters/ecs/ECSEntity.java`

Both are small enough to safely reproduce in full (54 and ~150 lines respectively) and
were verified line-by-line against `decompiled/zombie/characters/ecs/ECSComponent.java`
and `decompiled/zombie/characters/ecs/ECSEntity.java` before editing.

### Why not also cache at the entity level

A deeper fix would skip the per-component loop entirely for entities with zero
`ECSFrameStep` components (e.g. every zombie) by keeping a cached list/flag on the
entity itself. The only class implementing `ECSEntity.getECSComponentMap()` is
`IsoObject` (`decompiled/zombie/iso/IsoObject.java`, ~7100 lines). Patching it was
considered out of scope for this pass given the size and blast radius; the
`ECSComponent`-level cache already removes the more expensive part of the per-component
check (the type-check/cast) at a much smaller blast radius.

---

## Implemented: scheduler bucket cast cleanup

`zombie/MovingObjectUpdateSchedulerUpdateBucket.java` was added as a class override.
The hot `update()` and `postupdate()` loops now use direct `instanceof IsoZombie`
pattern matching instead of `Type.tryCastTo(...)` when checking
`VirtualZombieManager.instance.isReused(zombie)`.

This is intentionally small: it does not change bucket membership, timing cadence,
`preupdate()`, `frameStep()`, `update()`, or `postupdate()` ordering.

## Implemented: `IsoMovingObject.separate()`

### Problem

`separate()` runs for every solid, pushable `IsoMovingObject` against every candidate
in its 3x3 surrounding squares, every tick. For each candidate it currently:

1. Casts `obj` to `IsoGameCharacter`/`IsoPlayer`/`IsoZombie` via `Type.tryCastTo(...)`
   (three `Class.isInstance()` + `Class.cast()` calls per candidate).
2. Always computes `Vector2.getLength()` (a `sqrt`) for the distance between `this` and
   `obj`, even though in the overwhelming majority of cases the candidates in the 3x3
   neighborhood are not actually close enough to collide.

### Fix

Two behavior-preserving changes to `IsoMovingObject.separate()` only, everything else
in the file unchanged:

1. **Replace `Type.tryCastTo` with `instanceof` pattern matching** for `thisChr`,
   `thisPlyr`, `thisZombie`, and the per-candidate `objChr`/`objPlyr`/`objZombie`. All
   target types are compile-time known, so this avoids the `Class.isInstance()` +
   `Class.cast()` overhead in favor of a direct type check, e.g.:

   ```java
   IsoGameCharacter thisChr = this instanceof IsoGameCharacter tc ? tc : null;
   IsoGameCharacter objChr = obj instanceof IsoGameCharacter oc ? oc : null;
   ```

2. **Gate the sqrt with a squared-distance check first.** Compute
   `diffLenSq = diff.x * diff.x + diff.y * diff.y` before calling `diff.getLength()`.

   - The non-character branch (`thisChr == null || (objChr == null && !(obj instanceof BaseVehicle))`)
     only ever needs the boolean `len < twidth` to decide whether to call
     `CollisionManager.instance.AddContact(...)`; that boolean is derivable directly from
     `diffLenSq < twidth * twidth` with no `sqrt` at all. The unconditional `return` at
     the end of this branch must still execute regardless of distance - **only the sqrt
     is skippable here, not the branch itself.**
   - The character-vs-character branch (`objChr != null`) can only ever act (spear-charge
     return, or bump/push logic) when `len < twidth + maxWeaponRange`. Since
     `maxWeaponRange` is computed once per `separate()` call (min 0.3F) and `twidth` is known
     before the sqrt, the whole `objChr != null` block - including the `objPlyr`/
     `objZombie` casts - can be gated behind
     `diffLenSq < (twidth + maxWeaponRange) * (twidth + maxWeaponRange)` with **no
     behavior change**: if the squared distance exceeds that gate, neither inner
     condition (`len < twidth + maxWeaponRange` nor `len < twidth`) could have been true
     anyway.
   - `len` (the real, sqrt'd distance) is only computed once the gate passes, and only
     inside the `objChr != null` branch, where it is still needed for
     `diff.setLength((len - twidth) / 8.0F)` in the push-apart step.

This is a pure hot-path optimization: no thresholds, ranges, or collision outcomes
change, only the order/laziness of casts and the sqrt.

`src/zombie/iso/IsoMovingObject.java` now exists as a complete class override copied
from the matching 42.20.1 decompiled source, with the `separate()` body optimized as
described above.

Additional small safe cleanup in the same method:

- `maxWeaponRange` is computed once per `separate()` call instead of once per
  surrounding square.
- each square's `movingObjects` list is cached in a local variable instead of calling
  `sq.getMovingObjects()` repeatedly in the same loop.
- dedicated-server zombies with no target, no state-machine movement, no next-position
  delta, and no same-square crowd now return before scanning the 3x3 surrounding
  squares. If they are stationary but share their current square with another moving
  object, they still run separation against the current square only. This preserves
  close crowd push-apart while avoiding wasted neighbor scans for idle isolated zombies.

## Implemented: scheduler start-frame cleanup

`MovingObjectUpdateScheduler.startFrame()` now clears simulation buckets with a simple
indexed loop instead of `PZArrayUtil.forEach(..., MovingObjectUpdateSchedulerUpdateBucket::clear)`.
It also caches `GameServer.server` in a local boolean and avoids reading
`GameWindow.averageFPS` on the dedicated-server branch, where server simulation-level
selection does not use it.

This does not change bucket membership or simulation cadence. It only trims avoidable
per-frame work from the `stateMoveStartFrame` path.

Validation:

- `patchApocalipseBr.ps1 -DryRun` compiles successfully with the new override.

No regression tests exist for `separate()` collision behavior; if this fix is
re-attempted, manually verify in-game that:

- Zombie/player bump, push-apart, and spear-charge-through behavior are unchanged at
  close range.
- Vehicle-vs-character and vehicle-vs-vehicle interactions (handled elsewhere but
  gated by the same `obj instanceof BaseVehicle` check) are unaffected.

---

## GameState frame-budget analysis from telemetry

Target budget: keep the average server frame below 100ms so 300 ticks completes in
roughly 30 seconds.

Latest telemetry showed the relevant inclusive stack as:

- `gameState`: 85.16ms avg
- `stateIsoWorld`: 69.52ms avg, inside `gameState`
- `stateIsoWorldCell`: 63.15ms avg, inside `stateIsoWorld`
- `stateIsoCellObjects`: 49.81ms avg, inside `stateIsoWorldCell`
- `stateIsoCellSchedulerUpdate`: 49.30ms avg, inside `stateIsoCellObjects`
- `stateMoveUpdate`: 49.29ms avg, inside `stateIsoCellSchedulerUpdate`

So the moving-object cost is not a sibling of the 63ms/85ms numbers; it is the large
child inside them. Reducing `stateMoveUpdate` by 10ms should normally reduce the
parent chain by about the same amount, because the parent timers are inclusive.

The "about 6ms" ceiling only applies to the portion of `stateIsoWorld` outside
`stateIsoWorldCell`:

```text
stateIsoWorld - stateIsoWorldCell = 69.52 - 63.15 = 6.37ms
```

It does not cap the benefit of optimizing moving objects. A perfect removal of
`stateMoveUpdate` would have a theoretical ceiling near 49ms avg in this sample,
though realistic algorithmic fixes should be expected to recover a smaller fraction.
Even a 15-20% reduction in this path is meaningful: 7-10ms avg is enough to pull a
100ms frame back under budget with some headroom.

### Verified call hierarchy

- `GameServer.mainLoopDealWithNetData()` / state machine update records `gameState`.
- `IngameState.updateInternal()` calls `IsoWorld.instance.update()` and records
  `stateIsoWorld`.
- `IsoWorld.updateWorld()` calls `currentCell.update()` and records
  `stateIsoWorldCell`.
- `IsoCell.update()` calls `ProcessObjects(...)` and records `stateIsoCellObjects`.
- `IsoCell.ProcessObjects(...)` calls `MovingObjectUpdateScheduler.instance.update()`;
  this is the `stateIsoCellSchedulerUpdate` / `stateMoveUpdate` area.
- `MovingObjectUpdateScheduler.update()` iterates scheduler buckets and calls, per
  object: `preupdate()`, `frameStep()`, and `update()`.

On dedicated server, `MovingObjectUpdateScheduler.getUpdateSchedulerSimulationLevelForObject`
always returns `FULL` because the adaptive throttling is disabled when
`GameServer.server == true`. That means every scheduled moving object updates every
frame; the client-side distance/FPS simulation levels do not reduce server cost.

### Hot spots by priority

1. `stateMoveUpdate` / moving-object per-object update: the main steady-state cost.
   This includes `preupdate()`, ECS `frameStep()`, `IsoMovingObject.update()`, and
   subclass updates such as zombies, animals, players, and vehicles. The existing ECS
   patch removes avoidable type-check/lambda overhead, but the loop still runs for
   every component on every moving object.
2. `IsoMovingObject.separate()`: likely a major hot subpath when many zombies/players
   occupy nearby squares. The planned squared-distance and direct-`instanceof` patch is
   behavior-preserving and should reduce candidate-pair overhead, especially by avoiding
   unconditional `sqrt` calls.
3. Remainder of `stateIsoWorldCell` outside moving-object scheduler:
   `63.15 - 49.30 = 13.85ms`. Current telemetry already exposes
   `stateIsoCellIsoObject` at about 8.65ms avg, so static/process iso-object updates
   are the next cell-level candidate after moving objects.
4. `stateGem`: about 12.98ms avg as a sibling inside `gameState`, not inside
   `stateIsoWorld`. This is large enough to investigate separately if moving-object
   optimization does not create enough headroom.
5. `stateIsoWorld` outside `stateIsoWorldCell`: about 6.37ms avg. Worth cleaning only
   after larger paths, because its average ceiling is small.
6. Map streaming/unload spikes: `load2`, `load2RecalcAll2`, `load2DoLoadGridSquare`,
   and `cellUnload` show high max values but low call counts. These are spike/jitter
   problems, not the dominant steady average. They need backpressure or amortization
   rather than the moving-object algorithmic fix.
7. Lua callbacks, XP recovery, SRJ/BeyondTen, and ZKC are not visible as meaningful
   contributors in the sampled frame budget. They can spike individually, but they are
   not driving the average `gameState` load.

### Next telemetry splits needed

To avoid optimizing blind, split `stateMoveUpdate` one level deeper:

- bucket/simulation level: `FULL`, `HALF`, `QUARTER`, `EIGHTH`, `SIXTEENTH`
  counts and timings. On server this should confirm nearly all useful work is `FULL`.
- object type: `IsoZombie`, `IsoPlayer`, `IsoAnimal`, `BaseVehicle`, other.
- per-object phases: `preupdate`, `frameStep`, base/subclass `update`, and
  `postupdate`.
- `separate()` counters: surrounding squares visited, candidate moving objects checked,
  z-distance rejects, squared-distance rejects, actual `sqrt` calls, contacts added,
  push-apart operations, bump/spear-charge checks.

The key success metric for the `separate()` patch is not only lower time, but lower
`sqrt` calls per tick and lower expensive character-branch work per candidate while
keeping close-range contact counts unchanged.
