# Player validation after compatibility fixes: 2026-10-26

Target: 42.21.0 server. Latest local `servertest` run: **17:29:19 to 17:31:59
America/Fortaleza** (20:29:19Z to 20:31:59Z). Source:
`C:/Users/ricar/Zomboid/Saves/Multiplayer/servertest/player-migration.log`.

Related: [first local-run analysis](2026-10-26-player-validation-local-analysis.md)
and [migration/validation feature](../features/2026-10-26-offline-player-migration.md).

## Results

```text
selected=84; migrated=0; skipped=0; collisions=0; failed=0; verified=76; verificationFailed=8
```

The previous local run had 1 verified and 83 verification failures. **75 profiles
newly verify**; 432 stays verified. No targets were skipped and no head/identity
or worker failures occurred. All 59 equipment-position failures from the first local run and all 11
CS2Firearms magazine-ID failures now pass all selected checks in this run. This validates the selected reconstruction/serialization comparisons,
not every possible mod field or live gameplay behavior.

## Remaining failures

All eight reach `RESTORE_INVENTORY` and emit `ITEM_TYPE_MISMATCH` with
`actualClass=MISSING`, followed by `Invalid saved item type or identity`.
There are no remaining comparison-stage failures in this run.

| Local ID | Account | Missing requested full type | Expected class |
| --- | --- | --- | --- |
| 3 | Dlneck | `Trelai.Trousers_TrelaiPolice` | `Clothing` |
| 20 | jonathan jardim | `Base.location_community_school_01_22` | `Moveable` |
| 90 | KoR1nGaP2C | `Trelai.ThickBoxers_Hearts` | `Clothing` |
| 358 | caiao | `Trelai.ThickBoxers_Hearts` | `Clothing` |
| 537 | Eloir | `Base.furniture_storage_02_16` | `Moveable` |
| 574 | PatrickH | `Base.appliances_com_01_72` | `Moveable` |
| 643 | Carl Grimes | `Base.furniture_tables_low_01_13` | `Moveable` |
| 675 | john | `Base.location_military_knox_01_8` | `Moveable` |

These are the first blocking items per profile; a corrected creation path may
expose additional items or later validation errors.

## Trelai clothing: three profiles

IDs 3, 90 and 358 cannot instantiate two Trelai clothing definitions:
`Trelai.Trousers_TrelaiPolice` and `Trelai.ThickBoxers_Hearts`.
Both definitions exist in the local checkout and workshop copy of
`Trelai B42/common/media/scripts/trelai_items.txt`, inside `module Trelai`.
`Trelai_B42` is also listed in the current `servertest.ini` Mods setting.
Thus files being present/configured is not enough: the runtime factory did not
resolve them. Check script parse/load errors, selected mod version and overrides;
this evidence does not establish which of those caused registration to fail.
Do not silently remove the clothes based only on an unresolved local definition.

## Moveable furniture: five profiles

IDs 20, 537, 574, 643 and 675 fail on `Base.<sprite-name>` full types.
These are saved `Moveable` instances whose runtime type can encode a world sprite,
not necessarily a real script item definition.

Exact 42.21.0 source establishes the mismatch:

- [Moveable.load](../../decompiled/zombie/inventory/types/Moveable.java) reads
  `worldSprite`, calls `ReadFromWorldSprite`, then can replace `type` and
  `fullType` with the sprite name when there is no custom item.
- [InventoryItemFactory](../../decompiled/zombie/inventory/InventoryItemFactory.java)
  recognizes dynamic `Moveables.<sprite>` requests specially. A
  `Base.<sprite>` request follows ordinary script lookup and can return null.
- [Relational reconstruction](../../src/zombie/savefile/ApocBRPlayerRelationalReconstruction.java)
  currently passes saved `full_type` directly to `CreateItem`, before it can
  apply the saved `moveable_state`.

