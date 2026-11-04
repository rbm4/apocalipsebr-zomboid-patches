# Player reconstruction: component membership and hand-item lifecycle

The subsequent [RGB and identity/XP implementation](2026-10-29-player-reconstruction-visuals-and-progression.md)
adds item visual finalization and progression preparation; authoritative descriptor
publication now occurs between tracking attachment and equipment promotion.

Implemented 2026-10-29 for the 42.22.0 server. First implementation tranche from
the [native-load matrix](../reports/2026-10-29-native-player-load-reconstruction-matrix.md):
TRACK-03 and the owner/active-weapon portion of TRACK-04. This builds on the
[weight round-trip repair](2026-10-29-player-item-weight-roundtrip.md).
Remaining RGB/visual, descriptor/XP, health, subtype-cache and migration findings
are not closed by this change.

## Explicit membership and compatibility

[Capture](../../src/zombie/savefile/ApocBRPlayerRelationalCapture.java) now emits
`item.entityComponents = true` as a synthetic structured component for every
captured item. Its meaning is that the accompanying `entity.*` rows describe
the complete saved native component membership, **including an empty set**.
It is not attached to the game's GameEntity component collection.

The existing component/value-node schema accommodates the marker without DDL
or privilege changes. It adds one component/object/root-node set (three rows)
per item and remains subject to existing capture, graph and journal budgets.
The existing detached snapshot already stores component count/list, so old
journal captures can produce the marker without inventing missing state.

Reader schema/codec remain 1/4, as with the other additive structured kinds.
Capture and reconstruction must be deployed together: an older executable
rejects this new kind, so reverting code alone is not a compatible rollback.

Historical SQL records are handled explicitly:

- With `entity.*` rows, those rows establish the complete saved set, matching
  native entity-payload removal semantics even without the new marker.
- With neither marker nor entity rows, leave factory membership unchanged.
  There is no historical evidence distinguishing “explicit empty” from a native
  item without an entity payload.
- With the new marker and no entity rows, remove all factory components. This
  explicitly preserves the captured empty set across later script changes;
  vanilla's absent entity-payload branch would retain target factory defaults.

No old SQL heads are rewritten by loading them. Existing mutation reconciliation
publishes the marker when it next captures live state.

## Reconstruction phases

[Reconstruction](../../src/zombie/savefile/ApocBRPlayerRelationalReconstruction.java)
now completes component restoration before assigning equipment rows. It decodes
structured payloads, rejects duplicate kind/owner combinations and malformed
membership markers, and collects entity state by owner.

For each authoritative owner, `restoreEntityComponents`:

1. Resolves all saved names against target ComponentType before deleting anything.
2. Releases factory-only components through native `releaseComponent`, preserving
   native removal/pool lifecycle without the factory wrapper's intermediate connects.
3. Reuses saved components or allocates missing ones. Newly allocated components
   receive their saved payload before native attachment/onAddedToOwner, as in native
   load. Failed attachment is explicit rather than silently losing a saved kind.
4. Calls `connectComponents` once for the owner after its complete membership and
   payloads are restored. This is one reconstruction connect, not a claim that
   the factory never connected its initial defaults earlier.

The raw `item.weight` override still applies after component work. Existing
component state-specific initialization and cache rebuilding are retained.
Other callbacks invoked by native component setters/removal/attachment are not
globally suppressed; this change specifically removes incremental connection
of partially restored state. Unsupported component codecs still fail completeness.

After equipment rows and vehicle return state are consumed, populated-table
coverage is checked. Hand owners/active weapon are then established before player
finalization callbacks, including `onWornItemsChanged`.

## Temporary versus authoritative equipment

`restoreEquipmentState(player, register)` follows the native character load helper:
it calls protected `setEquipParent(null,item,register)` for both hands, with
secondary registration disabled when both hands share the same object, then
selects `useHandWeapon` from the primary hand or clears it.

It does not call ordinary `setPrimaryHandItem`/`setSecondaryHandItem` equip flows
or trigger their Lua callbacks/stat modifiers. Native item `onEquip(register)`
still establishes its equipped lifecycle flag.

General reconstruction uses `register=false`. Temporary client-profile and
migration-verification players therefore gain coherent hand references without
equipment registration in GameEntityManager. This distinction prevents temporary
copies from entering simulation merely because their fields were reconstructed.
It is not a claim that every pre-existing constructor/finalization callback has
been made globally side-effect-free.

[ServerPlayerDB.finishPostgresPlayer](../../src/zombie/savefile/ServerPlayerDB.java)
promotes hand equipment with `register=true` only after relational tracking has
successfully attached to the authoritative player. Shared two-hand items request
registration once. Component construction/restoration precedes that promotion,
so native addComponent does not register a partially reconstructed equipped item.
The simulation thread owns all these operations; JDBC workers receive no live owner.

## Verification

- `python tools/test_player_reconstruction_lifecycle_42_22.py`: **56 checks**.
  Compiles the complete target reconstruction implementation; verifies component
  removal/reuse/addition, saved fields before attachment, one final connect,
  unknown/malformed/duplicate rejection, historical absent versus explicit empty,
  and component add/replace/last-removal through production structured capture and
  Delta SQL into reconstructed native component owners. JDBC transport is an
  in-memory double; native world simulation is not started.
- Equipment checks use target-extracted character/item owner methods, registration
  instrumentation and a native HandWeapon without components. They cover shared
  hands, distinct hands, secondary-only weapon, clearing active weapon and temporary
  registration suppression. Source assertions pin live promotion after tracking
  and component restoration before equipment. Full GameServer/login/disconnect
  composition, actual manager queues and real equip events remain runtime work.
- `python tools/test_player_weight_roundtrip_42_22.py`: **1,672 checks** with the
  new marker present. Existing food/drainable, weight mutation and legacy behavior
  remain covered; added marker rows change assertion totals.
- `python tools/test_sprite_config_42_22.py`: **17 checks**.
- `python tools/test_postgres_resilience.py --version 42.22.0`: passed, including
  main **691 checks** and auxiliary startup/shutdown/missing-schema cases.
- The current installed JAR now exposes `IsoWorld.WorldVersion = 251`, verified
  with repository javap. The target dry build compiles **303 sources / 895 class
  files** successfully. Prior documentation's world249 build failure is historical.

No deployment, production database write or live gameplay test was performed.
Before production use, verify representative equipped component-bearing items,
temporary profile encoding, live login/reconnect and logout cleanup on the exact
target server. In particular, validate component native removal/connection callbacks
and manager registration under the real world lifecycle.
