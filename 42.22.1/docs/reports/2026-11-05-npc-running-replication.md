# NPC running: authoritative movement versus client playback

Date: 2026-11-05. Target: **42.22.1 dedicated server and stock clients**.
Status: source diagnosis followed by an applied publication/snapshot change on
2026-11-05. See the [implemented movement publication guide](../features/2026-11-05-npc-movement-replication.md)
for current behavior and validation. Native gait calibration remains proposed;
the reported visual clipping has not been reproduced or measured. No deployment.

## Finding

The current transport already sends running/sprinting flags, physical prediction
speed and animation modifiers. A missing running flag is not supported by this
source trace. There are two separate synchronization risks: animation-derived
client movement has no measured parity with the server's configured speed, and
the routine publication interval can exceed the collision-clipped prediction
horizon. Changing the wire structure is not required to address either one.

Ownership remains the existing [NPC movement contract](../../../42.21.0/docs/features/2026-10-21-npc-movement.md)
and [NPC architecture](../../../42.21.0/docs/features/2026-10-20-npc-engine-architecture.md).
Those guides describe the original implementation; this report verifies the
paths below against the selected target sources. The separate
[zombie sprinter investigation](2026-11-05-server-zombie-sprinter-speed.md)
concerns zombie authority, not these player-replica NPCs.

## End-to-end path

| Stage | Exact target behavior | Consequence |
| --- | --- | --- |
| Intent | [ServerHumanSurvival](../../src/zombie/apocalipse/humans/ServerHumanSurvival.java) requests `Pace.RUN` for retreat. [MovementModifiers](../../src/zombie/apocalipse/humans/movement/MovementModifiers.java) defaults to walk/run/sprint speeds of 2/4/6 tiles per real second, modified by conditions. | Fleeing uses the shared locomotion engine. There is no separate fleeing packet format. |
| Integration | [ServerHumanMovement.update](../../src/zombie/apocalipse/humans/ServerHumanMovement.java) integrates resolved speed against elapsed nanoseconds, capped at 150 ms per call, spending the remainder across cardinal waypoints. | Dedicated-server movement is numerical, independent of animation root displacement. |
| Animation projection | `applyPresentation()` sets gait flags, `WalkSpeed`, `StrafeSpeed`, and the network AI walk/run caches. | Physical speed and animation modifiers have a common resolver, but multiplying them by the same condition factor does not prove their base speeds agree. The defaults explicitly retain a RUN/SPRINT calibration TODO. |
| Publication | [ServerHumanFactory.tick](../../src/zombie/apocalipse/humans/ServerHumanFactory.java) gates simulation at 100 ms and routine transport at 500 ms. Facing turns and changes to gait/speed/animation trigger earlier publication. | Constant motion normally sends at 2 Hz, despite the simulation's 10 Hz target. Guarded travel direction is separate from facing and is absent from the compared `Presentation`. |
| Packet creation | [ServerHumanTransport.idle](../../src/zombie/apocalipse/humans/ServerHumanTransport.java) constructs native `PlayerPacketReliable`, writes the live position, clipped type-1 prediction, physical speed and gait/deferred-movement flags. [PlayerInjuriesPacket](../../decompiled/zombie/network/packets/character/PlayerInjuriesPacket.java) supplies walk/run animation caches when presentation changes or a viewer is announced. | The wire already has the fields needed for ordinary running. Keep reserved IDs, native serialization and viewer lifecycle intact. |
| Receipt | Native [PlayerPacket](../../decompiled/zombie/network/packets/character/PlayerPacket.java) parses and processes the update. [NetworkPlayerAI.parse](../../decompiled/zombie/characters/NetworkPlayerAI.java) copies prediction, applies flags and animation variables, and records the packet position as `realx/realy`. | The client has both authoritative position and a prediction target. |
| Extrapolation | Native [Prediction](../../decompiled/zombie/network/fields/character/Prediction.java) derives remaining time as `distance / (speed * 8)` and advances its target using packet speed until that time expires. | Packet speed is the target's velocity. It is not an absolute override of visible player movement speed. |
| Visible movement | Native [IsoPlayer](../../decompiled/zombie/characters/IsoPlayer.java) updates the prediction target, then calls [PathFindBehavior2.moveToPoint](../../decompiled/zombie/pathfind/PathFindBehavior2.java) with `getNetworkSpeedMul()`. The latter method moves using `getDeferredMovement()` length. `getNetworkSpeedMul()` provides roughly 0.9 to 1.1 correction for finite inputs. | Animation root displacement still determines how quickly the replica follows the target. A sustained base-speed mismatch can outgrow this correction. |

