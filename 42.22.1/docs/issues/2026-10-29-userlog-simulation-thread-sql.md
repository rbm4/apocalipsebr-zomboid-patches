# RequestUserLog rejected with SQLState 55000

Status: source fix implemented for the 42.22.0 server; compilation and isolated
regression pass. Deployment and live administrator UI validation remain pending.

## Cause

The native `RequestUserLogPacket.processServer` builds a response using
`setData`, which calls `ServerWorldDatabase.getUserlog` on the server simulation
thread. `ApocBRPostgresConnector.borrowConnection` deliberately rejects that
thread after simulation starts, with SQLState `55000`: PostgreSQL work must be
deferred. `ApocBRServerDatabase.open` replaces the original message with the
generic database-unavailable exception seen in `INetworkPacket.send`.

An open pool or existing physical database connection does not bypass this
thread-ownership rule. The failure happens before connection acquisition and
does not establish that PostgreSQL is offline. This request is a SELECT; the
existing `queueUserlog` persistence path handles log writes separately.

## Implemented behavior

The replacement is based on the exact target
[`RequestUserLogPacket`](../../decompiled/zombie/network/packets/service/RequestUserLogPacket.java).
It checks `ReadUserLog`, captures the requested username independently of the
reusable packet object, and submits the SELECT to
[`ApocBRAsyncServerDatabase`](../../src/zombie/savefile/ApocBRAsyncServerDatabase.java).
The worker calls the existing account database API, which borrows and releases
a lease through the existing PostgreSQL connector pool. It verifies the workflow
failure marker so a caught SQLException cannot produce a false empty/partial
response.

`ApocBRAsyncLogin.update` polls completions on the server thread. Before invoking
the response continuation, the coordinator checks the original connection object,
GUID, account identity, Steam/owner IDs, world and disconnect state. The packet
continuation rechecks `ReadUserLog`, obtains current validator counters on the
server thread and sends the detached rows. Response construction consumes those
rows without running another SELECT. Worker code does not touch players,
validator state, permissions, Lua or packet transport.

The administrative coordinator is separate from authentication: read failures or
overload do not deny login or disconnect an administrator. It has two daemon
workers, a bounded 32-task executor queue, at most 32 pending requests, one per
connection, and an eight-second request lifetime. Busy/repeated requests are
dropped; callers can retry. Completion polling starts at most two continuations
per tick, stopping between continuations after two milliseconds; an individual
packet serialization is not preempted. Queued/finished request counts are bounded,
but this change does not impose a new row/byte limit on native user-log results.
The normal foreground JDBC workflow/network deadlines still apply on workers.

Expired/disconnected requests are discarded; cancellation is logical and does
not forcibly interrupt an in-flight JDBC call. Exceptions withhold the response
and emit a fixed diagnostic without query/account data. Shutdown cancels retained
results and stops the executor through the existing async-login shutdown hook.
The native protocol/client methods and PostgreSQL-disabled SQLite path are
preserved. No schema or production configuration changes are needed.

This fixes the reported RequestUserLog call path. Other synchronous database
callers are not automatically made asynchronous, and the simulation-thread SQL
guard remains enabled. Queued log writes can still become visible after a read;
this change does not introduce a write-flush barrier.

## Verification

- `python tools/test_async_server_database_42_22.py`: production coordinator and
  complete replacement packet with controlled engine/JDBC doubles; worker/thread
  separation, reusable packet capture, result reuse, authorization, stale
  sessions/world, timeout, failed/swallowed reads, overload, completion bounds,
  cancellation, SQLite fallback and shutdown. Source comparisons preserve exact
  native wire/client methods and verify tick/shutdown integration.
- `.\42.22.0\patchApocalipseBr.ps1 -DryRun`: successful exact installed 42.22
  JAR compilation, 305 Java sources and 902 class files. No deployment.

Live checks still required: open the administrator user-log panel for online and
offline users, revoke permission/disconnect while a read is pending, and retry
after a database outage. Confirm the panel receives rows/counters without
simulation-thread SQL exceptions. The fixture does not exercise a real database,
RakNet serialization or the administrator UI.

Related historical feature ownership:
[42.21 PostgreSQL connector guide](../../../42.21.0/docs/features/2026-10-14-postgresql-connector.md)
(previous-build reference; this issue documents only the target call path).
