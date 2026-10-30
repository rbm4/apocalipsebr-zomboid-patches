# Player retaliation (42.21.0, server only)

The attacked NPC can attack and damage its aggressor through the existing weapon
engine. Proximity, aiming, carrying a weapon and refusing to unequip do not start
combat. Refusal still warns and denies interaction. Authenticated incoming player
hits now damage NPCs through native body damage and armor, update logical health
immediately and trigger retaliation. Client-authored victim/fall state is discarded.

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
processing. Accepted attempts consume the native attack once and use
`ServerHumanPlayerDamage` instead of the connection-oriented native victim path.
Native packet annotations, anticheats and
ordinary player-versus-player processing remain intact. God-mode NPC attempts
are rejected; the guide keeps its existing protection.

`ServerHumanPlayerDamage` bounds hit count by weapon projectile/hit limits,
rejects nonfinite/invalid damage evidence and rolls native `DamageFromWeapon`
on the server for positive hit evidence. Armor and native PvP damage modifiers
apply. Zero-damage shove evidence adds no wound. Client damage magnitudes do not
set logical health. Native `ignoreDamage` evidence (shoves or ignored pellets) adds
no wound, exposed through a read-only getter on the exact native `WeaponHit` patch.
Standing shoves also force no damage when `isDoShove()` is true and
`isAimAtFloor()` is false, matching `IsoGameCharacter.Hit()` even if the packet
contains positive, nonignored damage evidence. Native packet preprocessing applies
these flags before damage evaluation. This covers bare hands and shoving while
holding a weapon; aim-at-floor ground attacks remain distinct. Accepted shoves
still trigger aggression, encounter and speech handling. Damage traces include
`standingShove` to distinguish this path from a weapon wound.

Standing-shove followup (2026-10-26): `python tools/test_human_retaliation.py`
passes 65 assertions, including serialized armed/bare-hands shoves with positive
nonignored hit evidence, preprocessing of attack flags and the distinct ground
attack. `test_human_speech.py` (75), `test_human_weapon_combat.py` (116) and
`test_server_humans.py` (92) also pass. `42.21.0/patchApocalipseBr.ps1 -DryRun`
compiles 296 sources / 874 class files against the installed target game JAR.
No deployment or live shove/respawn test was performed.
Native body loss becomes one `HumanMutation.Damage` and is
projected immediately; existing checkpoints and terminal death lifecycle own
persistence/removal. This does not add a respawn timer or persist individual native
body-part wounds. Presentation is reconstructed with bounded server-generated hit
force/direction and zero fall coordinates; the original client hit is not relayed.

Incoming checks use `pvp(attacker, victim)`. An admin attacker's god/invisibility
does not grant immunity to their victim. Retaliation separately refuses protected
or invisible targets. Combat-enabled NPCs advertise native Safety disabled at
construction; native Safety defaults to enabled, which makes a stock client filter
two safety-on actors before sending a hit. `ConnectedPacket` already serializes
Safety. Guide god-mode protection and global PvP/non-PvP rules remain intact.

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
`human_hit_wire_fixture.py` also imports native ID classes and extracts the exact
target-version parse/write methods for character/player fields, attack vars,
moving-object IDs, tracers, hits, fall and hit packet payloads. Melee and ranged
byte-buffer round trips exercise packet consistency and actual damage intake;
they include an optional tracer endpoint and connection-slot attacker resolution.
It checks attempt ownership, protected NPCs, duplicate swings, cancellation,
permission revocation, ranged ammo and damage dispatch. PostgreSQL tests use a
disposable localhost cluster with distinct migration and engine roles.

Live acceptance still needs two clients: melee/ranged attack attempts, attacker
health and body injuries, no duplicate damage, ammo/reload, safety/faction and
non-PvP changes, walls/bystanders, territory exit, dialogue interruption and guide
protection. No production migration or deployment is part of the automated checks.

## Receive diagnostics

Receive traces are enabled by default independently of `apocbr.npc.logLevel`
(whose default is ERROR). Disable explicitly with
`-Dapocbr.npc.playerHitTrace=false`. Lines begin `[ApocBR][NPC][PlayerHit]`.
Startup prints `server intake ready`. `PlayerHitPlayer.parse` logs receipt before
parsing, then NPC resolution and packet consistency/weapon resolution. Native
anticheats still run between consistency and intake; their own rejection warnings
remain authoritative. Intake reports ownership, equipped weapon, life/floor,
PvP/safety/faction/zone, range, obstruction and duplicate-shot rejection. Accepted
hits show the retaliation module, attacker protection, profile revision and NPC
god/safety flags. Committed damage shows loss and logical health; retaliation
reports execution start or unavailable contact.

`PlayerHitSquarePacket` is a second narrow exact-version native patch: it logs
miss receipt before parsing and retains native processing. A square packet means
the client reported no character hit. It does not authorize damage or retaliation,
and the server does not invent a hit from a tracer. If only square packets arrive,
inspect target eligibility, safety, god mode, aim and shot obstruction on the client.
No receive lines after the startup marker means neither patched hit entry point
was reached. Traces are bounded to eight lines per NPC per second; generic raw
receipt and square-miss lines each have a separate one-per-second limit.

This followup needs the new server classes and a restart/reactivation, with no
additional migration after V29 and no client patch or wire-format change.

Followup verification (2026-10-25): 58 retaliation/intake/wire/damage assertions,
116 shared weapon assertions, 107 survival, 92 lifecycle, 66 dialogue (plus EN/PTBR),
56 ownership and 131 corpse assertions passed. Target game-JAR dry compilation
passed with 281 Java sources and 838 class files. No live server deployment or
live shooting result is claimed by these isolated tests.

2026-10-25 verification: retaliation fixture 38 assertions, shared weapon combat
116, encounter 31, dialogue 66 (plus EN/PTBR), lifecycle 92, ownership 56, trade
538, profiles 56, corpses 131, survival 107 and tactics 19 passed. Disposable
PostgreSQL/JDBC passed 87 assertions, targeted manager tests 45 and editor tests
six. Real-JAR dry compilation passed with 278 sources and 835 class files.
