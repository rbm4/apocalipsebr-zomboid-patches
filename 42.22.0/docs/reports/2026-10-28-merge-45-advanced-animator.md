# Review 45/48: AdvancedAnimator

Created 2026-10-28. Status: **approved and applied on 2026-10-28**.
Review44 is applied. This one-class patch is also applied after baseline 2b55f41. See the
[subsystem tracker](../features/2026-10-27-subsystem-port-tracker.md),
[candidate diff](2026-10-28-merge-45-advanced-animator.diff) and
[source verification record](2026-10-28-merge-45-advanced-animator.json).

## Old intent and target comparison

The only custom change between
[42.21 vanilla](../../../42.21.0/decompiled/zombie/core/skinnedmodel/advancedanimation/AdvancedAnimator.java)
and the [old patch](../../../42.21.0/src/zombie/core/skinnedmodel/advancedanimation/AdvancedAnimator.java)
is an early empty-result return in searchFolders when Files.notExists(pathDir).
Mods may omit optional AnimSets/actiongroups directories. The guard skips the
walk and its missing-path diagnostic; it does not change XML parsing/animation
behavior or generally recover from loader failures.

[42.22 vanilla](../../decompiled/zombie/core/skinnedmodel/advancedanimation/AdvancedAnimator.java)
replaces PZArrayList.emptyList() in setState with Collections.emptyList() and
updates imports accordingly. searchFolders remains the same native traversal.
Keep the new Collections API and do not reintroduce the removed PZArrayList.

## Candidate and boundaries

Copy the target class and add the identical old guard after creation of the empty
files list, before Files.walkFileTree. Return early only when Files.notExists
reports absence; otherwise the complete native walker and its failure logging
remain unchanged. Existing XML extension checks, relative-path lowercasing,
caller sorting, reload behavior and animator state/event logic are untouched.

collectBaseGameFiles and loadModMedia call this search for animation/action-group
folders. The native visitFileFailed path logs and continues; do not describe the
patch as fixing a demonstrated crash or preventing every loading exception.
It suppresses avoidable missing-directory traversal/diagnostics for the known case.

## Verification and decision

The diff applies cleanly to exact target vanilla. The guard text matches the old
patch; removing it restores all target class text after newline normalization.
The target PZArrayList-to-Collections update is retained. Call sites and visitor
failure handling were source-reviewed. No new fixture was added for this small
reversible diagnostic guard; no full-JAR compilation, actual mod-loading/runtime
or filesystem race/symlink/permission behavior is claimed as tested.

The user approved this guard. Exact native baseline 2b55f41 was committed locally,
then the reviewed diff applied. Applied-candidate equality/source checks passed;
no new fixture, deployment or installed-file changes occurred.

Next is [review46: LuaManager](2026-10-28-merge-46-lua-manager.md), a separate
unapplied Lua cache/source-reconstruction and server API exposure candidate.
