# Player reconstruction versus native deserialization: 42.22.0

Created 2026-10-29. Scope: the current **42.22.0 server working tree**, including
the pending SpriteConfig/quarantine changes already present when this review
started. Source audit with focused executable reproductions; no live player,
database, client, deployment or complete target-JAR verification.

Follow-up on 2026-10-29: weight capture/restoration and the condition/broken
invariant are now repaired in source with mutation SQL round-trip tests. See
[implementation and remaining runtime boundaries](../features/2026-10-29-player-item-weight-roundtrip.md).
The findings below preserve the pre-fix audit evidence; the original seven-check
diagnostic has been replaced by a forwarding entry point to the regression.

**Conclusion:** relational coverage is broad, but it is not behaviorally equivalent
to native player deserialization. There are confirmed weight differences and
additional missing load-time state transitions. These make the player reports
plausible; no particular player's incident is proven by this review.

Related: [SpriteConfig and quarantine recovery](../features/2026-10-29-player-sprite-config-and-quarantine-recovery.md),
[cross-build recovery](2026-10-28-cross-build-character-reconstruction.md), and
[subsystem tracker](../features/2026-10-27-subsystem-port-tracker.md).

## Actual persistence paths

1. Native import/migration constructs a player and calls `player.load(bytes,
   sourceWorldVersion)` under load-quality/provenance tracking. This traverses
   `IsoMovingObject`, `IsoGameCharacter`, `IsoPlayer`, item containers, compressed
   identical items, each concrete item loader, and entity/component loaders.
2. Live relational capture copies a detached graph on its owner thread through
   `ApocBRPlayerRelationalSnapshot`. Capture projects the schema's explicit field
   inventory; JDBC workers write typed rows and structured component/value nodes.
   Reflection reads fields before getters when a matching field exists.
3. PostgreSQL login uses `ApocBRPlayerRelationalReader`: one statement/MVCC view,
   compatible schema 1 / codec 4, expected graph counts, source dictionary
   preparation, then reconstruction on the simulation thread. Reconstruction
   creates fresh factory objects and projects rows; it does not call the native
   player/item binary loaders.
4. There are **two consumers**: a temporary reconstructed player is natively
   serialized into the client profile, while `finishPostgresPlayer` independently
   reconstructs the authoritative server player and activates live tracking.
   Client native loading may repair a derived flag that remains wrong on the
   server. Re-encoding cannot recreate a field omitted from SQL.
5. Default graph reconciliation samples the live graph every 30 seconds, and
   final sampling can run sooner. Deltas can persist reconstructed defaults and
   omissions as valid state. The first update of a non-`live_fields` snapshot
   forks it; subsequent updates to the live snapshot are in place. Thus the source
   can have an initial retained snapshot, but there is no immutable backup of
   every later revision. This qualifies the earlier cross-build report's warning
   about reusing the same snapshot ID.

Primary implementation: [ServerPlayerDB](../../src/zombie/savefile/ServerPlayerDB.java),
[Snapshot](../../src/zombie/savefile/ApocBRPlayerRelationalSnapshot.java),
[Capture](../../src/zombie/savefile/ApocBRPlayerRelationalCapture.java),
[Reader](../../src/zombie/savefile/ApocBRPlayerRelationalReader.java),
[Reconstruction](../../src/zombie/savefile/ApocBRPlayerRelationalReconstruction.java),
[Mutations](../../src/zombie/savefile/ApocBRPlayerRelationalMutations.java), and
[Database](../../src/zombie/savefile/ApocBRPlayerRelationalDatabase.java).

## Weight findings

### W1: custom-weight restoration updates only one of two fields

Native `InventoryItem.load`, custom-weight bit 128, calls `setActualWeight`,
`setWeight`, and `setCustomWeight(true)` for a nonnegative saved value. The
relational `item` row contains `actual_weight` and `custom_weight`, but no `weight`.
Reconstruction writes the first two fields directly. A factory weight of 2 and
saved custom mass of 0.5 restores `getActualWeight() == 0.5` while `getWeight() == 2`.

