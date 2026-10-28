# Follow-up review 49: reconstruction hand storage names

Created 2026-10-28. Status: **approved/applied; full reconstruction/runtime unverified**. The original48 changed
replacement decisions are complete. This is a copied-support compatibility repair.
See the [subsystem tracker](../features/2026-10-27-subsystem-port-tracker.md),
[candidate diff](2026-10-28-merge-49-reconstruction-hand-fields.diff) and
[preimage/verification record](2026-10-28-merge-49-reconstruction-hand-fields.json).

The [copied reconstruction helper](../../src/zombie/savefile/ApocBRPlayerRelationalReconstruction.java)
restores hand equipment through reflection using `leftHandItem` and `rightHandItem`.
These storage names exist in [42.21 vanilla](../../../42.21.0/decompiled/zombie/characters/IsoGameCharacter.java)
but have become `primaryHandItem` and `secondaryHandItem` in
[target vanilla](../../decompiled/zombie/characters/IsoGameCharacter.java).
The generic set helper checks exact fields and matching setters; it does not map
the old names, so those rows can fail with Missing reconstruction field.

The candidate changes only those two strings. Keep primary/secondary snapshot
slot names and database rows unchanged. Preserve direct field restoration:
[target IsoPlayer.load](../../decompiled/zombie/characters/IsoPlayer.java) assigns
the hand fields directly. Calling equip setters would introduce equip callbacks
and side effects during deserialization. Existing capture/snapshot helpers already
address primaryHandItem/secondaryHandItem.

Verification: compared both exact native baselines, target load assignments,
reflection lookup, and capture/snapshot names; candidate applies cleanly. This is
source verification only. No full reconstruction compile, item/equipment round-trip,
real database or engine callback execution has been performed. No new test was
added for this two-string repair. Review48's login tests do not validate it.

Decision: user approved on 2026-10-28; applied source equals the reviewed two-string
diff and native storage/load source checks passed. Dataset/dictionary version metadata,
event metadata, List-cache design, full build/runtime checks and stale-class cleanup
remain separate work. Vehicle PostgreSQL gaps remain deferred while disabled.
