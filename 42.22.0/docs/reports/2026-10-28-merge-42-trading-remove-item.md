# Review 42/48: TradingUIRemoveItemPacket

Created 2026-10-28. Status: **approved and applied on 2026-10-28**.
Review41 is applied. This one-class patch is also applied after baseline a78d82e. See the
[subsystem tracker](../features/2026-10-27-subsystem-port-tracker.md),
[candidate diff](2026-10-28-merge-42-trading-remove-item.diff) and
[verification record](2026-10-28-merge-42-trading-remove-item.json).

## Old intent and target changes

The [42.21 patch](../../../42.21.0/src/zombie/network/packets/TradingUIRemoveItemPacket.java)
intercepts NPC-involving removals before native TradingManager/relay, using
ServerHumanTrading.removeItem. Its inherited
[Trading field patch](../../../42.21.0/src/zombie/network/fields/Trading.java)
provides the detached NPC destination fallback. Ordinary old native removal checks
connection ownership of playerA's ID before removing from that participant's offer.

[42.22 vanilla](../../decompiled/zombie/network/packets/TradingUIRemoveItemPacket.java)
extends PlayerRequestPacket and uses sender ConnectionPlayer/destination OnlinePlayer.
setData takes sender, destination and an Integer item ID, replacing the old setter
that accepted an InventoryItem. Native processServer first validates the exact
trade pair with TradingManager.isInvalid(sender,destination), then removes the ID
from the sender's own offer list and relays to its actual destination.

## Candidate and consent contract

After native super.parse, resolve only a missing recognized NPC destination via
the applied trading helper. Before native processServer handling, invoke the
existing removeItem(sender,destination,connection,itemId) hook. Keep all remaining
native methods, declarations and pair checks unchanged; global player fields and
the shared PlayerRequestPacket remain vanilla.

The NPC adapter validates exact visitor/session/connection ownership and current
range/attention/authorization/expiry. It removes the ID from HumanTradeOffer and
sends DealWasUnsealed. It does not remove any inventory item, enqueue a sale or
put the NPC into native TradingManager. Withdrawal is ID-only: requiring the item
to remain in inventory would prevent safely withdrawing an already moved item.

Existing consent policy is retained: every valid removal invalidates consent,
even for an absent, repeated or negative ID. This does not delete possessions.
A later finalize requires fresh consent to the remaining nonempty offer. Ordinary
native removal likewise resets agreements when the participant matches, even
when that ID was absent from the sender's list. An ID belonging only to the other
participant cannot remove that participant's item.

The native payload stays **two shorts plus one int (8 bytes)** in either
direction. There is no item serialization or item-version field in this packet.
The sender short denotes a connection slot on server receipt and an online ID
on client receipt, following the native ConnectionPlayer contract.

## Verification

[Fixture](../../../tools/test_trading_remove_item_port.py): **120 assertions passed**.
It compiles the full candidate remove packet with approved add-item code,
applied review40 handshake/adapter, HumanTradeOffer and actual native identity
fields. Exact target TradingManager isInvalid/addNewTrading/addItem/removeItem
bodies run against minimal trading-state doubles. The shared review41 fixture
was rerun after its extension and still passes **105 assertions**.

Coverage includes selective offer withdrawal without inventory consumption,
remaining-item sale handoff through the existing adapter, consent invalidation
for absent/repeated/negative IDs, withdrawing the last item, no-session refusal,
all four sender slots, invalid/foreign sender IDs, missing NPC mappings, foreign
session ownership, connection claims, out-of-range cancellation, exact ordinary
trade pairs, other-participant item protection, unchanged inventory, fixed payload
width/directional IDs and native client event argument order.

Actor/NPC composition, attention, inventory, Lua, transport and journal/database
ports are explicit doubles. Real item encoding, persistence/credit durability,
full game JAR, native world/threading and live client UI are not validated.
This test does not execute an incoming TradingUIUpdateStatePacket: review43 still
owns that packet's adapter hook; helper finalization calls only exercise current
adapter logic through recording persistence ports.

## Decision and dependencies

The user approved this patch. Exact native baseline a78d82e was committed
locally, then the reviewed diff applied. Applied-candidate equality and 120
fixture assertions passed again. No deployment or installed-file change.
No support changes are needed beyond the applied review40 adapter. Review43 is
TradingUIUpdateStatePacket and will complete the original Trading field's remaining
UI target-resolution/interception use before that removed-class design is resolved.
Do not infer approval of review43 from this candidate.

Next is [review43: TradingUIUpdateStatePacket](2026-10-28-merge-43-trading-update-state.md),
a separate unapplied state/finalization candidate.
