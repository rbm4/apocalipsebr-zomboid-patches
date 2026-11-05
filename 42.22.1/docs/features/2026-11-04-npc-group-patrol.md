# Shared NPC patrol and Umbrella R.P.D. Alpha

Target: **42.22.1 server**. Manager migrations **V42-V43** add group/medical
capabilities and seed one three-member patrol for **apoc-dev**. Sources and
migrations are prepared; no live migration, deployment, restart or game command
has been executed.

Crowded-combat pathfinding follow-up: [Java fallback rejected ServerZombiePath](../issues/2026-11-04-server-zombie-path-unsupported-mover.md).

Latest encounter investigation: [retreat oscillation, missing zombie group support,
terrain inputs and deployed dwell mismatch](../issues/2026-11-04-npc-patrol-combat-runaway.md).

Combat support follow-up: [A-life squad threat reports, last-known approaches and
bounded assistance combat](2026-11-04-npc-squad-combat-assistance.md) are implemented
in source. Group members with zombie engagement now help nearby threatened allies
when their own higher-priority survival/healing/retaliation permits it. Deployment
and dedicated-server validation remain pending.

2026-11-05: [separated squadmate distress](../issues/2026-11-05-squad-distress-outside-assistance-radius.md)
extends aid to fleeing members up to 256 tiles away, using validated awareness
reports and fixed emergency bounds. Short pressure gaps now preserve retreat
until one full second of relief. Dedicated-server validation remains pending.

## Authored group

| Slot | Logical key | Name | Outfit | Initial position |
| --- | --- | --- | --- | --- |
| 0 | umbrella-hunk | HUNK | Police | 9857,2419,0 |
| 1 | umbrella-vector | Vector | Police | 9857,2419,0 |
| 2 | umbrella-spectre | Spectre | Police | 9857,2419,0 |

The shared group is `umbrella-rpd-alpha`; the shared immutable profile is
`umbrella-rpd-patrol` revision 1. Effective health capacity is **2x**. Dialogue,
trading, god mode, ghost mode and invisibility are disabled. Player aggression
is reactive: no player encounter/hostility module is enabled. Zombie engagement
continues through existing combat capabilities.

The route is exactly the supplied ordered circuit, with Z=0 throughout:

```text
9868,2419 -> 9888,2409 -> 9886,2450 -> 9850,2449
 -> 9794,2463 -> 9818,2466 -> 9846,2442 -> 9851,2401
 -> 9868,2419
```

Assigned bounds are X **9788-9894**, Y **2395-2472**: route extents plus six
tiles for local positioning/formation. This is a stable outdoor domain, not a
home-building test. Terrain, loaded squares, physical collision and traversal
remain authoritative; these authored bounds do not assert that every tile is
walkable or pin unloaded chunks.

## Shared intent and individual execution

2026-11-05 correction: [shared cohesion/re-entry and moving passive recovery](2026-11-05-squad-cohesion-healing-and-state-speech.md)
now address [independent area returns while a member heals](../issues/2026-11-05-patrol-separated-area-return-and-healing.md).
The shared waypoint stays authoritative while the roster returns/regroups.

`groups/GroupPatrolCoordinator` owns one transient `Intent.PATROL`, waypoint cursor
and moving/regrouping/stalled/dwelling phase for each group. It consumes immutable member
positions and participation facts. It does not hold native actors or send packets.
`GroupPatrolModule` owns each actor's cancellable `HumanActionExecution`; the
existing navigation, physical movement and transport execute the move.

Slot zero follows the authored point; other slots receive alternating two-tile
lateral offsets relative to the route leg. All members start at the requested
spawn tile; initial movement requests are staggered by 500 ms per slot to reduce
contention. This is a waypoint/spacing heuristic, not a rigid collision-free
formation. Existing body separation and collision logic still decides movement.

Once all live roster members are within the arrival tolerance of their slots or
the authored center, the group enters `DWELLING`. It draws one shared random
duration from **15,000 through 45,000 milliseconds, inclusive**, once per visit.
The current waypoint remains selected and patrol modules cancel owned movement
for the entire pause, including minor corrective walking. Only after the pause
does the shared cursor advance to the next point (including point eight to one).

Combat/healing/exclusive activity can still preempt patrol. The remaining pause
does not decrease while any roster member is not participating, or while world
observations are unavailable/stale. If the group stays at the point, it resumes
the remaining time when patrol resumes. Displacement requires regrouping and a
fresh full pause. The monotonic countdown is transient; it is not persisted.

