# Review 24/48: IsoPlayer

Created 2026-10-28. Status: **approved/applied on 2026-10-28**. Review 23 IsoMannequin is
applied. See the [tracker](../features/2026-10-27-subsystem-port-tracker.md).

## Inputs and proposal

- [Old vanilla](../../../42.21.0/decompiled/zombie/characters/IsoPlayer.java).
- [Old patch](../../../42.21.0/src/zombie/characters/IsoPlayer.java).
- [Target vanilla](../../decompiled/zombie/characters/IsoPlayer.java).
- [Candidate diff](2026-10-28-merge-24-iso-player.diff).

Keep mechanics expiry gating, server spatial zombie-authority LOS, selective
vehicle proximity work, backend death notification and telemetry. The text merge
is clean, but adapt the server LOS helper so it does not erase the new scheduler's
chasing-zombie statistics. Applied source matches the reviewed candidate after exact vanilla baseline `89ce60b`.
Primary owner: INTEGRATION; shared owners: OPT-PLAYER, zombie authority/spatial
queries, mechanics state, remote-player movement, backend bridge and telemetry.

## Existing optimizations and integrations

Mechanics work tracks the next expiry and last observed calendar time, skipping
map traversal while no entry can expire. Scan/remove entries in place when due;
invalidate at native load/insertion hooks and rescan after clock rollback.
Keep strict expiration after 24 hours, not at the exact deadline. This relies on
all extra writers (including later relational reconstruction) invalidating or
rebuilding the deadline when they mutate mechanicsItem. It is not an observable
map wrapper and does not protect against arbitrary reflection/mod mutation.

Server updateLOS uses a dedicated authority path rather than the full client
render/audio-oriented pass. Query the cell's zombie spatial index within
max(20,view distance), retain current-floor/range/liveness and special-zombie
filters, and perform server LOS before spotting or lead-aggro ZombieControl
handoff. Preserve current target/lead-aggro/canSpotted checks and closest-zombie
tracking. Client updateLOS retains the native implementation. This is the old
server-specific behavior contract, not a claim of equivalence for every client
visibility/audio statistic.

Skip selected server near-vehicle checks unless sneaking; retain client behavior
and the existing surrounding branch conditions. Retain the backend onDeath hook.
Timing additions attribute player internal/base work, movement, remote handling,
LOS, sounds, nutrition, fitness and other stages. Remote square work still
executes detach, position update, ensureOnTile and membership reconstruction;
unchanged-position/same-square counters do not skip those mutations.

## Required adaptation: native chasing counts

Review 16 already preserves 42.22.0's scheduler reset/count pass. Native player
updateLOS now updates last-number/reset bookkeeping only off-server. Copying our
old server helper would still unconditionally reset numChasingZombies to zero.
Remove both old last-number/update and reset statements from that helper; leave
scheduler-owned server counts untouched. The native client-only reset remains.
This reconciles an integration issue that a clean text merge does not detect.

## Upstream retained

Keep new ladder/rope ClimbState/ClimbDownState/ClimbSystem and ForceOpenState
registration/context/actions, remote climb prediction/movement and muscle strain,
climbing-death handling, portal forcing, body-damage packet API, clothing/visual
sync, multiplayer lookup helpers, animal Set/snapshot lure traversal, and target
combat/trait behavior changes. Keep List-based hit targets and removal of old
LastStand/timed-action logic. The candidate layers our hooks onto target vanilla;
it does not restore removed methods/classes.

## Verification and remaining checks

The [candidate fixture](../../../tools/test_iso_player_port.py) reconstructs the
candidate without creating an override. It passed **5,008 assertions** using
extracted mechanics expiry and server LOS bookkeeping with clock/state doubles.
Checks cover strict expiry, additions/invalidation, clock rollback and preserving
scheduler-owned chasing/last-number state while clearing other LOS scratch state.
It does not execute full spatial LOS, real zombie authority/network sends,
movement, backend notification or native player lifecycle.

The spatial/index helpers and IsoCell are staged/applied dependencies. Later
NetworkZombieManager/packet/GameServer/IsoGameCharacter/backend integrations and
relational mechanics-map writers require their own adaptation/validation. Verify
real target LOS selection/order, owner handoffs, vehicle proximity consumers,
remote square callbacks, death notification and climbing behavior. Exact-JAR
compilation and multiplayer/native runtime checks remain pending. No installation
or database changes were made.

User approved: "yes". Applied-candidate equality and 5,008 extracted expiry/
bookkeeping assertions passed again; full integration remains pending.
No installed files were changed.
