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
<=0.35, or endurance <=0.24. Threats outside patrol territory also count. Visible
and remembered zombie sightings up to two seconds old contribute; remembered
distances remain last-seen facts. The contact list is bounded at 48.

Phases are `SAFE -> ESCAPING -> RECOVERING -> SAFE`, with `CORNERED` when no
escape starts. A cornered actor retries every two seconds and may defend against
a visible eligible territory target within 1.5 tiles. Defense is cancelled when
the target leaves that range; ordinary pursuit stays suppressed. Terminal or
failed defense executions also back off. Existing combat validation still applies.

Arrival is not safety. Failure or a 14-second route deadline stops movement and
returns to recovery. Release requires health >=0.55, endurance >=0.72, at least
eight seconds in survival, and five uninterrupted seconds of fresh, available,
non-truncated observations without a known zombie within six tiles. Stale or
unavailable sensing resets the hold. Partial coverage can establish sustained
observed clearance; it does not prove the area empty. No healing is invented:
injured actors can remain in recovery until another system restores their health.
Personality fear, ammunition doctrine, inferred attack intent and squad morale
are future policies; crowd/condition pressure does not manufacture a fear stat.

## Escape routes

The planner expands at most 256 nodes inside the configured retreat distance
(default 12 tiles), scoring clearance from the whole supplied crowd and penalizing
path length and crossing cost. It requires a destination at least two tiles farther
from the nearest threat and avoids the crowd contact envelope. Actors already
touching a threat can move outward. This bounded heuristic cannot guarantee escape.

Routes retain the `background` movement scope: building, rectangle and floor.
Leon cannot flee outside his assigned indoor territory in this version. RUN intent
is resolved through existing condition restrictions. Physical collision checks,
doors/windows/fences, reservations, landing checks and cancellation remain shared
movement capabilities. A failed traversal becomes a failed route. Dynamic threat
positions may change after planning; physical occupancy is checked during motion.

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
there is no per-tick logging. `tools/test_human_survival.py` exercises production
policy, preemption, hysteresis, sensing loss, blocked escape, bounded planning,
cornered defense and independent activation flags. The persistence fixture and
manager tests use disposable PostgreSQL with separate migration/engine roles.
Compile with `42.21.0/patchApocalipseBr.ps1 -DryRun` against the real game JAR.

Live validation remains necessary for crowd approach, crossing contention, indoor
escape, exhaustion, streaming and client animation. Keep protection enabled while
evaluating these paths.

Related: [combat](NPC-COMBAT.md), [movement](NPC-MOVEMENT.md),
[persistence](NPC-PERSISTENCE.md), [architecture](NPC-ENGINE-ARCHITECTURE.md).
