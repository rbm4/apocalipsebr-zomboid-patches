# Wounded recovery and squad roaming

Target: **42.22.1**, server-owned NPC execution. Implemented and fixture-tested; in-game animation, collision and roaming replay remain pending. No deployment, server configuration or database migration was performed.

This extends [squad cohesion and passive recovery](2026-11-05-squad-cohesion-healing-and-state-speech.md), [self-treatment](2026-11-04-npc-healing.md), [group patrol](2026-11-04-npc-group-patrol.md), and the [eight-zombie contact fixes](../issues/2026-11-05-eight-zombie-contact-and-bandage-presentation.md). The user confirmed that the first-aid animation now plays, but observed the entire squad waiting for Spectre to recover.

## Recovering member

`healing/HumanRecoveryState` is a fresh, per-incarnation convalescence latch owned by `ServerHumanHealing`. An open wound or health below 65% enters recovery; health at least 85%, no open wound and no logical arm/leg injury release it. Separate thresholds prevent health-boundary flickering. On the first native medical sample, persisted health below 85% conservatively reconstructs recovery, so reactivation does not discard an unfinished convalescence. Death clears the state. Existing durable health/injury facts remain unchanged; no runtime timer or actor reference is persisted.

This status differs from the survival module's RECOVERING tactical phase and from the medical snapshot's passive-regeneration eligibility. It remains active after emergency reengagement becomes possible, until the convalescence release condition is met. Only actors with the healing capability acquire it.

The factory supplies that explicit state through `HumanConditionCalculator` to existing `MovementModifiers`. Recovering locomotion requests WALK, applies the existing hurt walk speed factor, and presents native `WalkInjury=0.5` when there is no actual logical leg injury. Existing signed leg injuries retain their side and severity. This is a movement/presentation condition; no fake native body-part wound or durable damage mutation is created.

The exact installed `media/AnimSets/player/movement/defaultWalk.xml` blends `WalkInjury=0.5` to `Bob_WalkLightLimpR` and negative values to the left limp; `WalkSpeed` is its other blend axis. The existing `PlayerInjuriesPacket` carries this value to remote players. Idle refresh and the existing presentation cache publish condition changes. Bandaging continues to own its native timed action and performing-action flag.

## Shared recovery behavior

`groups/GroupRecoveryCoordinator` consumes detached member pose, incarnation, recovery and availability facts. It keeps one patient until that member recovers or disappears. When multiple members are wounded, a deterministic slot order selects a replacement; another wound does not continuously move the current anchor.

The anchor is the patient's captured tile. It remains stable during small movements, and resets if the patient changes floor, changes incarnation, or moves more than four tiles away. Helpers receive distinct directions on a ring with three-tile offsets; the primary patient gets one-tile offsets. Every ten seconds the target direction rotates. The last two seconds of each interval are a settled pause; reaching a destination earlier also permits rest. Physical progress, obstacles and existing slot startup staggering determine actual movement. This is local wandering around the patient, not guaranteed fixed spacing or a synchronized orbit.

The active camp retains the sole shared patrol waypoint and resets its dwell; it never advances or consumes it. Once all members leave recovery, normal formation/re-entry resumes toward that same waypoint with a fresh arrival/dwell requirement. A negative observation-revision namespace distinguishes camp goals from ordinary cohesion goals, preventing accidental execution reuse at a mode change.

Fresh same-floor members must be autonomous and either in ordinary patrol, healing, or the RECOVERING survival phase to roam. Combat, escape, retaliation, exclusive dialogue/trade or missing observations pause camp movement. Higher-priority modules retain their existing authority. An actively bandaging member remains stationary while its available companions can roam.

`ServerHumanGroups` binds a fresh recovery-only `GroupPatrolModule` into each member's survival module. While safely RECOVERING, even below the normal reengagement health threshold, survival delegates local movement to that composed execution. Actual bandaging wins before that delegation. New danger, loss of clearance, preemption, suspension and shutdown cancel the owned local execution before another movement owner proceeds. This does not run the normal patrol module concurrently with survival.

## Navigation and ownership

`MovementRequest.Nearby` is a generic local destination/center/radius contract, validated for matching floor and a radius from one to eight tiles. Recovery requests radius four. It uses `VicinityGrid` over the existing native movement/occupancy grid, including an approach corridor from the captured origin to the anchor. Either dimension above 64 tiles rejects admission. Local recovery can therefore operate just outside the authored patrol rectangle after a retreat without inventing a personal area-return destination.

Native static geometry, edge traversal, body occupancy, loaded squares and collision segments remain mandatory. Local objectives do not complete authored walking patterns. Failed attempts use the existing two-second retry; they never fall back to walking onto the patient's center. Blocked/unloaded target tiles fail closed until a later goal is usable. Other floors do not produce a fabricated local route.

All medical status changes, native geometry reads, module execution and packet delivery run on the registered server owner in both startup modes. Coordinators hold only values, with at most eight members. Pending routes reuse the existing bounded server topology capture, immutable worker graph, cancellation tickets and two-second acknowledgement deadline. No worker receives a patient, actor or native grid reference. Camp timers, anchors and executions are transient and rebuilt per incarnation.

## Validation

- `tools/test_human_groups.py`: 352 assertions, including helper movement during recovery, local patient targets, brief pause, rotating goals with unchanged waypoint, aggression and missing-sample pause, patient removal/replacement, incarnation change and normal patrol resumption.
- `tools/test_human_healing.py`: 89 assertions, including recovery threshold hysteresis/reactivation, low-health survival movement, danger cancellation, stationary bandaging precedence and the existing fallback/medical transaction checks.
- `tools/test_npc_movement_replication.py`: 157 checks, including slowed recovery gait and preservation of actual signed leg injury.
- `tools/test_npc_async_authority.py`: 213 default-async, 203 sync and 213 explicit-async checks, including actual local navigation outside assigned territory, worker acknowledgement, untouched pattern cursor and bounded vicinity admission.
- `tools/test_human_squad_assist.py`: 143 assertions.
- `42.22.1/patchApocalipseBr.ps1 -BuildOnly`: 339 sources and 984 classes compiled against the installed 42.22.1 game JAR. Artifact: `artifacts/build_20261105_121756`. No deployment performed.

Gameplay follow-up: replay a bandaged, low-health patient with two helpers; confirm the hurt walk and surrounding local walks, stationary medical work, clean interruption by a nearby zombie, and resumption of the original waypoint after recovery. Tight geometry may prevent a local leg; the engine does not teleport through blockers to maintain the ring.

Combat follow-up: [survival covering fire and engagement capacity](2026-11-05-squad-engagement-capacity-and-cover.md) allow a cleared RECOVERING member to hold a validated stationary ranged target before choosing local recovery roaming. Actual bandaging and immediate personal danger retain priority.
