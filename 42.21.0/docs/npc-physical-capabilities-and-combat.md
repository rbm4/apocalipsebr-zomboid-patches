# NPC physical capabilities, combat and relationships

Implementation follow-up: the walking mechanics described in the original
investigation below have now been extracted from roaming into
`ServerHumanMovement`. See [implemented movement boundaries](../NPC-MOVEMENT.md).
Running and combat remain proposed extensions; the source table below records
the pre-extraction implementation inspected for this report.

Investigation date: 2026-10-21. Target: server patch 42.21.0 and unmodified
42.21.0 clients. This is a source-backed design report, not an implementation,
protocol compatibility test or live multiplayer result.

## Recommendation

Build reusable physical capabilities beneath the existing behavior modules.
Start with walking/running transitions on the existing indoor route integrator,
then equipment and aiming, then a single authoritative attack lifecycle. Add
damage reception and zombie integration as explicit milestones. Relationships
inform decisions about those capabilities; they do not implement movement or hits.

Preserve the current motion cadence and collision-clipped prediction. A separate
NPC subclass or complete combat behavior is unnecessary to make an actor run.
Do not make faction persistence a prerequisite for that first step.

## Current implementation: verified boundaries

Paths in this section are relative to `42.21.0/`.

| Area | Finding | Source |
| --- | --- | --- |
| Actor | A remote `IsoPlayer`, player index -1, deliberately detached from cell object/add lists and square membership, with god mode enabled. | `src/zombie/apocalipse/humans/ServerHuman.java:39` |
| Movement | Indoor, same-building, same-floor, cardinal routes; no stairs. Position integration uses 2 tiles/second and caps elapsed integration time at 150 ms. | `src/zombie/apocalipse/humans/ServerHumanRoaming.java:14` |
| Cadence | Simulation/actions run at 100 ms intervals; routine movement packets at 500 ms. Starts, stops and direction changes publish immediately. | `src/zombie/apocalipse/humans/ServerHumanFactory.java:25`, `:143` |
| Prediction | One-second lookahead, eighth-tile distance units, collinear connected edges only, with clearance and endpoint headroom. | `src/zombie/apocalipse/humans/ServerHumanRoaming.java:88` |
| Transport | Reliable vanilla player updates; prediction speed is the fixed roaming speed. Only `hasDeferredMovement` is currently set in the boolean flags. | `src/zombie/apocalipse/humans/ServerHumanTransport.java:24` |
| Actions | Roam, facing/attention, speech, emotes, sequences, waits and return-to-post. Parallel currently permits speech plus emote only. | `src/zombie/apocalipse/humans/brain/HumanAction.java` |
| Perception | Connected visible player visitors only; no zombie, weapon, injury or relationship observation. | `src/zombie/apocalipse/humans/ServerHumanActuator.java:24` |
| Control | Dialogue/trade suspend autonomous behavior. Perception is normally skipped while an exclusive interaction owns the actor. | `src/zombie/apocalipse/humans/brain/HumanActivityController.java:20` |
| Persistence | V15 stores profiles, modules, identities, lifecycle and positional checkpoints. Durable health, weapons and relationships are not implemented. | `NPC-PERSISTENCE.md`; manager `V15__Create_Npc_Engine.sql` |

These facts describe a convincing replicated presence, with an intentionally
narrow simulation. They do not establish a fully simulated vanilla player.

## First increment: running without disturbing walking

Vanilla already provides `isRunning` and `isSprinting` flags. Its receiver calls
`setRunning`, `setSprinting` and sets network movement from the movement flag:
`decompiled/zombie/characters/NetworkPlayerVariables.java:19`, `:40`.
Prediction carries an independent numeric speed and direction:
`decompiled/zombie/network/fields/character/Prediction.java:39`, `:107`.

Introduce a locomotion request containing destination/route and requested pace.
The locomotion service resolves an effective pace and physical speed. Start with
WALK and RUN; reserve SPRINT as a distinct capability/policy. The guide should
continue requesting WALK unless its profile explicitly changes its policy.

One resolved motion state must drive all of the following:

