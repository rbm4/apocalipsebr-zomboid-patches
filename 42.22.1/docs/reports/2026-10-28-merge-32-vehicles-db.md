# Review 32/48: VehiclesDB2

Created 2026-10-28. Status: **approved/applied on 2026-10-28; PostgreSQL gaps deferred**. VehicleParts review 31 is
applied. See the [tracker](../features/2026-10-27-subsystem-port-tracker.md).

## Inputs and proposal

- [Old vanilla](../../../42.21.0/decompiled/zombie/vehicles/VehiclesDB2.java).
- [Old patch](../../../42.21.0/src/zombie/vehicles/VehiclesDB2.java).
- [Target vanilla](../../decompiled/zombie/vehicles/VehiclesDB2.java).
- [Candidate diff](2026-10-28-merge-32-vehicles-db.diff).

Keep optional PostgreSQL store selection and buffer provenance on target vanilla.
Text merging is clean. Add native-equivalent finite x/y rejection to the custom
PostgresStore row adapter, because upstream's new SQLStore guards do not cover
that custom backend. Preserve all native coordinate repair/serialization/thread
changes. Applied source equals the reviewed candidate after exact vanilla baseline `d9e1c1a`. Primary owner: DB-VEHICLE; shared owners:
streaming/persistence, payload provenance, connector lifecycle and vehicle loading.

## Existing PostgreSQL behavior

Use PostgresStore only for a server with apocbr.postgres.vehicles.enabled=true;
otherwise retain SQLStore. PostgreSQL startup initializes native used-ID and
seen-chunk sets through ApocBRVehicleDatabase and fails startup if that operation
fails. Native no-save checks remain. The custom store uses per-operation leases;
Reset does not close the shared pool, which ServerMap closes later.

Retain chunk/meta loading, native factory-header validation and native callbacks;
retain save/remove and logging behavior. Runtime store failures are currently
logged/swallowed by this old adapter, unlike its fatal startup path. This proposal
does not redesign acknowledgment/retry policy or claim every write is durable.
The SQL helper commits/rolls back its own operations; no live SQL was run here.

VehicleBuffer captures local encoding origin before native save, retains world
version from IsoWorld (now 251), and restores captured origin after copying bytes.
Raw byte/stream setters clear origin to avoid accidentally retaining prior buffer
provenance. Memory-store copies and PostgreSQL rows preserve an explicitly known
origin. Historical row versions/origins are not relabeled to the current server.

## New native behavior retained

Keep SQLStore.repairStoredVehicleChunks at startup: finite x/y positions determine
chunk coordinates using floor(x/8), floor(y/8), mismatches are updated and committed,
and invalid positions are logged. Keep SQLStore finite-coordinate load rejection.
PostgresStore now also rejects non-finite row x/y before buffer/header decoding.

Keep VehicleBuffer's native finite/world-validity check and coordinate-derived
wx/wy when capturing new snapshots rather than trusting vehicle.chunk. Preserve
safe script initialization on unload: only permitted game/loading/server threads
may initialize a missing script; unsafe callers skip that vehicle. Keep all other
load-completion and native streaming guards.

## Explicit follow-up gaps

Native startup coordinate repair lives inside SQLite SQLStore. PostgreSQL startup
currently only initializes IDs/chunk membership and does not repair old mismatched
wx/wy rows. New captures use corrected coordinates, but that does not fix stored
rows not subsequently captured. PostgreSQL repair/import consistency must be
resolved and verified before enabling vehicle PostgreSQL storage; do not run a live repair from this review.

The old patch carries buffer.origin through storage, but does not by itself bind
that buffer to a prepared dictionary view at each native vehicle.load call.
Audit/complete source-aware preparation and decode routing before claiming foreign
vehicle payload reconstruction works. Merely retaining provenance fields is not
proof of that integration. Later WorldDictionary/connector/support adaptation
work remains in scope, along with pool/shutdown and failure handling.

## Verification and remaining checks

The [candidate fixture](../../../tools/test_vehicle_db_port.py) reconstructs the
candidate without creating an override. It passed **83 assertions** against the
actual target ApocBRVehicleDatabase/ApocBRPayloadProvenance with isolated JDBC
proxies and a connector stub that rejects real borrowing. Checks cover insert/
update/chunk/meta/delete, foreign origin preservation, buffer copying, rollback,
query timeout, safe SQL failures and connection release. Source checks preserve
native repairStoredVehicleChunks, isSafeToSetScript and unloadChunk exactly and
confirm coordinate-derived chunk capture/custom finite-row guard.

The fixture does not execute full VehiclesDB2/PostgresStore, native vehicle
serialization/decoding, SQLite repair SQL, the real connector/pool, database
migrations or a live database. Target-JAR compilation, full store adapter,
worker/streaming/corrupt-row behavior and real migration/reconstruction tests
remain pending. No installation or live database was changed.

User authorized this staged port and explicitly deferred VehiclesDB2 gap repairs
because vehicle PostgreSQL storage will stay disabled in production. The source
default for apocbr.postgres.vehicles.enabled is false. No production configuration
was inspected/changed or claimed verified. Applied-candidate equality and all
83 isolated JDBC-proxy assertions passed again. Revisit the listed gaps before
enabling this backend; do not treat the entire persistence subsystem as complete.
