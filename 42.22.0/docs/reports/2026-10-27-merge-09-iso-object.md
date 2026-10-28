# Merge review 09/48: IsoObject

Status: **awaiting individual user discussion; not applied**. Reviews 01-08 are
approved and applied. See the [tracker](../features/2026-10-27-subsystem-port-tracker.md).

## Inputs and candidate

- [Old vanilla](../../../42.21.0/decompiled/zombie/iso/IsoObject.java).
- [Old patch](../../../42.21.0/src/zombie/iso/IsoObject.java).
- [Target vanilla](../../decompiled/zombie/iso/IsoObject.java).
- [Unapplied List-compatible candidate](2026-10-27-merge-09-iso-object.diff).

The three-way merge is textually clean, but its raw result still refers to the
removed PZArrayList type and APIs. The candidate includes the explicit API
adaptation below; it has not been installed and no baseline commit exists yet.

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

## Verification after acceptance

Compare the installed diff and native getter body with target vanilla. Exercise
plain-list fallback, tracked-list stable hits and invalidation after insertion,
removal/reorder/replacement, object detach/reset/reuse, subclass overrides and
client behavior. Keep native registry callbacks and ID/index semantics unchanged;
do not infer world thread safety from a local cache. Exercise source dictionary
scope across virtual decode, failures and nested object/item data when the full
reconstruction pipeline is ported. Exact-JAR/native save-load and streaming tests
remain integration requirements.

Decision requested: apply this IsoObject candidate with the dictionary hook and
List-compatible, opt-in ID cache, while leaving the actual collection adapter
as explicit later work.