1. Distance integrated on the server: effective speed times capped elapsed time.
2. Prediction speed and lookahead length, still clipped before turns/obstructions.
3. Native movement, running and sprinting flags.
4. Animation speed modifiers, when needed to match footfalls and displacement.
5. Immediate publication when pace/speed changes, even if direction and moving
   status remain unchanged. The current factory change detector misses this case.

Keep the 100/500 ms cadence, full distance-budget consumption across waypoints,
collision checks and conservative no-catch-up behavior after stalls. Running
must not bypass a blocked edge simply because its integration step is longer.
Clamp prediction to its representable distance before conversion to a signed byte.
Do not increase packet frequency as a substitute for consistent motion state.

Retain 2 tiles/second for WALK. Determine RUN's initial speed by comparison with
an actual 42.21.0 running player and two-client playback; no source inspection
here establishes a universally correct run multiplier. Physical tiles/second
and native animation modifiers are different units.

Stopping, failure, dialogue/trade acquisition and despawn must clear pace and
prediction consistently. A new/reloaded viewer needs the current motion state.
Existing `Roam` can delegate to a more generic movement executor while retaining
the existing indoor planner. Chase/flee destinations and outdoor/stair routing
are subsequent extensions, not prerequisites for an initial running fixture.

## Wounded movement: condition modifies the requested action

Model injury as physical condition, rather than a competing brain called
"wounded". A bot can be wounded while greeting, fleeing or fighting. Leg injury
can restrict effective pace and speed; arm injury can affect weapon handling.

Vanilla `PlayerVariables.set()` serializes `WalkInjury`/`WalkSpeed` only for
specific action-state names. Our detached server actor does not automatically
advance through all those states. Setting those variables locally alone is
insufficient evidence that clients will receive or retain them.

There is also an existing `PlayerInjuries` packet. It carries IdleSpeed,
StrafeSpeed, WalkInjury and network walk/run modifiers:
`decompiled/zombie/network/packets/character/PlayerInjuriesPacket.java:22`.
Client `IsoPlayer.calculateWalkSpeed()` selects `networkAi.walkSpeed/runSpeed`
according to running/sprinting and handles limp scaling:
`decompiled/zombie/characters/IsoPlayer.java:3776`.

Use that native channel as a candidate for condition/animation changes and
viewer initialization. Verify its packet registration and actual receiver
playback before choosing the final adapter. Keep actual speed consistent with
the effective gait; setting WalkSpeed each update may be overwritten by the
client's calculation. A cosmetic limp fixture should precede claims of complete
body damage, bleeding, treatment or recovery simulation.

## Equipment, aiming and attacks

### Equipment and aim

The next reusable operations are Equip, Unequip, AimAt and StopAiming. Both
firearms and melee weapons need equipment and combat stance. AimAt holds a
validated target/point and direction; it does not necessarily fire.

Vanilla `EquipPacket` serializes real primary/secondary items and visuals on
the server (`decompiled/zombie/network/packets/EquipPacket.java:31`).
`PlayerAimState` serializes aim/floor-aim parameters through the State channel;
`PlayerStrafeState` handles aiming movement and strafe speed
(`decompiled/zombie/ai/states/PlayerAimState.java:42`, `PlayerStrafeState.java:41`).
These are promising existing wire formats, similar in approach to the existing
custom emote packet builder. Do not implement weapon stance as an arbitrary emote.
Audit action-context/state entry and exit and replay them to new viewers.

Our transport currently sets prediction movement direction equal to facing.
Separate those for future strafing/backpedalling: the bot may face a target while
moving sideways or retreating. Stopping to aim is a valid smaller first milestone.

### Authoritative attack lifecycle

Proposed execution: validate -> wind-up/aim -> commit once -> recover -> complete.
Cancellation before commit causes no hit/ammo expenditure; cancellation afterward
does not roll back a committed hit. Use a stable execution/attack identity and
activation generation so repeated ticks or late packets cannot repeat damage.

At commit, revalidate target incarnation, range, floor, obstruction, weapon,
ammunition, cooldown and applicable player-protection rules. Melee needs an arc
and reach; firearms need line of fire, ammo/chamber handling, shot/spread policy,
sound and effects. Misses still need attack presentation. Start with stationary
melee against a zombie before adding firearms and moving attacks.

