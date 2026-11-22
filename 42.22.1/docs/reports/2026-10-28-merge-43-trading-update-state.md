# Review 43/48: TradingUIUpdateStatePacket

Created 2026-10-28. Status: **approved and applied on 2026-10-28**.
Review42 is applied. This one-class patch is also applied after baseline d26bfff. See the
[subsystem tracker](../features/2026-10-27-subsystem-port-tracker.md),
[candidate diff](2026-10-28-merge-43-trading-update-state.diff) and
[verification record](2026-10-28-merge-43-trading-update-state.json).

## Old intent and native changes

The [42.21 patch](../../../42.21.0/src/zombie/network/packets/TradingUIUpdateStatePacket.java)
intercepts NPC state changes before native TradingManager. Its inherited
[Trading field patch](../../../42.21.0/src/zombie/network/fields/Trading.java)
resolves the detached NPC destination. ServerHumanTrading owns the NPC offer and
its consent, cancellation, sale handoff and cleanup; NPCs never join native trades.

[42.22 vanilla](../../decompiled/zombie/network/packets/TradingUIUpdateStatePacket.java)
extends PlayerRequestPacket and uses sender ConnectionPlayer/destination OnlinePlayer.
It clears a pending TradingRequests pair when WindowWasClosed arrives, then
checks the exact native trade pair before processing/relaying ordinary states.
Preserve this new pending-request cleanup and partner check rather than restoring
the old ID-only ownership branch. Native setData and client event order remain.

## Candidate

After super.parse, resolve only a missing recognized reserved NPC destination via
the applied trading helper. Before the native processServer body, call
ServerHumanTrading.state(sender,destination,connection,state), returning if the
NPC adapter consumes the packet. This ordering keeps NPC session handling outside
native pending-pair/TradingManager state, while all ordinary behavior stays native.
Global OnlinePlayer/PlayerRequestPacket remain unpatched.

The existing adapter handles WindowWasClosed, DealWasUnsealed, DealWasSealed and
DealWasFinalized. Valid session/visitor/connection/range/attention/authorization
remain prerequisites. Sealing requires a nonempty currently owned offer; unseal
or item edits invalidate consent. Finalization revalidates owned items and
availability, invokes the existing sale enqueue, then removes items and snapshots
the visitor. The packet's fixed body is **two shorts and one enum byte (5 bytes)**.

No receipt, credit quantity, journal, schema, inventory or retry policy changes
are proposed. Money and bundle IDs are classified by the current adapter; offered
non-money items are disposed without monetary credit. The current persistence
boundary still governs real durable processing and account credit.

## Failure behavior retained

A failed sale enqueue closes the session before consumption and logs
OFFER_NOT_CONSUMED. A failure after the handoff/removal/snapshot begins logs
CONSUMPTION_REQUIRES_REVIEW, closes the UI/session and requires reconciliation;
it does not automatically submit another sale. A queued receipt may therefore
exist even if later consumption or snapshot fails. This candidate preserves that
existing policy and does not add transactional rollback or prove exactly-once
cross-database credit delivery. Repeated client finalization after session close
has no active offer to submit again.

## Verification

[Fixture](../../../tools/test_trading_update_state_port.py): **195 assertions passed**.
It compiles the full state candidate, native identity fields/ByteBufferReader/
TradingState, applied handshake and actual ServerHumanTrading/HumanTradeOffer.
Exact native manager pair/consent/cancel/finalization method bodies are extracted
and run against minimal trading state. Engine/NPC composition, Lua, inventory,
primitive writer, transport and persistence are explicit doubles.

Coverage includes sealing/unsealing, edits after consent, empty/missing items,
owned-session enforcement, invalid/foreign sender slots, unknown NPC destination,
close and duplicate close, receipt item classification, handoff-before-removal,
unavailable persistence, pre-enqueue and post-handoff failure paths, duplicate
finalization, ordinary pending-request cancellation, native partner/consent checks,
normal peer finalization queueing, all native enum values and native client event
argument order. Shared add/remove fixtures were rerun after extension and still
pass **105** and **120** assertions respectively.

No live journal fsync, SQL, credit processing, real inventory serialization, live
Lua trading UI, full-JAR compilation, full NPC composition, worker persistence or
native threading/world runtime was exercised. The test's recorded Sale is not
proof of actual credit or durable receipt delivery.

## Removed Trading field and review decision

This is the last original UI target-resolution/interception use of the removed
Trading field: request/accept/reject, add-item and remove-item are already applied.
The user approved application, resolving that removed-class design through these
narrow packet overrides without recreating obsolete Trading. Its old loose-class
family is now tracked for cleanup, which remains unimplemented; installed files
were not removed. The shared native player fields remain
unchanged, so unrelated request protocols gain no NPC fallback.

This completes the audited packet-hook source migration, not full trade-subsystem
or production validation. Exact native baseline d26bfff was committed locally,
then the approved candidate applied. Applied-candidate equality and 195 assertions
passed again. No deployment, live DB or installed-file change occurred.

Next is [review44: ChatServer](2026-10-28-merge-44-chat-server.md), a separate
unapplied NPC speech/general-chat backend candidate.
