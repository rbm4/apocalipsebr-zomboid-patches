# Review 41/48: TradingUIAddItemPacket

Created 2026-10-28. Status: **approved and applied on 2026-10-28**.
Review40 is applied. This one-class patch is also applied after baseline 300b619. See the
[subsystem tracker](../features/2026-10-27-subsystem-port-tracker.md),
[candidate diff](2026-10-28-merge-41-trading-add-item.diff) and
[verification record](2026-10-28-merge-41-trading-add-item.json).

## Old custom intent and target contract

The [42.21 patch](../../../42.21.0/src/zombie/network/packets/TradingUIAddItemPacket.java)
adds the item to a validated NPC offer before native TradingManager handling.
Its inherited [old Trading field patch](../../../42.21.0/src/zombie/network/fields/Trading.java)
resolves only the destination NPC. For ordinary trades, old native code checks
connection.hasPlayer(playerA ID) and the item; it does not perform the target
version's exact participant-pair validation.

[42.22 vanilla](../../decompiled/zombie/network/packets/TradingUIAddItemPacket.java)
extends PlayerRequestPacket instead of the removed Trading field. It uses the
connection-scoped sender and online destination introduced in review40. setData
now accepts sender, destination and an Integer item ID. The server retrieves the
actual item from the sender's inventory; clients do not send a trusted item object.

The target clears item=null on every parse and requires a resolved item during
isConsistent. Native processServer also checks TradingManager.isInvalid(sender,
destination), preventing item relay to another player outside that exact active
trade pair. Keep both native protections. Native client item loading advances
from version249 to **251**; do not restore old serializers/setters.

## Candidate

After super.parse, call the applied ServerHumanTrading.resolveTarget(destination)
helper. It only fills a missing recognized server NPC in reserved IDs 768..1023;
the sender, global OnlinePlayer field and shared PlayerRequestPacket are unchanged.
Keep all native item parsing, null reset and consistency checks.

At the start of processServer, call the existing ServerHumanTrading.addItem(sender,
destination,connection,item), consuming NPC-involving traffic before native
TradingManager. That adapter validates the existing visitor/session, ownership,
item/container identity, range/attention, timeout and consent; it records only item
IDs in the NPC offer and never adds NPCs to TradingManager. The add packet itself
does not remove inventory or create a sale. An edit invalidates prior offer consent.
Ordinary trades continue through the unmodified native pair/item/relay code.

Missing/unknown item IDs now fail **native consistency before processServer**.
Consequently they cannot reach the adapter's invalid-item cancellation path, as
they could under the old less restrictive packet. The existing NPC offer remains
unchanged until a valid edit, explicit cancellation or timeout. The candidate
retains this target behavior rather than weakening consistency for NPCs.

## Verification

[Fixture](../../../tools/test_trading_add_item_port.py): **105 assertions passed**.
It compiles the complete candidate packet plus the applied review40 handshake,
actual ServerHumanTrading/HumanTradeOffer and actual native identity fields.
TradingManager.isInvalid/addNewTrading/addItem bodies are extracted unchanged
from target vanilla and run against small trading-state doubles.

Coverage includes all four sender slots, malformed/foreign sender identities,
unknown and foreign inventory IDs, reused-packet item reset, missing/stale NPC
mapping, item/container mismatch, no-session refusal, no inventory consumption on
add, offer-consent invalidation after edits and an authorized sale handoff through
the existing adapter. Ordinary trades must have the exact active pair before
native item insertion/relay. Native client/server writer branches and the native
client loadItem(...,251) call/event argument order are exercised.

Actor/NPC composition, attention, inventory encoding, transport, Lua and
journal/database ports are explicit doubles. The fixture's item save/load body
encodes only a mock item ID: it verifies branch/version/field use, not actual game
item serialization or credit durability. No full-JAR compile, live client UI,
real persistence, native-world or complete thread/lifecycle verification occurred.

## Decision and dependencies

The user approved this one-class patch. Exact target baseline 300b619 was
committed locally, then the approved diff applied. Applied-candidate equality
and 105 fixture assertions passed again. No deployment or installed-file changes.
No extra support change is needed: review40 already supplies the OnlinePlayer
resolver and offer adapter. Reviews42-43 still own remove-item and state/finalize
interception. The removed Trading field design remains open until those target
resolution uses are migrated. This is not a claim that the whole trading feature
is production ready.

Next is [review42: TradingUIRemoveItemPacket](2026-10-28-merge-42-trading-remove-item.md),
a separate unapplied withdrawal/consent candidate.
