# Initial weapon combat backend

Implemented for server Build 42.21.0, behind the existing
[engagement policy and combat lease](NPC-ENGAGEMENT.md). This is an initial
outgoing zombie-combat implementation with a fixed test inventory adapter.
It requires live dedicated-server validation; fixture results are not a claim
that native client animation impact timing is already calibrated.

Incoming zombie pursuit/attacks now have a separate temporary server-authority
controller. It yields to native weapon hit reactions and keeps persisted NPC
god-mode protection. See [server zombie interactions](NPC-ZOMBIE-CONTROL.md).

## Owners and execution

| Component | Responsibility |
| --- | --- |
| `brain/HumanWeaponCombat` | Per-engagement SELECT, EQUIP, APPROACH, WINDUP, RELOAD, RECOVER and terminal phases. No native references. |
| `brain/HumanCombatInventory` | Weapon snapshots and equip/spend/reload operations. This is the bridge to a future general inventory controller. |
| `brain/HumanCombatTactics` | Per-engagement spacing bands, sampled reaction decisions and retreat cooldown. Native-free, with injectable randomness. |
| `ServerHumanInventory` | Current fixed test adapter with real native items and finite ammunition; lazy initialization on first combat use. |
| `ServerHumanWeaponBackend` | Composes the phase engine, inventory port, native movement, targeting and packet/hit bridge. |
| `movement/CombatRoutePlanner` | Bounded cardinal search to a reachable clear attack position. |
| `ServerHumanCombatApproach` | Territory-scoped route selection, repath and WALK/RUN requests to `ServerHumanMovement`. |
| `ServerHumanCombatNetwork` | Native attack metadata, hit/miss packet construction, native local consequence processing, sound and endurance ingestion. |
| `ServerHumanTransport`, aim/hit/miss packet builders | Native wire delivery, current equipment/aim snapshots and late-viewer initialization. |

The existing server simulation thread owns world reads/writes and packet
construction. It runs NPC decisions/physics at the existing 100ms cadence;
routine movement packets remain at 500ms, with existing immediate movement
changes. No new asynchronous native-entity simulation thread is introduced.
Only detached persistence work remains on the existing worker/journal path.

## Fixed inventory contract

The phase engine actually calls the inventory interface. The first adapter
returns the requested fixture types:

| Role | Native item type |
| --- | --- |
| Melee | `Base.Crowbar` |
| Ranged | `Base.Pistol` |
| Spare empty magazine | `Base.9mmClip` |
| Reserve ammunition | `Base.Bullets9mm` |

Initialization creates all four item instances before inserting any into the
actor inventory. Missing definitions fail without adding a partial loadout.
Initialization happens once per presentation activation, not per attack. Removed
or broken weapons are not silently recreated. Other profiles receive no weapons
unless they invoke this combat backend.

The pistol begins with one inserted-magazine state and 15 total rounds including
the chamber (using its native capacity). The separate clip item is an empty test
spare; the bullet stack contains 30 reserve rounds. Reload fills to native total
capacity by debiting that reserve. This is a simplified test-controller reload,
not the complete vanilla eject/load-magazine/insert/rack timed-action workflow.
The initial reload delay is 1.5 seconds and currently holds aim rather than
replicating a dedicated reload animation. Jammed state can be cleared through
that same controller operation; this adapter does not randomly introduce jams.

Weapon descriptions carry real item ID, type, condition/availability, ranged
classification, reach, ammunition, reserve and test timings. The native bridge
rechecks actual held item identity and condition at commit. Fixed inventory and
its ammunition are transient: disposal/reactivation creates a new test fixture.
No inventory/weapon persistence migration is added, and no contact is persisted.

## Selecting the test weapon

```text
-Dapocbr.npc.combat.testWeapon=AUTO
```

Accepted values are AUTO (default), MELEE and RANGED, case-insensitive. This is
a temporary server-wide test option, not a millisecond profile parameter.

- AUTO chooses a usable loaded/reloadable pistol beyond three tiles, otherwise
  Crowbar, with fallback to the available other class.
