# Merge review 06/48: ItemContainer

Status: **approved and applied; target container fixture passed; target-JAR/runtime pending**. Reviews 01-05 are
approved and applied. See the [tracker](../features/2026-10-27-subsystem-port-tracker.md).

## Inputs and candidate

- [Old vanilla](../../../42.21.0/decompiled/zombie/inventory/ItemContainer.java).
- [Old patch](../../../42.21.0/src/zombie/inventory/ItemContainer.java).
- [Target vanilla](../../decompiled/zombie/inventory/ItemContainer.java).
- [Applied diff](2026-10-27-merge-06-item-container.diff).

The user approved the merge, accepting the pending integration dependencies.
The exact target vanilla baseline was committed separately as `d5f5cd7`, then
the reviewed diff was applied to [target ItemContainer](../../src/zombie/inventory/ItemContainer.java).
The behavioral patch remains separate from the baseline commit; no push occurred.
Dependency acceptance is not blanket approval for later class merges.

## Existing patch and ownership

Our old-versus-vanilla diff changes only three locations:

1. Initialize the public `ArrayList<InventoryItem> items` field with
   `ServerInventoryUpdateIndex.Items`, an ArrayList-compatible mutation-tracked
   subclass, rather than a plain ArrayList.
2. Recreate that tracked list in `emptyIt()` as well.
3. Add server timing/work counters around the original weight-summing loop,
   retaining its order, float accumulation and return/exception behavior.

The copied `ServerInventoryUpdateIndex` caches sparse routes to nested containers
and IUpdater items. Actual character consumption is in the old patched
`IsoGameCharacter`, not directly in IsoPlayer. Updates remain child-before-parent;
callback mutation or a replaced/publicly supplied plain list falls back to live
scanning without replacing that caller-owned list. The integration consumer is
still awaiting its own class review, so this merge alone does not activate the
complete optimization. Weight timing is secondary; it is not a weight cache or
change to item weight policy.

## What 42.22.0 changes

- Adds `CONTAINER_MAX_NESTING = 16`, bounded parent-chain traversals and visited
  sets/depth checks in several recursive queries/removals and recipe searches.
- Rejects insertion of an item whose ID appears in the destination's containing
  chain through `chainContainsContainingItem`, including AddItemBlind.
- Removes deprecated `removeItemOnServer`. The old patched IsoMannequin still
  calls it; target vanilla IsoMannequin removes that call and will be handled in
  its own review. Do not restore the obsolete API just to preserve the old call.
- Changes `contains(item, doInv)` from PZArrayList reference comparison to
  `Object::equals`, removing the PZArrayList import.
- Routes fluid-container searches through `InventoryItem.isAvailableFluidContainer`,
  and adds `containsAvailableFluidContainer`. That target item helper belongs to
  the upcoming InventoryItem review and is absent from the old patched item class.
- Retains the new recursion/iteration and resource lookup behavior in full; the
  candidate does not substitute old implementations for those methods.

These changes also affect nested inventories read by NPC and relational player
persistence code. No schema, SQL, configuration or PostgreSQL grants change in
this candidate.

## Applied decision and validation

Keep every upstream change and retain our tracked-list initialization/reset and
weight instrumentation. Preserve the public field's declared ArrayList type and
the new add/query/removal contracts. Do not introduce a parallel recursive
implementation or modify the 16-depth policy during this merge.

The installed source matches the approved candidate. `python -B tools/test_item_container_port.py`
passed **31 target inventory/container assertions**. It extracts target reset,
weight, parent-chain and recursive query/removal methods, and compiles the actual
target ServerInventoryUpdateIndex and MutationTrackedArrayList. It reuses the
existing sparse-inventory fixture cases with these target sources. Items,
containers' remaining services, telemetry and engine dependencies are doubles.

Coverage includes child-before-parent update order, stable-list cache reuse,
iterator/sublist changes, callback removals/additions/public-list replacement,
plain-list fallback, tracked reset, exact float accumulation order, propagated
weight failure, cyclic inventory/parent traversal, nested lookup/count/removal,
ancestor-chain rejection predicate and depth 16/17 boundaries. Java assertions
run enabled. The production AddItem/AddItemBlind bodies and fluid helper are not
executed by this fixture; source comparison confirms their upstream code remains
unchanged, and native insertion/fluid integration still needs validation.

Compare update behavior with the actual target character consumer and exercise
new item fluid predicates when those classes are ported. Target-JAR compilation
and runtime inventory/trade/save-load remain integration requirements.

Next decision: [review 07, InventoryItem](2026-10-27-merge-07-inventory-item.md).
