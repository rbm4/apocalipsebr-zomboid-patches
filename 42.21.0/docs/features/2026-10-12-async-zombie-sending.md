# Immutable asynchronous zombie sending (42.21.0 server)

The previous connection workers selected relay candidates from live world objects.
The main loop then waited for those workers and called `send` synchronously while
draining their results. In snapshot seq 461, sending consumed 26,803.508 ms of a
30,457 ms window; worker waiting consumed 0.033 ms. That capture does not locate
the internal send hotspot or prove savings from this implementation.

## Ownership and data flow

`NetworkZombiePacker.postupdate` retains authoritative ownership/bookkeeping on
the main thread. Connection selection, relevance checks, request consumption,
authorization hashes, connection timers, and zombie field preparation also remain
there. There are no background reads of players, zombies, ownership collections,
request queues, connection packet caches, or the relay grid.

The snapshot encodes individual zombie fields through the existing
`ZombiePacket.write` implementation. This is intentional: `ZombiePacket.copy`
still retains player references in nested `PlayerID` fields. Private defensive
copies of wire fragments freeze pathfinding, grapple, reanimated-body, enum,
optional-field, and string data without creating a second protocol serializer.
Workers assemble those fragments into complete packets and transmit them.

`ServerZombieAsyncSender` workers receive only immutable messages, captured packet
settings, and an endpoint holding the RakNet peer and session GUID. They reuse
thread-local direct buffers and call `SendRaw`, matching the exact-build vanilla
`PlayerDownloadServer` background transport pattern. This avoids both the shared
connection packet buffer and `RakNetPeerInterface.Send`'s shared Java buffer lock.
It cannot eliminate contention or congestion inside native RakNet/Steam.

Per-connection batches retain delete, authorization, synchronization send-call
order. Packet IDs, field bytes, priorities, reliability and ordering channels
remain vanilla. This is local submission order, not a new guarantee of ordered
delivery for packets whose vanilla reliability is unordered.

## Admission, recovery, and lifecycle

- One outstanding batch per connection includes queued work, active work, and an
  undrained completion. The main loop never joins workers or runs rejected work.
- `apocbr.zombiePacketWorkers` defaults to 6, clamped to 1..32.
- `apocbr.zombieSendCapacity` defaults to 64, clamped to 1..256. A semaphore bounds
  queued/running/completed batches collectively; the executor queue is bounded too.
  Main-thread admission rotates across connections to avoid fixed-order starvation.
- When admission fails, requests are not consumed and timers do not advance.
  Relevant delete IDs are retained per connection (bounded by the short-ID domain),
  force-update flags persist, and up to 300 pending relay references remain solely
  in main-thread bookkeeping. Excess relay candidates are not an unlimited backlog;
  sustained overload can reduce synchronization freshness.
- A synchronization packet selects at most 300 zombies and fits the vanilla
  1,000,000-byte buffer. Entries that exceed the remaining packet capacity return
  to requests. Retained deletions are split into packets of at most 300 IDs so the
  client's signed-short count remains valid.
- On a native failure/worker exception, a completion restores delete IDs and valid
  selected requests on the main thread and forces authorization and sync refresh.
  A partially transmitted batch can resend controls on retry; deletion is idempotent.
- Disconnected/delayed-disconnect connections retire their lane. Workers check
  cancellation before each send. A native call already in progress cannot be
  canceled; its endpoint stays bound to the original session GUID.
- `awaitWorkers` is retained for binary compatibility and now drains completions
  without blocking. It updates vanilla outgoing statistics on the main thread.
  The direct transport bypasses `PacketType.send`'s optional packet debug logging.
- Shutdown retires lanes and joins workers **before** `UdpEngine.Shutdown` destroys
  the peer. This join is confined to shutdown; a hung native send can delay shutdown.
- `zombiesToSend` records IDs admitted to a batch for the existing culling consumer.
  Busy connections have no new entry after that frame's clear. This is not an
  acknowledgment of delivery to the client.

The packet bytes are captured earlier than the old next-loop synchronous send.
Worker queuing can delay those bytes, and timers advance on admission rather than
native completion. These are intentional scheduling changes requiring multiplayer
validation. Snapshot capture/authorization can still be expensive on the main
thread; a speedup is not guaranteed solely by moving transport.

## Measurement and verification

Main-thread work: `network.zombies.dispatch` includes selection, control retention,
admission checks and its `network.zombies.snapshot` capture phase. Snapshot
contains its authorization child; other main-thread timings include existing
`auth`/`packetBookkeeping`/`packetPrepare`, and `network.zombies.completions`.
`network.zombies.awaitAndSend` now encloses completion draining in GameServer,
not a worker join or synchronous send. Old `send` and `workerWait` disappear.

Worker work: `network.zombies.async.send`, inclusive of assembly and transport,
with `async.assemble` and `async.transport` children. `async.queueDelay` is elapsed
time from submission to execution and is not CPU work. Worker phases overlap
simulation/main-thread timers and must not be added to tick cost.

Counters: `zombieJobs.submitted` now counts admitted frozen batches;
`zombies.async.snapshotBytes` counts complete admitted packet bytes;
`backpressure` counts rejected connection admission attempts; `failed` counts
unsuccessful/canceled batches; `exceptions` counts worker exceptions;
`packetCapacityDeferred` counts selected zombies returned because the packet was full.
These latter counters all have the `zombies.async.` prefix.

Run from this version directory:

```powershell
python tools/test_async_zombie_sender.py
python ../tools/test_player_packet_algorithms.py
python ../tools/test_zombie_auth_algorithms.py
./patchApocalipseBr.ps1 -DryRun
```

The focused fixture executes the production async sender and extracts production
main-thread capture/completion/lifecycle methods. Transport barriers exercise
backpressure, connection independence, defensive copying, FIFO admission,
cancellation, failure/retry, packet bounds, and delete splitting. Mock zombie wire
fragments validate the wrapper bytes; existing packet fixtures check the unchanged
vanilla zombie wire/parse/copy contract. Neither validates native transport.

The startup fixture preserves production static declaration order and constructs
the singleton in fresh JVMs with default, zero, negative, oversized, and malformed
configuration properties. Configuration fields must initialize before the singleton:
otherwise its instance sender sees the default zero capacity during class startup.

Live checks still required: heavy combat/ownership transfers, deletion and ID/pool
reuse, grapples and pathfinding, exploration, split-screen relevance, reconnect,
slow clients, shutdown under active transmission, and comparable telemetry windows.
Compare snapshot cost, tick rate, queue delay, transport, failures/backpressure,
packet drops, and synchronization freshness. No deployment is performed by tests.

Related report: [Player updates and zombie packet preparation: 42.21.0 investigation](../reports/2026-10-05-player-zombie-packet-hotspots.md).
