# Server human fight or flight

Implemented for server Build 42.21.0. No client patch or new packet contract.
This does not remove protection or complete zombie-to-NPC targeting/damage integration.

## Ownership and lifecycle

`humans/survival` owns immutable settings, pure threat interpretation, bounded
escape planning and per-actor `SurvivalModule` state. `ServerHumanSurvival` adapts
route planning to simulation-thread world reads. No SQL or actor-key policy occurs
in these capabilities. The existing combat port owns individual attacks; shared
movement owns physical integration, traversal and packet presentation.

Profiles opt into `survival`, with priority strictly before all other enabled
autonomous modules. The scheduler cancels engagement before survival starts moving.
Dialogue/trade retain exclusive ownership. Suspension cancels movement/defense,
retains the recovery latch and resets the safety hold. Activation creates fresh
transient state; phases/routes/monotonic timers are not persisted.

Default pressure is four known zombies within four tiles (Euclidean), health
<=0.35, or endurance <=0.24. Threats outside patrol territory also count. Local
crowd awareness detects eligible same-floor zombies inside its extended awareness radius
without line-of-sight rays, sharing the existing bounded discovery scan. Attack
visibility remains separate. Known hidden zombies refresh their positions;
retained contacts up to two seconds old use last-known positions but distances
are recomputed from the observer. The assessment list is bounded at 48.

Phases are `SAFE -> ESCAPING -> RECOVERING -> SAFE`, with `CORNERED` when no
escape starts or execution fails. Two accumulated failures enter a four-second
`CREATE_OPENING` window before another escape attempt; a completed defensive
execution ends that window early. Outside that window route retries are
throttled every two seconds. Escaping, cornered and recovering actors can defend against
a visible eligible territory target within 1.5 tiles. Defense is cancelled when
the target leaves that range; ordinary pursuit stays suppressed. Terminal or
failed defense executions also back off, capped at 300ms for close defense. During escape, defense runs alongside
movement and cannot cancel or replace its route. Existing combat validation still applies.

Arrival is not safety and enters recovery. Failed execution or the 14-second
deadline enters cornered defense immediately, rather than waiting passively.
No positional progress for 1.5 seconds also interrupts escape; active obstacle
traversal is excluded from that progress check. Release requires health >=0.55, endurance >=0.72, at least
eight seconds in survival, and five uninterrupted seconds of fresh, available,
non-truncated observations without a known zombie within six tiles. Stale or
unavailable sensing resets the hold. Partial coverage can establish sustained
observed clearance; it does not prove the area empty. No healing is invented:
injured actors can remain in recovery until another system restores their health.
Personality fear, ammunition doctrine, inferred attack intent and squad morale
are future policies; crowd/condition pressure does not manufacture a fear stat.

When reliable sensing shows no crowd/condition pressure and no threat within
1.75 tiles, opening creation and escape retries end immediately. A longer
controlled-contact hold (`max(safeHoldMs, minimumRetreatMs)`, default eight seconds)
also permits returning to normal engagement with peripheral contacts still
present, subject to recovered vitals and minimum survival duration. This prevents
post-combat retry loops when a few distant zombies remain. See [tactical
coordination](NPC-TACTICS.md) for urgent footwork, body-clear gaps and diagnostics.

## Escape routes

The planner expands at most 256 nodes inside the configured retreat distance
(default 12 tiles), scoring clearance from the whole supplied crowd and penalizing
path length and crossing cost. It requires a destination at least two tiles farther
from the nearest threat and avoids the crowd contact envelope. Actors already
touching a threat can move outward. This bounded heuristic cannot guarantee escape.
Destination scoring also rewards usable local exits and penalizes pockets with
one exit. Native occupied tiles are normally excluded during planning except for
the actor's origin. After two failures, elevated exposure permits body-clear
segments through occupied tiles with an additional route cost; it does not
permit crossing bodies or static obstacles. See [tactical coordination](NPC-TACTICS.md).
Recently failed directed edges are refused for three seconds
for body/timeout failures and ten seconds for other failures, in bounded per-actor
memory. Cleared edges become eligible again; no permanent obstacle is invented.

