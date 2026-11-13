# Follow-up review 53: NPC native player-update transport

Created2026-10-28. Status: **approved/applied; live transport/runtime unverified**. Review52 is applied.
See the [subsystem tracker](../features/2026-10-27-subsystem-port-tracker.md),
[candidate diff](2026-10-28-merge-53-npc-transport.diff),
[verification record](2026-10-28-merge-53-npc-transport.json) and
[build checkpoint](2026-10-28-build-check.md).

## Native contract and proposed adaptation

The copied [transport](../../src/zombie/apocalipse/humans/ServerHumanTransport.java)
constructs PlayerPacketReliable for NPC movement, using old packet.id.set and
setPlayerIndex(-1). Target [PlayerPacket](../../decompiled/zombie/network/packets/character/PlayerPacket.java)
now exposes player:ConnectionPlayer, replacing old id:PlayerID. PlayerPacketReliable
inherits that field. The removed members account for two build errors.

Set packet.player with human.player and remove setPlayerIndex. Update only the
packet-version comment alongside these lines. Keep prediction, position, movement/
grapple flags, variables, injury/equipment presentation and sendToClient unchanged.

This preserves the target native wire contract rather than retaining an obsolete
index byte: old PlayerID wrote an online short plus player-index byte; target
[Player](../../decompiled/zombie/network/fields/character/Player.java) writes only
the short. Target [ConnectionPlayer](../../decompiled/zombie/network/fields/character/ConnectionPlayer.java)
set chooses online ID when GameServer.server is true. Stock clients parse that
short and resolve their client IDToPlayerMap. Review38's reserved768..1023 IDs
remain within the native accepted range; bot registration/ConnectedPlayer
announcement still provides client replicas. Those lifecycle paths are unchanged.

No receiving resolver is patched. Server-bound ConnectionPlayer still accepts
only connection-local indices0..3 and resolves through that connection, preserving
ownership checks. This candidate does not restore the old global player lookup.

## Verification and limits

[Focused fixture](../../../tools/test_npc_transport_port.py): **773 checks passed**.
It compiles the exact native Player and ConnectionPlayer sources with engine/network
doubles. Covers all256 reserved IDs: two-byte writer size, online-ID bytes and
client lookup/consistency. Also checks a forged server-bound online ID is rejected,
valid connection-local resolution, missing connection, and invalid client IDs-1/1024.
Native parse/write bodies run, while player registries, buffers and connection
ports are controlled doubles. Full PlayerPacket prediction/variables serialization,
RakNet, actual client NPC visibility and movement are not executed.

The303-source Java25 candidate-overlay compile removes both transport diagnostics,
leaving one ServerZombiePath addRequest boolean-argument error. No full successful
build or runtime claim. Applied on2026-10-28 after user approval; exact candidate equivalence and all773
focused checks passed after application. No installed game files,
live database or production configuration changed.

Decision: transport adaptation applied. Review54 proposes the pathfinding argument
using the old default and native callers. Event-control metadata and List-cache
work remain tracked; historical-data migration planning stays deferred until the
port is viable/running, as requested.
