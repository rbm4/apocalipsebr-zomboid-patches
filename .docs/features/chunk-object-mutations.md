# Experimental chunk object mutations

This is the capture and PostgreSQL write phase of chunk object persistence.
Enable with `-Dapocbr.experimental.chunkObjectMutations.enabled=true` alongside
experimental PostgreSQL chunks. The flag defaults off. Startup checks the
engine role's access to the V2 table. The existing migration owner applies
`V2__chunk_object_mutations.sql` through the same agent migration runner.

## Capture and worker

The agent observes ordinary `IsoObject` instances in published, loaded squares
on the dedicated-server owner thread. It hooks native tile and special-object
placement, successful square removal, sprite and custom-color setters, and
`flagForHotSave` (including ordinary container changes that call it). It records
a removal before the native list positions are lost and enqueues a tombstone
only after the object leaves the list. Native world detachment and object
recycling have different semantics from gameplay removal.

Changes mark the affected objects directly. A single capture pass at server-map
postupdate coalesces the dirty set, then calls only each dirty object's own
native `save` codec. Item and mod data stay in that opaque payload. The pass
applies a 32-record limit and a soft 1 ms budget, checked between objects.
The owner holds at most 1,024 dirty records and 8,192 weak identity entries.
A reusable 64 KiB buffer caps one encoded payload. The worker queue holds up to
256 detached records and the active batch up to 64 records. An object that is
too large, stale, or exceeds a capacity limit increments the gap count. The
game's native persistence still owns those changes.

PostgreSQL rows carry a run-scoped object identity, position, order, special-list
membership and monotonic revision. The worker groups writes over a short window
and commits up to 64 rows together. It retains a failed batch for retry and
uses the row revision to make the same batch retry-safe. A successful clean
quit first seals capture on the owner and then gives the writer a bounded drain
before the shared pool closes. The shutdown log reports committed, dropped and
uncommitted records.

## Authority and coverage

This table reflects observed mutations only. Chunk reload, client downloads, and unload-time durability
continue to use the native chunk snapshot. The table can hold observations
from multiple runs. The runtime identities distinguish observed object
incarnations within a run.

The initial hooks cover common operations and share a capture path. Vanilla
also exposes mutable object lists and nested state that can change without
calling any of these hooks. Reliable authority needs a baseline with persistent
identities, structural ordering and membership coverage, a revision-aware read
and client export path, and durable pending changes. Subtypes and missed
mutation paths still need dedicated coverage or native fallback. The worker
uses an in-memory queue. Crash-safe replay remains open.

## Validation

The Windows disk-to-PostgreSQL integration pass exercises actual ordinary tile
and special-object placement, color changes, and removal through the transformed
original classes. It checks the committed object payload and removal tombstone
as the engine role, plus hook origins and the existing full chunk reload and
save drain. Use an existing completed four-cell disk run with the
[engine migration verification switch](../validation/server-integration.md#disk-to-postgresql-chunk-migration).
The storage A/B benchmark leaves the projection disabled.

The Windows run against the original 42.22.2 JAR and PostgreSQL 17.6
passed with the shaded agent. The server completed all six streaming
cycles and shut down with four applied object writes, zero dropped captures
and zero uncommitted records. Evidence is retained under
`target/server-integration/run-9823235793340735137`.
