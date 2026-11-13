# Umbrella final encounter: survival suppresses covering fire

Target: **42.22.1**. Latest completed run: `C:/Users/ricar/Zomboid/Logs/2026-11-05_11-34_DebugLog-server.txt`, normal shutdown at 12:45:00. This report reviews the final 12:43 fight. The retained local capture `artifacts/npc-combat-2026-11-05-1243.txt` contains NPC events from 12:43:17 through 12:44:19. Log times are server-local. Java combat behavior is unchanged by this investigation; the squad health change is a new manager migration.

Related: [squad assistance](../features/2026-11-04-npc-squad-combat-assistance.md), [squad recovery](../features/2026-11-05-squad-recovery-roaming.md), [group patrol](../features/2026-11-04-npc-group-patrol.md), and [previous close-spawn investigation](../issues/2026-11-05-eight-zombie-contact-and-bandage-presentation.md).

## What happened

| Time | Authoritative observation |
| --- | --- |
| 12:43:18.540 / 22.145 | Vector landed pistol kills. Helpers were capable of firing before survival preempted them. |
| 12:43:23.338 | Spectre and Vector each fired at zombie 19342 and missed. |
| 12:43:23.439-.450 | HUNK switched to survival: `outnumbered`, four nearby, zero counted allies, full health and almost full endurance. His retreat initially aimed toward the two active helpers. |
| 12:43:24.939 / 27.941 | HUNK's retreat failed scope validation, then another failed attempt escalated to CREATE_OPENING. |
| 12:43:29.241 / 29.738 | Vector and Spectre each selected survival: four nearby, zero counted allies, full health/endurance. This cancelled their ordinary engagement. |
| 12:43:30.839 / 31.239 | Both helpers reported pressure relief and RECOVERING, with zero nearby. They remained owned by survival rather than immediately resuming ordinary ranged engagement. |
| 12:43:32.243-41.138 | Four positive zombie damage results were logged while HUNK was under pressure. His health reached 0.973539. Helpers remained at full health. |
| 12:43:37.847-38.041 | Vector briefly reached SAFE, then re-entered survival about 0.2 seconds later after again counting four nearby. |
| 12:43:43.441 / 46.141 | HUNK finally committed successful crowbar attacks, killing two zombies. |
| 12:43:46.636-.739 | Spectre reached SAFE and selected engagement/assistance again. |
| 12:43:49.938 / 51.437 | Spectre fired his pistol again, missing both attempts. HUNK continued using the crowbar. |
| 12:43:53.540 | Spectre switched to a successful crowbar attack. |
| 12:43:57.439 | Vector finally left survival and resumed engagement. |
| 12:44:03.841 | HUNK landed the last logged pistol kill; all three returned to group patrol. |
| 12:44:09.137-18.239 | HUNK completed two native inventory bandages. Earlier treatment tokens at 12:43:57-59 were cancelled during module handoffs. |

For the retained window, HUNK committed four crowbar strikes and three pistol strikes; Spectre one crowbar and eight pistol strikes; Vector four pistol strikes. A commit includes misses, not just damaging hits. HUNK also had 41 combat endings marked PREEMPTED and three UNREACHABLE. Those repeated cancellations are evidence of execution churn, not proof of 41 independent weapon faults: the current log does not identify the internal attack phase or every cancellation caller.

The helpers did not simply stand under one squad-wide cease-fire. Each independently entered survival, and the survival latch continued owning its turn after physical retreat stopped. This distinction matches the visual impression while explaining the selected modules precisely.

## Exact decision points to change next

1. **Repair ally support visibility before balancing the crowd limit.** `ServerHumanGroups.captureStrength()` counts combat-ready allies within six tiles only when `ServerHumanPerception.visible(ally.player)` succeeds. That method rejects `body.getCurrentSquare()==null`. NPC construction intentionally calls `removeFromSquare()` and does not maintain native square membership; ordinary movement updates coordinates. The exact baseline `IsoMovingObject.removeFromSquare()` clears `current`, and `getCurrentSquare()` returns that field. Native traversal temporarily assigns a square and restores the prior reference. Consequently the generic visibility helper is unsuitable for these detached NPC allies. This is a source-backed defect consistent with the zero-allies logs; the run has no explicit per-ally rejection diagnostics. Use validated loaded coordinates and native LOS for detached allies, retaining strict native membership checks for real zombies. Do not attach all NPCs to world lists merely to make this predicate pass.