This is a construction-path gap, not sufficient evidence of five removed mods.
The repair should select the correct stable script/class for a saved Moveable,
then restore and validate sprite/state/full type and native identity. Do not
blindly rename stored types or reuse foreign registry ordinals as local IDs.
No repair or database write was performed during this follow-up analysis.

Subsequent implementation on 2026-10-26 adds the targeted generic Moveable
construction path with native sprite initialization before saved field projection.
See the maintained [login guide](../features/2026-10-15-player-relational-login.md).
Regression coverage verifies runtime type, native identity, weight/light state,
container ownership, named-item routing and invalid-sprite diagnostics. The eight
failures above remain the historical results of this run; deployment and a new
cohort validation are required to measure the furniture fix.

## Trait outcome

The latest run has **zero** `MISSING_TRAIT_SKIPPED`, `MISSING_REGISTRY`, and
`TRAITS_SKIPPED_MISSING` events. Therefore there are no currently reported failing
or discarded trait keys to list. The earlier trait failures were local IDs 20,
685, 750, 784 and 845. IDs 685, 750, 784 and 845 now verify; ID 20 passes trait
restoration and then fails on the Moveable item shown above.

Do not infer specific trait names from the old accounts: the first run did not
persist their registry keys, and no missing trait warning is produced now. The
skip policy is available but was not exercised by this run. Exact historical
missing keys cannot be recovered from these persisted logs.

## Complete outcome comparison

