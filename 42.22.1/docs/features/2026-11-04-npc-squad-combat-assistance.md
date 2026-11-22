# Squad combat assistance from the installed A-life behavior

Target: **42.22.1 server**. Implemented in source for the user's requested combat
assistance scope. Deployment and dedicated-server validation remain pending.

Related: [group patrol](2026-11-04-npc-group-patrol.md) and
[encounter investigation](../issues/2026-11-04-npc-patrol-combat-runaway.md).

2026-11-05: [confirmed factory/Lua deadlock during squad weapon equip](../issues/2026-11-05-npc-lua-factory-deadlock.md).

2026-11-05 follow-up: [separated fleeing member distress and retreat relief](../issues/2026-11-05-squad-distress-outside-assistance-radius.md).

Earlier runtime evidence: [helpers follow but never reach engagement](../reports/2026-11-05-squad-assistance-following-without-engagement.md).
The [latest encounter](2026-11-05-npc-squad-fight-or-flight.md) now reaches ENGAGING
before helpers flee under solo crowd thresholds. Successful native hits remain
unverified; the new local squad-strength assessment addresses that survival cascade.

Implemented follow-up: [safe retreat toward active squad assistance](2026-11-05-npc-retreat-squad-rendezvous.md)
adds a fresh helper-position preference to each new escape leg. Explicit helper
interception and the existing approach backoff remain separate work.

## Source behavior adopted

The installed A-life snapshot is Workshop `3803984183`,
`mods/ProjectALifeNPCs/42.20/media/lua/`. Inspected:

- `server/ProjectALife/Combat/ALifeSquadTactics.lua`: `reportThreat`, `assistTarget`,
  `acquireThreat`; a nearby assistance range of 20 tiles and eight-second report
  memory, with per-actor reports and a shared group contact.
- `server/ProjectALife/Senses/ALifePerception.lua`: shared targets still require
  the helper's own visibility; otherwise `sharedLead` supplies a last-known lead.
- `server/ProjectALife/Combat/ALifeInjuryRescue.lua`: a separate downed-ally medical
  rescue system. The user explicitly selected **combat assistance for this
  encounter** rather than dragging/bandaging downed allies.

The Java implementation adapts the report/assist/last-known-lead behavior to our
existing server-owned IsoPlayer actors. The installed A-life mod is unchanged.

## Composition and behavior

`ServerHumanGroups` binds a fresh `ServerHumanSquadAssist` to each grouped actor
whose runtime has both group patrol and zombie engagement. `EngageZombiesModule`
composes `SquadAssistModule` ahead of ordinary zombie target selection. Existing
profiles and group membership select the capability; no actor names or keys are
hardcoded and no new profile/schema migration is needed. Live actors need a
restart to receive the new composition.

On the world thread, a member reports its nearest independently visible, valid
zombie within four tiles. This local-danger admission is our adaptation to the
reported encounter; it is not claimed as an A-life constant. One transient report
is kept per source member. Each helper receives the closest other member's threat
within **20 tiles**, on its own floor. As of 2026-11-05, a member in survival also
publishes **distress** from its fresh bounded awareness contacts within 12 tiles,
including contacts without visual authority. Native identity/membership is still
validated. Same-group distress takes priority over ordinary reports and reaches
helpers within **256 tiles** of the reported threat. Helpers still in survival
are excluded from assignments; higher-priority policies retain control.
Reports expire **eight seconds** after the
last sighting. The source member must retain the same live incarnation and the
reporter's original target binding must still resolve to the same valid native
zombie membership. Dead/reused targets and removed/dead members invalidate reports
on the next world capture, rather than waiting for their memory timeout.

An unseen report starts a bounded RUN approach toward its last observed position.
It uses the existing A* partial-leg planner (256-node/64-edge limits) and normal
physical movement, loaded-square, body, collision and traversal checks. Occupied
target centers remain blocked. A moving report can trigger replanning after
700 ms and 1.5 tiles of displacement, while active traversal keeps control until
it settles. Each approach has a 14-second deadline; failures/search completion
retry after two seconds. A partial route only advances the search.

Once the helper's own fresh sensor frame sees the zombie, assistance releases
search movement and starts existing weapon combat with `Template.SQUAD_ASSIST`.
The target identity comes from the **helper's** sensor generation. Reporter-local
generations are never installed as helper attack authority. Immediate visible
attackers within 1.5 tiles take precedence over the reported threat, allowing the
helper to defend itself while assisting. Existing weapon selection, ammunition,
approach, line of attack, native hits and packet delivery execute the attack.

