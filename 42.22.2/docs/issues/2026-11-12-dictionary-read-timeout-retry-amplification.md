# Dictionary source-read cancellation and retry amplification

Status: open; source diagnosis only. No runtime fix or live database inspection.
Investigated 2026-11-12 against latest server target 42.22.2. The deployed build
and class hashes were not provided. Relevant source is carried from 42.22.1.

Related contract: [historical dictionary compatibility](../../../42.22.1/docs/features/2026-10-29-historical-dictionary-compatibility.md).
Operational capture: [read-only diagnostic SQL](2026-11-12-dictionary-read-timeout-diagnostics.sql).

## Observed failure and confidence

The supplied event has stage READ_SOURCE_ENTRIES, elapsedMs=5077,
PSQLException/57014, and message `canceling statement due to user request`.
The following PREPARE_FAILED is propagation of that same failed load, not evidence
of a second SQL attempt. sourceServer=2 identifies the dictionary's origin; it
does not identify which server issued this read.

[ApocBRWorldDictionaryDatabase](../../src/zombie/savefile/ApocBRWorldDictionaryDatabase.java)
sets Statement.setQueryTimeout(5) in prepare(). load() uses that helper for its
ready-view, registry, override and entry queries. The failing entry query reads
every identity in the source namespace through the pinned identity revision and
left-joins its exact view's state. It is not a per-item lookup.

The pgJDBC timeout sends a cancel request, which can produce precisely this
message. The five-second configuration and observed time strongly support driver
query-timeout cancellation. SQLSTATE 57014 alone cannot distinguish it from an
administrator/client cancel. elapsedMs measures the whole load, including earlier
queries and acquisition, not just the final statement. Neither corruption nor
the underlying reason the query ran slowly is established by these logs.

