# Runtime replay failure isolation (42.21.0 server)

A `player-live-v1` entry failing with SQLState `22000` can be an incomplete
relational capture, not a lost PostgreSQL connection. Retrying the immutable
capture cannot make its unsupported state complete. Previously that entry held
the global FIFO head, entered `RECOVERING`, and prevented unrelated replay,
logins and synchronous account/admin database calls.

During normal runtime, connection/transport failures still retain the active head
and enter the existing outage recovery flow. Operation-specific failures no longer
close global admission: SQLState classes `22` (invalid data) and `23` (constraints)
are quarantined on the first failure; other non-connectivity SQL/runtime/linkage
failures get five attempts, 250 ms apart, before quarantine. A successful fifth
attempt commits normally. Successful operations keep their original FIFO ordering
around the isolated entry. Later operations for the same player also continue;
they may replace an invalid older capture with valid current state.

Quarantine is not a database commit or silent discard. Before advancing the active
checkpoint, the journal atomically writes and flushes the original operation UUID,
timestamp and complete detached payload to
`<journalDirectory>/quarantine/<operation UUID>.record`, with CRC32C protection.
No success receipt is fabricated. An archive/checkpoint failure retains the head;
a crash between archive and checkpoint completion resumes quarantine without
executing the rejected operation again. Existing journal records/checkpoints are
compatible; no PostgreSQL migration is needed.

For player operations, a small checksummed `.pending` marker also protects that
identity/slot against loading an older relational head. Other identities remain
readable. This marker survives journal compaction and process restart. A later
successfully committed full live graph, or a newly published character life,
clears that player's marker. Scalar-only patches and legacy native blob saves do
not clear it. An existing SQLite backfill can skip writing an already complete
head, so it resolves the marker through its next complete live graph instead.

The affected player's simulation/capture continues while this marker exists.
If no later complete capture can commit, that player's reload remains blocked
until the underlying capture/data problem is corrected. The rejected payload is
retained even after resolution. It is not automatically replayed later: applying
an old capture after newer state would risk reverting the player.

`REPLAY_FAILED` retains the original detailed diagnosis. `REPLAY_QUARANTINED`
reports UUID, kind, attempts, SQLState, sanitized capture issues and whether a
player reload was blocked. Periodic progress includes `blockedPlayers`. Existing
`persistenceStatus()` durable/staged totals count the active replay queue, not
retained quarantine archives. A zero active backlog can therefore coexist with
a quarantined player's reload block. Do not remove the journal or its quarantine
files as an outage workaround.

Startup/shutdown retain their existing five-attempt discard policy for active
entries; see [startup and shutdown retries](persistence-shutdown.md). Already
quarantined operations are excluded from active replay in every phase, while
their player reload markers remain enforced. Filesystem/corrupt-journal failures
still require intervention because safe advancement cannot be guaranteed.

`python tools/test_postgres_resilience.py` exercises the production coordinator,
journal and projection adapters with fake JDBC. It reproduces a real incomplete
live graph, verifies unrelated SQL/native progress and login availability,
retention without success receipts, bounded local retries, transport recovery,
quarantine/checkpoint crash recovery, persistent player guards, and resolution
only by a newer committed full capture. Exact-game-JAR compilation validates
binary compatibility; a deployed multiplayer trial is still required.
