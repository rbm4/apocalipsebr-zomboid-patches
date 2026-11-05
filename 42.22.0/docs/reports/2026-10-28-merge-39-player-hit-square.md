# Review 39/48: PlayerHitSquarePacket

Created 2026-10-28. Status: **approved and applied on 2026-10-28**.
Review38 is applied. This diagnostic is also applied, after baseline ae6e61a. See the
[subsystem tracker](../features/2026-10-27-subsystem-port-tracker.md),
[candidate diff](2026-10-28-merge-39-player-hit-square.diff) and
[verification record](2026-10-28-merge-39-player-hit-square.json).

## Intent and baseline comparison

The only custom change between
[42.21 vanilla](../../../42.21.0/decompiled/zombie/network/packets/hit/PlayerHitSquarePacket.java)
and the [42.21 patch](../../../42.21.0/src/zombie/network/packets/hit/PlayerHitSquarePacket.java)
is a call to ServerHumanPlayerAttacks.missed() before native parsing.
It reports that a client sent a no-character-hit payload, useful when diagnosing
NPC hit detection. It neither applies NPC damage nor infers retaliation.

[42.22 vanilla](../../decompiled/zombie/network/packets/hit/PlayerHitSquarePacket.java)
uses handlingType=0: it is parsed as a child of PlayerHitPacket, rather than sent
as the old standalone packet. Constructors/setData/set now use
CombatManager.TargetHitList and tracers. It exposes getTarget() == null and uses
its inherited character/position APIs instead of the removed wielder field.

## Candidate

Copy the full target class and add the same single missed() call. Retain all
native constructors, setters, processing, consistency checks, writer, capability/
anti-cheat annotations and envelope-child handling. The existing
[helper](../../src/zombie/apocalipse/humans/ServerHumanPlayerAttacks.java)
logs only on the server with apocbr.npc.playerHitTrace enabled (default true),
throttled to once per second. It was retained during review38.

The message is a parse-time observation **before consistency/anti-cheat** and must
not be treated as authenticated evidence or a confirmed missed shot at an NPC.
No new payload fields, damage, inventory effects or gameplay state changes occur.
The adapted outgoing NPC miss constructor/transport is already applied in review38.

## Verification and decision

The candidate applies cleanly to exact target vanilla. Removing the single added
call restores the complete target source byte-for-byte after newline normalization.
The old vanilla-to-patch comparison confirms this is the full old custom intent.
The existing helper's server/config/throttle guards were source-reviewed.
No new fixture was added for this diagnostic-only call; no exact-game-JAR or live
network validation is claimed. Review38's 1,725 assertions and 16-file applied
candidate equality were separately verified; they do not validate this diagnostic's logging
at runtime.

The user approved application. Exact target baseline ae6e61a was committed
locally, then the reviewed one-call diff was applied. Applied source matches the
candidate; removing the diagnostic call restores native text after newline
normalization. No new gameplay fixture, deployment or installed-file change.

Next is [review40: trading handshake](2026-10-28-merge-40-request-trading.md),
a four-file candidate covering the new request/accept/reject packet split and
NPC adapter reply APIs. That candidate remains unapplied.