This is a confirmed API inconsistency. Do not equate it with every inventory
total: `getUnequippedWeight` uses actual mass plus contents; `HandWeapon` and
`Clothing` override weight getters. However, `ItemContainer.AddItem(InventoryItem)`
has a capacity check using `item.getWeight()`. Generic items and food without a
replacement-on-use getter override can expose the discrepancy. Native client
loading of a custom-weight profile can set both fields again, so server and
client effects can differ.

### W2: non-custom saved mass overrides current definitions

Vanilla saves the custom-weight payload only when `isCustomWeight()` is true.
Otherwise native loading retains/recalculates weight from the current factory
and script. Relational capture always stores the raw `actualWeight` field and
reconstruction always reapplies it, even when `custom_weight=false`.

With old non-custom mass 1 and target script mass 2, ordinary server relational
restoration keeps 1 where target native loading would use 2. The client-profile
save omits that mass because the flag is false, so a client may load 2. This is
a concrete mechanism for a persistent server/client disagreement after mod/script
changes. It does not require a cross-build upgrade; changing a definition suffices.

### W3: drainable restoration depends on row iteration order

`current_uses` resolves to `setCurrentUses`: storage is named `uses`, so the
field-first setter does **not** bypass this method. In `DrainableComboItem`, the
setter runs `updateWeight`, which derives mass/custom state from current scripts.
Other row entries directly restore `actualWeight` and `customWeight`. Applying
uses before these entries produces different state from applying uses afterward
when saved values disagree with the current definition/formula.

The diagnostic uses full mass 2, empty mass 0.5, 50% uses, and saved mass 1.8:
projection can end at actual mass 1.8 or 1.25. This demonstrates a dependency;
it does not claim JDBC randomly chooses either order on every login. SQL builds
JSONB, `JSONObject.toMap()` supplies a map, and reconstruction iterates entries;
there is no semantic phase ordering contract for dependent fields. Reordering
alone is insufficient: native uses/default initialization and the later custom
weight override have an explicit order that must be modeled.

### Weight paths already represented

- Bags restore their contents ownership and call `setWeightReduction`, which
  updates both bag and container reduction. The old containing-item versus
  parent-container field-hiding trap is explicitly handled.
- Fluid state records capacity, fluids, filters and flags. `restoreFluid` empties
  existing contents, restores instances and calls `recalculateCaches(true)`.
  A blanket claim that fluid amount is never recalculated would be incorrect.
- Food hunger/base hunger/thirst, extras/spices, wet clothing, ammunition,
  chamber/magazine state and weapon attachment relations have explicit coverage.
- Weapon mass is derived from current script weight and attachments; a generic
  actual-weight repair cannot by itself correct every weapon report.
- Embedded animal reconstruction calls `initAnimalData`, which recalculates
  inventory-animal weight. Dynamic `Base.<sprite>` Moveables initialize their
  sprite before saved scalar projection.

Weight source: [InventoryItem](../../decompiled/zombie/inventory/InventoryItem.java),
[DrainableComboItem](../../decompiled/zombie/inventory/types/DrainableComboItem.java),
[Food](../../decompiled/zombie/inventory/types/Food.java),
[HandWeapon](../../decompiled/zombie/inventory/types/HandWeapon.java),
[Clothing](../../decompiled/zombie/inventory/types/Clothing.java), and
[ItemContainer](../../decompiled/zombie/inventory/ItemContainer.java).

## Other deserialization differences