2. **Distinguish personal attackers from a nearby crowd around a teammate.** `ThreatAssessment.assess()` currently counts every remembered zombie inside four tiles and retreats at `crowd_count * (1 + allies)`, default four when support is zero. It does not distinguish zombies actively attacking HUNK from zombies personally threatening Vector or Spectre. Both healthy helpers crossed that spatial threshold. A personal-pressure observation should include victim/attack focus, immediate separation and available support so one cornered member can retain covering shooters. Do not simply disable their own emergency response.

3. **Allow appropriate covering fire while safely RECOVERING.** `SurvivalModule.emergency()` remains true until SAFE; arbitration therefore keeps the engagement/assistance module preempted. `SurvivalModule.defend()` only starts attacks against a visible target within 1.5 tiles (`distanceSquared <= 2.25`), even when a ranged weapon could legally fire farther. Its recovery latch is useful against flee/re-engage flicker, but currently also excludes ranged cover outside that defensive radius. Add a separate validated stationary cover capability in RECOVERING when personal clearance permits it, preserving escape ownership while ESCAPING, immediate personal defense, weapon range/line checks and cancellation. The new local recovery movement composition does not itself provide ranged cover.

4. **Trace cancellation causes before changing melee timing.** HUNK's rapidly replaced defensive executions precede the first successful melee strike. Survival cancels defense on route failure/retry, target identity changes and movement-ownership changes; squad assistance also changes assignments. Record caller/phase/target-change details, then preserve an already committed attack where mechanically valid. PREEMPTED alone cannot prove which cancellation path caused each delay. Adding another blanket intent cooldown would not repair the missing support or ranged-cover policy.

These are proposed combat changes, not implemented claims. Current Lua/native authority boundaries, target incarnation checks and server-only execution must remain intact.

## Normal health migration

[Manager V45](../../../../apocalipsebr-manager/manager/src/main/resources/db/game-runtime-migration/V45__Umbrella_Patrol_Normal_Health.sql) publishes a new revision of `umbrella-rpd-patrol` with `health_multiplier=1`. It locks the profile, clones its current published settings and revision-owned children in FK order, allocates `max(revision)+1` including drafts, and advances the pointer only after cloning. It does not mutate the immutable 2x historical revision or repurpose an existing draft. Profile-level dialogue binding, route, modules, parameters, spawns, group slots and runtime checkpoints remain untouched.

The generic multiplier column, engine scaling and manager editor remain available for future monster profiles. The migration has no new objects and relies on existing engine-role table grants. It is a no-op if the profile is absent/unpublished or already at normal capacity. The seeded double-health description is updated without overwriting a custom description.

Health is stored as a normalized fraction. No checkpoint rewrite or forced heal is needed; the reduced capacity takes effect when an actor binds the new revision. Existing manager semantics poll published profiles but keep active actors pinned until normal deactivation/reactivation or server restart. The migration does not send live commands or force that lifecycle transition. Removing 2x capacity increases susceptibility to later damage; it does not fix AI suppression, and the observed 2.65% health loss alone does not establish an otherwise lethal encounter.

## Validation

[Migration regression tests](../../../../apocalipsebr-manager/manager/src/test/java/com/apocalipsebr/zomboid/server/manager/infrastructure/config/NpcUmbrellaHealthMigrationTest.java) cover a real isolated Flyway upgrade, repeat migration validation, normal squad capacity, preserved historical revision and draft, unchanged unrelated 4x monster profile, child-setting equality, engine-role reads, immutable new publication and standalone no-op replay. Run from the patches repo: `py -3 tools/test_npc_umbrella_health_migration.py`.

`py -3 tools/test_npc_umbrella_health_migration.py` passed: the actual manager Maven wrapper ran all three migration tests against a disposable PostgreSQL 17.6 cluster, with zero failures/errors/skips. Flyway upgrade, repeated no-op and validation passed. Documentation links and both repository diffs passed whitespace checks. No Java engine changes required a game-JAR rebuild. No live database migration or Java deployment was performed.

Subsequent implementation: [squad capacity, stationary cover and physical escape](../features/2026-11-05-squad-engagement-capacity-and-cover.md) repairs detached-ally LOS, makes capacity inclusive, adds a tight personal-surround check, allows validated recovery cover and preserves defensive windup through route retries. Native zombie victim-focus classification remains future work. This report retains the historical encounter and proposal scope above.
