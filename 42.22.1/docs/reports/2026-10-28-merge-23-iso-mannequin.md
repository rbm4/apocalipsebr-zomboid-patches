# Review 23/48: IsoMannequin

Created 2026-10-28. Status: **approved/applied on 2026-10-28**. IsoDeadBody review 22 is
applied. See the [tracker](../features/2026-10-27-subsystem-port-tracker.md).

## Inputs and proposal

- [Old vanilla](../../../42.21.0/decompiled/zombie/iso/objects/IsoMannequin.java).
- [Old patch](../../../42.21.0/src/zombie/iso/objects/IsoMannequin.java).
- [Target vanilla](../../decompiled/zombie/iso/objects/IsoMannequin.java).
- [Candidate diff](2026-10-28-merge-23-iso-mannequin.diff).

Retain dictionary decode context, new-payload provenance and the old syncModel
local-variable repair on target vanilla. Textual merging is clean, but update
new-payload provenance worldVersion from 249 to **251** to match the native
encoder header. Applied source equals the reviewed candidate after exact vanilla baseline `70a0b84`. Primary owner: DB-DICT;
shared owners: inventory payload provenance and mannequin equipment/visuals.

## Existing hooks and purpose

Wrap the full three-argument load method, including super.load and mannequin
contents/visuals, in ApocBRWorldDictionaryDecode.enter(input). Preserve nested
source context through decode and restore prior context on exit. This does not
prepare database views or implement reconstruction routing by itself.

For setCustomSettingsToItem on Moveable items, capture localEncoding inside the
existing SliceBufferLock before saving. Save native bytes into an independently
allocated item buffer, then remember that buffer's identity with captured origin
and world version 251. Metadata remains out of band; no extra bytes enter the
native payload. Leave the native buffer position and weight behavior unchanged.
Do not relabel preexisting 249 payloads as 251: this code describes newly encoded
251 data only. Failure before buffer assignment leaves the old buffer/metadata.

Retain String modelScript instead of String i in syncModel. The decompiled
method later declares for-loop int i locals in overlapping scopes; this is a
Java source compilation repair, not an optimization.

## Upstream retained

Keep loadState's native version 251 and the newly encoded buffer header 251.
Keep List traversal in all table-surface rendering paths, new offset calculations
and getZi usage. Keep upstream removal of the redundant removeItemOnServer call
when transferring unworn mannequin contents. Do not restore old rendering-array
APIs or inventory transfer statements.

## Verification and remaining integration

The [encoder fixture](../../../tools/test_mannequin_port.py) reconstructs the
candidate in a temporary directory. It passed **16 assertions** executing the
extracted candidate encoder with the actual ApocBRPayloadProvenance class and
explicit item/container/save/lock doubles. Checks cover version/header agreement,
unknown origin, known origin captured before encoding even if publication changes
during save, independent buffers, weight behavior, non-Moveable bypass and failure
preserving the prior buffer/metadata. This does not execute the real save/load
serializer, dictionary view resolution, nested inventory or model/render paths.

Copied provenance/dictionary helpers and the applied IsoObject/InventoryItem
hooks are staged dependencies. Native payload round-trips for historical 249 and
new 251, metadata transfer through outer persistence/copy paths, full PostgreSQL
routing/reconstruction, rendering/equipment effects and exact-JAR compilation
remain pending. No game installation or database was changed.

User approved: "yes". Applied-candidate equality and 16 encoder/provenance
assertions passed again. Native round-trip and dictionary/runtime validation
remain pending. No installed files were changed.
