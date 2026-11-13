# Fleeing squad member outside nearby assistance range

Status: **source fix verified; deployment/runtime validation pending**.
Target: 42.22.1 server. Follow-up to
[squad combat assistance](../features/2026-11-04-npc-squad-combat-assistance.md) and
[the earlier encounter](2026-11-04-npc-patrol-combat-runaway.md).

## Reproduction and findings

Latest local evidence: `C:/Users/ricar/Zomboid/Logs/2026-11-04_23-49_DebugLog-server.txt`.
At literal log time 23:53:35.642, Vector's checkpoint is at Y=2525.2192;
HUNK/Spectre are near X=9868,Y=2419. Vector starts an escape at
23:53:35.779 from `(9849,2525)` and is the only member logged entering survival.
There are **zero `squad assist` transitions** in this capture. Later, his escape
loops reach approximately `(9974,2312)` as quoted by the user.

The initial separation is already over 100 tiles. The first assistance
implementation admits helpers only within 20 tiles of a report, and limits its
emergency area to patrol bounds plus 20 tiles. It cannot admit this squadmate's
call even when helpers are otherwise available. It also requires visual reports,
although survival reacts to the separate awareness frame. Distress known to
survival therefore need not become a visual assistance report.

The rapid zero/seven count changes trigger another independent policy problem:
`SurvivalModule` cancels a running escape immediately after a single controlled
sample, then starts a fresh route as soon as pressure returns. Existing safe-hold
timers only guard SAFE/reengagement. The logs prove the oscillation, but not why
the underlying measured counts fluctuate; that needs sensor/position captures.

## Changes on 2026-11-05

- A live squad member in survival publishes a distress report from fresh
  awareness of a validated native zombie within 12 tiles. Sharing that report
  does not supply visual attack authority.
- Same-group distress reaches available helpers up to **256 tiles** from its
  last observed threat and takes priority over ordinary nearby reports. Members
  still in survival are not assigned as helpers.
- Distress expands the helper's fixed patrol emergency bounds by 256 tiles.
  Ordinary 20-tile assistance keeps its previous scope. Both retain bounded A*
  partial-leg work, collisions, loaded terrain and same-floor movement. The
  helper approaches in successive legs and still needs its own fresh target
  identity/visibility and the existing 20-tile combat admission before attacking.
- Failed approaches emit a phase-change `BLOCKED` diagnostic with
  `reason=origin_unavailable`, `no_loaded_route` or `stale_order`, actor coordinates,
  source member and distress flag. Actual movement can still be blocked by
  unloaded terrain between separated actors; this change does not pin chunks.
- Running retreat requires **one sustained second** of relieved pressure before
  changing to recovery. Brief zero-count gaps preserve the route. Existing
  survival priority, health/endurance thresholds and longer SAFE timers remain.

The board's eight-second memory, member incarnations, target native membership,
500 ms world-snapshot freshness and per-helper sensor validation remain in force.
Native observations remain world-thread/factory-monitor owned; immutable reports
cross to the existing simulation adapters. No new thread or network protocol is
introduced. The existing async native movement/combat limitations still apply.

## Verification

`py -3 tools/test_human_squad_assist.py` passes **76 assertions** against production
classes with engine doubles. New regressions recreate Vector at `(9849,2525)`,
a helper at `(9857,2402)`, seven nonvisual awareness contacts and a running
survival phase. They verify distress publication, long-range bounded approach,
patrol preemption, no blind attack, survival exclusion, unloaded-origin rejection,
256-tile boundary/priority and repeated seven-zero-seven pressure samples retaining
one route until a full second of relief.

Group patrol fixtures pass **141 assertions**; healing passes **70 assertions**.
Exact installed-JAR `42.22.1/patchApocalipseBr.ps1 -BuildOnly` passes with JDK25.0.4,
**331 sources / 955 classes**. Retained output:
`42.22.1/artifacts/build_20261105_000444/`. No live configuration, database,
installation, deployment or server restart was changed.

Runtime follow-up in both startup modes: reproduce the separated squad, observe
`distress=true` assistance transitions, check loaded versus unloaded helper origins
and corridor, target death/loss, interrupted climbs, safe recovery and return to
the group's paused waypoint. A blocked or equally fast helper may not physically
catch a distant fleeing member; the logs must distinguish assignment from actual
successful movement and combat.
