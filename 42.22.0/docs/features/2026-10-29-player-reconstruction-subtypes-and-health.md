# Reconstruction: subtype initialization and health/time baselines

Implemented 2026-10-29 for **42.22.0 / world251**, priorities 5 and 6 from the
[native-load matrix](../reports/2026-10-29-native-player-load-reconstruction-matrix.md).
Extends [component/equipment lifecycle](2026-10-29-player-reconstruction-lifecycle.md)
and [visual/progression restoration](2026-10-29-player-reconstruction-visuals-and-progression.md).
No schema, capture format, database contents or deployment changed.

## Subtype initialization

[Reconstruction](../../src/zombie/savefile/ApocBRPlayerRelationalReconstruction.java)
now initializes saved sprites for all Moveable subclasses, including Radio,
before projecting saved values. This rebuilds sprite-derived properties without
overwriting saved weight, light or component state afterward. Native CustomItem
handling controls sprite-derived type/fullType. Invalid ordinary/Radio sprites
retain native tolerant behavior; the existing dynamic Base sprite fallback still
fails explicitly when its required sprite cannot be resolved.

Weapon parts and light/sight caches are cleared before scalar restoration and
saved attachment edges. Attaching with `doChange=false` continues to prevent
double application of stat modifiers. Finalization derives melee/explosive flags
from the current definition and clears containsClip when no magazine type exists.

Radio device state is allocated when missing and rebound to its owning Radio.
Map symbols run native setRotation to rebuild sine/cosine caches, with native
0..24 zoom clamps. EntityScriptInfo resolves the saved script identity through
the current ScriptManager; an unresolved nonnull identity rejects reconstruction
rather than retaining an unrelated factory script. UiConfig resolves its saved
skin through native setSkin. These component operations run before final owner
connection; subtype finalization runs after components, before visual sync and
equipment/player callbacks. Animal-corpse food invokes native initAnimalIcons
after its embedded state is restored.

## Health and time baselines

After all body-part values are restored, setBodyPartsLastState initializes the
previous-state snapshot, matching BodyDamage.load ordering before thermoregulation.
The next health comparison can consequently detect new changes instead of
comparing against constructor state.

Nutrition uses native nutrient setters and their clamps. Weight is clamped to
the native minimum of 35 before calling setWeight: saved health is restored
separately, so constructing temporary and authoritative copies must not apply
LOWWEIGHT damage or emit its event again. This deliberately preserves normalized
state without replaying that gameplay action.

Clothing lastWetnessUpdate resets to current world hours, retaining saved wetness
but preventing elapsed drying from a stale load timestamp. Food lastAged=-1
becomes current world hours on the server; other timestamps remain saved values.
The client sentinel behavior remains native.

Embedded animal restoration sets current/last/next XY and current/last Z together,
including fractional coordinates. Genome/breed state precedes size clamping.
Milk capacity and saved timers precede quantity normalization. The native milk
setter starts from zero quantity, avoiding the milking side effects that a
constructor-relative decrease would otherwise trigger. Quantity is bounded by
zero and saved capacity; nonfinite milk values or a negative capacity fail
explicitly. Preferred hutch position uses its native setter. Saved wild state
uses native domestication eligibility and clears migration identity when tame,
while preserving saved stress instead of invoking setWild(true)'s randomization.
Existing animal offline time, attachments and owner initAnimalData remain intact.

All live object restoration stays within the existing reconstruction ownership
boundary. These changes add no worker, queue or world publication. Detached
snapshot/mutation/JDBC work continues through the existing persistence pipeline.

## Verification and remaining boundaries

- `python tools/test_player_reconstruction_subtypes_health_42_22.py`: **156**
  checks, including the existing 56 lifecycle checks. Uses production reconstruction,
  capture/Delta SQL and structured-value decoding, native target classes and
  explicit doubles for asset/world-dependent animal initialization.
- Time/nutrition/animal mutation cases persist changed food aging sentinel,
  clothing wetness/clock, nutrient limits and embedded milk/timers, then apply
  reconstruction normalization. Other cases cover weapons, radio parent repair,
  map caches, script/UI resolution and body-part previous-state comparisons.
- Visual/progression suite: **143**; weight mutation/reconstruction: **1,763**;
  SpriteConfig: **17**. These suite totals overlap and must not be summed.
- PostgreSQL resilience: **691**, plus auxiliary startup/missing-schema/shutdown
  cases. Full target DryRun: **303 sources / 895 classes**, no deployment.

Sprite assets, corpse icon decoding, complete embedded animal construction and
the first real world simulation tick still require runtime validation. The
fixture verifies a body-part change against the restored baseline; it does not
claim a complete health simulation tick. Animal setter doubles use the target
native implementations with controlled world dependencies. Empty-versus-absent
state (priority 7 / TRACK-10), opaque payloads and migration/client boundaries
remain separate work. See the matrix for individual outstanding steps.