Vanilla has useful adapters, but a server call to `pressedAttack()` is not proof
of a completed attack: `SwipeStatePlayer.OnAnimEvent_AttackCollisionCheck()`
gates hit computation on `IsoPlayer.isLocalPlayer(owner)`
(`decompiled/zombie/ai/states/SwipeStatePlayer.java:280`). Our actor is remote,
and the current factory does not run its normal animation-driven player update.
Server execution needs its own authoritative commit timing and a carefully
selected reuse of vanilla combat calculations. Clients present the result;
they must not independently decide the same hit.

Inspect/reuse `CombatManager`, `PlayerHitZombiePacket`, `PlayerHitPlayerPacket`,
hit fields and ranged sound packets as individual components. Packet constructors
can depend on already-populated attack/hit state. Do not assume invoking a packet
both computes and applies the full attack. This report does not certify a final
server-originated attack adapter.

## Zombie attraction, damage and death

Separate audibility, visibility, prey selection, contact and damage:

- Running/gunfire can emit bounded world stimuli even before combat is enabled.
  Audible client footsteps alone do not prove correct server-owned attraction.
  Native footstep animation calls the sound routine, which can add world sounds
  (`decompiled/zombie/characters/IsoGameCharacter.java:4874`, `:6327`). Define who
  generates authoritative NPC stimuli to avoid one copy per observing client.
- Native spotting has a zombie-owner control path in
  `IsoPlayer.TestZombieSpotPlayer()` (`decompiled/zombie/characters/IsoPlayer.java:1045`).
  Trace it with `ZombieControl`, target replication and ownership changes before
  assuming `setTarget(bot)` establishes stable multiplayer attacks.
- Our server registry is private to `ServerHumanFactory`. Incoming player IDs
  resolve through `GameServer.IDToPlayerMap` in `PlayerID.parsePlayer()`;
  `CharacterID` delegates to that player field. An incoming hit cannot currently
  resolve a bot solely from the NPC factory. Prefer an explicit NPC-aware lookup
  boundary, auditing ownership/anti-cheat checks, rather than adding bots wholesale
  to normal account/connection/player simulation lists.
- `ZombieHitPlayerPacket.isConsistent()` checks the attacking zombie's connection
  ownership on the server. Its Bite field applies `BodyDamage` on the server
  (`decompiled/zombie/network/packets/hit/ZombieHitPlayerPacket.java:60`;
  `decompiled/zombie/network/fields/hit/Bite.java:80`). God mode prevents ordinary
  damage (`decompiled/zombie/characters/BodyDamage/BodyDamage.java:1256`).
- Resolve one authority for contact and damage, including owner migration and
  replay rejection. Keep ordinary player packet validation intact.
- Knockback/hit reaction should interrupt movement and incompatible actions.
  Death must settle/cancel interactions and attacks, capture inventory/condition,
  handle corpse/loot once and transition logical lifecycle to DEAD. The existing
  factory's isDead check is useful but does not supply the complete death pipeline.

Do not remove the current detached-actor protections or god mode globally as a
shortcut. Make damageability a physical policy and prove one damageable NPC with
two clients before extending normal guide behavior.

## Composition and control

Proposed contracts below are conceptual names, not existing Java declarations:

| Service | Owns | Example operations |
| --- | --- | --- |
| Locomotion | Route progress, effective pace/speed, collision and facing | MoveTo, Stop, Face |
| Equipment | Authoritative item instances, hand ownership and replication | Equip, Unequip |
| Weapon execution | Aim state, attack lifecycle, ammo and hit validation | AimAt, MeleeAttack, FireWeapon |
| Physical condition | Health/injury/endurance mutations and restrictions | ReceiveHit, Recover, Die |
| Perception/relations | Immutable observations and target-specific stance | ObserveThreats, ResolveRelation |
| Behavior modules | Decide whether to approach, flee, defend, greet or trade | Propose plans using capabilities |

Keep execution state per NPC, action descriptions immutable and world access on
the simulation thread. Workers receive detached persistence snapshots only.
Capability availability and profile permission are distinct: an engine may support
firearms while a civilian profile does not initiate ranged combat.