- MELEE forces the Crowbar selection path and makes running approach easy to test.
- RANGED forces Pistol selection and finite-ammo/reload testing.

The selected weapon remains bound during its execution; it is reselected if
removed/broken or exhausted without reserve. The initial backend has one target,
no friendly/player target policy, no multi-hit attack and no advanced weapon
utility or headshot doctrine. Native melee/item firearm critical chances are rolled on landed hits
and serialized in the hit packet. Melee reach uses native item reach
plus a small body allowance; firearm reach is bounded to 15 tiles. Selection,
inventory availability and execution are separate concerns.

## Approach and pacing

`ServerHumanAttackLine` owns weapon-specific obstruction checks on the simulation
thread. Melee retains the polygon movement-collision query. Ranged uses native
42.21.0 `CombatManager` target-visibility semantics (`LosUtil.lineClear`), rejecting
Blocked and ClearThroughClosedDoor while allowing clear visibility over furniture
and native clear-through-window results. It also verifies loaded same-floor
segments and preserves intervening living-character checks. This does not add
projectile penetration, window destruction or a full ballistic simulation.

Approach/footwork, candidate firing positions, windup and the final hit commit
use this shared rule with the selected weapon type. Physical navigation still
uses collision geometry: permitting a shot over a table never permits walking
through it. A new obstruction prevents the hit from committing; ordinary approach,
failure and cancellation rules remain in control. No profile, migration, protection
flag or client protocol change is required. The weapon fixture covers ranged fire
across movement-only obstruction, melee refusal, opaque walls, closed doors,
windows, intervening characters, unloaded squares and final-hit revalidation.
The native reference is `decompiled/zombie/CombatManager.java` target LOS handling;
no vanilla replacement baseline was introduced. Live testing remains necessary
for the exact sprites and furniture used by the server map.

The approach planner searches at most 256 visited tiles, 32 route depth and 16 expensive
attack-line probes. It selects a reachable tile center inside a preferred spacing band with
a clear attack lane. All route tiles/edges must remain inside the loaded home
building/floor and satisfy the existing physical movement checks and occupancy.
It never walks into the target's occupied square or teleports the actor.

Approach requests RUN beyond 3.5 tiles and WALK nearer the target; existing
movement conditions still determine effective pace and speed. The same resolved
state drives position, prediction and native movement flags. Target displacement
over 1.5 tiles or completed/blocked movement permits replanning after a 700ms
gate. Three failed route attempts produce UNREACHABLE. Native geometry checks
conservatively block obstacles/windows rather than implementing glass shooting,
breaching or door-opening. Same-floor indoor routing only is intentional.

An additional bounded sampled lane check rejects intervening character bodies
found in loaded square membership. This is a conservative initial obstruction
check, not complete ballistic collision or a general friendly-fire simulator.
Detached NPCs are not ordinary square-list occupants. Wider ballistic and
cross-NPC collision semantics remain future extensions.

### Local combat tactics and exposure windows

`HumanCombatTactics` chooses HOLD, CLOSE or WITHDRAW independently of physical
route execution. Ranged spacing is 4.5–7 tiles for the test Pistol, bounded by
the weapon's actual reach for shorter-range weapons. Melee closes to 90% of
reach and normally reacts to pressure below 55% of reach. During late melee
recovery that pressure threshold becomes 85%, allowing a short disengaging step.
These are initial engine defaults, not additional database profile parameters.
Immediate danger also uses the [urgent awareness and footwork rules](NPC-TACTICS.md),
which bypass sampled reaction latency for additional nearby attackers.

The controller samples decisions every 300–650ms; it does not reroll or dodge
on every 100ms physics tick. An initially surprised actor waits for that first
decision. A retreat request consumes a 1.6–2.4s cooldown even if the route is
blocked. Within that cooldown the actor may stand and fight inside its preferred
minimum range. Range bounds provide hysteresis instead of chasing an exact
distance, and one cached retreat order can start at most one leg.

