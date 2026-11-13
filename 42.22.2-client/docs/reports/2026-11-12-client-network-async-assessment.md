# Client network asynchronous-processing assessment

2026-11-13 follow-up: [implemented schema 7 probes](../features/2026-11-13-client-network-telemetry.md)
and [binary wire-format/direct-codec assessment](2026-11-13-client-binary-protocol-and-decode-pipeline.md).

Investigated 2026-11-12 for 42.22.2-client, following the
[schema 5 capture](2026-11-12-schema-5-capture-analysis.md). Owning telemetry:
[main-thread telemetry](../features/2026-11-12-client-main-thread-telemetry.md).
Source investigation only: packet scheduling and handlers were not changed.

## What the measured network spike means

NETWORK wraps the entire `GameClient.instance.update()` call in GameWindow,
not only incoming packet parsing. Its observed 726.537 ms invocation therefore
cannot be attributed to one packet or to deserialization from the current data.
In the exact target source, the update includes incoming queue drainage,
disconnect/Lua handling, loading/delayed packet reinsertion, packet dispatch,
outgoing requests and inventory changes, remote-player timeout, voice updates,
vehicle anti-clipping, WorldStreamer.updateMain, connection readiness, chat and
statistics. Some work is guarded or periodic.

The incoming network transport already runs on UdpEngine's network thread.
`GameClient.addIncoming` copies ordinary received packets into ZomboidNetData and
queues main-loop work. Special chunk/profile paths are already handled there
directly. Moving transport again would not remove main-owner integration.

Source anchors under `42.22.2/decompiled/zombie/`:

- `core/raknet/UdpEngine.java`: network thread and addIncoming call.
- `network/GameClient.java`: update, addIncoming, mainLoopDealWithNetData and
  mainLoopHandlePacketInternal.
- `network/PacketTypes.java`: PacketType.onClientPacket.
- `network/ZomboidNetData.java` and ZomboidNetDataPool: mutable owned byte buffers
  and pool reset/reuse.
- `network/packets/character/ZombieSynchronizationPacket.java`: parse updates the
  client zombie timer and delegates into NetworkZombieSimulator.receivePacket.
- `network/packets/character/PlayerPacket.java`: parse delegates to player/
  prediction/variable readers; processClient updates live player/network AI state.
- `network/fields/character/ConnectionPlayer.java` and
  `network/fields/vehicle/VehicleID.java`: parsing resolves IDs through live
  player/vehicle registries, rather than merely decoding detached values.
- `iso/WorldStreamer.java`: owner-side streaming update work inside GameClient.update.

## Viable asynchronous boundary

A worker can prepare **allowlisted, detached** work such as verified pure binary
decoding, decompression or value-only validation. Which packet provides a useful
CPU target is not yet measured. Existing parse methods cannot be assumed pure:
some call world/network subsystems; packet instances may come from the mutable
per-connection packet cache. Splitting parseClient from processClient does not
by itself establish safe thread ownership.

A viable design needs:

1. Explicit ownership of copied bytes or an exclusively transferred buffer.
   ZomboidNetData currently returns to its pool after processing; a worker cannot
   retain its reader after discard/reset.
2. Detached immutable results and bounded queued bytes as well as job count.
   No live player, chunk, Lua, asset or renderer mutation on the decoder worker.
3. Ordered owner-thread integration for dependent messages, with a connection/
   session generation to reject results from disconnects/reconnects. Preserve
   loading, coop-delay and packet retry behavior.
4. Current-state validation at commit. Creation/destruction, inventory changes
   and dependent updates cannot be casually reordered or discarded. Coalescing
   movement updates requires packet-specific semantics, not a general drop rule.
5. Failure/cancellation/shutdown semantics and tests before concurrency claims.

Do not move GameClient.update or PacketType.onClientPacket wholesale to a pool.
The existing main loop also removes ArrayList entries while draining; large
batches may incur repeated shifting. A stable compaction strategy is a source
candidate, not proof that it caused the measured spike.

## Next measurement boundary

Add per-type packet counts, bytes, total/max handler time and backlog age/size,
plus separate timings for queue drainage, packet dispatch, outgoing inventory,
streaming and remaining maintenance. Record numeric/type identities only, never
payloads or credentials. Distinguish one expensive packet from a large backlog.
Then split parse/preparation from integration for the measured types and decide
whether worker decoding or owner-side batch processing offers useful savings.

Doubling RegionManager's frame-based callback interval reduces invocation
frequency but does not shorten an individual slow callback. Its observed long
callback included registered module work and had zero sampled CPU; investigate
nested work and thread waits before treating it as pure Lua computation. This
investigation does not alter that mod or its configured interval.