The present Parallel action only supports speech/emote. Move+aim requires explicit
motion/facing/weapon resource ownership, not unrestricted parallel actions. Begin
with sequential stop/equip/aim/attack. Add compatible lanes when a use case needs
them, with cleanup on failure and preemption.

Physical condition must continue updating while a bot trades or talks. A lower
priority autonomous module cannot simply take over an exclusive lease. Add a
controller-owned threat interruption path that closes dialogue/trade safely and
then permits flee/defend actions. RECORDING_SALE requires explicit settlement
handling: interrupting presentation must not discard or duplicate a committed
sale. Threats, injury and death must not wait for the visitor perception gate.

## What A-life actually does

Evidence below is relative to installed ProjectALifeNPCs
`42.20/media/lua/` (the installed folder name is not our Java target version).

- It separates requested/effective pace, with endurance, flight and injury gates;
  its zombie shells use ALifePace/custom animation machinery and explicitly clear
  native run/sprint flags (`server/ProjectALife/Movement/ALifeMovement.lua:142`,
  `:202`). Borrow the condition/policy split; our IsoPlayer uses native player gait.
- Its client HordeRelations controller maintains custom prey/bite handling for
  zombie-shell NPCs. It includes bite windows, contact checks and custom injury
  effects (`client/ProjectALife/Combat/ALifeHordeRelations.lua`, including
  `closeIn` and `startBite`). That machinery is not a drop-in requirement for an
  IsoPlayer bot. Prefer native player integration where authority can be proven.
- Factions are catalog records containing general data, spawn policy, support,
  relations and member-module settings
  (`shared/ProjectALife/Records/ALifeCustomFactions.lua:33`, `:100`). They are more
  than faction membership in vanilla multiplayer UI.
- Faction-to-faction rules resolve explicit target faction/folder rules, then
  target bloc, unknown fallback and legacy defaults. Player rules use an explicit
  profession override or player default with optional profession/bloc shifts
  (`shared/ProjectALife/Records/ALifeRelationLayers.lua:128`, `:160`, `:184`).
- NPC-to-NPC status considers support/group/outpost ties, same faction, personal
  actor grudges and faction rules. Notably support ties and same-faction friendship
  precede actor grudges in this implementation; they are not a flat summed score
  (`server/ProjectALife/Decisions/ALifeRelations.lua:144`, `:159`).
- Toward players, authored stance, explicit orders/overrides, temporary truces,
  standoffs, fairness rules, personal hostility and reputation contribute through
  different resolver entry points. Do not claim one universal precedence list
  (`ALifeRelations.lua:32`, `:86`, `:185`).
- Reputation records are player/faction-specific; personal NPC grudges are
  separate. Multiplayer player keys normally use username, with an adapter port
  (`server/ProjectALife/Decisions/ALifeReputation.lua:85`, `:145`, `:250`). Our
  persistence should use a stable local identity rather than copy that choice.
- Reinforcement support has its own policy/type/size/maxCalls/answerers/vehicle
  settings. Friendly stance alone does not implement reinforcement spawning.

Hostility is a relationship. Aggression/retaliation is policy. Attacking is an
action. Injury is physical condition. These four dimensions should stay separate.

## Proposed database extension

V15 already supplies `npc_engine.profile`, `profile_revision`, `profile_module`,
`instance`, `runtime_state` and a scoped ownership epoch. Extend them through a
new migration after inspecting the actual migration head; do not rewrite V15.
The following tables/fields are proposed, not implemented or final DDL.

