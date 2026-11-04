# Follow-up review55: event-control engine metadata

Created2026-10-28. Status: **approved/applied; live event runtime unverified**. Review54 is applied and
all303 approved sources compile successfully. See the
[subsystem tracker](../features/2026-10-27-subsystem-port-tracker.md),
[candidate diff](2026-10-28-merge-55-event-control-metadata.diff) and
[verification record](2026-10-28-merge-55-event-control-metadata.json).

The copied [event-control API](../../src/zombie/apocalipse/events/ApocBREventControl.java)
still reports42.21.0/event-control-v2 in REGISTER engine_version. Replace only
the game build prefix with42.22.0; keep event-control-v2 because this change
introduces no protocol/schema revision. The existing upsert updates engine_version
and heartbeat on conflict, so a later actual registration will refresh that row.
No live registration or SQL is executed during this review.

Polling/worker ownership, event schedules, receipts, Lua exposure, keys and
metadata parsing remain unchanged. This is the metadata follow-up identified in
review46, not a new build blocker. Verification is source-only: inspected REGISTER
upsert and confirmed candidate applies cleanly. No extra test for this one literal;
no claim of runtime event/backend behavior. Decision: applied on2026-10-28 after user approval; exact one-literal candidate
equivalence passed. Continue the remaining List-cache and runtime/cleanup work.