A member in combat/healing does not get silently dropped from that arrival
barrier. Arriving members wait for the others. After 20 seconds of regrouping,
the phase becomes `STALLED` with diagnostics; the group retains the waypoint
rather than skipping authored coverage. Dead/removed members leave the roster.
An entirely interrupted group retains progress. Returning members now use a
shared group re-entry rally instead of separate background/home return anchors.
A soft companion-centroid route cost and travelling separation threshold prefer
cohesion; see the linked implementation for limits and interruption behavior.

Failure retries are per actor, throttled to two seconds. After two failed moves,
that actor can try the authored center instead of its lateral offset. Completing
a partial leg does not advance the group cursor. The route cursor and native
execution handles reset on process restart; membership/configuration is durable.

Profile order is survival 10, retaliation 11, healing 20, zombie engagement 100,
group patrol 1000. `group-patrol` replaces `walk` scheduling and requires a bounded
background `waypoint-circuit`. There are no actor-key/name checks in policy.

## Bounded long legs and territory

The leg `(9850,2449) -> (9794,2463)` has Manhattan distance 70, exceeding the
existing 64-step full-route limit. `MovementRequest.To.partialLeg` explicitly
opts this policy into `DestinationRoutePlanner.planLeg`: the same bounded search
returns a route making progress toward the objective when a complete route does
not fit. Searches remain limited to 256 expanded nodes and 64 edges. The group
checks detached actual positions for arrival and requests further legs as needed.
Ordinary complete-destination requests retain their prior all-or-failure contract.

For circuits with explicit bounds, those bounds now define stable territory.
They are no longer additionally clipped to a moving 64-tile window around the
actor. Unbounded legacy circuits retain their prior local scope; route-search
budgets remain separate. Streaming still limits physical execution. A difficult
detour can exhaust the bounded search and leave the group waiting.

## One accepted hit alerts the group

After existing attacker ownership, life, PvP/safety/faction, range, line-of-attack
and replay validation, `ServerHumanFactory.authenticatedAggression` serializes
assignment of the victim's existing retaliation module and same-group allies.
Each ally receives the authenticated player online ID **and character instance
identity**. The group fanout does not assign the victim twice, target other
groups, or include dead actors.

The existing retaliation adapter independently revalidates visibility, territory,
perception radius, player incarnation and PvP before every attack. Alerting allies
does not authorize shooting through walls, across protected zones or at a recycled
identity. This profile remembers retaliation for up to 120 seconds, with a
five-second visual-loss grace; invalid targets clear normally. A native
PlayerHitSquare miss is still not an authenticated NPC hit.

## Double health before native clamping

`profile_revision.health_multiplier` is revisioned, defaults to 1 and permits
1-10. Logical health remains a 0-1 fraction. `BodyPart.ReduceHealth` calls the
NPC adapter **before** native subtraction/clamping, dividing the native loss by
capacity for the exact registered server NPC. Other players, animals, clients
and absent/mismatched presentation identities keep the original amount. The
reserved-ID check occurs before a factory lookup, preserving ordinary body
updates before NPC bootstrap.

For 2x capacity, a baseline 20-point hit costs ten normalized native points.
With five points remaining, another such hit reaches zero. Scaling an already
clamped after-hit delta would incorrectly make near-death health approach zero
without reaching it; this implementation deliberately avoids that failure.
Native armor/wound rolls remain native and run once. Existing native-loss import
does not divide them again. Logical bleeding and fixed baseline medical recovery
are also divided by capacity. Survival thresholds remain health percentages.
Instant native death mechanics such as drag-down remain separate from HP capacity.

Exact vanilla baseline: `42.22.1/decompiled/zombie/characters/BodyDamage/BodyPart.java`.
The repository baseline tool copied/hash-verified it and created baseline-only
commit `09a01a8`. The behavioral override adds one adapter call to `ReduceHealth`;
no decompiled source or game JAR was modified.

## Ownership and persistence

Factory-managed `ServerHumanGroups` composes the policy, validates unique live
slots and matching routes, and binds fresh per-actor modules. The registered
server thread captures positions, floor, native territory and activity facts
under the factory monitor. The NPC worker consumes those detached snapshots,
rejecting samples older than 500 ms or from another incarnation. Missing roster
samples stop shared progression. Removed actors cannot actuate through old ports.
Aggression mutation also holds the factory monitor, serializing it with AI ticks.

