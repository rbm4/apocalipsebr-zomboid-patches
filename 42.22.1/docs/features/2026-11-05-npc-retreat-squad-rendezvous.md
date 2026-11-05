# Retreat toward squadmates actively providing assistance

Target: **42.22.1 server**. Implemented and fixture/JAR-build verified on
2026-11-05. Deployment and dedicated-server validation remain pending.

Related: [squad combat assistance](2026-11-04-npc-squad-combat-assistance.md),
[distress outside nearby range](../issues/2026-11-05-squad-distress-outside-assistance-radius.md),
and [helpers following without engagement](../reports/2026-11-05-squad-assistance-following-without-engagement.md).

## Behavior

Follow-up: [squad fight-or-flight assessment](2026-11-05-npc-squad-fight-or-flight.md)
now counts capable nearby squadmates when deciding whether to retreat. That
capacity snapshot is independent of the active-pursuit rendezvous channel below.

Each new escape leg can now prefer a safe route that gradually closes the distance
to the nearest squadmate actively coming to help. This addresses both sides
chasing moving points indefinitely: the helpers approach the threat while the
fleeing member can flank toward their actual positions.

The preference comes from active assistance, not mere group membership or an
assignment. `SquadAssistModule` renews a short pursuit lease only while it owns an
approach or a running combat execution. Cancellation, failed approaches and search
backoff clear that lease. The world-thread group capture accepts only live,
autonomous, mobile helpers on the same floor, out of their own survival state,
with a loaded movement origin and a fresh lease for this exact member incarnation.
It captures up to seven immutable helper positions. Samples/leases older than
500 ms are ignored; removing an adapter closes its support channel permanently.

`ServerHumanSurvival.escape` supplies those detached positions to `RetreatPlanner`.
The planner selects the nearest helper, with stable coordinate tie-breaking.
It retains the existing 256-node work limit, retreat radius and route-length
limits, native traversal policy and endpoint requirement to improve nearest-threat
clearance by at least two tiles.

For supported retreat, clearance gains beyond a useful separation are capped in
the route score: `max(6, initial clearance + 2)`. Progress toward the helper adds
0.8 points per tile; existing depth/cost penalties still apply. The route's threat
envelope is also tightened to retain up to three tiles of initial clearance.
This permits a sideways/flanking approach around known threats instead of always
selecting the point farthest from them. It never makes the helper's coordinates
an unconditional destination through a horde.

If the more conservative supported search finds no escape, the original planner
is tried. Without current helpers, the original route scoring is unchanged.
Locally observed threats and physical collision remain authoritative; this does
not assert that unobserved surroundings are safe.

The preference is reconsidered at each **new retreat leg**. Helper position
changes do not restart an active escape or native crossing. Existing survival
priority, pressure relief, health/endurance gates and weapon authorization remain.
The helpers' existing approach deadlines/backoff are unchanged in this feature.
Successful rendezvous therefore still depends on terrain, streaming and motion.

## Ownership and diagnostics

Pursuit lease updates run on the selected NPC simulation thread. Group capture,
member validation and actual-position reads run on the registered world thread.
The existing factory monitor serializes both with removal and AI ticks. Worker
planning consumes immutable `RetreatPlanner.Rendezvous` values, not other NPCs'
native actor references. The pure planner performs no extra world access and adds
no threads, queues, packets or persistence changes. Existing async native movement
limitations remain as documented by the
[worker guide](../../../42.21.0/docs/features/2026-10-26-npc-async-worker.md).

`retreat route` diagnostics now include `support=[Rendezvous[x=..., y=...], ...]`.
An empty list means ordinary escape. A nonempty list identifies available support
inputs; it does not promise the final route could move closer or that combat
assistance has succeeded.

## Verification and rollout

- `py -3 tools/test_human_squad_assist.py`: **116 assertions** against production
  group capture, assistance/engagement/survival policy, native retreat adapter,
  pure retreat planner and combat authority, with engine doubles. New scenarios
  cover threats between the fleeing actor and helpers, actual native adapter
  handoff, gradual gap reduction, route/endpoint threat clearance, a blocked
  squadward corridor, nearest-helper choice, empty-support behavior, and stale,
  unloaded or cancelled helper exclusion.
- `py -3 tools/test_human_groups.py`: **141 assertions**.
- `py -3 tools/test_human_healing.py`: **70 assertions**.
- Exact installed-JAR compilation using `42.22.1/patchApocalipseBr.ps1 -BuildOnly`:
  JDK25.0.4, **331 sources / 957 class files**, retained in
  `42.22.1/artifacts/build_20261105_002443/`.

No installation, live database, server configuration, deployment or restart was
changed. Deploy the matching patch and restart before testing. In both sync and
async modes, verify retreat with an ally behind the pursuers, obstructed routes,
helper preemption/death/unload, active climbs, subsequent combat handoff and
return to the group's paused waypoint. Inspect support coordinates alongside
actual helper/victim positions; fixture success is not a measured live rescue.
