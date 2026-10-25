# Player retaliation (42.21.0, server only)

The attacked NPC can attack and damage its aggressor through the existing weapon
engine. Proximity, aiming, carrying a weapon and refusing to unequip do not start
combat. Refusal still warns and denies interaction. Incoming player damage to
NPCs is deferred: accepted attempts trigger retaliation without applying damage
or client-authored fall state to the NPC.

## Ownership and boundaries

`RetaliatePlayerModule` owns the transient aggressor and one cancellable weapon
execution. `RetaliationPort` supplies detached validity/visibility and execution.
`ServerHumanRetaliation` resolves live identities, enforces authority and composes
`HumanWeaponCombat` with the existing inventory and movement ports. Survival runs
first, retaliation second, then zombie combat, encounters, tasks and walking.
Emergency checks remain available during dialogue/trade suspension.

`ServerHumanPlayerAttacks` accepts attempts only from the live player's owning
connection with an equipped primary weapon or bare hands, bounded hit count,
same floor, range, clear attack line and PvP eligibility. NPC target resolution
uses the factory's reserved ID map only when ordinary target resolution failed.
NPCs are not registered as connected players. Duplicate attacker/weapon/shot
combinations within 500ms cannot spend ammunition or refresh retaliation twice.

The native patch baseline is the exact
`42.21.0/decompiled/zombie/network/packets/hit/PlayerHitPlayerPacket.java`.
The replacement resolves NPC targets after parsing and intercepts their server
processing. Accepted attempts consume the native attack once and skip victim
damage/fall processing and forwarding. Native packet annotations, anticheats and
ordinary player-versus-player processing remain intact. God-mode NPC attempts
are rejected; the guide keeps its existing protection.

## Execution, damage and cancellation

The module defaults to a 30-second retaliation window, two seconds of lost
visibility grace and one second between completed attempts. Attacks stop
immediately while visibility is lost. Invalid/dead/disconnected targets, changed
character identity, PvP changes or leaving territory end retaliation. Both actor
and target must remain inside the NPC's movement territory, same floor and
perception radius. PvP, safety, factions, non-PvP zones and protected players are
checked again before a strike. Bystanders block attack lines.

The existing weapon phases choose/equip weapons, approach, aim, wind up, consume
ammunition, reload and recover. `ServerHumanCombatNetwork` accepts either zombie
or player targets. Player damage uses native resolved weapon damage without the
zombie calibration multiplier. `ServerHumanPlayerHitPacket` builds the ordinary
PlayerHitPlayer wire packet, suppresses duplicate attacker ammo processing and
clears stale fall coordinates. Native local processing applies player body damage
and its normal damage synchronization; clients receive hit presentation.
The inventory remains the existing fixed, finite test inventory adapter.

Preemption, suspension and shutdown cancel owned movement/weapon work. Aggressor
identity includes native instance identity to reject recycled online IDs. Active
targets and monotonic deadlines are never persisted. Only the attacked NPC fights;
shared militia memory does not recruit other guards into combat.

`ATTACKED` is persisted through the existing relationship worker/journal as an
account/affiliation outcome. It revokes interaction permission and sets the policy
denial period without adding a refusal. Queue/database failure does not invent a
durable attack record; the current actor's combat decision stays on the simulation
thread. No JDBC runs in packet or combat processing.

## Migration and validation

Apply manager game-runtime V29 after V28 and restart/reactivate actors. V29
publishes new revisions above drafts for every current encounter-policy profile,
copies authored settings and doubles each existing warning duration once. The
standard warning becomes 16 seconds. The upper bound becomes 120 seconds to allow
doubling custom V28 durations. V29 registers `retaliate-player`, validates priority
and adds the `ATTACKED` outcome. Its new revisions disable god mode; `lobby-guide`
has no encounter policy and remains unchanged. Historical revisions stay immutable.

Run `python tools/test_human_retaliation.py`,
`python tools/test_human_weapon_combat.py` and
`python tools/test_npc_persistence.py --manager-test`, then compile against the
real game JAR with `powershell -File 42.21.0/patchApocalipseBr.ps1 -DryRun`.
The targeted fixture compiles the production packet patch and unmodified native
Safety anticheat, with stubs at native world/network/body-damage boundaries.
It checks attempt ownership, protected NPCs, duplicate swings, cancellation,
permission revocation, ranged ammo and damage dispatch. PostgreSQL tests use a
disposable localhost cluster with distinct migration and engine roles.

Live acceptance still needs two clients: melee/ranged attack attempts, attacker
health and body injuries, no duplicate damage, ammo/reload, safety/faction and
non-PvP changes, walls/bystanders, territory exit, dialogue interruption and guide
protection. No production migration or deployment is part of the automated checks.

2026-10-25 verification: retaliation fixture 38 assertions, shared weapon combat
116, encounter 31, dialogue 66 (plus EN/PTBR), lifecycle 92, ownership 56, trade
538, profiles 56, corpses 131, survival 107 and tactics 19 passed. Disposable
PostgreSQL/JDBC passed 87 assertions, targeted manager tests 45 and editor tests
six. Real-JAR dry compilation passed with 278 sources and 835 class files.
