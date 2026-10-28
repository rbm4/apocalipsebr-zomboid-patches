# Review 27/48: ZombieCountOptimiser

Created 2026-10-28. Status: **approved/applied on 2026-10-28**. Review 26 is applied.
See the [tracker](../features/2026-10-27-subsystem-port-tracker.md).

## Inputs and proposal

- [Old vanilla](../../../42.21.0/decompiled/zombie/popman/ZombieCountOptimiser.java).
- [Old patch](../../../42.21.0/src/zombie/popman/ZombieCountOptimiser.java).
- [Target vanilla](../../decompiled/zombie/popman/ZombieCountOptimiser.java).
- [Candidate diff](2026-10-28-merge-27-zombie-count-optimiser.diff).

Keep the two existing deletion-budget early exits on target vanilla. Three-way
merge is clean. Applied source matches the reviewed candidate after exact vanilla baseline `a1ce877`. Primary owner: OPT-ZOMBIE;
shared owners: zombie packet lists, multiplayer relevance and deletion lifecycle.

## Optimization and unchanged selection

After calculating max(0, send-list size minus configured deletion threshold),
skip the connection if the deletion budget is zero. Stop its zombie-ID traversal
once enough eligible zombies have been selected. This avoids remaining map
lookups and loop work; eligible selection/order is unchanged for stable input.

Keep the native random eligibility, reanimated-player exclusion, absent-target
requirement, outside/roof rules, relevance protection, counter increments and
actual delete/remove ordering. Native random calls already short-circuit behind
a positive budget, so skipping a depleted tail does not change that RNG call
sequence. This does not disable culling or change the configured threshold.
It assumes ordinary map reads and stable ownership; concurrent list mutation
is not made safe by these early exits.

## Upstream retained

Old native code checked only players attached to the current zombie connection.
42.22.0 uses IsoPlayer.anyPlayerMP to test local/connected multiplayer players,
protecting a zombie if any player falls within the native relevance radius.
The predicate still derives radius from the zombie's connection as upstream does.
Keep both canBeDeletedUnnoticed and isZombieInsidePlayerRelevantRange exactly
native. The applied IsoPlayer port supplies the new multiplayer helper.

## Verification and remaining checks

Source assertions passed: zero conflicts; complete candidate equals target
vanilla plus exactly two early-exit statements; native relevance predicates and
deleteZombies are unchanged. No Java fixture was added for these two simple
budget checks. Check multiplayer visibility/relevance, culling order and ownership,
packet send-list lifetime and native deletion behavior during integration.
NetworkZombiePacker and other zombie subsystem ports remain pending. Exact-JAR
compilation and native runtime validation are not claimed. No installation or
database was changed.

User approved: "yes". Applied-candidate equality/source checks passed; native
runtime checks remain pending. No installed files were changed.
