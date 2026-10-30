# Merge review 07/48: InventoryItem

Status: **approved and applied; target parser fixture passed; target-JAR/reconstruction integration pending**. Reviews 01-06 are
approved and applied. The user accepts that later integration ports will complete
the staged dependencies. See the [tracker](../features/2026-10-27-subsystem-port-tracker.md).

## Inputs and proposed resolution

- [Old vanilla](../../../42.21.0/decompiled/zombie/inventory/InventoryItem.java).
- [Old patch](../../../42.21.0/src/zombie/inventory/InventoryItem.java).
- [Target vanilla](../../decompiled/zombie/inventory/InventoryItem.java).
- [Applied resolved diff](2026-10-27-merge-07-inventory-item.diff).

The three-way merge has **one textual conflict**, in the item length-mismatch
branch. The conflict-free resolution was approved with "apply, this wil hook into our reconstruction algorithm later
inside the postgresql changes". The exact target vanilla baseline was committed
separately as `50799c9`, then the reviewed resolution was applied to
[target InventoryItem](../../src/zombie/inventory/InventoryItem.java). The behavioral
patch remains separate from the baseline commit; no push occurred. PostgreSQL
reconstruction is an explicit later integration, not certified by this merge.

## Existing patch responsibilities

This patch primarily integrates DB-PLAYER and DB-DICT:

- Capture the current encoder origin before `storeInByteData`, and associate
  origin/world-format metadata with the resulting ByteBuffer out of band.
- Enter/restore the source dictionary scope around static `loadItem` and instance
  `load`; propagate a dictionary `Unavailable` exception rather than allowing
  native recovery to silently skip a foreign payload that could not be decoded.
- Record ITEM_LOAD_FAILED, ITEM_LENGTH_MISMATCH and ITEM_SKIPPED through the
  scoped load-quality collector. Keep native exception logging/recovery otherwise.
- Attach inherited source context to nested byteData for later native decoding.

These hooks neither replace the native item format nor perform SQL from a decoder.
Dictionary namespace publication and relational reconstruction have their own
later adaptation work. The candidate does not approve or implement that work.

## What 42.22.0 changes

The target supplies the new `isAvailableFluidContainer` helper used by the merged
ItemContainer. Other upstream changes include:

- Require positive declared item length no larger than the remaining buffer;
  validate embedded byteData size before reading it.
- Add `loadByteCount`, preserving actual bytes consumed before native mismatch
  recovery resets the input position.
- Write/use world format 251 for newly encoded item/object byteData and cloning.
- Bound outer-container traversal and revise item synchronization behavior for
  remote inventories.
- Add activation/model/hand-item capability and maintenance-XP behavior.

Keep all target fields, helpers, control flow, validation and native behavior.
Only the existing integration hooks described above are reapplied.

## Two proposed resolution decisions

**Length conflict:** retain upstream's consumed-byte field and predicate, then
record our diagnostic before its existing recovery:

```java
item.loadByteCount = input.position() - position;
if (dataLen != item.loadByteCount) {
    zombie.savefile.ApocBRPlayerLoadQuality.issue("ITEM_LENGTH_MISMATCH");
    // Existing target buffer repositioning, logging and debug exception follow.
```

Do not restore the old predicate or bypass the stricter target size guard.

**New encoding metadata:** the target `storeInByteData` already writes a 251
header. Change our attached `Metadata(encodingOrigin, 249)` to **251** as well.
This applies only to newly encoded data in that method. Existing payloads retain
their own stored version/provenance; there is no global replacement of historical
249 data or database metadata in this candidate.

All other integration hunks merge without conflict. The candidate diff is against
target vanilla and visibly adds the hooks while retaining the upstream changes.

## Validation and remaining integration

The installed source matches the reviewed resolution. `python -B tools/test_inventory_item_port.py`
passed **35 assertions** using the target static item parser, byteData encoder and
fluid predicate extracted unchanged, plus the actual provenance and load-quality
helpers. Dictionary scope/routing, virtual item decoder, native object/fluid state
and logging are explicit doubles; this is not a real dictionary/reconstruction test.

Coverage includes successful virtual dispatch, consumed-byte reporting before
under/over-read recovery, native debug mismatch exceptions, invalid lengths,
ordinary failed/skipped items and issue codes, Unavailable propagation, nested
scope restoration, historical version preservation, 251 byteData header/metadata
agreement and origin captured before encoding. The actual target fluid predicate
is exercised for empty/matching/nonmatching/missing fluids. Java assertions run
enabled. Source checks also retain instance-load bounds validation and its scope/
nested provenance hooks; the complete instance decoder is not compiled here.

Full dictionary routing, embedded payload reconstruction, actual ItemContainer
fluid-query integration, subclass loaders and PostgreSQL character round trips
remain pending. Keep those dependencies in the shared ledger rather than
claiming full-class linkage or persistence portability. Exact-JAR and runtime
save/load validation remain later checkpoints.

Next decision: [review 08, IsoGridSquare](2026-10-27-merge-08-iso-grid-square.md).
