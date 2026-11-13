# Client network update and packet-stage telemetry

Measured follow-up: [schema 7 capture analysis](../reports/2026-11-13-schema-7-capture-analysis.md).
Ownership follow-up: [worker boundaries and full client cost review](../reports/2026-11-13-client-worker-boundaries-and-full-cost-review.md).

Implemented 2026-11-13 for 42.22.2-client after the
[schema 6 capture](../reports/2026-11-13-schema-6-capture-analysis.md).
Extends the [main telemetry guide](2026-11-12-client-main-thread-telemetry.md).
Protocol context: [binary wire format and decoder feasibility](../reports/2026-11-13-client-binary-protocol-and-decode-pipeline.md).

Schema **7** instruments GameClient.update and PacketType.onClientPacket without
changing packet bytes, handlers, transport scheduling, cache lookup, ordering,
retry rules, connection flags or pooling. This is measurement, not asynchronous
packet processing or a protocol migration. Existing texture/translucent changes
remain in place. No installed game files were changed by implementation/testing.

## Update boundaries

All timings require the capturing main-thread owner. They are nested wall times,
not network round-trip latency or GPU execution measurements.

| Phase | Boundary |
| --- | --- |
| NET_SAFEHOUSE | Safehouse periodic update and timer |
| NET_QUEUE_DRAIN | Poll incoming main-loop queue into the owner list; count polled entries |
| NET_DISCONNECT_EVENTS | Disconnect monitor acquisition and Lua connection-event dispatch |
| NET_LOADING_DISPATCH | Loading packet dispatch and owner-list clear |
| NET_REPLAY_QUEUES | Loading/coop delayed-list reinsertion |
| NET_DISPATCH_BATCH | Eligible incoming packet dispatch and existing per-entry ArrayList removal |
| NET_OUTGOING_REQUESTS | Periodic chunk-object, vehicle and thump-hit requests |
| NET_PLAYER_TIMEOUTS | Remote-player timeout maintenance |
| NET_OUTGOING_INVENTORY | Added/removed item work, including its original error handling |
| NET_VOICE | Voice roaming-limit check/update |
| NET_VEHICLE_CLIP | Vehicle anti-clipping maintenance |
| NET_STREAMING | WorldStreamer.updateMain, in loading and connected paths |
| NET_CONNECTION_MAINTENANCE | Keepalive counter and connection readiness |
| NET_CHAT | ChatManager.UpdateClient |
| NET_STATISTICS | Client-cycle bookkeeping and statistic update |
| NET_DISCONNECT_CLEANUP | Connection-lost packet handling and teardown bookkeeping |

The coarse NETWORK phase still wraps GameClient.update. These subphases do not
cover every branch predicate or timing-probe overhead. Batch time minus summed
packet dispatch time includes list manipulation, eligibility checks and probe
overhead; it is not a pure measurement of ArrayList shifting.

## Per-type packet stages

The existing onClientPacket path now records these stages globally as
NET_PACKET_* phases and per packet type as network.types[type].stages:

| Stage | Measured work |
| --- | --- |
| STATISTICS | Existing income-packet statistic update |
| FACTORY | Per-connection cached lookup, postponement/instantiation checks and any explicit fresh construction |
| PARSE | packet.parseClient; this may access live state and is not guaranteed pure decoding |
| VALIDATE | Consistency check and original inconsistent-packet reporting |
| LOG | Log-enabled check and original packet logging |
| POSTPONE | Existing packet.postpone |
| APPLY | Existing packet.processClient |
| LEGACY | Existing direct clientHandler call; decoding and effects are combined |

Legacy handlers and loading handlers cannot be interpreted as separated PARSE/
APPLY from this instrumentation. Per-type stage totals normally nest inside the
main-loop dispatch attempt. No factory work is added: normal cached instances
remain reused; factory timing includes lookup rather than implying allocation.
Connection-time packet-cache preconstruction is outside these stage scopes.

## Schema 7 network context

`network` is an immutable owner-window snapshot handed to the writer:

- polled: entries drained from MainLoopNetDataQ, not all UDP packets received.
- pendingLast/loadingLast/coopLast: last sampled owner-list sizes.
- ownerBacklogMax: maximum sampled sum of those three lists. Samples occur after
  incoming drainage, after replay merge and at update exit, including exceptional
  exit. The concurrently receiving queue is deliberately not scanned or counted
  with ConcurrentLinkedQueue.size; undrained arrivals are outside this gauge.
- typeOverflow: dispatch attempts whose names exceed the retained type bound.
- types: at most 256 retained type names plus `<other>`. Entries persist across
  windows, with reset statistics, so known types may have zero counts later.
- types[type].dispatch: full main-loop dispatch-attempt count/total/max/histogram,
  including the original exception logger and pool discard. Unknown types use
  `<unknown>` and are still discarded as before.
- payloadBytes: remaining payload bytes at attempt start, excluding the already
  consumed transport/type header. Retries count again: this is not wire bandwidth.
- queueAge: histogram in milliseconds of application-queue age at attempt start,
  based on data.time/System.currentTimeMillis. Includes the vanilla five-ms gate,
  loading/coop waiting and retries; excludes transport latency. Clock changes can
  affect it; negative age is clamped to zero.
- delayed: attempts explicitly retained in DelayedCoopNetData with buffer rewind.
- errors: original caught dispatch exceptions plus uncaught RuntimeException/
  Error rethrows. An inconsistent packet is not automatically an exception.
- freshInstances: successful explicit fresh receive-side packet constructions in
  onClientPacket. Excludes connection-time cache preconstruction and any nested
  allocations performed inside parsers or handlers.
- stages: the per-type timing arrays described above.

Each retained worst frame gains `network` with packets, payloadBytes,
ownerBacklogMax and largestPacket (type, ms, payloadBytes, queueAgeMs, delayed,
error). This correlates the dominant handler with the coarse NETWORK spike.
The packet identity and numeric metadata are captured before pool reset. No
packet, buffer, connection, live world object, payload, player identifier or
credential reaches the telemetry writer. Retention and slow-frame counts remain
bounded; no per-packet file I/O or new worker is introduced.

## Source, compatibility and validation

Exact 42.22.2 baseline GameClient and PacketTypes were copied into the separate
client target and committed with the baseline utility before editing: local
commits `fef6a2b` and `6f96a6c`, no push. Replacement files:
[GameClient.java](../../src/zombie/network/GameClient.java),
[PacketTypes.java](../../src/zombie/network/PacketTypes.java), and
[ApocBRClientTelemetryLite.java](../../src/zombie/ApocBRClientTelemetryLite.java).

Extracted production/vanilla methods are differentially exercised with external
engine doubles for connected/loading/disconnected updates, coop queues,
safehouses, disconnect switch fallthrough, exceptions, maintenance and incoming
age gating. Packet-stage tests cover cache lookup, explicit reflection,
inconsistency, logging, postponement, legacy handlers, retry buffer rewind,
exceptions/Errors and pool reset. Enabled and disabled runs preserve call order
and outcomes. Source checks compare all original game call sites in both classes.

The capture-helper fixture verifies bounded type overflow, stage/window reset,
escaping, frame correlation and rejection of non-owner calls. Exact-JAR build:
21 sources / 75 classes. Public APIs match original GameClient, PacketTypes and
PacketTypes.PacketType. Full installer fixtures use disposable root/java layouts.
Final full suite: all 13 tests pass (102.089 seconds); final targeted network
tests and exact-JAR DryRun also pass. Documentation links/index coverage pass.
These checks do not establish gameplay overhead or actual bottleneck attribution.

Reinstall through instalador.bat with the client closed. Use a fresh output file
and capture connection/entry, movement, aiming, inventory interactions and normal
play. First compare NET_DISPATCH_BATCH against NET_STREAMING and
NET_OUTGOING_INVENTORY, then rank network.types by dispatch total/max and its
FACTORY/PARSE/APPLY/LEGACY breakdown. Read largestPacket in the same slow frame.
