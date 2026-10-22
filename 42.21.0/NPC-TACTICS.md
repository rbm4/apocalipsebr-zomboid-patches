# Tactical combat and escape coordination

Server Build 42.21.0. This extends the existing NPC engine; no client patch,
database migration or change to profile protection flags is required. V25
remains the prerequisite for persisted survival settings and protection flags.

## Ownership

`brain/TacticalIntent` is immutable, transient decision output: objective
(`ENGAGE`, `ESCAPE`, `CREATE_OPENING`, `RECOVER`), posture (`RELAXED`, `GUARDED`,
`FLEEING`), target identity and permission to accept elevated crowd exposure.
It does not contain native entities, routes, timers, SQL or damage rules.
Engagement/survival policy supplies it through `HumanActuator.tactical`.
The weapon adapter refines engagement posture as approach/attack phases change.

`ServerHumanActuator` resolves the target against current visual perception.
`ServerHumanMovement` owns actual facing, pace, collision and traversal;
`HumanWeaponCombat` owns attack timing and one-shot commitments. Transport
serializes the resulting native state. Intent never overrides physical injury,
territory, native hit validation or obstacle traversal constraints.

Each actor retains its own intent and executions. Module interruption,
suspension, shutdown and return to safe state cancel owned work and clear
intent. Nothing is persisted; profile activation starts fresh.

## Guarded movement

A guarded actor faces its selected visible enemy while following its route.
Guarded pace is WALK, capped at 1.3 tiles/second; existing slower physical
restrictions still apply. Native heartbeat facing and travel directions are
serialized separately, including travel-based prediction. Releasing guard
restores ordinary pace resolution and travel-facing behavior.

Weapon windup/recovery can continue during guarded movement, subject to range,
clear attack lane, inventory, ammunition and existing commit validation. Active
obstacle traversal suppresses aim and attack commitment. Guarded equip/reload may
perform defensive footwork while their deadlines remain unchanged. During
survival, a combat request explicitly
marks movement as externally owned: weapon operations may face/attack but cannot
replace or stop the escape route, including during cancellation and reload.
Survival continues polling both escape progress and nearby defensive combat.

## Opening creation and elevated exposure

The escape objective survives a failed route. The first failure enters cornered
defense with the existing retry interval. Two accumulated failures enter
`CREATE_OPENING` for four seconds: path retries pause while eligible visible
enemies within 1.5 tiles can be fought with local combat footwork. The last failed
edge biases target choice toward a nearby blocker. Completing the defensive
execution ends the window early. At its end the actor retries escape; a new
failure starts another opening window. Successful arrival resets the failure
count and enters ordinary recovery/safety hysteresis.

Immediate pressure within 1.75 tiles bypasses ordinary reaction latency and
allows bounded retreat attempts every 500ms. The selected melee opponent counts
only inside 55% of weapon reach; additional attackers count at the full range.
This preserves melee approach while reacting promptly to flanking threats.
Guarded footwork scores the obstacle-independent awareness frame, may use a
body-clear gap in an occupied tile, and can briefly close on the selected target
if whole-crowd placement improves. Dense bodies and static geometry still block it.
Nearby combat/defense retries have a 300ms minimum instead of the ordinary
two-second retry delay. Ordinary spacing and retreat cooldowns remain in force
outside immediate pressure. The simulation heartbeat remains 100ms.

Reliable loss of crowd/condition pressure with no threat inside 1.75 tiles ends
opening creation and escape retries immediately, resets failure escalation and
enters recovery. Peripheral contacts do not themselves restart flight. Full
safe-distance clearance retains the configured safety hold. Controlled remaining
contacts require `max(safeHoldMs, minimumRetreatMs)` of reliable pressure relief
before regular engagement can resume, with the same recovered-vitals thresholds.
Unavailable/truncated sensing cannot establish this relief.

After two failures, planning and movement may accept a zombie-occupied tile when
a continuous segment has body clearance. Occupied destinations add four units
of route cost. The crowd envelope relaxes from 1.25 to 0.65 tiles, bounded by the
actor's existing overlap; a character-body test uses a 0.65-tile center envelope
and permits outward separation from existing overlap. Dead/zero-health character
bodies do not count as live blockers. Each check visits at most
16 squares and 64 unique moving bodies. Unknown mover types fail conservatively.
Static collision, route scope, failed-edge memory, traversal reservations and
landing checks remain mandatory. Existing 256-node planning limits remain.

This is a conservative gap heuristic, not a shove, collision prediction or a
guarantee of survival. A packed crowd or closed room can still prevent escape.
Protection remains profile-controlled. These tactics do not finish incoming
zombie targeting/damage integration or simulate a player pushing through bodies.

## Diagnostics and validation

DEBUG tactical logs report objective, posture, target and exposure when intent
changes. Existing movement failure logs retain edge, position, waypoint,
occupancy and reason. Escape logs include elevated exposure; phase logs identify
the opening window and its end. Stalls account for resolved movement speed.
The route adapter reports `retreat unavailable` with total threats, nearest
distance and exposure; only the policy emits opening/escape phases. Phase `nearby`
means the count inside `crowdRadius`, avoiding conflicting failure/count logs.

`tools/test_human_tactics.py` exercises production facing/travel replication,
guarded speed, mobile external-route combat, cancellation ownership and sparse
versus blocked crowd routes. Survival and weapon fixtures cover opening windows,
retry timing, preemption, target switching and attack commitments. Compile with
`42.21.0/patchApocalipseBr.ps1 -DryRun` against the target game JAR.

Live dedicated-server validation remains necessary for backward/sideways aim
animation, moving crowds, attacks during flight, failed-edge recovery, crossing
contention and dense enclosures. No deployment is performed by fixtures.