Movement allows separation from overlapping characters within the current tile
only when the step increases separation without crossing their body. It does not
permit entry into another occupied tile under normal exposure or ignore static collision. Occupied
origins skip the initial recentering waypoint; ordinary corner prediction and
traversal centering retain their existing checks.

Emergency routes use the loaded physical movement grid rather than the
`background` building/rectangle scope. An NPC, including Leon, can escape beyond
its designated area and continue escaping when already outside it. The same
unrestricted physical/occupancy grid is supplied to route execution, so crossing
the patrol boundary cannot invalidate an otherwise safe escape leg. Same-floor,
loaded-square and physical traversal restrictions remain mandatory. RUN intent
is resolved through existing condition restrictions. Physical collision checks,
doors/windows/fences, reservations, landing checks and cancellation remain shared
movement capabilities. A failed traversal becomes a failed route. Dynamic threat
positions may change after planning; physical occupancy is checked during motion.

After survival releases control according to its existing safety/vitals hold,
the background walking request detects whether the actor is outside its profile
scope. If outside, `ServerHumanWalking` requests a bounded physical route back
into that scope instead of attempting an in-area wander from an invalid origin.
Recovery can also correct displacement from other activities. Higher-priority
survival/defense or explicit task orders can preempt recovery normally. No actor
key, coordinate constant, new profile field or database migration is involved.
See [area recovery](NPC-MOVEMENT.md#returning-to-the-designated-area).

## Configuration and migration

Manager V25 adds revisioned `profile_survival` settings and `profile_revision`
columns `god_mode`, `ghost_mode`, `invisible`. Defaults preserve the current
guide and Leon flags: **true, false, false** respectively. Flags are applied from
profile configuration on activation, independently of role/key. The editor exposes
them and typed survival fields. Active actors retain their bound revision: publish
and reactivate (or restart) to apply changes. Shared-profile edits affect all users
of that profile on subsequent activation.

V25 publishes survival-enabled revisions for profiles with active `engage-zombies`,
including militia-patrol and Leon. It clones the current head above every existing
draft, retaining custom settings, movement points and pinned trade/speech revisions.
Instances, appearance, coordinates, runtime history and existing revisions/drafts
are preserved. Guides do not gain combat/survival. Occupied priority zero shifts
existing priorities up one while preserving their relative order; overflowing
priorities/module limits fail explicitly. Publication requires settings, background
scope and survival priority before other modules. Published settings are immutable.
The new table grants access to the engine role and preserves creator default grants.

Apply V25 before deploying this engine build; profile reads require its columns and
table. No live migration/deployment is performed by tests. Disable survival by
publishing a revision without that module, rather than rewriting published rows.

## Verification

DEBUG phase-transition logs include key, reason, crowd count, health and endurance;
failed movement records reason, source/target tiles, position, waypoint, route
length and target occupant count before cleanup. Reasons distinguish body
occupancy, collision segments, blocked/changed edges, scope, unloaded squares,
traversal failure and missing routes. Retreat logs include destination/length;
module-change logs show which policy preempted combat. Stall, timeout, no-route
and arrival are distinct events. There is no per-tick logging.
No additional migration is needed beyond V25; flags and territory are unchanged.
`tools/test_human_survival.py` exercises production
policy, preemption, hysteresis, sensing loss, blocked escape, bounded planning,
cornered defense and independent activation flags. The persistence fixture and
manager tests use disposable PostgreSQL with separate migration/engine roles.
Compile with `42.21.0/patchApocalipseBr.ps1 -DryRun` against the real game JAR.

Live validation remains necessary for crowd approach, crossing contention, indoor
escape, exhaustion, streaming and client animation. Keep protection enabled while
evaluating these paths.

Related: [combat](NPC-COMBAT.md), [movement](NPC-MOVEMENT.md),
[persistence](NPC-PERSISTENCE.md), [architecture](NPC-ENGINE-ARCHITECTURE.md).

Escape/reentry regressions: survival (107 assertions), walking (52), roaming
(571), traversal (93) and engagement (52) passed. The new cases verify actual
movement beyond a narrow patrol rectangle, renewed escape from outside, and
physical return into the area. Existing loaded-space and collision tests remain
in force; these tests do not claim a live dedicated-server escape demonstration.