| Finding | Native contract versus current reconstruction | Consequence / confidence |
| --- | --- | --- |
| Zero condition does not establish `broken` | `setConditionWhileLoading` clamps condition and sets `broken`; scalar projection writes `condition` only | Confirmed executable reproduction. Server item can have condition zero with broken false. Client native load may repair it. |
| Missing independent RGB fields | Native saves `col` RGBA and separate `colorRed/Green/Blue` channels; schema binds only RGBA through `col` | Confirmed representational omission. Distinct saved RGB cannot be recovered from a single color; `setColor` only assigns `col`. Visual synchronization can subsequently use the missing RGB channels. |
| No post-restore `synchWithVisual` | Native base item, clothing and bag loaders synchronize visuals after saved state is applied; factory synchronization occurs before SQL projection | Confirmed missing phase. Icon/model selection and clothing blood/dirt/color synchronization may differ. Simply calling it before repairing RGB would be unsafe. |
| Factory component set is not reconciled | Native entity-bearing item load removes components not in its loaded component list; reconstruction adds/restores saved components but never removes factory-only components | Confirmed structural difference. Script changes or removed components can retain unintended current defaults. If a retained component contributes fluid contents, weight can differ; requires an applicable real item fixture. Native removal is conditional on the entity payload, not unconditional for every legacy item. |
| Component connection happens incrementally | Reconstruction calls `connectComponents` after each component, rather than establishing a complete saved membership/state set first | Cross-component initialization ordering needs native integration tests; final table/count coverage does not verify this invariant. |
| Ordinary Moveable/Radio sprite initialization is incomplete | Native `Moveable.load` calls `ReadFromWorldSprite` for every loaded Moveable, including Radio. Special reconstruction initialization covers only exact `Moveable` class with dynamic `Base.<sprite>` full type | Confirmed missing general path; assigning `worldSprite` cannot restore grid/custom-name/drop flags. Factory `Moveables.*` initialization may already cover some cases. Weight impact is item/sprite dependent. |
| Hand item owner/use-weapon bookkeeping | Native `IsoGameCharacter.load` calls `setEquipParent` and `setUseHandWeapon`; reconstruction assigns hand fields without those calls | Confirmed omitted bookkeeping. Avoid public equip callbacks, but retain native load-safe owner registration and active-weapon setup. This is separate from the already ported primary/secondary field names. |
| Clothing time baseline | Native `Clothing.load` resets `lastWetnessUpdate` to current world hours; relational restoration replays the saved timestamp | Confirmed difference. Offline wetness processing and resulting wet weight need runtime comparison. |
| Food time/icon finalization | Native food load initializes `lastAged` when server-side and -1, and animal-corpse icons | Missing explicit reconstruction finalization. Do not infer that every food is affected. |
| EntityScriptInfo derived script reference | Native load resolves `script` from `originalIsItem` and `originalScript`; generic reconstruction sets the original fields only | Factory reference can remain appropriate for unchanged definitions, but migrated/transferred script identity can disagree. |
| Character health/descriptor finalization | Native body load updates `bodyPartsLastState`; descriptor load registers its ID, updates ID counter and runs `doStats` | Reconstruction does not reproduce these phases explicitly. Functional impact requires integration checks; `calculateOverallHealth` alone is not full native finalization. |
| Creation mode differs | Native registry-ID factory calls `createItemInternal(...,false,false)`; reconstruction's string factory uses first-creation=true | Confirmed mode difference reaching `GameEntity.onFirstCreation`. Current target component implementations inspected do not override that hook; do not claim a proven duplicate mod callback from this flag alone. Other factory initialization also runs on vanilla load. |

Native references: [IsoGameCharacter](../../decompiled/zombie/characters/IsoGameCharacter.java),
[IsoPlayer](../../decompiled/zombie/characters/IsoPlayer.java),
[InventoryItemFactory](../../decompiled/zombie/inventory/InventoryItemFactory.java),
[GameEntityFactory](../../decompiled/zombie/entity/GameEntityFactory.java),
[Moveable](../../decompiled/zombie/inventory/types/Moveable.java),
[BodyDamage](../../decompiled/zombie/characters/BodyDamage/BodyDamage.java),
[SurvivorDesc](../../decompiled/zombie/characters/SurvivorDesc.java), and
[EntityScriptInfo](../../decompiled/zombie/entity/components/script/EntityScriptInfo.java).