Native `IsoPlayer.calculateWalkSpeed()` selects `networkAi.runSpeed` for both
running and sprinting. `PlayerVariables.set()` chooses its variable subset from
the server action-state name, so it alone is not proof that movement variables
are transmitted for a server-owned actor. The existing injury packet provides
the separate cached walk/run values; retain that path.

## Failure mechanisms to distinguish

**Sustained straight-line drift:** if the configured 4 tiles/s running speed is
faster than that client's running root motion at modifier 1, the visible actor
falls behind even though its prediction target advances correctly. The actual
native rate is unknown here. A higher packet frequency cannot establish gait
parity. Native PlayerPacket has a teleport branch when the recorded positional
error exceeds 7 tiles, subject to vehicle/state guards; this provides a possible
large snap mechanism, not proof that the observed clipping reached that branch.

**Short prediction/stale targets:** prediction intentionally stops before route
turns and obstacles, with 0.15-tile headroom and eighth-tile quantization. At
4 tiles/s, a packet with distance 1 expires after 31.25 ms; distance 3 expires
after 93.75 ms. Both are shorter than the 500 ms routine interval. A zero-distance
packet is type 0. Until a subsequent turn/state change/heartbeat sends a new
packet, a client can therefore hold an old target while authoritative motion
continues. These numbers follow the native decode equation; they are not a
measured stall duration. Immediate turn publication reduces this window, and
the client may still be catching up to the old target.

**Corner/route transitions:** an integration step can consume an old segment and
some of a new segment before the next packet. A stock client receives the latest
position and one straight extrapolation direction, not the traversed polyline.
More frequent publication reduces the size of the missing segment; it does not
make the stock packet represent a full route. Guarded movement additionally
needs travel-heading change detection, because facing can remain unchanged.

## Proposed implementation boundary

1. Keep native packet layout and server authority. Produce one immutable motion
   observation per actor tick, including position, facing, travel heading, gait,
   physical speed, animation modifiers and the safe prediction horizon. Reuse it
   for relevant viewers instead of recomputing collision-sensitive prediction for
   each viewer. Capture and consume it under the current factory ownership;
   native reads used to produce it are not made thread-safe by a record.
2. Publish moving actors on actual simulation ticks (target 10 Hz), while keeping
   the 2 Hz idle heartbeat and immediate terminal transitions. Include travel
   heading in discontinuity detection. Do not lengthen prediction through unsafe
   corners to hide the interval. Assess added traffic before considering a lower,
   horizon-aware moving cadence. Preserve existing nonmovement flags, traversal
   and equipment/injury announcements.
3. Measure exact-build native player walk/run/sprint root displacement and actual
   movement on clear level ground. Record elapsed real and simulation time,
   gait, animation state/modifier, packet position/target, replica position and
   corrections. Measure steady gait separately from startup, turns and reactions.
   Fit a build-specific physical-speed/animation mapping only after these samples;
   do not substitute zombie rates or guess new animation multipliers.

The movement engine owns the observation; transport only serializes it; the
factory owns cadence. Behavior and retreat policy keep supplying intent. No
new worker, cross-thread native references or persistence fields are needed.
Snapshots must be incarnation-bound and discarded on cancellation/removal;
publication must not resume motion after traversal, death or a completed stop.

## Validation required for a behavior patch

Use production target movement/transport code and the native prediction
serializer/parser in deterministic fixtures. Exercise 100 ms simulation versus
500 ms heartbeat, truncated/zero prediction, straight RUN/SPRINT, corners,
guarded facing versus travel direction, route replacement, stop/cancellation,
traversal, late viewers and disconnects. Assert packet timing, flags, serialized
values and prediction lifetime rather than only checking a copied formula.

Then compile all target sources against the real 42.22.1 game JAR using
`42.22.1/patchApocalipseBr.ps1 -BuildOnly`. Test both async and synchronous startup
modes with two observing clients, delayed delivery, long straight fleeing,
repeated corners, injury/fatigue changes and simulation overruns. Compare client
error over time and stop settling; higher packet counts alone are not success.

The initial investigation changed documentation only. After user approval,
proposal steps 1 and 2 were implemented with call-local snapshots/packet reuse
and moving publication on each simulation tick. The focused 42.22.1 fixture
passes 146 checks and the real-JAR build passes with 331 sources / 958 classes.
See the implementation guide for fixture boundaries and retained artifacts.
Native gait calibration, multiplayer playback and traffic measurements remain
open. No game installation, live server or database was modified.
