# Server-controlled zombie interactions

Native client gait, correction and packet timing can be measured using the
[opt-in diagnostic client sampler](CLIENT-ZOMBIE-SAMPLER.md). It does not change
the production server controller or require a patch on ordinary clients.

Implemented for the 42.21.0 dedicated server. Requires live multiplayer validation,
especially attack animation transitions, movement pace and ownership handoff under latency.
No client Java patch, new wire format, database migration or deployment is included.
Enabled by default; `-Dapocbr.npc.zombieControl=false` disables acquisition on startup.

## Ownership

`zombie.apocalipse.zombies` contains five concrete classes and one NPC port:

| Component | Owns |
| --- | --- |
| `ServerZombieController` | Simulation-thread session registry, bounded acquisition, release, owner motion observations and native integration hooks. |
| `ServerZombieSession` | One zombie incarnation/target, pursuit progress, windup, one strike commitment and recovery. |
| `ServerZombieNativeAdapter` | Loaded geometry, collision checks, movement integration, native square membership and synthesized zombie packet fields. |
| `ServerZombiePath` | Asynchronous game polygon-path requests, main-thread result pump, copied fractional corners and cancellation. |
| `ServerZombiePace` | Captured animation gait selection and bounded owner-motion calibration for unsampled gaits. |
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
fields once per frame and overlays the controller position and Idle/WalkToward/Lunge/
Attack state before immutable async snapshots are created. All relevant clients
receive the same authoritative real position and collision-checked prediction. Packet flags preserve the
native field layout; stale client path goals are cleared. Clients relinquish
local simulation through the existing authorization-list protocol.

## Eligibility and pursuit

Factory composition supplies the victim port after NPC initialization. Dead,
inactive, persistence-paused, ghost and invisible NPCs are excluded. God mode
does not exclude prey: protected actors still exercise pursuit and attack timing.
Targets are sampled every 100 ms. Acquisition re-resolves actor identity before
committing. Sessions re-resolve the victim each frame.

Initial acquisition requires same floor, a loaded target tile and distance below
12 tiles. Native `LosUtil` sight qualifies; below six tiles close proximity also
qualifies without sight and starts a native route request after acquisition.
Contact still requires clear geometry. For routine acquisition a closer living,
visible, non-ghost real player prevents takeover; a visible NPC within 4.5 tiles
instead has immediate local priority even beside a closer spectator.
`ServerHumanCombat.begin` requests reciprocal authority after validating and
constructing its weapon execution. This direct combat intent bypasses routine
owner cooldown/player-distance arbitration and persists for that zombie session;
it does not require the next perception discovery or auth scan. An existing session
against the same NPC is promoted without resetting pursuit or pending strike timers.
Live validated combat updates retry the request after reaction protection, retaining
the current weapon execution rather than requiring another combat begin.
The request is best effort: ghost/invisible/inactive victims, invalid bodies,
native reactions, grapples and capacity still prevent it. Rejection does not cancel
the NPC's otherwise valid weapon execution. No role/key policy lives in this handoff.

Outside immediate proximity or explicit combat intent, an existing client owner
retains its normal two-second transfer cooldown; acquisition
runs on the first eligible auth frame thereafter without an extra sampling delay
or eight-acquisition batch queue. Active native reactions/grapples and the 128-session
capacity remain exceptions, rather than promises of unconditional takeover. This does not
give zombies the NPC survival system's obstacle-independent awareness.

Pursuit remembers an occluded target for five seconds (refreshed by close-range
proximity awareness), releases beyond 24 tiles,
and uses the game's asynchronous `PathfindNative` service (or configured Java
`PolygonalMap2` backend) when direct movement is obstructed. A separate walk-only
`Mover` request identity avoids stealing or cancelling the native character AI's
request slot. The controller pumps main-thread completions on the dedicated server.
Results are copied before native pool reuse, capped at 256 nodes, and rejected
for nonfinite coordinates or different floors. Pending requests stop motion until
a usable path exists; no synchronous tile-A* substitute is used.

