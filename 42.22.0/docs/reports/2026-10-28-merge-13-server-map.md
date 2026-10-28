# Review 13/48: ServerMap

Created 2026-10-28. Status: **approved/applied on 2026-10-28**. The target replacement
source matches the reviewed candidate. Reviews 01-09 and 12-13 are applied; 10-11 are retired.
See the [subsystem tracker](../features/2026-10-27-subsystem-port-tracker.md).

## Inputs and decision

- [Old vanilla](../../../42.21.0/decompiled/zombie/network/ServerMap.java).
- [Old patched source](../../../42.21.0/src/zombie/network/ServerMap.java).
- [Target vanilla](../../decompiled/zombie/network/ServerMap.java).
- [Candidate diff against target vanilla](2026-10-28-merge-13-server-map.diff).

Applied merge retains the old relevance lookup, stable completion-queue compaction,
local border lookup/deduplication, telemetry and custom shutdown integrations.
Adapt queue processing to the upstream void Load2 API and failed-cell cleanup.
Preserve the entire upstream QueuedSaveAll method and partial-cell Unload behavior.
Four textual conflicts require structural adaptation; accepting either whole side
would lose current behavior or restore obsolete signatures.

## Optimization and subsystem ownership

Primary owner: OPT-STREAM. Shared owners: NPC lifecycle, PostgreSQL persistence,
asynchronous zombie sending, event/backend bridge lifecycle and telemetry.

- Relevance membership: retain public ordered releventNow and its companion
  HashSet. Avoid repeated linear contains checks when adding/testing cells.
  Mirror all native removals and clears, including the new failed-load path.
  External code directly modifying the public list would bypass this invariant;
  this is an inherited contract, not automatic observation of every mutation.
- Completion queue: avoid shifting loaded2 on each completed cell and repeatedly
  scanning toLoad for each removal. Replace processed slots with null, compact
  survivors once in finally, then remove completed pending loads in one pass.
  Drain newly finished worker cells after each processed entry, as upstream does.
- Border recalculation: resolve the square directly through the owning cell's
  chunk array instead of routing through the global map. Recalculate corners
  once rather than twice: 252 perimeter visits per level instead of 256.
  Boundary setup, square marking and chunk integration retain their old scopes.
- Telemetry: retain pending-cell count, save/integration/update/unload phases,
  chunk vehicle-persistence/save-enqueue phases and integration counters.
  Telemetry records work; it is not itself a speedup.

The new native Load2 already removes the old method's queue search. Retire our
obsolete boolean Load2, load2At and queue-owning load2 helper. Keep native
public void Load2(), wrap recalc and vehicle loading in telemetry and leave
queue ownership in preupdate. No old return descriptor is retained.

## Upstream behavior preserved

1. Keep QueuedSaveAll **identical to target vanilla**: save work remains inside
   try/finally so paused clients receive StopPause even after save exceptions.
   Preserve the new HiddenAuthors ordering and quit save-finish wait.
2. Keep the per-cell integration exception handler. Failed cells attempt Unload,
   log secondary failures, clear their cell-map slot and leave loaded/relevant
   lists. Also clear our relevance index. Successful and failed processed cells
   both leave loaded2/toLoad, and later completions continue processing.
3. Keep Unload callable for partially integrated cells: do not restore the old
   isLoaded guard. This is needed by the new failed-integration cleanup path.
4. Retain native LOS suspend/resume; nest batch compaction cleanup so resume is
   still attempted when cleanup fails. Filter tombstones from debug queue output.

Stable compaction temporarily leaves null slots until the end of the batch.
They are skipped by the candidate loop and hidden in debug output. The target
Java call-site search found Load2 called only by this loop; native worker
getRecalc only drains completed cells into the supplied list. Runtime mods,
reentrant mutation and arbitrary queue corruption remain outside that evidence.

## Shutdown integrations retained

Stop/join NPC simulation before the final save. Drain final persistence work
through runShutdownDatabaseWork(Roles::save) before synchronous role saving.
Stop native pathfinding or PolygonalMap2 according to the selected backend.
Retain event/backend bridge shutdown, relational-sync shutdown, pool close and
zombie-packer shutdown before the relevant native resources disappear.

These hooks are staged dependencies, not proof the whole shutdown sequence is
validated on 42.22.0. NPC/support sources have been copied, while PostgreSQL,
vehicle database, pathfinding, zombie-packer and other owning integrations still
need their own reviews/adaptations. Preserve their shutdown ordering when porting.
This review does not create the deferred square-object collection adapter.

## Verification and remaining checks

The [queue fixture](../../../tools/test_server_map_port.py) reconstructs the
reviewed candidate in a temporary directory and verifies that the applied source
is identical. It does not change the replacement source.
It passed **799,768 assertions across 3,000 differential scenarios**, executing
extracted vanilla/candidate integration-queue blocks and the real compaction
method with explicit loader/cell/LOS/logging doubles. Scenarios include success,
integration failure, secondary unload failure, new completion waves, queue order,
cell/list cleanup and relevance-index consistency. It checks save-method equality,
the void descriptor, partial Unload and source ordering of shutdown hooks.

This does not execute complete Load2/RecalcAll2, save subsystems, JDBC operations,
shutdown workers, global/local square equivalence or native LOS. Target fixtures
for border ownership and full shutdown/save failure behavior, exact-JAR
compilation and live streaming/persistence validation remain required. No game
installation or database was changed.

The user approved: "apply the candidate". Exact vanilla baseline was committed
as `1340c3c` before applying the reviewed diff. Queue and source checks were rerun
and passed after application. Approval applies to this class only; review 14
IsoWorld remains unapproved.