Each helper has a fixed emergency area: its authored patrol bounds expanded by
20 tiles for ordinary assistance, or **256 tiles for squad distress**. Route
extents supply the bounds if no explicit area exists. The
area does not move with the fleeing member. Assistance combat revalidates a fresh
order, actual helper/target positions, floor, target identity, visibility and the
20-tile range on every execution update and again before committing a strike.
Only the reported zombie or an immediate personal attacker is eligible for this
emergency scope. Ordinary guard combat retains its original patrol territory.

## Interruption and priorities

Personal survival, retaliation and configured higher-priority healing remain
ahead of assistance. The [2026-11-05 squad fight-or-flight fix](2026-11-05-npc-squad-fight-or-flight.md)
now scales crowd tolerance by fresh, capable nearby squadmates. The latest log
shows helpers reaching ENGAGING, then independently fleeing the same six zombies
because the old assessment still used solo crowd limits. Personal health/endurance
emergencies and genuinely overwhelming crowds retain survival priority. The earlier follow-up requires
**one continuous second of pressure relief** before cancelling escape for
RECOVERING, preventing short seven-zero-seven samples from repeatedly restarting
the current route. The original cause of the awareness count fluctuation remains
unverified; the longer existing SAFE/reengagement timers still apply.

Dialogue/trading suspension, death/removal, stale world samples (over 500 ms),
target/source changes and shutdown cancel the owned search/combat execution.
Removed helper adapters are permanently closed. When assistance ends, ordinary
engagement/patrol resumes through the existing scheduler. While engagement owns
the actor, it does not participate in patrol dwell progression, so the shared
waypoint barrier continues to wait for its actual return.

Transition diagnostics are bounded to phase changes:

```text
squad assist key=...; phase=APPROACHING|ENGAGING|SEARCHING|BLOCKED; ally=...; distress=...; target=...; reason=...; x=...; y=...
```

## Ownership and bounds

`SquadCombatCoordinator` is pure policy over detached member/report records. There
is one board per group, at most eight reports/members, and at most one current
order per helper. Native report validation and discovery hints run inside
`ServerHumanGroups.capture` on the registered world thread under the factory
monitor, in both sync and async startup modes. A hint seeds bounded discovery;
the sensor still performs its own visibility check before attack resolution.

The NPC simulation consumes immutable orders and owns the assistance execution.
Search/combat actuation uses the existing serialized movement/combat adapters on
the selected simulation thread (server thread in sync mode, registered NPC worker
in async mode). Board capture, removal and worker ticks share the factory monitor.
No new worker, network message type, database work or independently shared native
target reference is introduced. Existing native movement/combat worker access is
still experimental: this is not a claim of complete world-thread isolation. See
the [worker ownership guide](../../../42.21.0/docs/features/2026-10-26-npc-async-worker.md).

## Verification

- `py -3 tools/test_human_squad_assist.py`: **76 assertions** against production
  board, assistance/engagement/survival policies, scheduler, group capture,
  native assistance adapter and combat authority adapter, with engine doubles.
  Covers range/floor/memory boundaries, missing/stale sightings, source incarnation,
  dead/reused bindings, group isolation, search-to-combat handoff, immediate defense,
  retry/deadline behavior, full-range A* search, unload, native scope checks,
  personal-survival preemption, suspension/removal and ordinary combat resumption.
  The 2026-11-05 regression adds actual 123-tile separation with an empty visual
  frame, awareness-only distress, 256-tile limits, distress priority, patrol
  preemption, loaded-terrain enforcement and sustained retreat relief.
- `py -3 tools/test_human_groups.py`: **141 assertions**, including actual-position
  arrival, shared dwell, interruptions, segmented paths, group player retaliation
  and native health capacity.
- `py -3 tools/test_human_healing.py`: **70 assertions**.
- Exact installed-JAR build: `./42.22.1/patchApocalipseBr.ps1 -BuildOnly`
  passed with JDK 25.0.4, **331 sources / 955 class files**. Final output is
  retained in `42.22.1/artifacts/build_20261105_000444/` after the distress/relief
  follow-up. The game installation
  was not modified.

Dedicated-server validation remains necessary in **both startup modes** for seven
zombies near three squadmates, shared targets behind obstacles, helpers near
patrol bounds, interrupted traversal, actual ranged/melee attacks, streaming loss,
member death and return to the paused waypoint. Verify `squad assist` diagnostics
alongside survival transitions; if every member is in survival, there may be no
available helper. Terrain/client drift and the cause of awareness fluctuations
remain separate investigations.

Follow-up: [12:43 encounter, suppressed covering fire and normal-health migration](../reports/2026-11-05-umbrella-combat-support-and-normal-health.md) identifies detached-ally LOS, spatial pressure classification and survival-owned ranged cover as the next policy changes.

Implemented follow-up: [inclusive squad capacity, detached-ally LOS, stationary cover and physical escape](2026-11-05-squad-engagement-capacity-and-cover.md). Four threats per available member remain within capacity; a tightly surrounded member retains personal survival.