Routes refresh at most every 750 ms; unchanged goals retain their path, pending
requests are not restarted, and moved targets/blocked segments can request a new
path. Fractional polygon corners remain fractional instead of being rounded to
tile centres. Every movement
segment rechecks loaded floor, static collision and tile edges. Native moving
square membership and the player spatial-query cache are updated with position.
There is no teleport through furniture or doors. Per-update travel is capped at
150 ms of elapsed movement; delayed ticks do not catch up with a large jump.

The first captured gait applies only to standing, active `walkType="2"`,
`speedType=2`, `speedMod=1` zombies without a native sprint override. Unobstructed
root/requested motion in `zombies-2026-10-24T00-56-19.046381200Z-511321780964200.ndjson`
measured **1.0475 tiles/s walking** and **1.5227 tiles/s lunging**. These rates use
elapsed real time; packet receipt timing and invisible-animation catch-up frames
do not increase them. Calibration cannot rescale this captured gait. Clear,
visible direct pursuit within 3.5 tiles selects the lunge speed and native Lunge
presentation, following vanilla `IsoZombie`'s `blunge` entry distance. Polygon
detours keep walking. This is a bounded proximity selection, not a full replica
of the native six-second lunge timer, turn/animation graph or animation blending.
All existing physical collision, contact, reaction and cancellation checks remain.

For unsampled gaits, pace calibrates measured real-position displacement from accepted nearby owner
packets in ordinary walking/pathfinding/lunge states. Three consecutive consistent
measurements are required; calibration expires after two seconds. Ratios below
.5 or above 1.25 of the native reference, inconsistent samples, excessive gaps
and reaction movement do not establish a gait. These bounds are conservative
tuning limits, not measured animation parity. Sampling does not delay ownership
takeover. Without a usable calibration, the fallback uses the native
`getZombieWalkTowardSpeed(.025, 1, ...)` helper at a 30 Hz reference rate; this
retains speedMod/inactivity/sprinter selection, but is not a claim of exact
animation-root-motion parity. Native speedMod and walk type are not overwritten.
Gait selection is reevaluated during movement, retaining per-body gait changes
and sprinter selection instead of multiplying captured sprinter speed by speedMod
again. Acquisition diagnostics distinguish `captured_walk_2`, `filtered_owner_sample` and fallback.
Vanilla `PathFindBehavior2` consumes `getDeferredMovement()` animation-root distance;
without an animation player that method returns zero. This server-only adapter
therefore uses the captured constants for the supported gait, filtered owner
displacement otherwise, and the helper as a reference fallback. A single capture
does not establish animation parity for other variants, speed modifiers or sprinters.

Movement spends one distance budget over up to 32 collision-checked .1-tile
substeps, carrying remaining distance across polygon corners. It stops .65 tiles
short of the final victim position. Short path nodes no longer discard a frame's
remaining movement or change the effective gait. Prediction remains clipped to
the next corner and does not extrapolate through stairs or missing floor.

Active sessions become routine relay candidates every **200 ms**, independent of
movement integration. Acquisition, moving/stopped transitions, attack start/end,
gait/running changes and turns over 30 degrees publish on the next packer frame
without waiting for that interval. A late frame queues one current update; it does
not replay missed intervals. Scheduling happens once before viewer fan-out; the
packer's per-viewer pending queues retain existing backpressure and relevance rules.
This is an enqueue cadence, not a guarantee of transport latency. Ownership
revocation/release and native hit packets retain their existing immediate paths.
The capture's 4000 ms upload interval is deliberately not used: server-controlled
NPC interactions use the nearby-viewer 200 ms interval.

Moving packets retain native prediction type 1. The captured gait uses vanilla
extrapolation's fixed **500 ms** horizon, decoupled from the server tick. Unsampled
gaits retain two measured simulation steps clamped to 100–300 ms. Both are bounded
to .6 tiles. Real coordinates remain authoritative; prediction
stops at the next waypoint or .65 tiles short of the victim and validates loaded
floor, stair exclusion, tile edges and static collision in .05-tile increments.
Idle, windup and recovery packets have no prediction. The native walk-speed helper
also determines the packet running flag for sprinters without mutating the native
flag or overwriting other boolean flags. Client-reported square membership uses
real coordinates, never the predicted destination, avoiding transient invalidation
of living perception contacts when prediction crosses a tile boundary.

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