Primary driver references: [queryTimeout configuration](https://jdbc.postgresql.org/documentation/use/)
and [PgStatement cancellation implementation](https://github.com/pgjdbc/pgjdbc/blob/master/pgjdbc/src/main/java/org/postgresql/jdbc/PgStatement.java).

## Actual retry and startup behavior

1. Each server publishes its local vanilla snapshot and installs that exact view
   directly in memory. This failure concerns loading a source view, not local ID
   allocation. Identical restarts can REUSE the existing publication, but still
   perform history and snapshot comparisons in PostgreSQL.
2. [ApocBRWorldDictionaryMirror](../../src/zombie/world/ApocBRWorldDictionaryMirror.java)
   starts a listener, commits LISTEN, then immediately reconciles foreign heads.
   It eagerly prepares one latest ready view per foreign namespace/server/save,
   including historical namespaces; it does not filter to currently running
   servers or supported namespace build metadata in foreignHeads().
3. [ApocBRWorldDictionaryDecode](../../src/zombie/world/ApocBRWorldDictionaryDecode.java)
   coalesces concurrent requests for the same exact provenance within this JVM.
   Successful views stay cached. A failed future is completed exceptionally and
   removed immediately, so the next caller can issue SQL again. There is no
   failure cooldown or cross-JVM coalescing.
4. One preparation failure aborts the reconcile loop. Later views are not
   attempted, and foreign metadata is not updated for that cycle. Earlier
   successful preparations remain cached.
5. The exception escapes to the listener's outer catch, closes the listener
   session and sleeps 1000 ms before reconnecting and reconciling again.
   The normal 60-second poll interval applies only after successful reconcile.
   LISTENER_RETRY is logged at most roughly once per 30 seconds, but that is
   log throttling only. SOURCE_READ_FAILED and PREPARE_FAILED are not similarly
   throttled. There is no attempt limit, exponential backoff or jitter here.
6. The [relational player reader](../../src/zombie/savefile/ApocBRPlayerRelationalReader.java)
   also calls the same preparation API for exact views referenced by payloads.
   Fixing only the listener leaves this second path able to repeat failed reads.

With a persistent five-second entry timeout and otherwise fast work, a listener
can repeat about every six seconds, roughly ten full attempts per minute per
affected JVM. This is an estimate, not a measured rate. For N servers, each
reading the other N-1 active origins at cold startup, warmup can require N(N-1)
loads across the fleet, plus historical namespaces. Local duplicate suppression
does not reduce that fleet-wide burst. These reads share the ordinary connector
pool with other database work; LISTEN itself owns an extra physical connection.

This supports a feedback mechanism: database pressure slows reads, reads time
out, failed reads lose their cache entry, and retries add more work. It does not
prove dictionary traffic caused the original database incident.

## Operational implications and immediate mitigation

- Stagger server starts to reduce overlapping publication/comparison and foreign
  warmup. Already-running listeners have no supported dedicated prefetch-off
  switch in this source; staggering does not stop their existing retry loops.
- Compare repeated failures by caller host plus exact source tuple, not only
  sourceServer. Capture wait events, blockers and query costs during recurrence.
- Do not use dictionary.enabled=false as an incident shortcut: it skips local
  publication/provenance setup, rather than disabling only background warmup.
- dictionary.publicationTimeoutSeconds defaults to 30 and controls publication
  statements and the background dictionary connection's network timeout. Raising
  it does not change the fixed five-second source SELECT timeout. There is no
  independent configurable read timeout in this implementation.
- A larger pool or longer timeout is not containment and can increase concurrent
  database work. Establish wait/plan evidence before tuning either.
- Do not substitute current/local/latest dictionary IDs when an exact source is
  unavailable. Preserve failed restoration as an explicit failure and retain
  persisted data for a later safe retry.

The V6 migration already defines entry PK(namespace_id,family,register_name,native_id)
and entry_state PK(namespace_id,view_id,family,register_name,native_id), which
match the join predicates. Missing deployed indexes, statistics problems,
table/history growth, inefficient plans and resource contention remain possible;
an extra index is not justified by these two logs. Ordinary SELECT does not
normally wait for publication's namespace row lock; check actual blockers rather
than attributing the failure to simultaneous row locking.

## Recommended containment design (not implemented)

Put bounded failure state in the shared exact-view preparation layer so listener
and player reads honor the same gate. Track attempt count, monotonic next retry,
last SQLSTATE and an in-flight load. Use capped exponential backoff with jitter
(for example 5, 10, 20, 40, 80, then 120 seconds) for transient failures and a
longer bounded cooldown for unavailable/incompatible sources. Bound failure
metadata separately from successful cache budgets. Keep only one retry probe per
exact view per JVM; cap total cold-load concurrency across views so many distinct
origins cannot bypass containment. A dependency-wide circuit breaker may be
appropriate during broad SQL failures. Allow recovery after cooldown; do not
permanently cache transient cancellation.

Keep LISTEN alive on per-view preparation failure, continue other eligible views,
and distinguish discovered metadata from successfully prepared metadata. Apply
separate jittered reconnect backoff to actual listener transport failures.
Budget/stagger background warmup independently from demand-driven login reads.
Avoid automatic retries within a single login operation; return an explicit
temporary unavailability result while its required source is cooling down.

Log READ_QUERY_TIMEOUT as a controlled classification for likely timeout events,
with per-stage time, configured timeout, attempt and nextRetryMs. Preserve the
original SQLSTATE/cause: currently load() wraps without a cause and prepare()
replaces it with 55000, obscuring transient versus unavailable classification
for callers. Rate-limit repeated diagnostics without hiding attempt counters.

Validate with production-class fixtures: concurrent same-view callers, different
views under a global limit, no SQL during cooldown, retry/recovery after expiry,
one bad view not starving healthy views, bounded failure metadata, reconnect
behavior and shutdown. Then compile the exact game target and test a disposable
multi-server/database environment. No Java tests/build were needed for this
documentation-only investigation; no runtime recovery is claimed.
