# Merge review 08/48: IsoGridSquare

Status: **awaiting individual user discussion; not applied**. Reviews 01-07 are
approved and applied. See the [tracker](../features/2026-10-27-subsystem-port-tracker.md).

## Inputs and resolved candidate

- [Old vanilla](../../../42.21.0/decompiled/zombie/iso/IsoGridSquare.java).
- [Old patch](../../../42.21.0/src/zombie/iso/IsoGridSquare.java).
- [Target vanilla](../../decompiled/zombie/iso/IsoGridSquare.java).
- [Unapplied resolved candidate diff](2026-10-27-merge-08-iso-grid-square.diff).

All **nine textual conflicts** are old array-exposure calls versus new vanilla
List traversal. The proposed candidate selects the target code for those regions
and reapplies the other existing visibility/LOS changes. It has no conflict
markers; no target source or baseline commit has been created for this class.

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

## Proposed decision and verification after acceptance

Keep target List traversal at all nine conflicts and retain the existing LOS,
thread-local and player-aware room-visibility changes shown in the diff. Do not
restore PZArrayList return types, raw-array calls or old door/trap signatures.

If approved, verify lighting slot initialization/reuse, thread-isolated vectors,
server versus client room-exploration ownership and room-seen overload behavior.
Compare the resolved List traversals/removals with target vanilla and test the
eventual identity/mutation adapter with callbacks, duplicates, reorder, reset and
pool reuse. Target-JAR compilation and native multiplayer/streaming/LOS validation
remain integration requirements.

Decision requested: apply this IsoGridSquare candidate, leaving the collection
cache adapter as explicit later work rather than restoring the removed array API.
