# Review 29/48: ZombiePacket

Created 2026-10-28. Status: **approved/applied on 2026-10-28**. NetworkZombieAI review 28 is
applied. See the [tracker](../features/2026-10-27-subsystem-port-tracker.md).

## Inputs and proposal

- [Old vanilla](../../../42.21.0/decompiled/zombie/network/packets/character/ZombiePacket.java).
- [Old patch](../../../42.21.0/src/zombie/network/packets/character/ZombiePacket.java).
- [Target vanilla](../../decompiled/zombie/network/packets/character/ZombiePacket.java).
- [Candidate diff](2026-10-28-merge-29-zombie-packet.diff).

Keep prepareFrame/setPrepared separation and ordinary set delegation. Resolve
the sole conflict with native grappledBy.set(null), without reintroducing the
redundant walk-type conversion now performed by NetworkZombieAI.setPacketFields.
Preserve all target parsing/writing/copying and field APIs. Applied source equals the reviewed candidate after exact vanilla baseline `1df7643`. Primary owner: OPT-ZOMBIE; shared owners: packet preparation/reuse,
network field encoding, grapple authority and main-thread bookkeeping.

## Existing optimization and contracts

Ordinary set(chr) runs prepareFrame followed by setPrepared. prepareFrame records
identity, updates prediction/state/extrapolation bookkeeping and marks thumpSent.
setPrepared populates reusable packet fields/mind sync, resets flags and grapple
reference, rebuilds flags and records visuals without repeating bookkeeping.
The AI field filler receives chr.getWalkType, so a second enum conversion here
would be redundant. State resolution uses the already-applied native fallback.

The intended server frame flow prepares bookkeeping once, then fills/reuses packet
data across connections. Calling setPrepared alone requires correct current-frame
bookkeeping and lifetime identity. This is not permission to mutate shared packet
fields on connection workers. The later packer/preparation lifecycle completes
that ownership contract; this class alone cannot guarantee once-per-frame work.

## Upstream retained and remaining API dependency

Keep PACKET_SIZE_BYTES/getPacketSizeBytes at native 56. Retain final OnlinePlayer
grappledBy instead of PlayerID, using set(null) for resets and native parse/copy
behavior. Keep write(ByteBufferWriter), putByte/putUTF and ObjectID.write changes;
do not restore old write(ByteBuffer) or GameWindow string serialization.
Native parse, write, copy and size methods are unchanged by this proposal.

The old patched NetworkZombiePacker writes snapshots through
zombie.zombiePacket.write(snapshotBuffer), where snapshotBuffer is a ByteBuffer.
Its later port must adapt to the new writer API and field format; do not claim
the old snapshot path is compatible or add a legacy writer overload here without
review. Native packet-size/wire changes must reach all snapshot/transport paths.

## Verification and remaining checks

The [candidate fixture](../../../tools/test_zombie_packet_port.py) reconstructs
the candidate without creating an override. It passed **1,792 assertions across
256 flag combinations**, using extracted native/candidate population methods,
actual enum conversion and explicit AI/field/zombie doubles. It checks flag
population, identity/outfit/skin/walk values, ordinary versus split call counts,
prepared reuse and grapple/flag reset. Source checks keep native parse/write/copy/
size methods and OnlinePlayer field declaration identical.

This does not execute native AI, actual OnlinePlayer/ObjectID serializers,
ByteBufferWriter wire bytes, packet size/copy round-trips, worker ownership,
packet receive or packer snapshots. Complete integration with review 28, packer,
corpses/grapple authority and asynchronous sending remains pending. Exact-JAR
compilation and native multiplayer/runtime checks are not claimed. No installation
or database was changed.

User approved: "yes". Applied-candidate equality and 1,792 population/flag
assertions passed again. Snapshot/transport and actual wire validation remain
pending. No installed files were changed.
