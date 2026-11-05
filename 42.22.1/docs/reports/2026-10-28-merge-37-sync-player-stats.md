# Review 37/48: SyncPlayerStatsPacket connection ownership

Created 2026-10-28. Status: **ownership guard approved/applied on 2026-10-28**.
The earlier native-retirement recommendation is withdrawn. Review36 is applied;
review37 source matches the reviewed guard candidate after exact baseline `0210374`.
No retirement was applied. See the
[tracker](../features/2026-10-27-subsystem-port-tracker.md).

## Corrected decision and threat model

The user clarified that the guard was required because manipulated packets could
resolve players from other sender connections and alter their damage/weight/stats.
Preserve connection ownership as an independent invariant. Recommend applying the
adapted guard, not retiring it based on capability authorization. The previous
recommendation confused permission to send a packet with ownership of its target.

CanModifyBodyStats and PacketAuthorization's capability/co-op-host check already
existed in 42.21.0. They are not a new 42.22.0 fix for this threat. A capability
check decides whether the sender may invoke a packet; it does not constrain which
player the supplied identifier resolves to. The old exploit's full authorization
bypass/root cause has not been established here, so no claim is made that native
42.22.0 closes that exploit.

## Inputs and candidate

- [Old vanilla](../../../42.21.0/decompiled/zombie/network/packets/SyncPlayerStatsPacket.java).
- [Old patch](../../../42.21.0/src/zombie/network/packets/SyncPlayerStatsPacket.java).
- [Target vanilla](../../decompiled/zombie/network/packets/SyncPlayerStatsPacket.java).
- [Corrected proposal record](2026-10-28-merge-37-sync-player-stats.json).
- [Adapted guard diff](2026-10-28-merge-37-sync-player-stats.diff).

Ordinary PlayerDamagePacket, PlayerEffectsPacket and PlayerStatsPacket now use
ConnectionPlayer: server parsing resolves a slot via connection.getPlayerAt.
That is native connection-scoped resolution, independent of capability checks.
We have retained the guard on those reviewed ports as well.

SyncPlayerStatsPacket instead uses OnlinePlayer, whose server parse resolves the
ID through GameServer.IDToPlayerMap. Native authorization may allow privileged
cross-player targeting, but that is not equivalent to the user's required
sender-ownership protection. Preserve our restriction; any deliberate trusted
administrator exception would need a separate design rather than silently
removing the check. No such exception is included in this candidate.

## Guard behavior and native changes retained

After parsing player identity and syncParams, validate the resolved online ID/player
with ApocBRPacketOwnershipGuard and internal sentinel -1. This adds no wire byte.
On servers require hasPlayer and the exact resolved instance in the sender's slots;
reject before stats/nutrition mutation. Clients accept authoritative server updates.
Retain native OnlinePlayer wire identity, missing-player diagnostics, stat masks,
nutrition mode, writer order and capability annotation. Both permission and
ownership checks remain in force on normal server dispatch.

## Verification and scope

The [candidate fixture](../../../tools/test_sync_player_stats_port.py) compiled
the full guarded packet, actual native OnlinePlayer/Player and production guard
with connection/stat doubles. **20 assertions passed**, including owned masked
stats/nutrition, cross-connection target rejection, invalid/missing IDs, hasPlayer
mismatch, null connection, client updates and online-ID writing. It does not run
the full capability manager, real stats serializer or network exploit replay.

The user approved implementation of the adapted guard. Applied-candidate
equality and 20 assertions passed again. Target source and tracking are updated;
no installed files, retirement or cleanup family changed. Full dispatch/role/
connection and native multiplayer validation remain pending.
