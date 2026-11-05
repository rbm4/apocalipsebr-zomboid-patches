# Moving recovery, squad cohesion and field-state speech

Implemented in source 2026-11-05 for **42.22.1 server**. Source tests and build
verification are recorded below; deployment and gameplay acceptance remain open.
This addresses the [Vector/resting and separated return-route diagnosis](../issues/2026-11-05-patrol-separated-area-return-and-healing.md).

Owning contracts: [group patrol](2026-11-04-npc-group-patrol.md),
[medicine](2026-11-04-npc-healing.md),
[async authority](2026-11-05-npc-async-authority.md), and the inherited
[reactive speech engine](../../../42.21.0/docs/features/2026-10-26-npc-speech-engine.md).

## Only dressing owns the medical movement pause

`HealingModule` is eligible for an active dressing execution or a wound with an
available clean supply. Low health/passive recovery alone cannot select it.
`ServerHumanHealing.begin()` rejects passive-only requests. Dressing retains its
stationary position/floor checks, cancelable lease, real item identity and
exactly-once consumption/commit. Existing dressing and body-part packets remain.

Passive recovery now runs in the server medical phase without taking a movement
lease or starting an animation. It uses real elapsed seconds capped at 150 ms
per service, so a stall cannot grant a backlog of healing. Health gain is
`recoveryPerSecond * elapsed / healthMultiplier`; logical limb recovery uses
`recoveryPerSecond * elapsed`. Native body-part deficits shrink proportionally
to the accepted logical gain, preserving wound timers/infection. An unwounded,
safe, living mobile actor can recover while walking. Nearby remembered zombies,
stale/unavailable awareness, fresh damage/combat, retaliation and existing
exclusive activity restrictions still prevent recovery. Combat/survival can
still stop or redirect an actor for their own reasons.

## Shared re-entry and regrouping

`GroupPatrolCoordinator` still owns the only waypoint cursor. New pure
`GroupCohesionCoordinator` owns an additional transient shared mode, rally and
revision:

| Mode | Patrol behavior |
| --- | --- |
| PATROL | Follow the shared waypoint with formation offsets and companion-aware route cost. |
| HOLD | A roster member is interrupted, for example actively bandaging. Other patrol executions stop; emergency/healing/combat modules retain their own higher-priority authority. |
| RETURN | At least one member is outside the assigned area. Everyone receives one shared, validated re-entry rally instead of individual home/boundary fallbacks. |
| REGROUP | The travelling roster has spread too far. Reunite at a shared rally before resuming the same waypoint. |

`ServerHumanGroups` captures member facts on the server and validates the shared
rally in the assigned scope. It uses a bounded centroid/clamp search of at most
49 static candidate queries per group when a cached rally is unavailable.
Unloaded/blocked rally candidates pause the shared intent; they do not send each
member toward a separate home fallback. A valid rally is retained rather than
chasing a new centroid every tick.

Formation offsets apply around the rally and clamp to the authored rectangle;
this keeps distinct boundary slots where possible. Blocked offset destinations
can use the shared center. Recovery is not released merely because one member
enters the rectangle. All members must be inside and near the shared rally.
The three-member patrol regroups above 12 tiles of separation and settles within
four tiles of the rally. Thresholds expand for larger authored slot footprints
(up to eight members), preventing a valid wide formation from triggering itself.

A cohesion revision change cancels each old move before starting a new one.
Dwell time pauses for interruption; spatial displacement resets the remaining
dwell interval without advancing the waypoint. Existing all-roster arrival and
15-45 second shared dwell remain. Repeated first/closing circuit points now use
the previous **distinct** point to compute heading, so initial formation offsets
do not collapse to zero.

## Navigation preference and authority

`MovementRequest.To` adds optional `returnToArea` and at most seven immutable
same-floor companion points. Existing constructors retain ordinary semantics.
Explicit re-entry requires a destination that is actually inside the assigned
scope. Only an outside origin uses the existing physical recovery grid; this
does not authorize arbitrary out-of-area destinations. An actor just outside
the boundary must enter it before the arrival tolerance can stop the move.

`CohesionGrid` delegates geometry and adds a soft cost around the other members'
centroid: no added cost within six tiles, then one cost unit per two tiles away,
capped at eight per edge. It does not declare tiles blocked or bypass collision,
occupancy, scope, traversal or search limits. Thus members prefer nearby routes
while keeping the real goal and valid detours. This is a cohesion preference,
not a rigid or collision-free formation guarantee.

The live cost wrapper is used only during server capture/synchronous navigation.
The async planner continues receiving a deeply copied `NpcDecisionPlanner.Grid`,
never the live wrapper or actors. The existing four captures/tick, 256 captured
cells, 1024 edges, 256 A* expansions and 64-edge legs remain bounded. Cancellation,
stale tickets, incarnation replacement and server-owned physical execution retain
the [authority split](2026-11-05-npc-async-authority.md).

## Immersive state-change callouts

The existing reactive speech engine gains three startup-catalog pools. There are
**ten distinct phrases per pool in both EN and PTBR**: 30 variants, 60 localized
rows. Examples:

| Pool / stable label family | Trigger | Example |
| --- | --- | --- |
| HEALING / `field_dressing` | Actual native dressing start | “Me cobre. Preciso estancar isso.” |
| DESPERATION / `under_siege` | Entering escape, cornered or opening-creation states | “Estão fechando o cerco. Cobre minha retirada!” |
| ALLY_ASSIST / `stand_together` | Assistance approach/engagement transition | “Aguenta! Estou indo até você!” |

Passive recovery ticks never trigger the healing callout. Existing native
adapters report transitions; speech does not choose state or own movement/combat
timing. State chatter uses the existing one-pending-plan delivery and three-second
expiry, safe chat delivery and delivered-message repeat memory.

The new pools share a **15-second internal per-actor gap**, independent of
priority, plus per-event defaults: healing 60 seconds, desperation 45 seconds,
assistance 45 seconds. Even a zero database cooldown cannot lower the per-event
floor below 30 seconds. A selection/chance attempt consumes cooldown even without
a viewer; transitions are not queued for later replay. Canceling speech preserves
these state cooldowns. A fresh incarnation receives a fresh engine; timers are
process-local and not persisted. Existing urgent attack/damage speech keeps its
separate priority/behavior.

## Migration and rollout

Manager migration
[V44__Npc_Field_State_Speech.sql](../../../../apocalipsebr-manager/manager/src/main/resources/db/game-runtime-migration/V44__Npc_Field_State_Speech.sql)
extends the closed event constraint and adds the three rules/60 localized lines.
Existing rule/content rows are preserved. It explicitly retains schema usage and
table privileges for `pz_server_01`; no new tables or sequences are created.
Standalone defaults mirror the database text and rules exactly.

Stop the old NPC runtime before applying V44, then start the matching engine
build. An older engine cannot parse the new enum vocabulary. A new engine with
an older catalog simply has no new state pools; movement/healing do not depend
on whether speech content exists. Speech content remains startup-loaded.
This work prepares the migration; it does not run it on the live database.

## Verification and remaining acceptance

- `test_human_healing.py`: moving passive recovery, no passive module lease,
  healing gossip only on actual dressing starts, danger checks and existing
  transaction/supply/native packet/cancellation cases.
- `test_human_groups.py`: shared re-entry/cohesion/HOLD, stable revisions,
  staggered spawn, duplicate closing point offsets, boundary slots, outside
  arrival guard, dwell reset, unloaded rally and soft path costs alongside the
  existing group/combat/capacity cases.
- `test_npc_async_authority.py`: same async/sync protocol plus actual production
  walking/movement re-entry, ordinary scope rejection, immutable companion
  navigation, cancellation and no individual circuit advancement.
- `test_npc_state_speech.py --manager-test`: production 42.22.1 speech policy,
  defaults and JDBC hydration, hard/shared cooldowns, cancellation/flapping,
  locale/variant parity; V31-to-V44 on disposable loopback PostgreSQL with distinct
  `apocalipsebr` and `pz_server_01` roles. The optional manager test executes and
  validates the actual Flyway transition and repeat migration no-op.
- Existing movement replication and squad assistance fixtures remain relevant.
  Final results:

| Check | Result |
| --- | --- |
| Healing fixture | 74 assertions passed. |
| Group patrol/cohesion fixture | 340 assertions passed. |
| Squad assistance fixture | 143 assertions passed. |
| Async authority fixture | 202 checks default async, 192 sync, 202 explicit async. |
| Movement replication fixture | 146 checks passed. |
| State speech/JDBC/SQL fixture | 378 assertions passed against isolated PostgreSQL 17.6. |
| Manager `NpcFieldStateSpeechMigrationTest` | 2 tests passed, none skipped; actual Flyway V31-to-V44, validation and repeat no-op. |
| Exact-JAR `patchApocalipseBr.ps1 -BuildOnly` | 336 sources / 978 class files, Java 25.0.4; final retained output `42.22.1/artifacts/build_20261105_094527/`. |

The Flyway fixture bootstraps only the `scope` table dependency, then runs the
actual speech migrations; it does not substitute for the manager's full historic
game-runtime migration suite. All checks above are source/isolated-build checks.

Engine/world/packet collaborators in isolated fixtures are controlled doubles;
the catalog SQL tests use real PostgreSQL. No fixture establishes measured
server savings or a collision-free formation. Before gameplay acceptance, replay
the original three-member outside start with Vector bandaging and then recovering
while moving; test streaming loss, obstacles, close quarters, wide separation,
combat/retreat preemption, deaths/rejoins, delayed plans and speech chatter over
several state changes. Verify the shared waypoint never skips and health/supplies
are applied only once. No deployment, live schema change or game command is part
of this implementation verification.

## Close-spawn encounter follow-up

The [eight-zombie investigation](../issues/2026-11-05-eight-zombie-contact-and-bandage-presentation.md) records the 10:40 encounter, adds minimum opening commitment, fixes unsafe origin recentering and medical action-bit continuity, and makes a fallback wrap available without inventory supplies. Source and fixture validation passed; a gameplay replay remains required.
