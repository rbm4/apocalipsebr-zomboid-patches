# NPC movement publication and stock-client prediction

Implemented 2026-11-05 for **42.22.1 dedicated servers**, using native player
packets and stock clients. Source diagnosis and remaining gait-calibration work:
[running replication investigation](../reports/2026-11-05-npc-running-replication.md).
The underlying [movement architecture](../../../42.21.0/docs/features/2026-10-21-npc-movement.md)
and [async worker ownership](../../../42.21.0/docs/features/2026-10-26-npc-async-worker.md)
remain the inherited design references.

## Behavior

Moving NPCs publish their current motion on each actual factory simulation tick
(target 100 ms / 10 Hz). Idle NPCs retain the 500 ms / 2 Hz routine heartbeat.
Stops, facing turns and gait/speed/animation changes still publish immediately
when observed by the simulation. Travel heading is also part of the compared
presentation, independently of facing. Short or zero prediction horizons no
longer force a moving actor to wait for the idle heartbeat to refresh its target.

This is bounded by the existing simulation gate; it creates no additional timer,
thread, missed-tick queue or catch-up burst. Slow simulation still produces fewer
updates. Native collision-clipped prediction can expire within a 100 ms tick,
and network delivery can introduce further delay. This change reduces the stale
target interval; it does not guarantee continuous playback under every condition.

Routine steady-motion packet frequency is up to five times the previous 2 Hz
rate per relevant viewer. Existing turn/state traffic can already exceed that
old routine rate. There is no new packet type or added wire field, and no client
Java patch is required. Real transport overhead and client behavior need runtime
measurement.

## Ownership and lifecycle

2026-11-05 authority follow-up: the [async ownership split](2026-11-05-npc-async-authority.md)
now advances physical movement/packet publication on the server thread. The
100 ms native tick remains gated by server progress; the independent worker runs
only detached planning. Earlier worker-thread descriptions below refer to the
pre-split implementation and are superseded by that guide's owner contract.

| Component | Responsibility |
| --- | --- |
| [ServerHumanFactory](../../src/zombie/apocalipse/humans/ServerHumanFactory.java) | Own simulation gating and publication decisions. Moving updates, idle heartbeat and observed transitions all use `shouldPublishMotion()`. |
| [ServerHumanMovement](../../src/zombie/apocalipse/humans/ServerHumanMovement.java) | Own physical integration, travel heading, gait/modifiers and collision-safe prediction. Its immutable `Snapshot` now includes position, floor, facing and travel heading in addition to gait, physical speed, animation and prediction units. |
| [ServerHumanTransport](../../src/zombie/apocalipse/humans/ServerHumanTransport.java) | Capture a snapshot and build one native movement packet lazily for a synchronous broadcast to all relevant viewers. Native `PlayerVariables` values and continuation flags are populated once in that packet. Per-viewer injury announcements retain their animation cache. |

No relevant viewers means no broadcast snapshot, packet allocation or prediction
collision queries. Viewer announcement reads only presentation modifiers instead
of doing unused prediction work. Existing single-viewer `idle()` calls remain
available for activation/late-viewer delivery. Traversal entry/exit/landing
callbacks and persistence-pause stops now use the shared broadcast path.

The snapshot and packet live only on the current call stack. They are sent
synchronously through the existing native writer and are not cached for a later
tick or actor incarnation. Removal/cancellation cannot leave a queued snapshot
that resumes stale motion. Traversal still disables ordinary extrapolation;
stopped motion uses a fresh type-0 packet with no movement/run/sprint flags or
positive prediction distance. Existing grapple continuation is retained.

Capture/publication now runs only on the factory's server owner after the async
authority follow-up. The dedicated NPC worker consumes detached planning data
and does not produce movement packets. A record alone does not make live native
reads atomic or thread-safe; this path relies on server-thread ownership.
Native send errors retain native handling; this change adds no retries, durable
delivery promise or new failure fallback.

## Preserved contracts and remaining work

The subsequent [live freeze investigation](../issues/2026-11-05-npc-lua-factory-deadlock.md)
confirmed a factory/Lua event deadlock during weapon equip with these classes
installed. Its blocked stacks are outside movement serialization.

Position integration, 150 ms elapsed cap, route remainder, speed resolution,
collision/occupancy checks, safe-corner clipping, eighth-tile quantization,
reserved presentation IDs and native packet layout are unchanged. Animation
injury/equipment/traversal announcements keep their existing protocols.

Walk/run/sprint speeds remain 2/4/6 tiles/s before condition modifiers. The client
still follows its prediction target using animation root displacement and native
correction. No native gait calibration has been measured or claimed. In-game
straight-line drift may therefore remain even with more frequent updates.

## Verification

- `py -3 tools/test_npc_movement_replication.py`: **146 checks passed**. Compiles
  the complete target movement engine, production modifiers/grid/edge types,
  extracted production transport/publication methods, exact native prediction
  and variable codecs, native player flag enum and native envelope writer.
  Exercises steady RUN/SPRINT, short/zero prediction, guarded heading, corners,
  collision rejection, variable/flag round trips, identical viewer payloads,
  one prediction calculation per broadcast, late/irrelevant viewers, modifier
  caches, stop/completion, idle heartbeat, traversal callbacks and slow-tick caps
  without catch-up packets. World/traversal, identity and connection services are
  explicit doubles; native animation and RakNet are outside this fixture.
- `42.22.1/patchApocalipseBr.ps1 -BuildOnly`: **331 sources / 958 classes**, Java
  25.0.4. Output retained under `42.22.1/artifacts/build_20261105_004655/`.
  The installed compilation JAR SHA-256 is
  `531daad77c92dcb93534e66ff37becf86dc119f55c62345c622c52021170723e`, matching
  the recorded 42.22.1/world251 baseline.
- Local documentation links/index coverage and `git diff --check` pass.

No deployment or live database change. Both startup modes use the same modified
factory path; live async/sync sessions, two observing clients, long fleeing runs,
repeated corners, injury/fatigue transitions, delayed delivery, reconnect and
network throughput still require in-game validation.
