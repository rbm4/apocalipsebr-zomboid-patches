# Server-controlled zombie interactions

Implemented for the 42.21.0 dedicated server. Requires live multiplayer validation,
especially attack animation transitions, movement pace and ownership handoff under latency.
No client Java patch, new wire format, database migration or deployment is included.
Enabled by default; `-Dapocbr.npc.zombieControl=false` disables acquisition on startup.

## Ownership

`zombie.apocalipse.zombies` contains three concrete classes and one NPC port:

| Component | Owns |
| --- | --- |
| `ServerZombieController` | Simulation-thread session registry, bounded acquisition, release, owner motion observations and native integration hooks. |
| `ServerZombieSession` | One zombie incarnation/target, pursuit progress, windup, one strike commitment and recovery. |
| `ServerZombieNativeAdapter` | Loaded geometry, collision checks, movement integration, native square membership and synthesized zombie packet fields. |
| `ZombieVictimPort` | NPC eligibility, current actor resolution and incoming strike boundary. |
| `ServerHumanZombieVictims` | Human adapter: persisted cheat policy, identity validation, native bite damage and logical health projection. |

The native zombie remains in the cell and square indexes. It is not replaced,
subclassed or detached from the world. The native connection owner is null while
the controller registry holds its server-authority session. `isRemoteZombie()`
and the ordinary server animation-variable restrictions are unchanged. This is
explicit synthesized execution, not activation of the full client zombie AI.

`NetworkZombieManager.updateAuth` retains/acquires control during its existing
population traversal. `moveZombie` cannot assign a client to an active session.
Acquisition removes the previous owner through the normal ownership index and
forces an authorization refresh. `NetworkZombiePacker.parseZombie` retains its
existing owner check before applying incoming movement, health or target fields;
late updates from the former owner are refused.

The packer's ordinary relevance grid includes active controller sessions, even
without incoming client updates. `ServerZombiePacketPreparation` prepares native
fields once per frame and overlays the controller position and Idle/WalkToward/
Attack state before immutable async snapshots are created. All relevant clients
receive the same authoritative sampled position. Packet flags preserve the
native field layout; stale client path goals are cleared. Clients relinquish
local simulation through the existing authorization-list protocol.

## Eligibility and pursuit

Factory composition supplies the victim port after NPC initialization. Dead,
inactive, persistence-paused, ghost and invisible NPCs are excluded. God mode
does not exclude prey: protected actors still exercise pursuit and attack timing.
Targets are sampled every 100 ms. Acquisition re-resolves actor identity before
committing. Sessions re-resolve the victim each frame.

Initial acquisition requires same floor, a loaded target tile, sight through
native `LosUtil`, and distance below 12 tiles. Closed doors and opaque walls block
sight. An existing real-player target wins; a closer living, non-ghost real
player also prevents acquisition. Native grapple ownership wins. This does not
give zombies the NPC survival system's obstacle-independent awareness.

Pursuit remembers an occluded target for five seconds, releases beyond 24 tiles,
and uses the shared bounded cardinal A* (256 expansions, depth 64) when direct
movement is obstructed. Routes refresh at most every 750 ms. Every movement
segment rechecks loaded floor, static collision and tile edges. Native moving
square membership and the player spatial-query cache are updated with position.
There is no teleport through furniture or doors. Per-update travel is capped at
150 ms of elapsed movement; delayed ticks do not catch up with a large jump.

Pace prefers measured real-position displacement from accepted nearby owner
packets in ordinary walking/pathfinding/lunge states. A short observation window
allows sampling before takeover. Teleports, excessive gaps and reaction movement
are excluded. Without a usable sample, the initial fallback uses the native
`getZombieWalkTowardSpeed(.025, 1, ...)` helper at a 30 Hz reference rate; this
retains speedMod/inactivity/sprinter selection, but is not a claim of exact
animation-root-motion parity. Native speedMod and walk type are not overwritten.
Subsequent speedMod changes scale the captured pace. Acquisition diagnostics
identify the pace and its source; live comparison is required to tune the fallback.

## Attacks and native hit reactions