## Coverage and failure policy

The schema has dedicated state for character progression, traits, health,
nutrition, visuals, fitness, equipment, bags, weapons/parts, food/eggs, clothing
patches, literature/pages, alarms, keys, Moveables, maps, radios, animal tracks
and embedded animals. Lua modData, opaque byteData with provenance, attributes,
durability, fluid state and supported entity components use structured nodes.
This is substantially more than a basic inventory-name reconstruction.

It is still an explicit supported subset. Components such as Resources and
crafting logic are not universally captured, and SpriteOverlayConfig has no
dedicated supported codec. Some native component types restrict inventory usage;
their existence alone does not prove a player can carry one. If encountered,
unsupported capture issues fail completeness rather than silently succeeding.
The current discard/reload policy abandons the rejected save and permits loading
the last committed state; this can look like item/progress rollback, a different
failure mechanism from an incorrect weight in a successfully committed graph.

Native byte loading can skip failed/missing items and repair sized-record
positions. Our native import adds quality issues and refuses incomplete
publication; relational reconstruction instead rejects missing types or malformed
ownership/references. The policies intentionally differ. Do not use catch-and-skip
as a general parity fix: dependencies and preserved source data still matter.

Graph counts, compatible codec numbers and consuming every populated table prove
structural acceptance, not complete native field coverage or semantic equivalence.
A field never represented in the schema cannot trigger an unrestored-table error.

## Recommended repair and verification order

1. Define explicit item load phases: factory creation in load mode, native default
   semantics, uses/condition, saved overrides, subtype state, component membership,
   component state/connection, visuals, then equipment/character finalization.
   Specify how live capture differs from an already natively loaded import.
2. Repair W1-W3 with target-native semantics. Do not blindly add a `weight` SQL
   column or call every public setter: some setters fire gameplay callbacks and
   some values are derived. Non-custom mass should follow the target definitions;
   custom mass must follow the correct native subtype/load ordering.
3. Cover missing RGB with a versioned storage/migration policy. Existing rows
   cannot distinguish both native color representations; do not fabricate fidelity.
   Repair condition flags and owner bookkeeping without triggering equip events.
4. Add native-versus-relational round trips using the exact 42.22/world251 runtime:
   generic custom/non-custom items, changed script weights, drainables, partially
   eaten mixed food, wet clothing, nested equipped bags, fluids and removed fluid
   components, ammo/magazines/parts, ordinary/dynamic/custom Moveables, Radio,
   animal items, colored clothing, condition-zero items and script changes.
5. Compare observable getters and derived state on the temporary encoded profile,
   authoritative server player, client after native load, and after reconciliation
   plus reconnect. Repeat with shuffled row entry order. Compare against native
   save/load outcomes, which may intentionally normalize raw fields.
6. Reproduce actual reports from a preserved database copy with item full type,
   expected/observed mass, custom flag, uses, wetness, contents, scripts/mods and
   whether the symptom appears after relog. Server/client measurements are needed
   to attribute a player's incident to any finding here.

## Verification performed

`python tools/test_player_reconstruction_semantics_42_22.py` passes **7 checks**
and deliberately reproduces current differences: custom weight split, stale
non-custom mass, broken-state omission, real drainable setter dispatch and
projection-order dependence. It extracts the actual target production projection,
setter/conversion methods and target vanilla weight/condition methods/custom-weight
load branch. Engine construction, definitions, logs and registries are doubles;
this is not a complete native binary player round trip. The test asserts the
known defects as investigation evidence and must change when repairs are made.

No runtime Java sources were changed by this review. Existing uncommitted work
was preserved. The installed world249 dependency JAR is not evidence of full
world251 compatibility; no whole-build or production incident-resolution claim
is made. Documentation links and index coverage are checked separately.
