# Squad engagement capacity, stationary cover and physical escape

Target: **42.22.1**. Implements the next combat changes identified in the [final Umbrella encounter review](../reports/2026-11-05-umbrella-combat-support-and-normal-health.md). Source/fixture validation passed; a live replay remains required. The separate V45 normal-health migration is unchanged and not applied here.

Related capabilities: [squad assistance](2026-11-04-npc-squad-combat-assistance.md), [recovery roaming](2026-11-05-squad-recovery-roaming.md), [group patrol](2026-11-04-npc-group-patrol.md), [server-owned async authority](2026-11-05-npc-async-authority.md).

## Capacity and personal danger

`ServerHumanGroups.captureStrength` still counts capable same-squad members within six tiles on the same floor, excluding dead, downed, immobile, actively bleeding, exhausted, unloaded, healing/exclusively occupied or retaliating members. Being in survival alone does not recursively remove an otherwise capable member's contribution.

It now uses `ServerHumanPerception.visibleAlly`, which validates loaded coordinates and native LOS without requiring the detached NPC to have a native current-square reference. The ordinary native-target visibility entry retains its membership check. Closed doors, walls, missing observer/intermediate/target squares and other floors still reject ally support. The coordinate LOS scan is bounded to 32 tiles; group capture already bounds ally distance to six.

`ThreatAssessment` treats `crowdCount` as inclusive capacity. With the default four, crowd pressure begins only **above** `4 * (1 + fresh allies)`: one member can remain engaged against four nearby zombies, two against eight, and three against twelve. Freshness remains 500 ms; stale/future support contributes zero. This changes the existing engine interpretation, without changing published configuration or adding a migration.

A separate immediate-contact check prevents squad capacity from hiding one member's personal surround: more than `crowdCount` remembered threats inside 1.5 tiles produces `surrounded`. It uses the same bounded, recent threat observations as the crowd assessment. Low health and exhausted endurance retain higher priority. Thus a member with five immediate contacts can flee while helpers with fewer immediate contacts keep normal engagement. This is geometric personal-pressure assessment, not a claim that every contact is attacking that particular actor; target/victim-focus classification is still future work.

## Survival does not silence a cleared shooter

The existing safe/controlled-observation hold and minimum retreat duration remain; this avoids repeatedly releasing and re-entering survival near a crowd boundary. During RECOVERING, a member with reliable awareness, no crowd/personal pressure and reengagement health/endurance can provide stationary cover up to seven tiles. A supported member can also defend a close target. Actual bandaging retains priority over that cover.

`HumanCombat.Template.SURVIVAL_DEFENSE` explicitly requires external movement ownership. `ServerHumanCombat` revalidates autonomous activity, a live non-SAFE survival module, fresh independently visible target identity, floor and bounded distance before each backend update. Immediate defense within 1.5 tiles remains allowed outside patrol territory. Longer cover requires RECOVERING, adequate health/endurance, fresh non-truncated available awareness and no recent immediate contact. A new immediate contact cancels the long-range lease before a subsequent backend update.

The weapon backend forces ranged selection when this template starts beyond 1.5 tiles. Existing equipment/ammunition, native range, obstruction, body blocking and hit-commit checks still apply. No suitable weapon means no fabricated shot. The external-movement contract prevents closing on the target or stopping/replacing the survival route. Ordinary guard combat retains its authored territorial authorization; player retaliation/PvP rules are unchanged. No new client packet or database template enum is required.

Survival's close-defense selector can now consider independently visible zombies outside the patrol rectangle. Defensive executions always yield movement ownership to survival, so a route failure or retry no longer cancels an otherwise valid windup merely to start another route. Target replacement, invalidation, phase safety release, preemption, medical action and lifecycle cleanup still cancel owned attacks.

## Escape scope diagnosis and correction

Emergency planning already used the physical movement grid rather than the patrol rectangle. Rendezvous positions influence route scoring and have an ordinary escape fallback; they are not a hard squad-distance leash. The user's observed freeze is therefore not established as a squad-range limit.

A concrete source-supported cause of the logged `scope_rejected` failures was the planned grid's dynamic whole-tile occupancy filter being reused as the runtime scope. A zombie entering a waypoint tile could reject the route before the motor assessed clearance of the next actual segment.

`ServerHumanSurvival.escape` still plans around occupancy, recent refused edges and threat envelopes, with the existing bounded search. Once chosen, the route executes against the physical grid. Every step still revalidates loaded squares, native geometry, traversal edges, static obstacles, collision segments and body clearance. A genuinely blocked step still fails and enters cornered defense/replanning; it does not teleport or bypass bodies. Outside patrol territory, bounded immediate self-defense remains available instead of being rejected by ordinary guard authorization. Patrol re-entry and recovery roaming keep their own scopes.

## Ownership and verification

All native LOS, threat/strength capture, emergency route execution, weapon authorization and effects remain on the registered server owner. Pure modules consume detached observations; no worker gains an actor/grid reference. No new queues, locks, threads or persistent timers were added.

- `py -3 tools/test_npc_detached_squad_los.py`: **9** production-method checks, including detached versus native visibility, blocked/unloaded segments and floor/budget limits.
- `py -3 tools/test_human_squad_assist.py`: **160** current-production checks: inclusive four-per-member capacity, twelve-threat squad engagement, one personal surround without helper flight, recovery cover, native outside-area authorization, no pursuit, cover revocation, route policy and retained defensive windup.
- `py -3 tools/test_human_groups.py`: **352** checks; `test_human_healing.py`: **89** checks.
- `py -3 tools/test_npc_async_authority.py`: **213** default-async, **203** sync, **213** explicit-async checks.
- `py -3 tools/test_npc_movement_replication.py`: **157** checks.
- `42.22.1/patchApocalipseBr.ps1 -BuildOnly`: **339 sources / 984 classes**, compiled against the installed 42.22.1 JAR; retained at `artifacts/build_20261105_134318`. No deployment performed.

Gameplay follow-up: replay four approaching zombies, twelve distributed across three capable members, a single tightly surrounded member with clear helpers, and an escape beyond both six-tile support distance and the patrol rectangle. Confirm covering fire, prompt close defense, meaningful physical failure diagnostics and same-waypoint regrouping. Capacity is a decision threshold, not a guarantee of winning those fights; normal-health actors remain vulnerable.
