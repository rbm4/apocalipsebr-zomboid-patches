# Review 40/48: RequestTradingPacket / NPC trading handshake

Created 2026-10-28. Status: **approved and applied on 2026-10-28**.
Review39 is applied. All four approved review40 files are also applied. See the
[subsystem tracker](../features/2026-10-27-subsystem-port-tracker.md),
[candidate diff](2026-10-28-merge-40-request-trading.diff) and
[scope/verification record](2026-10-28-merge-40-request-trading.json).

## Old custom intent

Compared with [42.21 vanilla](../../../42.21.0/decompiled/zombie/network/packets/RequestTradingPacket.java),
the [old patch](../../../42.21.0/src/zombie/network/packets/RequestTradingPacket.java)
resolves only a detached NPC trading destination after parsing, then intercepts
NPC-involving request/accept/reject traffic before native TradingManager/relay.
The old packet has an Ask/Accept/Reject enum and two PlayerID fields.

[ServerHumanTrading](../../src/zombie/apocalipse/humans/ServerHumanTrading.java)
owns one visitor/session per NPC, connection ownership, profile/encounter/attention/
range checks, repeated-request behavior, timeout, offer consent and the money-sale
handoff. NPCs stay outside the live-player/account maps and TradingManager. An
old non-Ask request cancels the visitor's matching NPC session; it does not open a
native trade. Keep that policy when mapping both new response packet types.
Historical feature context: [42.21 server humans](../../../42.21.0/docs/features/2026-10-19-server-humans.md).

## Native protocol changes

42.22 splits the old packet into
[RequestTradingPacket](../../decompiled/zombie/network/packets/RequestTradingPacket.java),
[AcceptTradingPacket](../../decompiled/zombie/network/packets/AcceptTradingPacket.java)
and [RejectTradingPacket](../../decompiled/zombie/network/packets/RejectTradingPacket.java).
All extend [PlayerRequestPacket](../../decompiled/zombie/network/packets/PlayerRequestPacket.java).
The shared sender is a connection-scoped ConnectionPlayer; destination is OnlinePlayer.
A server inbound sender is a connection slot, not an arbitrary online ID. Outbound
server IDs and destination IDs are online IDs. The payload consists of two shorts:
no old request enum or PlayerID local-index bytes may be appended.

For ordinary trades, a request records a pending pair in
[TradingRequests/Requests](../../decompiled/zombie/characters/Requests.java).
Accept/Reject calls response(requester,responder); only an existing pending pair
is consumed. Accept then registers the native trade. Preserve this new response
check rather than recreating the old packet's unconditional native Accept branch.

## Four-file candidate

1. Add a parse override to each of the three native handshake packet classes:
   call super.parse, then resolve only the destination through the NPC adapter.
   Keep native annotations, consistency, setData, writer and client event handlers.
2. Intercept NPC-involving traffic before each native processServer body.
   Request maps to ask=true; Accept and Reject both map to the old ask=false
   cancellation behavior. Ordinary player traffic follows unchanged native bodies.
3. Adapt ServerHumanTrading.resolveTarget to OnlinePlayer/getId. The removed local
   index check is replaced by the actual native field contract; resolve only
   reserved IDs 768..1023, only on the server, and only when vanilla found no player.
   The current factory mapping must supply the detached actor. Global OnlinePlayer
   and shared PlayerRequestPacket are not patched, so other protocols gain no NPC
   lookup fallback.
4. Replace obsolete packet.accept/reject methods with INetworkPacket.send using
   native AcceptTrading/RejectTrading and setData(NPC,visitor). IDs reserved in
   review38 pass the native client sender parser. NPC acceptance deliberately
   bypasses native pending-request/TradingManager creation; the existing adapter
   owns that session. Real-player pending-response behavior remains native.

The support diff changes only destination field adaptation and reply transport.
It leaves offer/item validation, credit quantities, receipt structures, durable
queue-before-consumption ordering and cancellation/timeout rules intact.

## Validation

[Fixture](../../../tools/test_request_trading_port.py): **131 assertions passed**.
It compiles the full candidate packet trio, actual ServerHumanTrading and
HumanTradeOffer with native PlayerRequestPacket, ConnectionPlayer, OnlinePlayer,
Player, Requests/TradingRequests and TradingState. Engine/NPC composition,
attention/encounter checks, inventory, transport, Lua, journal and database ports
use explicit doubles.

Coverage: four-byte field layout, slot-to-sender resolution, invalid/foreign
sender identities, exact connection/global-map ownership, missing/reused packet
destinations, NPC profile/range/encounter/emergency/availability refusals, busy
NPCs, repeated requests preserving a sealed offer, both non-Ask cancellation
paths, timeout, queue failure before consumption and a recorded sale handoff.
Native ordinary-player request/accept/reject and pending-pair consumption are
exercised, including unsolicited/duplicate accept rejection. Native client field
parsing/client event methods decode the NPC response and preserve argument order.

This is not full game-JAR compilation, live Lua trading UI/network compatibility,
real inventory serialization, real durable journal/SQL/receipt verification or a
full NPC lifecycle/thread-safety test. Sale-path ports are recording doubles;
passing tests do not establish production durability or correct credit delivery.

## Integration boundary and approval

Review40 needs these three handshake packets and one support adaptation together;
a RequestTradingPacket-only patch would leave removed reply APIs in use and omit
new response packet interception. Exact native baselines were committed locally before application:
RequestTradingPacket 2e1008f, AcceptTradingPacket 960fd49 and RejectTradingPacket
67deb11. All four applied files equal the approved candidate after newline
normalization; the 131-assertion fixture passed again. No push, deployment or
installed-file changes occurred.

Reviews41-43 still own TradingUIAddItemPacket, TradingUIRemoveItemPacket and
TradingUIUpdateStatePacket. Each needs its destination fallback/interception in a
separate review. The removed Trading field's design remains open until those uses
are covered. This handshake is not a claim that the full trade feature is ported.

The user approved this four-file bot-engine handshake port. Tracking and source
hashes are updated. Next is [review41: TradingUIAddItemPacket](2026-10-28-merge-41-trading-add-item.md),
a separate unapplied candidate retaining native sender-inventory lookup and
trade-pair validation.
