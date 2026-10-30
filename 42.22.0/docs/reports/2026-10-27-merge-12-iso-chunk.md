# Review 12/48: IsoChunk

Status: **approved/applied on 2026-10-28**. Reviews 01-09 are applied and 10-11 are
retired to target vanilla. The user retired only the obsolete reader patches;
the broader square-position/network-ID cache remains in scope. See the
[tracker](../features/2026-10-27-subsystem-port-tracker.md).

## Inputs and proposed merge

- [Old vanilla](../../../42.21.0/decompiled/zombie/iso/IsoChunk.java).
- [Old patch](../../../42.21.0/src/zombie/iso/IsoChunk.java).
- [Target vanilla](../../decompiled/zombie/iso/IsoChunk.java).
- [Candidate diff against target vanilla](2026-10-27-merge-12-iso-chunk.diff).

Keep the existing unload optimization, unload-phase telemetry and explicit
vehicle-spawn switch cases. Resolve the sole textual conflict by keeping the
native List traversal in chunk serialization, dropping the obsolete audited
raw-array reader. Target replacement source is applied after exact vanilla baseline commit `345374b`.

## Old patch intent

The moving-object unload loop formerly called `mov.contains(obj)` after removal
to decide whether to decrement its cursor. That adds another linear scan for
each object. Our patch snapshots `obj.current == sq || obj.last == sq` before
cleanup and uses that predicate instead. Native `IsoMovingObject.removeFromSquare()`
removes from those lists and clears both references. This avoids the extra scan;
list removals can still shift elements, so this does not make the whole unload
algorithm linear.

The predicate relies on consistent square membership and removal callbacks.
It is not universally equivalent to checking actual membership after arbitrary
callbacks or inconsistent state. Verify current/last relationships, duplicates,
despawn/removal side effects and cursor progress before accepting runtime safety.

The remaining additions measure unload phases and object counts, and explicitly
handle vehicle-spawn switch case 4 and default with chance 2. The latter is a
carried source change, not a measured optimization. Our old serialization reader
change is obsolete for the same reason as reviews 10-11.

## Upstream retained

Retain world read/write format 251; the new tutorial check; revised building
intersection API; renderer constant changes; vehicle SQL-ID lookup helpers; and
List-based object serialization. None should be reverted to the old baseline.

## Dependencies and verification

Primary owner: OPT-STREAM; telemetry support: ApocBRServerTelemetryLite. Native
moving-object removal and later streaming/lifecycle ports must preserve ownership
and cursor behavior. This class does not activate the deferred square-list cache.

Source comparison found one conflict, confined to the obsolete raw-array reader.
The candidate resolves that region to upstream and retains the other patch hunks.
The focused [unload fixture](../../../tools/test_iso_chunk_port.py) passed
437,322 assertions across 6,000 differential scenarios. It executes extracted
production vanilla/patched loops and native removeFromSquare with engine doubles,
covering server/client/single-player guards, current/last relationships, early
detach callbacks, survivor/animal/vehicle effects, and remaining collection state.
Source assertions preserve format 251 and the new vehicle lookup API and reject
obsolete raw-array calls. Duplicate membership and arbitrary callbacks that
change other list entries remain outside the equivalence guarantee; the inherited
predicate relies on the ownership invariant described above.

The user approved: "agreed, apply the merge". The source matches the reviewed
candidate. Exact-JAR compilation, deployment and native streaming/persistence
runtime verification remain pending. No installed files were changed.
