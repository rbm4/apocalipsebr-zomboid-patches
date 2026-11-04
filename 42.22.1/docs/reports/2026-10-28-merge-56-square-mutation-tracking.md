# Follow-up review56: square-object mutation tracking and entity-ID cache

Created2026-10-28. Status: **two-file candidate; not applied**. Review55 is applied.
See the [subsystem tracker](../features/2026-10-27-subsystem-port-tracker.md),
[candidate diff](2026-10-28-merge-56-square-mutation-tracking.diff) and
[verification record](2026-10-28-merge-56-square-mutation-tracking.json).

## What is inactive today

The applied review09 [IsoObject](../../src/zombie/iso/IsoObject.java) cache checks
that square.getObjects is MutationTrackedArrayList before using mutationVersion.
The applied [IsoGridSquare](../../src/zombie/iso/IsoGridSquare.java) still constructs
a plain ArrayList with capacity2, so ordinary square objects take the native
recalculation path. The fallback preserves behavior but leaves that cache inactive.

## Candidate and behavior

Use the existing [MutationTrackedArrayList](../../src/zombie/util/list/MutationTrackedArrayList.java)
for only the square.objects allocation, keeping its declared protected final
List<IsoObject> type and initial capacity2. Add explicit default and capacity
constructors to the helper: the default preserves current callers, while the
capacity constructor delegates to ArrayList. Do not replace other square lists.
Both sources already exist; extend them without another native baseline.

This activates the already ported server entity-ID cache for eligible native
objects. Repeated unchanged lookups can return cached IDs without repeating index
lookup/registry checks. List mutations, square/coordinates/floor changes and reset
continue to invalidate through the existing cache checks; custom equals,
getObjectIndex or isFloor overrides retain the native fallback, as do clients.
Existing MutationTrackedArrayList handles replacements, structural mutations and
backed views; no cache algorithm or tracking logic changes in this candidate.

This does not add an identity-position index: first lookup after invalidation
still calls normal List.indexOf. The old PZArrayList identity-index optimization
remains a separate design/port task; its removed-target ledger entry stays open.
Retired reader patches10/11 and the obsolete PZArrayList class are not restored.
No per-list identity map is added to unrelated MutationTrackedArrayList consumers.
Neither ArrayList subclass nor mutation versions make concurrent mutation safe.

## Verification and recommendation

[Fixture](../../../tools/test_square_list_port.py): **33 assertions passed**,
reusing actual extracted IsoObject cache/native getter/decode code with candidate
list source and engine/registry/decoder doubles. Covers stable hits, insert/reorder/
sublist replacement invalidation, detach/re-add/reset, client fallback, custom
inputs and mutation during calculation, plus constructor behavior. Source checks
verify the actual square initializer and capacity2 wiring; the complete native
square is not instantiated. Decode checks are inherited regression coverage and
do not establish new persistence behavior. This is not exhaustive testing of
every possible collection callback/mutation combination.

The full303-source Java25 candidate-overlay build passes against the identified
target JAR, producing895 class files. No live square/world/client, performance
measurement, production deployment or database execution was performed.

Recommendation: apply this narrow activation of the existing entity-ID cache,
then review the remaining identity-position index separately. Runtime and cleanup
validation remain open; historical-data migration planning remains deferred.
