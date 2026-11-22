# Review 34/48: PlayerDamagePacket

Created 2026-10-28. Status: **approved/applied on 2026-10-28**. WorldDictionary review 33 is
applied. See the [tracker](../features/2026-10-27-subsystem-port-tracker.md).

## Inputs and proposal

- [Old vanilla](../../../42.21.0/decompiled/zombie/network/packets/character/PlayerDamagePacket.java).
- [Old patch](../../../42.21.0/src/zombie/network/packets/character/PlayerDamagePacket.java).
- [Target vanilla](../../decompiled/zombie/network/packets/character/PlayerDamagePacket.java).
- [Candidate diff](2026-10-28-merge-34-player-damage-packet.diff).

Retain ownership validation before payload mutation, adapted to the native
ConnectionPlayer composition. Do not restore PlayerID inheritance or old wire
fields. The old guard overlaps a changed native parser (one audit conflict).
Applied source matches the reviewed candidate after exact vanilla baseline `c0502d7`. Primary owner: NETWORK-GUARDS; shared owners:
connection identity/slots, damage synchronization and receive-direction handling.

## Upstream identity change

Old PlayerID carried online ID plus a player-index byte and allowed a server
online-ID fallback. Native ConnectionPlayer now carries a short connection slot
from clients, resolves it through the sender's getPlayerAt, and replaces its ID
with the resolved player's online ID. Server-to-client packets use online ID.
Base Player.parse clears the prior resolved reference before each parse, including
reuse after malformed input. Preserve this native protocol and its consistency
check rather than treating the old field layout as compatible.

Native sender-slot resolution already eliminates the old global-ID selection
path. Our remaining guard is an additional ownership/identity check and rejection
diagnostic, not the only protection against spoofing in this new protocol.

## Adapted guard

After player.parse, call the unchanged ApocBRPacketOwnershipGuard with packet
name, connection, player.getId(), internal sentinel (byte)-1 and player.getPlayer().
The sentinel is only a helper argument; no extra byte is read from the packet.
On the server it requires a nonnull resolved player, matching online ID,
connection.hasPlayer and the exact instance in one of the sender's four slots.
Native parsing already selected the requested slot. The helper's legacy sentinel
path provides an exact-owner scan without inventing a removed playerIndex field.

Keep native isConsistent afterward. Only if both pass, read/apply weight,
corpse-sickness and body damage. On clients, the guard allows authoritative server
updates and native field consistency still applies. Preserve the native writer,
JSON field, setData and consistency delegation. Existing IOException behavior
and partial mutation on malformed bodies are not redesigned by this proposal.

## Verification and remaining checks

The [candidate fixture](../../../tools/test_player_damage_port.py) compiles the
complete candidate packet, actual target ConnectionPlayer/Player and production
ownership guard against engine/connection/body/network-interface doubles. It
passed **26 assertions** covering owned slots, invalid/out-of-range/missing slots,
old online-ID-shaped spoof input, hasPlayer mismatch, null connection, packet reuse,
resolved-instance changes, client receive and native connection-slot writing.
Rejected cases do not consume/apply the body after the identity header.

BodyDamage uses a small fixture encoding; actual body serialization, packet
manager/capability checks, real split-screen connections, multiplayer receive
routing and malformed-body behavior remain native/runtime validation targets.
No ownership-helper API or production configuration changed. Exact-JAR
compilation and game runtime checks remain pending; no installation or external
service was changed.

User approved: "yes". Applied-candidate equality and 26 ownership assertions
passed again. Full packet/body/native validation remains pending. No installed
files were changed.
