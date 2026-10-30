# Review 36/48: PlayerStatsPacket

Created 2026-10-28. Status: **approved/applied on 2026-10-28**. Review 35 is applied.
See the [tracker](../features/2026-10-27-subsystem-port-tracker.md).

## Inputs and proposal

- [Old vanilla](../../../42.21.0/decompiled/zombie/network/packets/character/PlayerStatsPacket.java).
- [Old patch](../../../42.21.0/src/zombie/network/packets/character/PlayerStatsPacket.java).
- [Target vanilla](../../decompiled/zombie/network/packets/character/PlayerStatsPacket.java).
- [Candidate diff](2026-10-28-merge-36-player-stats-packet.diff).

Retain the ownership check before stats payload processing, adapted to native
ConnectionPlayer as in reviews 34-35. Start from target vanilla and add only the
adapted guard/import at the changed parser (one audit conflict). Applied source equals the reviewed candidate after exact vanilla baseline `006e586`. Primary owner: NETWORK-GUARDS; shared owners: connection identity,
stats/nutrition/body-field synchronization and receive direction.

## Native protocol and retained check

Keep ConnectionPlayer composition and its short connection-slot client header,
sender-bound server resolution and authoritative online-ID client resolution.
Do not restore old PlayerID inheritance or its extra player-index byte. Native
resolution already eliminates the prior global-ID fallback; the retained guard
adds exact identity/hasPlayer checks and rejection diagnostics.

After native field parsing, pass its resolved online ID/player and internal
(byte)-1 sentinel to ApocBRPacketOwnershipGuard. That sentinel adds no wire byte.
On servers it requires the resolved object in the sender's slots and hasPlayer;
on clients it permits authoritative server updates. Retain native isConsistent
delegation afterward. Reject before reading stats, nutrition, smoking time or
body main fields. Preserve native loading/version calls, write order and setData.

Retain the new PlayerStatsPacket error labels replacing old PlayerDamagePacket
messages. Existing IOException/partial-body behavior remains native; this does
not make malformed packet application atomic or replace packet-manager checks.

## Verification and remaining checks

The [candidate fixture](../../../tools/test_player_stats_port.py) compiles the
complete packet, actual target ConnectionPlayer/Player and production guard with
player/stat/body/connection/interface doubles. **42 assertions passed**, covering
all four owned sender slots, invalid/out-of-range/online-ID-shaped inputs,
hasPlayer mismatch, missing connection, packet reuse, authoritative client updates,
version251 dispatch and native writer/header ordering. Rejections stop after the
identity header without payload mutation in these cases.

Stats/nutrition/body fields have small fixture encodings. Actual serializers,
packet-manager capabilities/routing, real split-screen connections and native
multiplayer/runtime behavior remain validation targets. Exact-JAR compilation
is not claimed. No helper API, installation, configuration or external service
was changed.

User approved: "yes". Applied-candidate equality and 42 ownership/native-field
assertions passed again. Native multiplayer validation remains pending; no
installed files were changed.