| Data | Suggested representation |
| --- | --- |
| Physical capability configuration | Versioned locomotion/combat settings referenced by profile revision: permitted gaits, typed speed values, aim/attack/recovery timing, weapon restrictions, damageability. |
| NPC physical state | Scoped instance child row for condition/endurance and versioned injury state; separately versioned inventory/item data for weapons/ammunition. |
| Faction identity/defaults | `faction` plus immutable `faction_revision`, including default stance toward players/unknown factions; optionally `bloc` for shared baselines. |
| Directed authored relations | `faction_relation(source faction revision, target faction, stance)`; optional separate bloc/profession override rows. A->B need not imply B->A. |
| Membership | Scoped NPC affiliation row referencing instance and faction; separate group/outpost membership if needed. Profiles describe behavior and should not be the sole source of affiliation. |
| Player reputation | Scoped `player_faction_reputation(player durable key, faction key, standing/stance, reason, updated_at, mutation_revision)`. |
| Personal grudges | Sparse directed `npc_relationship` rows from a scoped NPC to a typed stable NPC/player subject, with attitude, cause, expiry and mutation revision. Avoid all-pairs population. |
| Incidents | Bounded/retained `relationship_event` records with unique event identity, aggressor, victim, cause, time and outcome; idempotent application to reputation/grudges. |
| Support | Optional versioned support policy and allowed responder bindings, separate from faction diplomacy and personal memory. |

Use FKs for both NPC and player subjects, or a subject identity registry with
typed references; a free-form type/string pair alone loses referential integrity.
Decide whether player reputation follows account, character or life before
choosing its FK. Do not persist online IDs or recycled zombie object references.
Use absolute timestamps for durable expiry, monotonic timing only for live actions.

V15 module parameters and current HumanProfile parameter validation are explicitly
millisecond-only. Do not put speeds, distances, probabilities or capability flags
in `value_ms`. Use typed physical policy columns/tables (or deliberately extend
the parameter schema with units/types and validation). WALK/RUN code needs no
relationship migration; persistent configurable tuning can be a later slice.

Configuration publication belongs to administration; health, grievances and
inventory mutations belong to the engine. Keep their revision ownership separate.
Hydrate validated snapshots to memory; never query SQL per movement/attack tick.
Extend current epoch/revision fencing and mutation checkpoints for new state;
define a coherent inventory/health checkpoint and retry semantics before damage
and consumption become durable. Generic runtime memory currently has no durable
module codec and must not silently become an unversioned combat-state dump.

A proposed resolver should return stance plus its reason: e.g. temporary truce,
personal retaliation, group ally, earned faction reputation, authored faction
rule or neutral fallback. Exact precedence, friendly-fire escalation and expiry
are design choices for our engine, not implied by A-life's multiple functions.
Prefer neutral unknown defaults initially unless a profile deliberately chooses
otherwise. Keep engagement permissions and safe-zone/player protections separate.

Future migrations must include engine-role schema/table/sequence privileges for
`pz_server_01` and isolated validation under distinct migration/engine roles.
Nothing in this investigation reads or writes the live database.

## Delivery order and acceptance evidence

1. WALK/RUN physical execution on current indoor paths. Preserve walking output;
   test pace changes while moving, immediate updates, cancellation, corners,
   blocked routes and viewer reload. Compare two-client feet/speed/correction.
2. Condition-to-gait mapping, native injury playback and damageability policy.
   Prove limp/capped running without claiming full survival simulation.
3. Real equipment and stationary aiming for melee/firearms. Verify stance exit,
   hand models, late-viewer initialization and existing greeting/trade cleanup.
4. Stationary melee wind-up/commit/recovery against a zombie; exactly one hit,
   correct cancellation and consistent two-client effects. Then ranged attacks,
   ammo, misses, noise and projectile/tracer presentation.
5. Controlled zombie attraction and one damageable bot: targeting, ownership
   transfer, incoming-hit resolution, injury/death/corpse/loot with two clients.
6. Relationship observations and defend/flee/assist modules; add persistent
   reputation/grudges using confirmed stable identities and checkpoint rules.

Steps 4 and 5 can be interleaved for a small combat fixture; neither should
require an entire faction simulation first. Profile-level combat policy comes
after the operations have deterministic ownership and completion semantics.

The source supports native-protocol candidates for locomotion, injuries,
equipment and aim. Complete server-only combat with unmodified clients remains
an integration hypothesis until tested, especially zombie ownership and hits.
No engine code, database migration or live deployment changed for this report.

Related: [architecture contract](../NPC-ENGINE-ARCHITECTURE.md),
[implemented persistence](../NPC-PERSISTENCE.md),
[earlier A-life investigation](npc-alife-runtime-investigation.md).
