# Runtime replay failure isolation (42.21.0 server)

For identifying items in retained records, see the
[read-only quarantine inspector](../issues/2026-10-28-quarantine-item-inspection.md).
The [SpriteConfig codec addition](2026-10-28-player-sprite-config-persistence.md)
documents support for new captures and the separate handling of older archives.

For native-ID changes and the policy for external mod-data references, see
[player-scoped item lifecycles](2026-10-17-persistence-player-items.md). Vanilla assignment
paths are documented in the [item identity investigation](../reports/2026-10-17-item-identity-investigation.md).

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

Updated 2026-10-28: rejected player saves no longer create persistent reload
blocks. Their immutable payloads stay archived, but the failed database mutation
is abandoned. Once other active saves for that player finish, loading uses the
last committed database state. Changes present only in rejected captures are
lost; no success receipt is fabricated and no archived capture is reapplied.

At journal initialization, old checksummed `.pending` markers are validated and
atomically renamed to `<marker>.<operation UUID>.released`. Each release logs
`PLAYER_REJECTION_RELEASED` with the operation UUID and `recovery=last_committed`.
This scans outstanding markers, not the full contents of every historical record.
Original `.record` files and released marker bytes are preserved. An interrupted
upgrade resumes on the remaining markers. Active journal entries still contribute
to the normal per-player pending count, even if their old rejection marker was
retired. No database head validation or reconstruction is performed by this
migration: absence/corruption of an actual saved character can still fail loading.
See [discard-and-reload policy](2026-10-28-player-save-discard-recovery.md).

`REPLAY_FAILED` retains the original detailed diagnosis. `REPLAY_QUARANTINED`
reports UUID, kind, attempts, SQLState, sanitized capture issues, `discarded=true`, `reloadBlocked=false`, and
`recovery=last_committed` for player operations. Periodic progress includes `blockedPlayers`. Existing
`persistenceStatus()` durable/staged totals count the active replay queue, not
retained quarantine archives. The `blockedPlayers` field now reports zero; active pending-player guards are
separate from rejected captures. Do not remove the journal or its quarantine
files as an outage workaround.

Startup/shutdown retain their existing five-attempt discard policy for active
entries; see [startup and shutdown retries](2026-10-17-persistence-shutdown.md). Already
quarantined operations are excluded from active replay in every phase, while
their old player reload markers are retired at initialization. Filesystem/corrupt-journal failures
still require intervention because safe advancement cannot be guaranteed.

`python tools/test_postgres_resilience.py` exercises the production coordinator,
journal and projection adapters with fake JDBC. It reproduces a real incomplete
live graph, verifies unrelated SQL/native progress and login availability,
retention without success receipts, bounded local retries, transport recovery,
quarantine/checkpoint crash recovery, same-player successor guards, bulk legacy
marker retirement, idempotent restart, and last-committed reload availability. Exact-game-JAR compilation validates
binary compatibility; a deployed multiplayer trial is still required.
