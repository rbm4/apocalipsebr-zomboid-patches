# Initial weapon combat backend

Implemented for server Build 42.21.0, behind the existing
[engagement policy and combat lease](NPC-ENGAGEMENT.md). This is an initial
outgoing zombie-combat implementation with a fixed test inventory adapter.
It requires live dedicated-server validation; fixture results are not a claim
that native client animation impact timing is already calibrated.

## Owners and execution

| Component | Responsibility |
| --- | --- |
| `brain/HumanWeaponCombat` | Per-engagement SELECT, EQUIP, APPROACH, WINDUP, RELOAD, RECOVER and terminal phases. No native references. |
| `brain/HumanCombatInventory` | Weapon snapshots and equip/spend/reload operations. This is the bridge to a future general inventory controller. |
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
utility, headshot or critical-hit doctrine. Melee reach uses native item reach
plus a small body allowance; firearm reach is bounded to 15 tiles. Selection,
inventory availability and execution are separate concerns.

## Approach and pacing

The planner searches at most 256 visited tiles, 32 route depth and 16 expensive
attack-line probes. It selects a reachable tile center within weapon reach with
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

Initial test phase delays are equip 250ms; melee windup/recovery 400/800ms;
firearm aim/recovery 600/700ms; reload 1500ms. Deadlines execute on the first
eligible simulation tick, without catch-up bursts. Before a strike, reach,
geometry and live sensor identity are revalidated. The phase changes to RECOVER
before ammunition/hit effects, preventing repeated polling from replaying a
strike. Cancelling before commit spends no round; cancelling afterward neither
refunds nor repeats it. All terminal paths stop owned movement and clear aim.

The client attack animation is triggered by the native hit/miss event at commit;
the preceding windup currently presents aim/ready state. A second synthetic
SwipeState attack is not sent in addition. Exact visual-impact alignment, rich
draw/reload presets and animation-speed tuning still require live work.

## Native packet and server mutation contract

Outbound serialization does not execute server-side consequences. The bridge
uses the same native packet **and its native local processing**:

1. Build native attack variables, target hit info, attack type and shot ID.
2. For a hit, choose a base damage value from the native weapon range and call
   native `target.processHitDamage` for native hit modifiers/force.
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