Contact requires distance at most .72 tiles, matching floor, clear native sight,
no blocking tile edge and no physical obstacle. The first contact begins a
550 ms windup and faces the victim. Strike time rechecks contact. A miss or
accepted strike starts 900 ms recovery. The session commits once; a tick more
than 750 ms late expires the pending strike rather than landing it retroactively.
No client bite report or duplicate-report throttle is used.

Native weapon consequences remain responsible for zombie health, knockback,
stagger, knockdown and death. A narrow hook at the start of native
`IsoGameCharacter.hitConsequences` releases the controller **before** those
consequences run. This applies to player and NPC hits. The pending controller
strike disappears with the session; it cannot resume later. Ordinary client
ownership resumes immediately, with a two-second reacquisition cooldown.
Observed native reaction packets extend protection while the reaction continues.
Floor, ragdoll, stagger, grapple, climbing and other protected native states are
never replaced by controller pursuit or attack presentation.

This first implementation deliberately hands reactions back to their usual
simulator. It does not implement a second server knockdown/get-up simulator.
Existing native hit packets and legitimate player attacks against the zombie
retain their validation and consequence paths.

`ServerHumanZombieVictims` returns `would_hit_god_mode` before invoking native
damage for protected NPCs. Guide/Leon cheat configuration remains unchanged.
For a vulnerable actor it calls native `AddRandomDamageFromZombie`, computes the
body-health delta, commits a single `HumanMutation.Damage` and projects health.
Native wound/armor selection is reused. Full persistent wound/infection ticking,
NPC victim flinch/death presentation and native crowd drag-down parity are not
provided by this controller; detached NPCs do not participate in the native
surrounding-attacker square/state query. Keep existing god-mode testing until
those physiology and presentation paths are evaluated separately.

## Limits, cancellation and diagnostics

There are at most 128 sessions, eight acquisitions per auth frame and 512 motion
observations. Unused observations expire after ten seconds. There is no extra
population scan or native simulation worker. A session releases on invalid body
or incarnation, victim loss, player priority, native reaction, scope/memory loss,
unavailable route or 2.5 seconds without movement progress. Normal owner selection
resumes, bypassing transfer cooldown. Release imposes a short retry delay.
Packer shutdown releases sessions and clears controller state.

The initial navigation coverage is loaded, ordinary walkable tiles on one floor.
It does not execute zombie fence/window climbs, closed-door thumping or stairs;
blocked routes release to ordinary simulation. Crawling/prone/reaction actors
remain native. This keeps their current handling intact while the standing NPC
pursuit/attack path is validated.

Use `-Dapocbr.npc.logLevel=DEBUG` for `zombie control` records: acquisition target,
speed/source, position, windup, strike result and release reason. A protected
successful contact reports `would_hit_god_mode`; moving out of range reports
`contact_lost`, delayed strikes `strike_expired`, and a weapon interruption
`released; reason=weapon_hit`.

## Verification

`tools/test_server_zombie_control.py` compiles the actual controller, session,
native adapter, shared planner, native ownership manager and packet-preparation
service against isolated engine fixtures. It covers physical movement and square
membership, routing around furniture, windup/recovery, missed/expired/interrupted
strikes, identity changes, client authorization revocation, transfer rejection,
ownerless relay inclusion and handoff on hits. Source checks cover packer ingress
ordering and native hit-hook ordering.

`test_server_humans.py` covers the production NPC victim adapter, persisted god/
ghost/invisible policy, stale actor rejection and native-to-logical damage.
Existing authorization, packet, weapon, roaming, perception and survival fixtures
remain applicable. Compile with `42.21.0/patchApocalipseBr.ps1 -DryRun` against the
real game JAR before deployment.

Live scenarios: one unengaged zombie approaching a protected NPC; an NPC fleeing
after windup; player and NPC melee/firearm hits during windup; knockdown and death;
two observers and ownership transfers; disconnect during handoff; late viewer;
furniture/closed-door separation; and pace comparison for shamblers, fast
shamblers and sprinters. Verify native reactions finish without repeated controller
takeover and attacks remain visible while their damage is suppressed.
