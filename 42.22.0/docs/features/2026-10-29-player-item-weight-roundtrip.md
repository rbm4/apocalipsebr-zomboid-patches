# Player item weight mutation round trips (42.22.0 server)

Implemented 2026-10-29 following the
[reconstruction audit](../reports/2026-10-29-player-reconstruction-deserialization-audit.md).
This addresses weight capture and reconstruction, plus the condition/broken
invariant encountered in the same scalar restoration path. Other audit findings
remain open. No deployment or live database changes were performed.

## Gameplay source and storage contract

Target `IsoGameCharacter.Eat` scales food values for a partial bite. If the food
has custom weight, it also calls `setWeight` with the reduced weight. This can
change the raw `weight` independently of raw `actualWeight`. Food getters can
add ingredient weight and scale by remaining hunger or thirst; storing their
computed result as the raw base can apply consumption twice after loading.
`SyncItemFieldsPacket` also carries weight and food actual weight separately.

[Snapshot](../../src/zombie/savefile/ApocBRPlayerRelationalSnapshot.java) now
detaches raw `InventoryItem.weight` into optional `Node.itemWeight` alongside
the existing raw `actualWeight`, uses, custom flag and subtype state. This
sampling occurs on the live owner thread. It charges its capture budget and
does not retain an engine reference. Java journal serialization preserves it.

[Capture](../../src/zombie/savefile/ApocBRPlayerRelationalCapture.java) writes
the raw weight for custom-weight items as `component.kind = 'item.weight'`,
with one scalar number root in the existing `value_node` representation.
It does not add an actual game entity component. Stable component/node UUIDs
let existing graph reconciliation detect **weight-only** changes, write the
number-value delta and delete the extension when an item is consumed or stops
using custom weight. No mutation cadence or thread ownership changes.

The existing schema/codec 1/4 and V5 extensible component/value tables accommodate
this state without DDL, new objects or grant changes. Existing `actual_weight`
and `custom_weight` columns retain their meanings. New records require this
reader: older executable reconstruction code rejects the new component kind.
Deploy capture and restore together; reverting the executable while retaining
new rows is not backward compatible.

## Reconstruction order and older records

[Reconstruction](../../src/zombie/savefile/ApocBRPlayerRelationalReconstruction.java)
excludes uses, weight/custom flag and condition from unordered generic item
projection, then performs an explicit phase:

1. Initialize uses to 1, clear the custom flag, and restore non-default saved uses.
   This follows the target native loader order and permits drainable setters to
   derive weight from current definitions.
2. Call `setConditionWhileLoading` so zero condition also establishes `broken`.
3. Apply the saved raw custom actual mass to both native mass fields and set the
   flag. Non-custom mass follows current factory/script values, matching vanilla.
4. Restore food/subtype state, ingredient lists and components as before. After
   component restoration, apply the separate `item.weight` override to raw weight.
   This preserves a consumed food's independent value and avoids JSON-key-order
   dependence or later component initialization overwriting it.

Saved custom/extension weights must be finite and nonnegative. An extension on
an item without custom weight is rejected. Validation failures do not silently
substitute zero or publish an incomplete graph.

Older SQL rows without the extension use the deterministic native fallback:
custom actual mass initializes both fields. The older database never stored the
separate food weight, so it cannot be recovered exactly from those rows. Older
Java journal nodes deserialize with `itemWeight == null`; replay omits the
extension instead of fabricating a missing historical value. New captures
preserve it from that point onward. No historical records are rewritten here.

## Executable verification

`python tools/test_player_weight_roundtrip_42_22.py`: **1,630 checks passed**.
The earlier `test_player_reconstruction_semantics_42_22.py` diagnostic now forwards
to this regression rather than asserting that the old defects still occur.

The fixture executes production target Snapshot, Capture, mutation DirtyQueue,
graph indexing/diffing, Delta SQL generation, binding and batch application.
An in-memory JDBC transport applies those statements to stored rows. Rows pass
through JSON numeric conversion and graph ordinal mapping, then the extracted
production item/subtype/sequence/component reconstruction methods restore them.
Snapshots also pass through Java serialization before mutation projection.

Scenarios cover custom and non-custom food, mixed ingredients, replacement
containers, thirst-only food, repeated partial bites with native remaining-portion
normalization, independent raw weights, weight-only updates, nested bag ownership,
full consumption/removal, custom-to-default transitions, changed non-custom
definitions, drainable uses and saved overrides that disagree with the formula,
shuffled row keys, old SQL/journal fallback, detached ownership, invalid weights
and the broken flag. Tests compare raw fields as well as observable mass getters.

Native Food getters, `multiplyFoodValues`, the portion-normalization and item
mutation branches from `IsoGameCharacter.Eat`, and drainable weight methods are
extracted from exact 42.22.0 decompiled sources. Script/factory dependencies are
doubles. Ingredient lookup supplies a fixed known ingredient mass. Eat's health,
nutrition, Lua callbacks and network effects are outside this fixture; the
`UseAndSync` boundary records consumption and its container removal is simulated.
This is not a full live player login, PostgreSQL constraint/transaction test,
or complete native binary save/load comparison.

Other validation:

- `python tools/test_sprite_config_42_22.py`: 17 checks passed, compiling the
  complete target reconstruction helper and exact native SpriteConfig with the
  available dependency JAR. The fake item model gained the newly captured field.
- `python tools/test_postgres_resilience.py --version 42.22.0`: passed; main
  resilience run reports 691 checks, with missing-schema/startup/shutdown scenarios
  also passing. Its target fake item model likewise gained raw weight.
- `42.22.0/patchApocalipseBr.ps1 -DryRun`: failed against the default installed
  world249 dependency JAR (435 errors, including absent ClimbState/ClimbDownState,
  target packet types and ladder flags). No deployment. The exact world251
  whole-build prerequisite remains outstanding.

## Runtime boundaries

Verify weight before use, after use, after mutation commit, after server
reconstruction and after client login using the exact target runtime/mods.
The stock native profile format saves custom mass through `getActualWeight`,
not the separate raw weight extension. Consequently client native loading can
still normalize custom food differently from the preserved relational server
state. This change does not modify that wire format or install a client patch;
it establishes lossless custom raw-weight persistence/reconstruction on the
server. Native profile parity and login item-field synchronization need a
separate integration check before claiming the reported client symptom resolved.

Graph mutations retain their existing durability, final-sample, journal and
rejected-save policy. See [SpriteConfig/quarantine recovery](2026-10-29-player-sprite-config-and-quarantine-recovery.md).