Withdrawal searches at most two cardinal steps (one for melee) and scores
distance against nearby obstacle-independent zombie awareness. It rejects
approach to the selected zombie unless whole-crowd placement improves.
Native movement validates every traversed edge.
The leg walks, lasts at most 900ms and does not continue indefinitely until an
ideal distance is restored. Known awareness zombie centers also prevent selecting
tiles within 0.65 tiles of them; this is a conservative coarse placement check,
not complete collision prediction. Scores use bounded current sensor contacts,
not hidden world-wide knowledge. Improved whole-crowd placement may justify a
step closer to the selected target; physical body clearance remains required.
A blocked retreat holds position; it does not
teleport, walk through obstacles or fail the whole combat execution merely
because no escape exists. Normal unreachable approach failures still apply.

Guarded retreat retains aim toward the target while travel and prediction follow
the route. Guarded pace is capped at WALK / 1.3 tiles per second, subject to
slower physical restrictions. Guarded windup/recovery allow footwork and attacks
while moving, with range and clear-lane checks retained. Unguarded movement still
resets windup and retains the initial 350ms recovery movement lock. Weapon-owned
equip/reload may perform guarded defensive footwork. Survival-owned escape routes continue independently
of defensive weapon executions; cancellation cannot stop those routes. Active
obstacle traversal suppresses attacks. See [tactical ownership, opening creation
and elevated exposure](NPC-TACTICS.md) for the coordination contract and runtime
animation checks.

Windup and recovery each sample a bounded 80–120% multiplier when entering the
phase. Polling never resamples or shortens the deadline, and missed ticks never
replay multiple attacks. For the test timings below this gives 320–480ms melee
windup, 640–960ms melee recovery, 480–720ms firearm aim and 560–840ms firearm
recovery, before simulation-tick quantization and repositioning delays.

These constraints intentionally limit competence; they are not random forced
damage or guaranteed failure. A corner, surprise, slow injured movement, multiple
pressing zombies, reload or attack commitment can leave the actor exposed.
Actual incoming damage still requires the separate native authority/health work
described below; the actor is currently created with god mode enabled.

Reference: the locally installed A-life 42.20 sources use desired-range bands,
cooldown-limited kite legs and ready delays in
`server/ProjectALife/Combat/ALifeCombat.lua:986`, ranged recovery jitter at
`:1186`, and melee weapon/tired-tempo recovery at `:1249`.
`ALifeCombatApproach.lua:671` / `:692` select kite/backstep points;
`:911` samples surrounding zombies for melee footwork. Those mechanisms motivate
this controller. They do not establish that A-life has an explicit general
"designed failure" system, and its zombie-shell animation/damage code is not
copied into our IsoPlayer backend.

## Equip, aim, attack and recovery

Actual primary/secondary hand items and `useHandWeapon` are set through the
native player fields. Both-hand weapons use the same native item reference in
both hands. Native `Equip` packets serialize the equipped items and their state;
native `State` packets enter/exit `PlayerAimState`.

Equipment snapshots are cached per viewer using item identity, condition, ammo,
chamber and jam state. Changed snapshots publish immediately; repeated unchanged
snapshots do not send. A new/reloaded viewer receives the current equipment and
active aim state. Disconnect and disposal clear viewer caches. Updating an item
reuses the native Equip serialization path. We do not assume that a remote held
item is also fully enumerated in the client's inventory container, as required
by some inventory-specific update packets.

Base test phase delays are equip 250ms; melee windup/recovery 400/800ms;
firearm aim/recovery 600/700ms; reload 1500ms. Windup/recovery vary as above;
equip/reload do not. Deadlines execute on the first
eligible simulation tick, without catch-up bursts. Before a strike, reach,
geometry and live sensor identity are revalidated. The phase changes to RECOVER
before ammunition/hit effects, preventing repeated polling from replaying a
strike. Cancelling before commit spends no round; cancelling afterward neither
refunds nor repeats it. All terminal paths stop owned movement and clear aim.

