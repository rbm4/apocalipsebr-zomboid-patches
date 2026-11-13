# Binary wire format, direct codecs and client decoder feasibility

Investigated 2026-11-13 for exact Build 42.22.2. Companion implementation:
[schema 7 network telemetry](../features/2026-11-13-client-network-telemetry.md).
Earlier context: [client async assessment](2026-11-12-client-network-async-assessment.md).
No multiplayer protocol, packet scheduling or packet handler was changed.

## What the engine sends today

Java packet objects are **not sent over the network**. The current application
wire representation is already binary. The ordinary path is:

```text
connection.getPacket(type)                # cached Java helper
packet.setData(values)
connection.startPacket()                  # lock shared writer; clear buffer
type.doPacket(writer)                     # byte 134 + short type ID
packet.write(writer)                     # binary fields
type.send(connection)                     # limits and transport policy
connection.endPacket(priority, reliability, ordering)
RakNet peer.Send(ByteBuffer, ..., GUID)    # native byte transport
```

INetworkPacket.sendToConnection also checks the optional cached-content hash
and cancels identical cached packets. PacketType.doPacket writes the numeric ID;
getId uses enum ordinal in this exact build. Field structure is defined by the
chosen packet's write/parse code, not Java object serialization metadata.

Receive follows:

```text
UdpEngine network thread receives a message
identifier 134 -> read short user packet ID
GameClient.addIncoming(id, payload reader)
  special chunk/profile paths handled directly
  ordinary payload copied into owned/pooled ZomboidNetData buffer
  enqueue MainLoopNetDataQ
main owner: GameClient.update -> dispatch by type ID
PacketType.onClientPacket -> cached/fresh helper -> parseClient -> validate -> apply
```

ZomboidNetData is a local queue/buffer owner, not an additional serialized wire
wrapper. UDP/RakNet delivery is message based, not an unframed Java byte stream.
It is already possible to select a decoder from the ID and parse fields directly.
The bytes cannot reveal an arbitrary unknown schema: the receiver must know the
packet ID/version, field order, widths, variable-length encodings and semantics.

## Object construction is not necessarily the expensive part

PacketsCache's constructor creates and retains one instance for each registered
handler. getPacket is normally a HashMap lookup. The receive path explicitly
creates fresh instances for postponed or shouldInstantiate packets; other paths
also contain direct construction. Thus allocations exist, but it is incorrect
to assume every received message builds a new packet object.

There are already direct writers: GameClient.sendBigWaterSplash writes ints and
floats into the connection buffer without making a matching outgoing packet
object. It still writes the existing header and calls the normal packet send
policy. Object-free codecs can therefore coexist with this binary protocol.

The observed 700 ms NETWORK update does not identify factory cost. It includes
incoming dispatch plus streaming, outgoing inventory and maintenance. Main-thread
integration can stutter even when transport is asynchronous: authoritative data
still has to update world state. Schema 7 now separates lookup/construction,
parse, consistency, logs and application instead of assuming which is expensive.

## Two distinct changes

**Direct codecs with the existing wire format:** feasible packet by packet.
Encode/decode the same primitive fields directly, retaining byte-exact headers,
field layout, validation and effects. Stock endpoints can remain compatible when
bytes and semantics are unchanged. Preserve connection writer ownership, hash
suppression where applicable, rate limits, reliability, ordering, compression/
fragmentation behavior and cancellation on exceptions. Calling native peer.Send
with arbitrary arrays would bypass some of that machinery and is not a drop-in
substitute. A byte array is not intrinsically faster than the existing reused
ByteBuffer; copying into another array can add work.

**A new wire protocol:** possible, but both endpoints need matching encoders,
decoders and a version/capability negotiation strategy, or a deliberate old-client
compatibility break. Changing just the client cannot reinterpret unchanged server
messages as a new format. Self-describing tags do not remove decoding work and
may increase payload size and parsing cost. A wire rewrite does not remove
game-world integration; measured justification is needed before such a migration.

## A useful worker decoder design

Keep transport responsive; do not move heavy decoding onto UdpEngine's receive
thread merely because bytes arrive there. For measured expensive packet types:

1. Copy or transfer exclusive payload ownership into a bounded, byte-accounted
   preparation queue. Never retain a pooled reader after its discard/reset.
2. Decode using an explicit packet-specific schema into detached values. Retain
   packet sequence/session generation and required dependency ordering.
3. Publish completed values to the main owner. Resolve player/vehicle/chunk IDs,
   validate current state, and integrate effects there. Reject stale connection
   generations after reconnect.
4. Preserve create/update/delete dependencies, loading/coop retries, reliable
   actions and failure behavior. Do not arbitrarily drop or reorder inventory or
   gameplay effects. Coalescing needs packet-specific semantics.

Current parseClient methods are not automatically worker-safe. For example,
ConnectionPlayer.parse resolves GameClient.IDToPlayerMap; VehicleID.parse reads
the vehicle registry; ZombieSynchronizationPacket.parse updates a client timer
and enters NetworkZombieSimulator. A detached decoder needs to separate those
actions from reading numeric fields, even if it uses no packet object at all.

## How the new capture decides the next optimization

- Large FACTORY with small PARSE/APPLY: investigate explicit instantiation/cache
  behavior and compare an object-free codec for those types.
- Large pure decoding portion of PARSE: candidate for worker decoding using the
  same wire format. Audit every field reader first.
- Large APPLY or a live-state portion of PARSE: optimize integration algorithms,
  batching or consistent owner commits; bypassing Java wrappers is insufficient.
- Large LEGACY: split that handler's decode from effects before choosing a worker.
- Large NET_STREAMING/NET_OUTGOING_INVENTORY: investigate those systems rather
  than treating the entire NETWORK bucket as incoming packet decoding.
- Large batch-minus-handler difference with high counts: inspect eligibility/
  list-removal costs. MainLoopNetData currently removes ArrayList entries during
  drainage, which can shift remaining entries repeatedly. This is a candidate,
  not measured proof that shifting caused the spike.

## Source evidence

Primary local exact-build sources under `42.22.2/decompiled/zombie/`:
network/packets/INetworkPacket.java, network/PacketsCache.java,
network/PacketTypes.java, core/raknet/UdpConnection.java,
core/raknet/UdpEngine.java, network/GameClient.java,
network/ZomboidNetData.java, network/ZomboidNetDataPool.java,
network/fields/character/ConnectionPlayer.java,
network/fields/vehicle/VehicleID.java and
network/packets/character/ZombieSynchronizationPacket.java.

Conclusion: a direct binary codec and asynchronous preparation are technically
feasible without changing the wire format. The proposed bytes-first transport
largely exists already. The new telemetry is needed to select the costly type
and distinguish decoding from live-world integration before implementing a codec.
