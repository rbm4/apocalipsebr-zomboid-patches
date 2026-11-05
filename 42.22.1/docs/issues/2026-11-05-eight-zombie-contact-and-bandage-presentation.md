# Eight-zombie contact, tactical retries and hidden bandaging

Target: **42.22.1**. Source fixes implemented and compiled; live replay remains pending. Sprinter support is deferred at the user's request. No server options or live database were changed.

Owning guides: [squad cohesion and state speech](../features/2026-11-05-squad-cohesion-healing-and-state-speech.md), [self-treatment](../features/2026-11-04-npc-healing.md), and [movement replication](../features/2026-11-05-npc-movement-replication.md). The earlier [sprinter investigation](../reports/2026-11-05-server-zombie-sprinter-speed.md) still applies to the uncalibrated gait fallback.

## Evidence

Latest completed run: `C:/Users/ricar/Zomboid/Logs/2026-11-05_10-11_DebugLog-server.txt`, ending with `Shutdown handling finished` at 10:44:46. Startup reports `simulation=async-planning; authority=server`. Cohesion went RETURN -> PATROL before this encounter and HOLD at 10:40:44.493. This is distinct from the historical Lua/factory deadlock.

A filtered 517-line encounter capture is retained locally at `42.22.1/artifacts/npc-eight-zombies-2026-11-05-1011.txt` (ignored build/evidence directory). Window: 10:40:44 through 10:41:04, local server log time.

| Time | Observed event |
| --- | --- |
| 10:40:44.095-.097 | Eight zombies, IDs 3801-3808, acquired target 771; every acquisition reports `speed=2.3999999; speedSource=native_walk_fallback`. |
| 10:40:45.689-.718 | HUNK and Spectre entered survival, with zero counted support allies. HUNK received a 12-step route east; Spectre a four-step route toward a support rendezvous. |
| 10:40:46.690-.695 | Vector entered survival and received a 12-step route south. |
| 10:40:46.989 | Spectre's route failed with `scope_rejected`, attempting tile (9852,2449). |
| 10:40:49.289 | Subsequent route failed with `body_occupied`; Spectre entered CREATE_OPENING. Failures repeatedly showed origin and target both (9852,2449), position (9852.604,2449.5), waypoint zero, and an empty destination moving-object list. |
| 10:40:54.691-.990 | Vector and HUNK recovered to SAFE and resumed engagement to assist distressed Spectre, roughly nine seconds after retreat began. |
| 10:40:57.693-10:41:01.590 | Spectre repeatedly entered CREATE_OPENING, CORNERED and ESCAPING; several newly started routes failed within 100-200 ms. |
| 10:41:03.693 | Spectre entered RECOVERING with no nearby contacts and health 0.5387091. |
| 10:44:01.287 | Spectre's checkpoint health was 0.44405276; bleeding continued after combat. |

The encounter window contains 32 movement failures (31 body occupancy, one scope rejection), six CREATE_OPENING entries, and 62 logged strike outcomes: 44 positive native damage results, ten zero-damage native results and eight `contact_lost` results. These are authoritative outcomes, not a count of visually apparent client attacks. They do not establish that a client-visible bite should have killed the NPC. No client capture or synchronized client positions were available to measure the suspected sprinter desync.

## Diagnosis and changes

**State commitment:** Survival already latches the emergency, requires a minimum retreat duration (default eight seconds), and holds safe/controlled observations before ordinary engagement resumes. HUNK and Vector's return was consistent with that policy, not a per-frame loss of the survival module. A blanket cooldown on all module transitions would also delay necessary emergency responses. The concrete short cycle was different: any successful defensive strike could end the four-second CREATE_OPENING window immediately, without evidence that the route was clear. A two-second minimum opening commitment now prevents landed hits from collapsing that window. Afterward a hit may shorten it; the existing four-second maximum and immediate danger/cancellation rules remain. This is a per-actor monotonic timer on the server owner, with no worker/native crossing or unbounded queue.

**Movement:** Guarded/elevated movement checks continuous body clearance in neighboring squares. Thus an empty destination occupant list does not mean the body check is wrong. Spectre was close enough to zombies centered near (9851.97,2449.35) that recentering from x=9852.604 toward x=9852.5 could reduce clearance. Start previously skipped origin centering only when a blocker occupied the origin's own object list. It now also skips unsafe centering detected by the same continuous body check during guarded/elevated movement. The actual outward step must still pass scope, edge, static geometry, segment and body clearance checks. A controlled production-movement fixture reproduces neighboring-tile overlap with an empty origin list and verifies outward separation. This addresses a source-supported cause consistent with the logs; it does not guarantee every surrounding crowd has a traversable escape.

**Bandage animation:** Native `PlayerActionsState.setParams(Enter)` starts the remote action; `NetworkPlayerVariables.setBooleanVariables` then replaces `isPerformingAction` on every movement update. NPC movement packets previously omitted that bit. Dressing now sets the native performing flag at start and clears it on completion/cancellation; movement serializes it in the existing native format. The original log has no per-transaction medical receipts, so neither speech nor reduced bleeding proves completion of every dressing. New bounded start/commit/cancel DEBUG records include actor, token, dressing type and inventory/fallback source to establish that on the next run.

**Fallback wrap:** Clean inventory dressings remain preferred and are consumed once. If none is found, a transaction-local `Base.RippedSheets` wrap is available without any inventory item. It takes `sheetsMs` (default 5600 ms), applies ordinary non-alcoholic bandage power 1, and creates no inventory/loot entry. Safety, stationary pose, fresh damage/combat quiet period, renewed lease, cancellation and exactly-once commitment remain required. Removing a real item during its active treatment cancels that transaction; the next attempt can use fallback. No supply migration or live inventory operation is necessary.

## Validation

- `py -3 tools/test_human_healing.py`: 80 assertions, including current production survival minimum opening commitment, zero-inventory fallback, one-time application, real-item loss cancellation and native action-flag lifetime.
- `py -3 tools/test_npc_movement_replication.py`: 155 checks, including native action-bit serialization/clear and neighboring-body origin escape.
- `py -3 tools/test_npc_async_authority.py`: 202 default-async, 192 sync and 202 explicit-async checks.
- `py -3 tools/test_human_groups.py`: 340 assertions; `test_human_squad_assist.py`: 143 assertions.
- `42.22.1/patchApocalipseBr.ps1 -BuildOnly`: 336 sources, 978 classes compiled against the installed 42.22.1 game JAR, retained in `artifacts/build_20261105_105742`. No deployment performed.

Gameplay follow-up must verify a non-sprinter close-spawn encounter, outbound escape around actual bodies, a visible full-length dressing with no inventory supply, matching medical commit receipts, and recovery without persistent bleeding. Squad-wide retreat/re-entry doctrine is not replaced by this targeted timer fix; cohesion still governs patrol, while emergencies retain individual authority.
