# Targeted player validation: local October 26 run

Status: investigated; runtime fixes for equipment position comparison and hand
restoration are proposed, not implemented by this analysis. The previously
implemented immutable item-list fix passes this runtime validation.

Follow-up implementation on 2026-10-26 adds normalized retained equipment ranks,
native-style hand restoration, missing-trait skip/log policy, and persisted
reconstruction diagnostics. See the maintained
[compatibility policy](../features/2026-10-26-offline-player-migration.md#reconstruction-compatibility-fixes-october-26).
The measurements below describe the original local run before those fixes;
a new live cohort run is still required to assess their results.

The subsequent [17:29 local run](2026-10-26-player-validation-follow-up.md)
completed with 76 verified and eight named item-creation failures. It supersedes
the pending-runtime-validation status above; the original measurements below
remain historical evidence.

Owner: [offline player migration and targeted validation](../features/2026-10-26-offline-player-migration.md).
Target: **42.21.0 dedicated server**.

## Run and evidence

Local server `servertest`, world `servertest`, `validate-failed` mode. Run began
2026-10-26 16:55:47 America/Fortaleza (19:55:47Z) and drained at 16:58:23
(19:58:23Z). Source: `C:/Users/ricar/Zomboid/Saves/Multiplayer/servertest/player-migration.log`.
The final summary was:

```text
selected=84; migrated=0; skipped=0; collisions=0; failed=0; verified=1; verificationFailed=83
```

Every allowlisted profile was reached. There were no missing/inactive, online,
changed-head or dataset identity failures in this run. `failed=0` counts worker
failures; it does not negate `verificationFailed=83`. PostgreSQL inspection in
this investigation used SELECT-only transactions marked read-only, followed by
rollback; no player profile writes or deployment were performed.

| Local outcome | Count | Interpretation |
| --- | ---: | --- |
| Equipment positions only | 59 | Same equipment slot/native-ID multisets; stored positions contain gaps. |
| Item key mismatch | 11 | One missing and one new native item ID in each profile; all affected expected items are CS2Firearms magazine attachments. |
| Item factory/runtime-class validation | 8 | Reconstruction fails before comparison. |
| Trait registry resolution | 5 | Reconstruction fails before inventory restoration. |
| Verified | 1 | Local ID 432, the original immutable-list failure. |

## Equipment: skipped references leave sparse positions

All **59/59** equipment failures log `equipmentPositionsOnly=true`. A read-only
query of each expected snapshot's worn-equipment rows found position gaps in
**all 59**. Across the bounded diagnostics, **235/235** actual-side position
samples match the zero-based rank of the retained source rows sorted by their
stored position; zero sampled entries contradict simple compaction. All 470
printed equipment-difference entries are `worn` entries. The diagnostic cap means
these printed entries are a sample, not the complete position mapping.

Concrete example: local ID 71 has 17 retained worn rows with positions 1 through 6
missing. Its `base:back` item (native ID 1192292378) moves from stored position 21
to reconstructed position 15, and its `base:belt` item (1473634299) moves from 7
to 1. These are the same items in the same slots.

[Capture](../../src/zombie/savefile/ApocBRPlayerRelationalCapture.java) increments
`pos++` for every entry of the native worn list. `equipped()` omits references
that are not in the owned inventory graph, leaving gaps. Reconstruction appends
only the stored rows through native equipment APIs, producing a compact list.
[WornItems.setItem](../../decompiled/zombie/characters/WornItems/WornItems.java)
also inserts according to body-location group order. In this run, the measured
samples are explained by compaction; a changed body-location order is unnecessary
to explain them.

The verifier compares `(kind,slot,position) -> native_id` literally, so sparse
source positions fail against compact positions. This is a verifier representation
mismatch for the equipment check, not evidence that these retained targets were
swapped. It does not prove that every original worn pointer was valid: the
unreachable references had already been deliberately omitted by capture.

Proposed fix: compare equipment identity by stable slot/native item ID and, where
order matters, compare relative retained order or normalized positions rather
than absolute sparse offsets. Preserve detection of real target/slot changes.
No database reimport is needed merely to remove positional gaps. The verifier
stops at its first failure, so these 59 profiles have not yet passed later item,
trait, XP, health and character-state checks.

## Eleven item IDs: CS2Firearms equip callbacks replace magazine attachments

All 11 profiles have one missing and one unexpected ID, with **Integer on both
sides**. This rules out an Integer-versus-Long comparison problem. Item counts and
equipment target checks passed first.

A read-only query joined `player_rel.item` to `item_location` in each original
snapshot. Every missing expected item is a `zombie.inventory.types.WeaponPart`
with `relation=weapon_attachment`, condition 10 and uses 32:

| Local ID | Attachment type | Expected native ID | Recaptured native ID |
| --- | --- | ---: | ---: |
| 85 | `CS2Firearms.AR15_Mag_30_Attachment` | 1217400286 | 1394831860 |
| 103 | `CS2Firearms.TEC9_Mag_18_Attachment` | 985715107 | 990687181 |
| 176 | `CS2Firearms.AR15_Mag_30_Attachment` | 781183810 | 181690749 |
| 198 | `CS2Firearms.AK_Mag_30_Attachment` | 589850594 | 1967615303 |
| 247 | `CS2Firearms.SMG_45_Mag_30_Attachment` | 1293998438 | 260200991 |
| 264 | `CS2Firearms.AK_Mag_30_Attachment` | 1463515071 | 611892906 |
| 277 | `CS2Firearms.AR15_Mag_30_Attachment` | 759412876 | 912210072 |
| 285 | `CS2Firearms.AR10_Mag_20_Attachment` | 300314038 | 771846360 |
| 377 | `CS2Firearms.AK_Mag_30_Attachment` | 54296019 | 1093743824 |
| 581 | `CS2Firearms.AK_Mag_30_Attachment` | 1236078849 | 1551311194 |
| 688 | `CS2Firearms.AR15_Mag_30_Attachment` | 427817015 | 963330966 |

The installed mod's exact local source is:

```text
Z:/SteamLibrary/steamapps/workshop/content/108600/3691681426/mods/CS 2 Firearms 93's Definitive Edition/42.0/media/lua/shared/CS2Firearms_mag_attachment.lua
```

Its `ISAttachMagazine` callback is registered on `Events.OnEquipPrimary`. For a
ranged weapon containing a magazine it unconditionally creates
`instanceItem(magazineType .. "_Attachment")` and calls `weapon:attachWeaponPart`.
This replaces the existing part in the `Clip` slot. Item creation gives the new
instance a new native ID.

Our [reconstructor](../../src/zombie/savefile/ApocBRPlayerRelationalReconstruction.java)
restores attachments, then calls `player.setPrimaryHandItem(item)`.
[IsoGameCharacter.setPrimaryHandItem](../../decompiled/zombie/characters/IsoGameCharacter.java)
fires `OnEquipPrimary`. In contrast, exact-build
[IsoPlayer.load](../../decompiled/zombie/characters/IsoPlayer.java) assigns
`leftHandItem` and `rightHandItem` directly from the saved inventory list, without
those equip events. The source chain plus the 11 matching attachment types
strongly identifies callback-driven replacement as the root cause. This analysis
did not add per-event instrumentation or rerun a corrected implementation, so
that final causal verification remains pending.

Proposed fix: restore hand state using the native load semantics for temporary
and login reconstruction, avoiding gameplay equip callbacks during deserialization.
Audit equip-parent bookkeeping, weapon variables and models separately instead
of bypassing arbitrary gameplay state. Do not weaken the item ID check or
accept every random replacement as equivalent; the callback may also reapply
weapon-stat modifiers because its attach call uses normal gameplay semantics.

## Thirteen earlier reconstruction failures: local compatibility limits

Item factory/runtime-class validation fails for local IDs:

```text
3, 90, 239, 358, 537, 574, 643, 675
```

Trait registry resolution fails for local IDs:

```text
20, 685, 750, 784, 845
```

This differs from the production export:

- Original 66 equipment failures become 59 equipment-position failures, 3 item
  validation failures (3, 90, 239), and 4 trait registry failures (685, 750, 784, 845).
- Original 6 item-validation failures become 5 item-validation failures and 1
  earlier trait-registry failure (20).
- All 11 item-key failures repeat.
- The original immutable-list failure (432) now verifies.

A failure moving earlier on the same pinned snapshot is evidence that the local
reconstruction environment differs from the production environment, not that the
stored profile newly deteriorated. Possible differences include loaded mod
versions, item definitions and registered traits. The current migration log
alone does not identify which item or trait definition failed.

The detailed `ITEM_TYPE_MISMATCH` and `MISSING_REGISTRY` events are emitted to the
server's reconstruction log/terminal, not to `player-migration.log`. During this
investigation both `server-console.txt` and
`Logs/2026-10-26_16-54_DebugLog-server.txt` were empty. VS Code's task terminal was
not available as a persisted text source. Therefore the exact missing type/key
names cannot be established from the available persisted logs. This is a real
remaining diagnostic gap; do not label all 13 as proven missing mods, since the
item check also rejects runtime-class mismatches.

Next collection should persist both reconstruction and migration output, or copy
bounded item/type/registry context into the migration failure event itself.
Compare those names against the exact production mod definitions before changing
the validator's rejection policy. Keep strict missing-definition checks.

## Verified immutable-list case and next validation

Local ID **432** passes every selected assertion and client profile encoding
(`payloadBytes=17587`), including inventory, equipment, item state, traits, stats,
XP, health and core character state. This is runtime evidence that the owned
mutable item-list restoration fixed this original failure. It does not certify
every mod field beyond the verifier's selected coverage.

Apply and regression-test the sparse-position comparison fix and native-style
hand restoration, persist reconstruction diagnostics, and rerun this same 84-ID
cohort. Further failures may become visible once the first failing check is
removed. None of the original snapshots need deletion to run that experiment.

## Per-profile evidence index

The line numbers refer to the local append-only migration log at analysis time.
They preserve all 84 original audit IDs and the transition from production to
local outcome without embedding account or survivor names.

| Local ID | Original production failure | Local result | Local log line |
| --- | --- | --- | ---: |
| 3 | EQUIPMENT_TARGET_MISMATCH | item type | 6 |
| 20 | item type | trait registry | 10 |
| 71 | EQUIPMENT_TARGET_MISMATCH | equipment positions | 21 |
| 79 | EQUIPMENT_TARGET_MISMATCH | equipment positions | 32 |
| 81 | EQUIPMENT_TARGET_MISMATCH | equipment positions | 43 |
| 85 | KEYS_MISMATCH_item | item keys | 55 |
| 90 | EQUIPMENT_TARGET_MISMATCH | item type | 59 |
| 103 | KEYS_MISMATCH_item | item keys | 71 |
| 110 | EQUIPMENT_TARGET_MISMATCH | equipment positions | 82 |
| 129 | EQUIPMENT_TARGET_MISMATCH | equipment positions | 93 |
| 146 | EQUIPMENT_TARGET_MISMATCH | equipment positions | 104 |
| 170 | EQUIPMENT_TARGET_MISMATCH | equipment positions | 115 |
| 176 | KEYS_MISMATCH_item | item keys | 127 |
| 198 | KEYS_MISMATCH_item | item keys | 139 |
| 239 | EQUIPMENT_TARGET_MISMATCH | item type | 143 |
| 247 | KEYS_MISMATCH_item | item keys | 155 |
| 250 | EQUIPMENT_TARGET_MISMATCH | equipment positions | 166 |
| 264 | KEYS_MISMATCH_item | item keys | 178 |
| 273 | EQUIPMENT_TARGET_MISMATCH | equipment positions | 189 |
| 277 | KEYS_MISMATCH_item | item keys | 201 |
| 285 | KEYS_MISMATCH_item | item keys | 213 |
| 358 | item type | item type | 217 |
| 377 | KEYS_MISMATCH_item | item keys | 229 |
| 385 | EQUIPMENT_TARGET_MISMATCH | equipment positions | 240 |
| 386 | EQUIPMENT_TARGET_MISMATCH | equipment positions | 251 |
| 405 | EQUIPMENT_TARGET_MISMATCH | equipment positions | 262 |
| 432 | immutable list | VERIFIED | 280 |
| 451 | EQUIPMENT_TARGET_MISMATCH | equipment positions | 291 |
| 491 | EQUIPMENT_TARGET_MISMATCH | equipment positions | 302 |
| 524 | EQUIPMENT_TARGET_MISMATCH | equipment positions | 313 |
| 534 | EQUIPMENT_TARGET_MISMATCH | equipment positions | 324 |
| 537 | item type | item type | 328 |
| 574 | item type | item type | 332 |
| 581 | KEYS_MISMATCH_item | item keys | 344 |
| 632 | EQUIPMENT_TARGET_MISMATCH | equipment positions | 355 |
| 640 | EQUIPMENT_TARGET_MISMATCH | equipment positions | 366 |
| 643 | item type | item type | 370 |
| 650 | EQUIPMENT_TARGET_MISMATCH | equipment positions | 381 |
| 652 | EQUIPMENT_TARGET_MISMATCH | equipment positions | 392 |
| 653 | EQUIPMENT_TARGET_MISMATCH | equipment positions | 403 |
| 656 | EQUIPMENT_TARGET_MISMATCH | equipment positions | 414 |
| 663 | EQUIPMENT_TARGET_MISMATCH | equipment positions | 425 |
| 664 | EQUIPMENT_TARGET_MISMATCH | equipment positions | 436 |
| 665 | EQUIPMENT_TARGET_MISMATCH | equipment positions | 447 |
| 666 | EQUIPMENT_TARGET_MISMATCH | equipment positions | 458 |
| 668 | EQUIPMENT_TARGET_MISMATCH | equipment positions | 469 |
| 671 | EQUIPMENT_TARGET_MISMATCH | equipment positions | 480 |
| 673 | EQUIPMENT_TARGET_MISMATCH | equipment positions | 491 |
| 675 | item type | item type | 495 |
| 679 | EQUIPMENT_TARGET_MISMATCH | equipment positions | 506 |
| 685 | EQUIPMENT_TARGET_MISMATCH | trait registry | 510 |
| 688 | KEYS_MISMATCH_item | item keys | 522 |
| 690 | EQUIPMENT_TARGET_MISMATCH | equipment positions | 533 |
| 703 | EQUIPMENT_TARGET_MISMATCH | equipment positions | 544 |
| 716 | EQUIPMENT_TARGET_MISMATCH | equipment positions | 555 |
| 717 | EQUIPMENT_TARGET_MISMATCH | equipment positions | 566 |
| 726 | EQUIPMENT_TARGET_MISMATCH | equipment positions | 577 |
| 730 | EQUIPMENT_TARGET_MISMATCH | equipment positions | 588 |
| 731 | EQUIPMENT_TARGET_MISMATCH | equipment positions | 599 |
| 732 | EQUIPMENT_TARGET_MISMATCH | equipment positions | 610 |
| 734 | EQUIPMENT_TARGET_MISMATCH | equipment positions | 621 |
| 738 | EQUIPMENT_TARGET_MISMATCH | equipment positions | 632 |
| 745 | EQUIPMENT_TARGET_MISMATCH | equipment positions | 643 |
| 746 | EQUIPMENT_TARGET_MISMATCH | equipment positions | 654 |
| 750 | EQUIPMENT_TARGET_MISMATCH | trait registry | 658 |
| 754 | EQUIPMENT_TARGET_MISMATCH | equipment positions | 669 |
| 762 | EQUIPMENT_TARGET_MISMATCH | equipment positions | 680 |
| 775 | EQUIPMENT_TARGET_MISMATCH | equipment positions | 691 |
| 778 | EQUIPMENT_TARGET_MISMATCH | equipment positions | 702 |
| 784 | EQUIPMENT_TARGET_MISMATCH | trait registry | 706 |
| 786 | EQUIPMENT_TARGET_MISMATCH | equipment positions | 717 |
| 787 | EQUIPMENT_TARGET_MISMATCH | equipment positions | 728 |
| 788 | EQUIPMENT_TARGET_MISMATCH | equipment positions | 739 |
| 794 | EQUIPMENT_TARGET_MISMATCH | equipment positions | 750 |
| 811 | EQUIPMENT_TARGET_MISMATCH | equipment positions | 761 |
| 818 | EQUIPMENT_TARGET_MISMATCH | equipment positions | 772 |
| 826 | EQUIPMENT_TARGET_MISMATCH | equipment positions | 783 |
| 833 | EQUIPMENT_TARGET_MISMATCH | equipment positions | 794 |
| 839 | EQUIPMENT_TARGET_MISMATCH | equipment positions | 805 |
| 845 | EQUIPMENT_TARGET_MISMATCH | trait registry | 809 |
| 846 | EQUIPMENT_TARGET_MISMATCH | equipment positions | 820 |
| 847 | EQUIPMENT_TARGET_MISMATCH | equipment positions | 831 |
| 849 | EQUIPMENT_TARGET_MISMATCH | equipment positions | 842 |
| 856 | EQUIPMENT_TARGET_MISMATCH | equipment positions | 853 |