There are at most 128 sessions and 512 motion
observations. Unused observations expire after ten seconds. There is no extra
population scan or native simulation worker. A session releases on invalid body
or incarnation, victim loss, player priority, native reaction, scope/memory loss,
ten seconds without pursuit progress (including repeated unavailable routes).
Temporary route failure stops presentation and retries every 750 ms while retaining
authority. Windup/recovery reset the pursuit progress clock. Normal owner selection
resumes, bypassing transfer cooldown. Release imposes a short retry delay.
Packer shutdown releases sessions and clears controller state. Release also cancels
the owned path request; generation and incarnation checks reject stale callbacks.

The initial navigation coverage is loaded, ordinary walkable tiles on one floor.
It does not execute zombie fence/window climbs, closed-door thumping or stairs;
blocked routes release to ordinary simulation. Crawling/prone/reaction actors
remain native. This keeps their current handling intact while the standing NPC
pursuit/attack path is validated.

Current stairs, a stair-containing native route, a floor-changing route or an
attempted step onto stairs yield `native_stairs` immediately instead of waiting
for the ten-second pursuit stall. This release preserves the NPC target and
restores ordinary client authority. Acquisition refuses a body currently on
stairs. Native stair completion and two-observer handoff still require live
validation; this does not implement server-side stair height/animation simulation.

Use `-Dapocbr.npc.logLevel=DEBUG` for `zombie control` records: acquisition target,
speed/source, position, windup, strike result and release reason. A protected
successful contact reports `would_hit_god_mode`; moving out of range reports
`contact_lost`, delayed strikes `strike_expired`, and a weapon interruption
`released; reason=weapon_hit`.

Acquisition records distinguish `npc_target`, `npc_proximity` and `npc_combat`.
Nearby rejected attempts log `zombie acquisition` with the reason, native state and
position at most once per two seconds per body. Diagnostics reuse the bounded
512-entry observation registry and expire after ten seconds without activity.
Alert turns (`ZombieTurnAlerted` / `turnalerted`) are ordinary acquisition states,
not protected hit reactions. The existing two-second post-hit protection remains.
A null local Java AI state is also permitted: client-simulated zombies may lack
that server state. Explicit physical reaction/grapple checks still run first, and
the existing replicated-packet reaction cooldown remains in force.

`FORGOTTEN` is the human sensor's three-second no-sighting retention expiry (or
explicit body invalidation), not an ownership release. Ownership sessions resolve
victims independently; this change does not alter visual memory or retreat policy.

## Verification

Captured-gait/cadence checks: 123 controller assertions plus the disabled-controller
case, 3037 authorization assertions and 45359 packet assertions pass. The real-JAR
`42.21.0/patchApocalipseBr.ps1 -DryRun` compiles 254 sources / 788 classes without
deployment. Compare one captured-variant zombie walking and lunging beside a
protected NPC at runtime; check acquisition, turns, contact, hits and release from
two unpatched viewers. Client-visible smoothing and latency still need that test.

`tools/test_server_zombie_control.py` compiles the actual controller, session,
native adapter/path-request lifecycle, native ownership manager and packet-preparation
service against isolated engine fixtures. It covers physical movement and square
membership, routing around furniture, windup/recovery, missed/expired/interrupted
strikes, identity changes, client authorization revocation, transfer rejection,
ownerless relay inclusion and handoff on hits. Source checks cover packer ingress
ordering and native hit-hook ordering. Async service fixtures supply explicit polygon
results; they verify fractional-corner retention, pending/result/cancel transitions,
pool reuse and Java-backend selection, not the native solver's live geometry.

Native `PlayerHitZombie` events already apply `WeaponHit` damage on clients as
well as reactions. The ordinary movement decoder initializes health when creating
a zombie but does not overwrite an existing client's health each update. Therefore
adding another damage event risks duplicate damage; exact health reconciliation
requires a separate authority design. This routing change does not fix stale
owner health reports overwriting server health after handoff.

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
