# Startup and shutdown persistence retries (42.21.0 server)

## Trigger and resulting behavior

A journal operation such as `player-live-v1` can repeatedly fail with SQLState
`22000` (including the patch's incomplete-live-graph validation). Previously, replay
retained that head forever while the shutdown hook waited for an empty journal.
The main thread then remained in `System.exit()` joining `GameServer$1`.
The same failing journal head could also block replay and login admission at the
next server startup.

`savefile/ApocBRPersistence.java` now limits each operation to **five failed
execution attempts total during startup replay or DRAINING**. The worker retries after 250 ms and
advances the durable checkpoint after the fifth failure. It continues with later
entries in FIFO order. SQL, runtime and linkage failures share the budget; a success
on attempt five commits and acknowledges normally. Each new operation starts with
its own budget. Normal runtime failures before shutdown do not consume the shutdown budget.

Discard is permanent: reopening the journal does not replay the removed operation.
No database receipt is fabricated for a discard. Receipt-based duplicate protection
still applies to successful replay, including commit-response loss. A failed local
discard checkpoint is retried without executing the exhausted operation again.
Filesystem/corrupt-journal errors are not counted as execution attempts or silently
discarded, since safe journal advancement cannot be established.

Entering drain wakes a replay worker waiting in normal recovery backoff without
interrupting an in-flight JDBC operation. Shutdown skips the independent database
probe, allowing connection failures to consume the selected operation's budget.
An empty journal can finish draining while PostgreSQL remains unavailable.
Startup still checks database availability/schema/credentials and normal runtime
retains failed entries for recovery.

Startup probes must succeed before an operation is executed. Failed connection or
receipt-schema probes do not count as operation attempts and cannot discard queued
data. Missing receipt schema or invalid credentials still fail startup; a database
outage still keeps admission closed until recovery. Once probes succeed, entries
that fail execution five times are discarded permanently and later entries run.
Startup opens login admission only after the journal is empty and no replay is
in flight. Thus an invalid entry can no longer block an otherwise healthy startup.

The synchronous shutdown database callback (currently role saving via
`ServerMap.QueuedQuit`) also stops after five database failures, with 250 ms between
attempts. Other callback errors still propagate. Shutdown continues cleanup after
the database callback is discarded.

## Diagnostics, configuration and limits

Every discard emits an unthrottled `REPLAY_DISCARDED` with operation UUID, kind,
attempt count, exception class and sanitized SQLState. Synchronous callback
exhaustion emits `DRAIN_DATABASE_WORK_DISCARDED`. Logs omit SQL text, bound values,
player data, credentials and exception messages. Periodic `DRAIN_WAITING` and
`REPLAY_DEFERRED` remain rate limited; `SQLState=LOCAL` is a logging fallback.

Five attempts and the 250 ms delay are fixed constants with no profile override.
The existing `apocbr.postgres.enabled` and `apocbr.postgres.journalDirectory`
configuration remains unchanged; see [the connector guide](../POSTGRESQL-CONNECTOR.md).
No permissions, database schema or migration changes are required.

This is an attempt limit, not an overall startup/shutdown deadline: JDBC acquisition/query/
socket timeouts still bound each database call, and filesystem/native calls can
take longer. A large backlog takes time even when every entry is discarded.
Four retry gaps add roughly one second per failed entry, in addition to execution
time. Discarded updates are lost, and later dependent operations can also fail and
exhaust their own budgets.

## Validation

`python tools/test_postgres_resilience.py` executes the production coordinator,
journal and native/account adapters with fake JDBC and temporary journal files.
Regression cases cover independent five-attempt budgets, SQL and runtime failures,
success on attempt five, progress after a bad head, permanent database outage,
empty offline shutdown, bounded synchronous shutdown work, discard checkpoint
reopening, unthrottled sanitized discard logs, and preservation of startup/runtime
recovery and receipt deduplication. Startup-specific tests seed a previous-process
journal and verify independent SQL/runtime failure budgets, success on attempt
five, progress through failed heads, admission remaining closed until replay ends,
and permanent discard after reopening. Compile the full patch with
`42.21.0/patchApocalipseBr.ps1 -DryRun` against the target game JAR as well.
Full deployed shutdown under real JDBC/native/mod workloads remains a runtime check.