The client attack animation is triggered by the native hit/miss event at commit;
the preceding windup currently presents aim/ready state. A second synthetic
SwipeState attack is not sent in addition. Exact visual-impact alignment, rich
draw/reload presets and animation-speed tuning still require live work.

## NPC damage calibration

The synthetic actor starts without authored weapon proficiency. Native 42.21.0
applies a 0.3 baseline weapon-level multiplier and an additional 0.15 melee damage
multiplier. The old bridge also disabled every critical. This explains prolonged
low-damage fights; the bridge now uses explicit NPC output calibration of
1/0.3 for ranged and 1/(0.3*0.15) for melee. Calibration happens after native
force/modifier calculation and before packet delivery; native consequences still
apply exactly once. Native knockback, critical multiplier, ground-hit modifiers,
relative proficiency bonuses and server changes from default combat multipliers
retain their effects. No zombie health cap, scripted kill or damage floor is used.
This is NPC balance tuning, not a claim of complete native player attack parity.

At normal 1.8-2.1 zombie health, healthy base-weapon rolls should generally produce
about 1-5 landed hits with the fixed Crowbar/Pistol loadout. Misses,
server combat modifiers, modded weapons and altered zombie health can extend that.
Melee criticals use the native player chance; ranged criticals use the item's
authored chance. Native ranged chance calculation requires client lighting and
cannot safely use a detached player's index -1. Full aimed-ballistic/lighting
modifiers remain outside this initial adapter. Both use native critical damage
multipliers and neither guarantees a critical. DEBUG commit logs
include target ID, critical result, packet damage and authoritative health before
and after the native hit. Packet damage is before consequence reductions and may
therefore differ from observed health loss. No migration or live flag change is
required. Verify actual live health deltas before further tuning.

## Native packet and server mutation contract

Outbound serialization does not execute server-side consequences. The bridge
uses the same native packet **and its native local processing**:

1. Build native attack variables, target hit info, attack type and shot ID.
2. For a hit, roll damage from the native weapon range, apply melee weapon/strength
   modifiers and call native `target.processHitDamage` for modifiers/force. Roll
   critical chance (native melee/item firearm) and preserve its flag through serialization.
   `HumanCombatDamage` calibrates the resulting packet damage for NPC balance.
3. Construct native `PlayerHitZombie` / `WeaponHit`, or `PlayerHitSquare` for a
   miss. Broadcast immediately to relevant announced viewers.
4. Invoke the native HitCharacter local processing path once on the server.
   It runs `WeaponHit -> target.Hit`, native maintenance, hooks and death logic.
   No synthetic `setHealth`, separate custom wound subtraction or fake socket
   receive/ownership path is used.
5. Feed native endurance expenditure into the central `HumanMutation.Adjust`
   store and refresh the native endurance projection. Publish changed equipment
   condition/ammo and firearm sound; emit one authoritative gunshot world sound
   independently of viewer count. Native hit consequences may also emit impact
   sounds/stimuli, as in the normal hit path.

The local wrapper is named `processClient` in native `HitCharacter`; vanilla's
server receive path also calls that wrapper after relaying. Here it is invoked
directly on a constructed, validated packet object. No client parse, anti-cheat
bypass, player-map insertion or fabricated `UdpConnection` is introduced.
`GameServer.sendHitCharacter` expects a real originating connection, so the
outbound loop uses `sendToClient` instead of passing a fake/null sender to it.

### Concrete firearm victim reactions

The attacker retains `ZombieHitReaction="Shot"`, the ordinary firearm attack
marker. The victim must carry a **resolved** animation: the current bridge uses
`ShotChestL` for a standing firearm victim and `Floor` for a prone firearm victim.
Melee retains `HeadLeft`. This is presentation owned by `ServerHumanCombatNetwork`,
not a new zombie-controller timeout or reaction override.

Vanilla `CombatManager.processHit` resolves the generic `Shot` marker before
`PlayerHitZombiePacket` snapshots its victim. Our synthetic bridge previously
placed the unresolved marker directly on the victim. The native `ShotDefault.xml`
is an inheritance template without an animation clip, while `Shot/ShotChestL.xml`
selects `Zombie_ShotChestL`. Hit-reaction transitions depend on
`ActiveAnimFinishing`; a missing playable clip can leave the visible pose stuck.
Both pre/post-processing of the hit packet reapply its captured victim reaction,
so resolution must happen before packet construction.

