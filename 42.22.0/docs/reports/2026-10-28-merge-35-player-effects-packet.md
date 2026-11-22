# Review 35/48: PlayerEffectsPacket

Created 2026-10-28. Status: **approved/applied on 2026-10-28**. Review 34 is applied.
See the [tracker](../features/2026-10-27-subsystem-port-tracker.md).

## Inputs and proposal

- [Old vanilla](../../../42.21.0/decompiled/zombie/network/packets/character/PlayerEffectsPacket.java).
- [Old patch](../../../42.21.0/src/zombie/network/packets/character/PlayerEffectsPacket.java).
- [Target vanilla](../../decompiled/zombie/network/packets/character/PlayerEffectsPacket.java).
- [Candidate diff](2026-10-28-merge-35-player-effects-packet.diff).

Keep ownership validation before effect payload processing, adapted to native
ConnectionPlayer composition as in review34. Do not restore PlayerID inheritance,
old extra index byte or removed accessors. The audit reports one conflict around
the rewritten parser; this candidate starts from target vanilla and adds only
the adapted guard/import. Applied source equals the reviewed candidate after exact vanilla baseline `2c2180d`. Owner: NETWORK-GUARDS.

## Native protocol and retained guard

Native ConnectionPlayer reads the sender's connection-slot short on server receive,
resolves through that connection, and stores the resolved online ID. On clients
it resolves the authoritative online ID. Native sender-slot resolution already
removes the old global-ID fallback; retain our additional identity/ownership check
and diagnostics rather than claiming it is the only new-protocol protection.

After player.parse, call ApocBRPacketOwnershipGuard with the resolved ID/player
and internal (byte)-1 sentinel. On the server this verifies connection.hasPlayer
and exact membership in one of the four sender slots. No sentinel byte is read
from/written to the wire. On clients it allows authoritative updates, subject to
native field consistency. Keep isConsistent delegation and the existing
GameTime.instance.calender != null check before reading/applying payload fields.

Preserve native sleeping-tablet effects/delta/pill count, beta/depression/pain
effects/deltas, writer order, annotations and setData. Rejected ownership or a
missing calendar leaves payload fields unread/unapplied after identity parsing.
Malformed-body partial reads remain native behavior; this is not atomic packet
application or a redesign of packet-manager/capability validation.

## Verification and remaining checks

The [candidate fixture](../../../tools/test_player_effects_port.py) compiles the
complete candidate, actual target ConnectionPlayer/Player and production guard
with player/clock/connection/interface doubles. **62 assertions passed**, covering
all four owned slots and effect values, invalid/index-shaped spoof input,
hasPlayer mismatch, null connection, packet reuse, missing calendar, client receive
and native writer/header behavior. The shared damage fixture also passed its
26 assertions after fixture reuse was introduced.

Real IsoPlayer/clock lifecycle, packet-manager routing/capability behavior,
malformed bodies, real split-screen/multiplayer connections and exact-JAR/runtime
checks remain pending. No ownership-helper API, installation, configuration or
external service was changed.

User approved: "yes". Applied-candidate equality and 62 ownership assertions
passed again. Native multiplayer validation remains pending. No installed
files were changed.