There is one controller per group, at most eight slots per group and at most the
factory's 64 active actors. No per-NPC thread, blocking worker rendezvous or
unbounded command queue is added. Existing native movement/perception and other
worker threading debt remains; this does not claim full snapshot isolation.

V42 creates `patrol_group` / `patrol_group_member`, grants the engine role access,
registers healing/group modules and validates publication ordering/scope. Durable
membership is server/logical-key configuration, separate from reusable profiles
and native IDs. The SQL worker loads immutable membership into the actor
definition; it never creates live actors. Membership edits take effect at restart.
V43 seeds server-spawn defaults only; actual-world materialization retains the
existing conflict-do-nothing behavior and does not overwrite existing instances
or runtime checkpoints. No new physical-state serialization version is introduced.

The manager allows and validates health capacity on profile publication; its
schema-driven editor labels the field. Group membership authoring currently uses
the migration/admin configuration, not a new editor or public endpoint.

## R.P.D. textures

The supplied `Resident Evil 3 - Raccoon City Police Uniform` folder replaces
vanilla clothing textures and defines no custom named outfit. `Police` selects
the vanilla uniform covered by these assets. `servertest.ini` already enables
`RE_UNIFORMS`; no INI change is made. The folder's root mod.info used that ID but
42/mod.info used its display name as the ID. Target `ChooseGameInfo.readModInfoAux`
reads version-specific metadata, so 42/mod.info is corrected to `id=RE_UNIFORMS`.
Assets, workshop publication and clients still need to match for in-game visuals.

## Verification and rollout

Production fixtures: `py -3 tools/test_human_groups.py` covers shared barriers,
stalls, loop order, formations, staggered start, segmented long paths, blocked
paths, stale/retired ports, native adapter patrol, group-scoped retaliation,
dead allies, suspension, area return and health capacity. Native HP checks extract
the actual patched `BodyPart.ReduceHealth` and adapter method, including real-player,
client and mismatched-identity fallback. Healing regressions remain separate.
The manager's `NpcGroupPatrolMigrationTest` applies V42-V43 in a disposable
PostgreSQL database with distinct creator/engine roles, checks route order,
server scope, profile immutability, constraints, grants and repeat Flyway behavior.

Waypoint-pause verification on 2026-11-04: the updated group/native-damage fixture
passes **141 assertions**, including both 15/45-second boundaries, one draw per
visit, shared movement hold, interruptions, stale observations, displacement,
looping and resumed movement. Earlier group/HP verification passed 155 assertions
before dwell timing changed the simulated patrol schedule. Healing
fixtures **70 assertions**; actual compiled profile registration **12 assertions**;
manager migration **4 tests**, service **15 tests**, editor JavaScript **7 tests**.
The updated exact-JAR `-BuildOnly` compilation passed with **327 sources / 946 classes**,
retained under `42.22.1/artifacts/build_20261104_231736/`. No game binaries were deployed.

Apply V42-V43 through the manager's game-runtime Flyway process **before** running
this engine: profile and membership queries require the new schema. Deploy the
matching 42.22.1 patch build, retain `apocbr.npc.serverKey=apoc-dev`, install the
corrected texture mod and restart the NPC runtime. Profile publication alone does
not rebind existing live actors. No live step was performed during implementation.

Dedicated-server validation remains necessary for shared-spawn separation,
all eight waypoints, the 70-step leg, actual terrain/streaming, three-way combat,
retaliation after a single hit, medical animation, deaths, regrouping, HP capacity
and the mod's clothing appearance in both NPC startup modes.

Related: [healing](2026-11-04-npc-healing.md),
[architecture](../../../42.21.0/docs/features/2026-10-20-npc-engine-architecture.md),
[movement](../../../42.21.0/docs/features/2026-10-21-npc-movement.md),
[retaliation](../../../42.21.0/docs/features/2026-10-25-npc-player-retaliation.md).

2026-11-05 follow-up: [wounded recovery gait and local squad roaming](2026-11-05-squad-recovery-roaming.md) replace whole-squad waiting during convalescence with short local walks around a retained patient anchor. Actual dressing remains stationary, and the shared patrol waypoint is preserved.