This fix deliberately chooses an existing non-fall standing animation without
calling vanilla's full firearm resolver, which rerolls critical chance using
client lighting/ballistics. Damage, critical outcome, hit/miss selection, ammunition
and native hit/release ownership remain unchanged. It does not reproduce the
native direction/body-part reaction distribution or introduce a new forced fall.
Repeated shots and prone targets use the same native packet and cancellation path.

Regression: `tools/test_human_weapon_combat.py` has 116 assertions, including
packet-time reaction capture, standing/prone firearm hits, retained critical
outcomes, unchanged melee reactions and no victim reaction on misses. Live
unpatched-client confirmation of reaction completion remains required.

Native server `PlayerHit.attack()` normally triggers `OnWeaponSwingHitPoint`
for ranged hits, which vanilla Lua uses to spend ammo. Our server-only packet
builders suppress that callback because the inventory port has already spent
the round. On the wire they are ordinary vanilla packet types: clients decode
the original classes and keep normal attack/muzzle presentation. This also
keeps misses and hits under the same inventory authority, avoiding double spend.

Initial Pistol hit chance uses the native weapon's base hit-chance field, clamped
to 5..95%. This is an explicit test approximation, not full native aiming,
spread/projectile/penetration simulation. Misses consume ammunition and present
a shot through PlayerHitSquare without a zombie hit/reaction. Hits use the
native consequence path, but the initial hit selection/base calculation does
not reproduce every native `CombatManager.attackCollisionCheck` branch.

42.21.0 authority: `decompiled/zombie/network/packets/hit/HitCharacter.java`,
`PlayerHit.java`, `PlayerHitCharacter.java`, `PlayerHitZombiePacket.java`,
`PlayerHitSquarePacket.java`, `network/fields/hit/WeaponHit.java`,
`characters/IsoGameCharacter.java:6787`, `:6862`, `:6928`, `EquipPacket.java`,
`ai/states/PlayerAimState.java`, and vanilla shared
`TimedActions/ISReloadWeaponAction.lua:onShoot`. No vanilla source class was
replaced to implement this backend; only existing/new custom engine adapters.

## Scope, deployment and verification

The initial target is a visible zombie inside the militia's home building.
Bot protection/god mode and incoming zombie/player-hit resolution remain as
before: outgoing attacks do not establish reciprocal zombie damage support.
Native state/packet behavior must be tested with one and two actual clients.

V21 still owns the policy profile. Manager V22 updates the module implementation
version/description and profile header description to stop describing this
backend as unsupported; it changes no published policy parameters, runtime
tables or grants. No actor is automatically created, and no live migration or
deployment is performed by this work.

`tools/test_human_weapon_combat.py` compiles production phase/controller/approach/
packet-bridge code with engine stubs. It checks cancellation/one-shot commit,
finite resources, reload insertion, blocked geometry, intervening bodies,
incapacitation, real inventory-port calls, running movement packets, hit/miss
delivery to multiple viewers, one local hit-processing call, no duplicate server
ammo callback and late-viewer equipment. Existing NPC regressions check shared
behavior/lifecycle compatibility. Stubs validate adapter calls and scheduling;
real game-JAR compilation validates signatures. Neither proves native animation,
damage balancing or streaming behavior in a running server.

Tactical regression cases additionally cover distinct preferred bands, surprise
latency, retreat cooldown/exposure, sampled timing extremes, recovery movement
lock, windup cancellation for footwork, a bounded walking retreat, cornered
escape failure and pressure-aware route scoring. Real-client checks must include
a pursuing zombie, several zombies pressing from different sides, a wall behind
the bot, injury/endurance slowdown, reload under pressure and client aim/travel
presentation. No claim that zombie hit probability is balanced follows from the
isolated fixtures.
