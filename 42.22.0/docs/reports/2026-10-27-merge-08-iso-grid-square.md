# Merge review 08/48: IsoGridSquare

Status: **approved and applied; grid fixture passed; target-JAR/native LOS pending**. Reviews 01-07 are
approved and applied. See the [tracker](../features/2026-10-27-subsystem-port-tracker.md).

## Inputs and resolved candidate

- [Old vanilla](../../../42.21.0/decompiled/zombie/iso/IsoGridSquare.java).
- [Old patch](../../../42.21.0/src/zombie/iso/IsoGridSquare.java).
- [Target vanilla](../../decompiled/zombie/iso/IsoGridSquare.java).
- [Applied resolved diff](2026-10-27-merge-08-iso-grid-square.diff).

All **nine textual conflicts** are old array-exposure calls versus new vanilla
List traversal. The proposed candidate selects the target code for those regions
and reapplies the other existing visibility/LOS changes. It has no conflict
markers. The user approved it with "yes". The exact target vanilla baseline was
committed separately as `0abd0ed`, then the reviewed resolution was applied to
[target IsoGridSquare](../../src/zombie/iso/IsoGridSquare.java). The behavioral
patch remains separate from the baseline commit; no push occurred.

## Upstream contract that must survive

`objects` is now `List<IsoObject>` backed by `new ArrayList<>(2)`, and
`getObjects()` returns List. Keep those declarations and the target traversal,
removal handling and sort behavior. The nine old
`apocbrReadOnlyElements()`/`getElements()` replacements cannot be reused: the
target List contract exposes no backing-array API.

The target also changes edge/door calls to `isNorth()`, adds ladder/reach helpers,
changes container query signatures to List, and removes the old trap-sync helper.
All those upstream changes remain in the candidate.

## Existing visibility changes retained

- Size lighting slots with the copied `LosUtil.SLOT_COUNT` and initialize every
  server slot, matching the copied parallel ServerLOS implementation.
- Give each calling thread its own two Vector2 scratch values for CalcVisibility.
- Keep room-exploration side effects out of the server LOS worker path.
- Retain `checkRoomSeen(IsoPlayer)` and the existing integer overload that delegates
  to it. The player-aware overload permits server-side application without relying
  on the local-player array. Distance/floor checks and client music behavior remain.

These are OPT-LOS/OPT-PLAYER integrations even though this class also owns the
OPT-REGISTRY collection boundary. This does not make every square/world read safe
for concurrent NPC simulation or LOS workers.

## Deferred cache work remains in scope

The old raw-read wrappers prevented verified engine readers from declaring an
uncontrolled PZArrayList backing-array escape. They are unnecessary for target
traversal through List methods; retiring those calls is not approval to remove
the separate identity-position/ID-cache optimization.

That optimization lives in the old PZArrayList and IsoObject patches, not in these
nine reader calls. A List-compatible mutation/identity mechanism still needs
design and validation during the IsoObject/removed-PZArrayList work. The candidate
deliberately retains vanilla ArrayList storage for now; it does not invent an
unreviewed adapter or claim the old lazy ID cache already works on this storage.
Any later change to this collection's storage/helper integration must be made
explicit in that review. The subsystem remains incomplete until that work lands.

## Applied decision and verification

Keep target List traversal at all nine conflicts and retain the existing LOS,
thread-local and player-aware room-visibility changes shown in the diff. Do not
restore PZArrayList return types, raw-array calls or old door/trap signatures.

The installed source matches the reviewed resolution. `python -B tools/test_iso_grid_square_port.py`
passed **357 assertions**. It extracts the target room-seen overloads, lighting
initialization loop, scratch fields and slot constant. Room behavior is compared
with the exact target vanilla room-seen method over floor/distance/building/local
player cases; null inputs and integer-overload delegation are checked. Lighting
tests cover every server slot, reinitialization and client JNI/fallback paths.
Four concurrent fixture threads verify separate scratch objects and per-thread
reuse. World/player/vector/lighting services are explicit doubles.

Source checks confirm the full CalcVisibility method consumes those thread-local
values and retains the server exploration guard; full CalcVisibility/native LOS
is not executed. The List declarations and absence of old array APIs are checked.
Target-JAR compilation and native multiplayer/streaming/LOS validation remain
integration requirements, along with the deferred collection/cache adapter tests.

Next decision: [review 09, IsoObject](2026-10-27-merge-09-iso-object.md).