| Local ID | Previous local result | Latest result |
| --- | --- | --- |
| 3 | item type | item type |
| 20 | trait registry | item type |
| 71 | EQUIPMENT_TARGET_MISMATCH | VERIFIED |
| 79 | EQUIPMENT_TARGET_MISMATCH | VERIFIED |
| 81 | EQUIPMENT_TARGET_MISMATCH | VERIFIED |
| 85 | KEYS_MISMATCH_item | VERIFIED |
| 90 | item type | item type |
| 103 | KEYS_MISMATCH_item | VERIFIED |
| 110 | EQUIPMENT_TARGET_MISMATCH | VERIFIED |
| 129 | EQUIPMENT_TARGET_MISMATCH | VERIFIED |
| 146 | EQUIPMENT_TARGET_MISMATCH | VERIFIED |
| 170 | EQUIPMENT_TARGET_MISMATCH | VERIFIED |
| 176 | KEYS_MISMATCH_item | VERIFIED |
| 198 | KEYS_MISMATCH_item | VERIFIED |
| 239 | item type | VERIFIED |
| 247 | KEYS_MISMATCH_item | VERIFIED |
| 250 | EQUIPMENT_TARGET_MISMATCH | VERIFIED |
| 264 | KEYS_MISMATCH_item | VERIFIED |
| 273 | EQUIPMENT_TARGET_MISMATCH | VERIFIED |
| 277 | KEYS_MISMATCH_item | VERIFIED |
| 285 | KEYS_MISMATCH_item | VERIFIED |
| 358 | item type | item type |
| 377 | KEYS_MISMATCH_item | VERIFIED |
| 385 | EQUIPMENT_TARGET_MISMATCH | VERIFIED |
| 386 | EQUIPMENT_TARGET_MISMATCH | VERIFIED |
| 405 | EQUIPMENT_TARGET_MISMATCH | VERIFIED |
| 432 | VERIFIED | VERIFIED |
| 451 | EQUIPMENT_TARGET_MISMATCH | VERIFIED |
| 491 | EQUIPMENT_TARGET_MISMATCH | VERIFIED |
| 524 | EQUIPMENT_TARGET_MISMATCH | VERIFIED |
| 534 | EQUIPMENT_TARGET_MISMATCH | VERIFIED |
| 537 | item type | item type |
| 574 | item type | item type |
| 581 | KEYS_MISMATCH_item | VERIFIED |
| 632 | EQUIPMENT_TARGET_MISMATCH | VERIFIED |
| 640 | EQUIPMENT_TARGET_MISMATCH | VERIFIED |
| 643 | item type | item type |
| 650 | EQUIPMENT_TARGET_MISMATCH | VERIFIED |
| 652 | EQUIPMENT_TARGET_MISMATCH | VERIFIED |
| 653 | EQUIPMENT_TARGET_MISMATCH | VERIFIED |
| 656 | EQUIPMENT_TARGET_MISMATCH | VERIFIED |
| 663 | EQUIPMENT_TARGET_MISMATCH | VERIFIED |
| 664 | EQUIPMENT_TARGET_MISMATCH | VERIFIED |
| 665 | EQUIPMENT_TARGET_MISMATCH | VERIFIED |
| 666 | EQUIPMENT_TARGET_MISMATCH | VERIFIED |
| 668 | EQUIPMENT_TARGET_MISMATCH | VERIFIED |
| 671 | EQUIPMENT_TARGET_MISMATCH | VERIFIED |
| 673 | EQUIPMENT_TARGET_MISMATCH | VERIFIED |
| 675 | item type | item type |
| 679 | EQUIPMENT_TARGET_MISMATCH | VERIFIED |
| 685 | trait registry | VERIFIED |
| 688 | KEYS_MISMATCH_item | VERIFIED |
| 690 | EQUIPMENT_TARGET_MISMATCH | VERIFIED |
| 703 | EQUIPMENT_TARGET_MISMATCH | VERIFIED |
| 716 | EQUIPMENT_TARGET_MISMATCH | VERIFIED |
| 717 | EQUIPMENT_TARGET_MISMATCH | VERIFIED |
| 726 | EQUIPMENT_TARGET_MISMATCH | VERIFIED |
| 730 | EQUIPMENT_TARGET_MISMATCH | VERIFIED |
| 731 | EQUIPMENT_TARGET_MISMATCH | VERIFIED |
| 732 | EQUIPMENT_TARGET_MISMATCH | VERIFIED |
| 734 | EQUIPMENT_TARGET_MISMATCH | VERIFIED |
| 738 | EQUIPMENT_TARGET_MISMATCH | VERIFIED |
| 745 | EQUIPMENT_TARGET_MISMATCH | VERIFIED |
| 746 | EQUIPMENT_TARGET_MISMATCH | VERIFIED |
| 750 | trait registry | VERIFIED |
| 754 | EQUIPMENT_TARGET_MISMATCH | VERIFIED |
| 762 | EQUIPMENT_TARGET_MISMATCH | VERIFIED |
| 775 | EQUIPMENT_TARGET_MISMATCH | VERIFIED |
| 778 | EQUIPMENT_TARGET_MISMATCH | VERIFIED |
| 784 | trait registry | VERIFIED |
| 786 | EQUIPMENT_TARGET_MISMATCH | VERIFIED |
| 787 | EQUIPMENT_TARGET_MISMATCH | VERIFIED |
| 788 | EQUIPMENT_TARGET_MISMATCH | VERIFIED |
| 794 | EQUIPMENT_TARGET_MISMATCH | VERIFIED |
| 811 | EQUIPMENT_TARGET_MISMATCH | VERIFIED |
| 818 | EQUIPMENT_TARGET_MISMATCH | VERIFIED |
| 826 | EQUIPMENT_TARGET_MISMATCH | VERIFIED |
| 833 | EQUIPMENT_TARGET_MISMATCH | VERIFIED |
| 839 | EQUIPMENT_TARGET_MISMATCH | VERIFIED |
| 845 | trait registry | VERIFIED |
| 846 | EQUIPMENT_TARGET_MISMATCH | VERIFIED |
| 847 | EQUIPMENT_TARGET_MISMATCH | VERIFIED |
| 849 | EQUIPMENT_TARGET_MISMATCH | VERIFIED |
| 856 | EQUIPMENT_TARGET_MISMATCH | VERIFIED |
