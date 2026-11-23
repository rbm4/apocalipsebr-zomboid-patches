# Experimental PostgreSQL chunk persistence

The root agent targets the original 42.22.2 dedicated server. The feature defaults
off and uses the existing shaded PostgreSQL driver and connection pool. It stores
the native serialized map payload, with one row per dataset and chunk coordinate.

## Start on a local PostgreSQL service

Create the database and two login roles through your PostgreSQL administrator.
The migration login owns the database or has permission to create and own the
agent schemas there. The engine login is a separate role, normally
`pz_server_01`. Supply both JDBC URLs through the service environment.

| Setting | Purpose |
| --- | --- |
| `APOCBR_POSTGRES_URL` | Engine JDBC URL, for example `jdbc:postgresql://127.0.0.1:5432/game_runtime?user=pz_server_01` with the site's configured authentication |
| `APOCBR_POSTGRES_MIGRATION_URL` | JDBC URL for the schema owner of the same database. Set it when starting with a new schema version. |
| `-Dapocbr.postgres.enabled=true` | Enable shared PostgreSQL runtime support |
| `-Dapocbr.experimental.postgresChunks.enabled=true` | Install the dedicated-server chunk hooks |
| `-Dapocbr.experimental.postgresChunks.dataset=<save-id>` | Stable, unique key for one save. Use one running server per dataset. |

A loopback TCP connection works with the bundled driver on Linux. The migrated
chunk rows and the rest of the world save form a single logical backup. Keep
their data and dataset identity together. Disk files behind migrated coordinates
will age because saves go to PostgreSQL. Returning to file storage requires
exporting the current database payloads first.

The migration runner applies bundled versioned SQL at startup in a transaction
under an advisory lock. It records versions and SHA-256 checksums in
`apocbr_agent.schema_history` and keeps map rows in `apocbr_world.map_chunks`.
Updates add new versioned resources and entries to the ordered migration list.
The migration owner grants the engine usage/create and table/sequence access
in `apocbr_world`, including default privileges for that owner. The history
schema stays under the migration owner. Existing manager schemas are outside
this runner. Once the schema is installed, the startup migration connection
can be omitted. The engine still verifies its chunk access before installing
the hooks.

## Native worker flow

- The loader takes the vanilla pending-save buffer first, then queries
  PostgreSQL. A missing row goes through the original file-existence check,
  disk decode or new-chunk generation. A database hit uses the native buffer
  decoder after validating the payload header and CRC.
- Both loaded and unloaded native save tasks still serialize the chunk. The
  save worker replaces their shared file writer with a synchronous upsert
  and commits before native completion. The first save imports the chunk even
  if its bytes match its old disk file. Subsequent identical payloads skip the
  SQL update. Vanilla drains the save worker before shared pool shutdown.
- The native map-download worker accepts a database hit through its existing
  buffer branch when an in-memory request buffer is absent. Misses continue
  through the original fallback. Connected-client validation is still open.

A database error or corrupted database header surfaces as an I/O failure.
Only a successful query with zero rows enables file fallback. The experiment
relies on native worker error reporting, and a failed load can strand a cell
request. Durable retry and automatic recovery are outside the experiment.
Use it on isolated copies until outage, immediate reload, connected-client,
restart, and Linux-host acceptance are covered.

Each lookup checks one coordinate using PostgreSQL's usual buffer cache.
The 64-row replay timings cover a different query shape and exclude save
commits and native codecs.

## Validation

The [live storage comparison](../validation/server-integration.md#live-chunk-storage-ab-benchmark)
runs matched disk and PostgreSQL arms for generation, migration, and
prewarmed reloads through the shipped worker hooks.

Run the [two-pass integration fixture](../validation/server-integration.md#disk-to-postgresql-chunk-migration)
against the original game JAR. It imports 255 disk chunks and generates one
missing chunk, then checks PostgreSQL reloads, preserved disk bytes, schema
history, and save drain in a disposable database.

The Windows run with original 42.22.2 and PostgreSQL 17.6 passed using the
shaded agent. It reused disk baseline `run-16483898819448985441` and retained
the migration run as `run-2800062976259645545` under `target/server-integration`.
Five PostgreSQL reloads each decoded 256 chunks, and the server exited cleanly.
