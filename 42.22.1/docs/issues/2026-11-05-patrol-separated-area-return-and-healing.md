# Patrol separates during individual area return while Vector heals

Date: 2026-11-05. Target: **42.22.1 server**, async-planning authority build.
Status: **source fix implemented; gameplay verification pending**. The
[cohesion/healing implementation](../features/2026-11-05-squad-cohesion-healing-and-state-speech.md)
adds shared re-entry/regroup goals and passive recovery while moving. The evidence
below describes the earlier run; no deployment was performed by this change.

Owning features: [shared group patrol](../features/2026-11-04-npc-group-patrol.md),
[healing](../features/2026-11-04-npc-healing.md),
[server authority and detached planning](../features/2026-11-05-npc-async-authority.md).

## Evidence from the latest completed run

Inspected `C:/Users/ricar/Zomboid/Logs/2026-11-05_08-33_DebugLog-server.txt`
and matching `server-console.txt`, through shutdown at 09:01:15. Retained
[local log copy](../../artifacts/npc-patrol-healing-2026-11-05-0833-server.txt).
The startup log identifies `simulation=async-planning; authority=server`.

| Local time | Event |
| --- | --- |
| 08:59:37.759 | HUNK selects `group-patrol`. |
| 08:59:37.840 | HUNK starts area return from `(9931,2510)`, anchor `(9857,2419)`, first leg goal `(9867,2510)`, 65 route entries. |
| 08:59:37.845 | Spectre selects `group-patrol`. |
| 08:59:37.846 | Vector selects **healing**, not patrol. |
| 08:59:38.857 | Spectre starts area return from `(9931,2510)`, anchor `(9894,2472)`, first leg goal `(9894,2483)`, 65 entries. |
| 08:59:45.061 | Vector's logical bleeding rate changes from `7.564921E-5` to zero; health is `0.8809897`. |
| 09:00:15.067 | Vector checkpoint health increases from `0.8809897` to `0.89798945`. |
| 09:00:17.756 | HUNK finishes its first recovery leg, `success=true; inside=false`, then starts a northbound leg toward `(9867,2472)`. |
| 09:00:18.453 | Spectre finishes its first leg, `inside=false`, then starts another toward `(9894,2472)`. |
| 09:00:25.351 | Spectre reports successful recovery with `inside=true`. |
| 09:00:40.849 | HUNK also reports successful recovery with `inside=true`. |
| 09:00:45.149 | Vector health reaches `0.91398925`. |

World frames progress from 22 to 693 and shutdown finishes. This is not the
previous frame-486 deadlock signature. There is no logged shared patrol phase/
waypoint transition in this run. The evidence does not establish independent
shared-cursor advancement or an async planner failure.

## Vector was resting under the healing policy

[HealingModule.update](../../src/zombie/apocalipse/humans/healing/HealingModule.java)
calls `stopMoving()` while healing is selected. Its eligibility includes passive
recovery; [ServerHumanHealing](../../src/zombie/apocalipse/humans/ServerHumanHealing.java)
keeps `recovering` true while health is below 1 and configured recovery is enabled.
Only dressing creates a body-region presentation; passive health recovery can
look like standing idle. The rising checkpoint health demonstrates progress.

Healing has higher priority than patrol. Vector therefore remains outside
`group-patrol` until its medical eligibility ends or an emergency preempts it.
This investigation does not claim the precise wound/supply state from health
checkpoints alone, or predict an exact remaining recovery duration.

## Separation occurs before shared waypoint navigation

The assigned patrol rectangle is X `9788..9894`, Y `2395..2472`. HUNK and Spectre
start their observed routes at `(9931,2510)`, outside that rectangle.

[GroupPatrolModule](../../src/zombie/apocalipse/humans/groups/GroupPatrolModule.java)
uses the shared formation destination only when `v.inside()` is true. Otherwise
it requests `MovementRequest.Pattern("background")`.
[ServerHumanWalking](../../src/zombie/apocalipse/humans/ServerHumanWalking.java)
then computes an **individual** `returnAnchor()`: clamp that actor to the area
edge if the assigned grid says it is walkable, otherwise fall back to home.
The log explicitly shows the two different anchors and resulting legs.

The log cannot distinguish why HUNK's clamped anchor failed its availability/
walkability check initially: streaming, geometry and occupancy are possible.
It does show that the later leg could use a boundary anchor. Recovery success
completes only that bounded leg; `inside=false` correctly requests another leg.

[GroupPatrolCoordinator](../../src/zombie/apocalipse/humans/groups/GroupPatrolCoordinator.java)
still owns one group cursor. It requires all roster members at the waypoint
before advancing, but has no travelling formation-distance leash and does not
pause all locomotion because a remote member is healing. Consequently, keeping
one waypoint cursor does not make these recovery legs or movement timing shared.

## Required correction for the requested behavior

Keep the shared cursor and add a **group-owned recovery/regroup stage** before
ordinary waypoint travel. Select one valid rally/re-entry goal for that group
revision; use per-member formation offsets and bounded routes toward it instead
of actor-local home/boundary anchors. Retain the current waypoint while recovery
is incomplete; do not treat entering the rectangle or a partial leg as waypoint
completion. Revalidate loaded/collision-safe landing and path edges per actor.

Add explicit interrupted-member handling: when a member is healing or under
another exclusive interruption, healthy members should hold/regroup near the
interrupted member under a bounded cohesion policy, rather than continuing away.
Preserve emergency combat/flight authority; a patrol hold must not override it.
Resume the same waypoint after the roster is ready. A shared cursor alone is
insufficient, and replacing it with individual circuit cursors would be wrong.

Add diagnostics for each member's active module, healing/recovery reason,
inside status, shared cursor, recovery revision/goal, separation and hold reason.
Regression scenarios must include the same outside start with different anchor
availability, one healing member, partial recovery legs, combat preemption,
unloaded rally tiles and resume without waypoint skipping. This review changed
documentation only; no Java tests, build, live configuration or database changes.
