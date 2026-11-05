# Latest squad run: helpers follow but never reach engagement

Date: 2026-11-05. Target: 42.22.1 server. Status: **observed incomplete assistance;
no runtime source changes in this investigation**.

Feature: [squad combat assistance](../features/2026-11-04-npc-squad-combat-assistance.md).
Previous fix: [long-range distress](../issues/2026-11-05-squad-distress-outside-assistance-radius.md).

## Capture and findings

Read `C:/Users/ricar/Zomboid/Logs/2026-11-05_00-10_DebugLog-server.txt`, last modified
00:15:58. Times below are literal log timestamps. This capture contains:

- 50 `squad assist` transitions: **33 APPROACHING, 15 SEARCHING, two BLOCKED**.
- **Zero ENGAGING**, zero squad combat beginnings and zero squad combat commits.
- Vector is the only member entering survival. HUNK and Spectre select
  `engage-zombies` to execute his distress assignment.
- No confirmed NPC death, death transition, accepted squad combat damage or NPC
  error. All logged Vector survival health values are **1.0**. A client-visible
  death cannot be ruled out, but this log does not establish a server NPC death.

| Time | Evidence |
| --- | --- |
| 00:13:06.292 | Vector enters survival with seven nearby zombies and full health. |
| 00:13:06.395 | Both helpers receive `distress=true` for Vector but initially log `BLOCKED; reason=origin_unavailable`. |
| 00:13:08.066 / .094 | HUNK and Spectre start approaching after their origins become available. |
| 00:13:16.639 | Checkpoint batch places HUNK/Spectre about 116/117 tiles from Vector. |
| 00:13:30.658 | Spectre's movement fails once with `traversal_landing_occupied`; he retries at 00:13:32.727. |
| 00:14:16.800 | Helpers are about 61/67 tiles from Vector. |
| 00:14:46.899-.900 | Same checkpoint batch places helpers about 40/42 tiles from Vector. |
| 00:15:16.985 | Vector and helpers are all on Y=2510.5, with helpers about 60/62 tiles behind. |
| 00:15:32.453-.454 | Helpers remain about 59/61 tiles behind Vector. No helper has entered combat. |

Distances use each checkpoint batch after applying all three actors' deltas.
Comparing one actor's new checkpoint to another actor's previous 30-second sample
can falsely suggest a much smaller distance. These samples do not establish the
minimum separation between checkpoints or the actual helper-to-zombie distance.

## Why assignment is not yet effective rescue

The previous distress gates are working in this run: helpers accept the report,
leave their previous locations and make substantial physical progress. The
remaining failure is **closing/intercepting the fleeing actor's pursuers**.

`ServerHumanSquadAssist` approaches the threat's last reported position. It has
no coordinated rendezvous with Vector and no interception objective. Both escape
and assistance request RUN through the same actor movement system. Following a
continuously moving point offers no guaranteed speed advantage.

Source-confirmed costs also reduce useful pursuit:

- `SquadAssistModule` ends each approach after 14 seconds and waits two seconds
  before starting another. For example, HUNK searches at 00:15:08.885, resumes
  at 00:15:10.976, searches at 00:15:25.046 and resumes at 00:15:27.129. These are
  policy-imposed pauses, not proof the target was lost or a route was blocked.
- Reported target changes cancel/recreate the approach. The early capture shows
  several alternating zombie identities, producing extra route restarts.
- Movement start normally recenters the actor on its current tile. Replanning
  a moving lead can therefore spend time recentering. The log does not measure
  how much progress this costs; it should not be presented as a measured rate.
- `SEARCHING` currently prints `reason=none` for deadline/completion paths. That
  message cannot distinguish the policy deadline from successful partial-leg
  completion without matching source/timing.

Attack admission still requires the helper's own visible sensor contact; a shared
distress report is not attack permission. The observed lack of ENGAGING establishes
that this handoff never happened. Sparse checkpoint distances and absent visibility
detail cannot identify its exact range/LOS cause.

Vector also still alternates escape/recovery: **110 survival transitions** in this
capture. The one-second relief change reduced the very short cancellation case,
but zero-pressure runs can still last long enough to pass that threshold. The
underlying seven/zero count fluctuation remains unexplained.

## Next implementation target

Make moving distress a coordinated rendezvous/interception objective. Preserve
progress across target-report changes, avoid mandatory rest after a successful
partial leg, distinguish route completion/timeout/failure in diagnostics, and
allow safe retreat planning to consider actual supporting members' positions.
Physical collision, loaded terrain, personal survival and independently validated
attacks remain authoritative. Increasing the distress radius again would not
address the observed gap: these reports are already being delivered and followed.

This report does not implement movement tuning, interception, streaming changes
or speech. No deployment, database change or server restart was performed.
