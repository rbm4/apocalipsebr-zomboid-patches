# Bounded server network diagnostics

`zombie.network.ApocBRNetworkDiagnostics` centralizes selected repetitive server
receive logs. Packet parsing, authorization, consistency checks, rejection, sync
and processing remain vanilla. This is noise control, not a fix for invalid client
packet construction.

## Current integration

- `ConnectionPlayer.parse`: `invalid-player-slot`, Error severity.
- `INetworkPacket.logInconsistentPacket`: `inconsistent-packet`, Warning severity,
  for every packet using that default method. Overrides remain unchanged.
- `GameServer` wraps its `onServerPacket` call in a receive scope so nested field
  parsers can identify the packet type without changing native method signatures.
  The scope closes on success, rejection and exceptions.

Client/offline logging retains the existing paths. No packet fields, ordering or
wire format changed. In particular, slot -1 still leaves the resolved player null;
an inconsistent Equip is still rejected before equipment application. No fallback
selects a character from the originating connection.

## Cadence and counts

Buckets are isolated by connection GUID, packet type, authored event name and
severity. A null connection has a separate identity. First occurrence logs
immediately; subsequent occurrences increment counters silently. The next
occurrence at least 60 seconds after the previous emission produces a summary
at the original severity, with the current detail sample:

```text
[ApocBR][NetworkDiagnostics] connection=... packet=Equip event=invalid-player-slot total=102 sinceLast=101 suppressed=100 detail=wrong player connection index (-1)
```

`total` counts occurrences since bucket creation; `sinceLast` includes the current
occurrence; `suppressed` counts earlier occurrences hidden since the last emission.
The two diagnostics from one rejected Equip have separate counts, so each emits
at most once per 60 seconds while its bucket remains resident. Packet descriptions
are evaluated only when emitting a log.

Summaries are event-driven: silence generates no timer logs, and quiet/disconnected
buckets have no final flush. There is no background thread. The access-ordered
table retains at most 4096 buckets; the oldest is evicted at capacity. Eviction
discards that bucket's counts; a later occurrence starts at one and logs again.
This bounds retained tracking state, not aggregate output across arbitrarily many
distinct connections/events. Connections are not retained as object references.
Counts are process-local, not persisted.

## Adding another diagnostic

Call `warn(connection, packetType, "stable-authored-event", () -> detail)` when the
packet type is known. Call `error(connection, "stable-authored-event", () -> detail)`
inside a server receive scope to use its packet type. Without a scope the error
bucket uses `packet=unknown`. Keep event names constant; put changing IDs and
payload values in the lazy detail supplier. Add a server-side guard at call sites
that can also execute on a client. Neither helper makes a processing decision.

Counter updates and emission are synchronized; monotonic `System.nanoTime()` owns
cadence. Receive context is thread-local and nestable, restored on scope exit.
Detail suppliers run synchronously under the diagnostic monitor: keep them bounded,
side-effect free and limited to data safe on the caller's thread. The handler does
not make native packet objects safe for asynchronous access.

## Validation

`py tools/test_network_diagnostics.py` compiles the actual handler with minimal
engine doubles and passes 14 assertions covering first emission, lazy suppression,
the exact cadence boundary, cumulative/window counts, isolation by connection,
packet/event/severity, null identity, nested/thread-local/exception scopes, the
capacity limit, eviction reset and concurrent counting (4000 occurrences).
These are handler tests, not a live multiplayer reproduction.

The 2026-11-04 exact installed-JAR build through `patchApocalipseBr.ps1 -BuildOnly`
passed with javac 25.0.4: 308 sources / 909 class files, retained at
`../artifacts/build_20261104_151435/`. No installation files were deployed or
running processes restarted. Live-server cadence and rejection validation remain
pending deployment.
