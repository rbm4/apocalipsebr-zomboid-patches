# Merge review 09/48: IsoObject

Status: **approved and applied; object fixture passed; target-JAR/native integration pending**. Reviews 01-08 are
approved and applied. See the [tracker](../features/2026-10-27-subsystem-port-tracker.md).

## Inputs and candidate

- [Old vanilla](../../../42.21.0/decompiled/zombie/iso/IsoObject.java).
- [Old patch](../../../42.21.0/src/zombie/iso/IsoObject.java).
- [Target vanilla](../../decompiled/zombie/iso/IsoObject.java).
- [Applied List-compatible diff](2026-10-27-merge-09-iso-object.diff).

The three-way merge is textually clean, but its raw result still refers to the
removed PZArrayList type and APIs. The candidate includes the explicit API
adaptation below. The user approved it with "proceed". The exact target vanilla
baseline was committed separately as `5aca951`, then the approved diff was applied
to [target IsoObject](../../src/zombie/iso/IsoObject.java). The behavioral patch
remains separate from that baseline commit; no push occurred.

## Existing patch responsibilities

DB-DICT: scope the final two-argument `load` wrapper around its virtual
three-argument decoder, selecting/restoring the source dictionary for nested
native object reconstruction. This connects to the InventoryItem provenance and
later PostgreSQL reconstruction work, without performing SQL from the decoder.

OPT-REGISTRY: cache the native server entity ID when the square/list identity,
mutation version, coordinates, floor input and cached ID remain unchanged. Keep
the ClassValue guard for subclasses overriding index/floor/equality behavior,
clear cached references on reset, and retain client/native fallback semantics.
This is a lazy getter cache, not the historical global registry-reconciliation
implementation that is absent from current GameEntityManager.

## Upstream behavior retained

Keep all target changes, including List container-query signatures,
`syncIsoObjectReceive(ByteBufferReader, UdpConnection)` and source-aware sheet-rope
damage validation, world-format 251 native loads, object-index handling and
rendering/hoppable updates. The split `apocbrVanillaEntityNetID` body in the
candidate is exactly the new vanilla getter body: in particular, it retains the
target's actual object-index assignment rather than restoring an old floor-index
special case. The wire/persistence ID calculation is not redesigned here.

## Proposed List compatibility decision

Use `List<IsoObject>` for the cached list and local square-list reference. Enable
the fast path only when the list implements the copied
`MutationTrackedArrayList` contract, reading its `mutationVersion()` instead of
the removed PZArrayList APIs. Preserve all other existing eligibility/input checks.

The current IsoGridSquare storage is a plain ArrayList, so it takes the native
getter fallback on every call. It must never be cached as if it supplied reliable
mutation notifications. A future List-compatible collection/cache adapter can
enable the fast path, but requires its own explicit implementation and validation.
No square-storage change or new adapter is included in this candidate.

Remove the obsolete backing-array exposure check and its raw-array fallback
telemetry; those APIs do not exist in the target List contract. Existing cache-hit
and native-check counters remain. Do not restore the old public PZArrayList
descriptors to make the patch compile.

The separate identity-position lookup optimization formerly implemented by
PZArrayList remains pending. This candidate does not claim it is present in the
new List storage or retire it from the overall migration scope.

## Validation and remaining integration

The installed diff matches the reviewed candidate; the split native getter body
equals target vanilla. `python -B tools/test_iso_object_port.py` passed **31 assertions**.
It extracts target cache fields/getters, setSquare and final decode wrapper, uses
the production mutation-tracked list with counting subclasses, and compiles them
against square/registry/dictionary/native-decoder doubles. Four source-extracted
reset assignments are exercised rather than compiling the entire native reset.

Coverage includes plain-list fallback, stable tracked hits avoiding index/registry
calls, insertion/reorder/sublist invalidation, removal/re-add, reset, client/server
transition, null square, index/equality overrides and mutation during calculation.
The target floor object's actual-index ID behavior is retained. The final load
wrapper is tested for scope entry before virtual dispatch, version preservation
and scope closure on failure. Actual dictionary routing/nested reconstruction,
full native class linkage and world concurrency are not tested here.

Current plain square lists continue to use native fallback. The actual collection
adapter/identity-position index, exact-JAR compilation, native reconstruction,
streaming and registry integration remain explicit future work.

Next decision: [review 10, LoadGridsquarePerformanceWorkaround](2026-10-27-merge-10-load-gridsquare.md).
