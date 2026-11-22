# Managed native generators

Created 2026-11-11; refactored on the same date from server-only fixed-power rectangles. Status: implementation and isolated tests passed; dedicated-server/stock-client gameplay verification remains pending.

## Contract and authority

The 42.22.2 server exposes `ApocBRManagedGenerators.registerGenerator(id, x, y, z)`, `unregisterGenerator(id)`, `getStatus(id)`, `isManagedGenerator(object)` and `finishStartup()`. Registration accepts source tiles, not coverage rectangles. It returns admission, not proof of creation. IDs are 1..128 nonblank characters; X/Y are 0..1000000 and Z -32..31. There are at most 256 definitions, with one definition per tile. Repeating the same ID/tile is idempotent; movement requires unregister/register.

`GameServer.doMinimumInit` initializes the manager on the server Lua/main thread after LuaManager initialization; all mutation/API operations enforce that owner. `GameServer` updates it on the existing main loop, including empty/paused operation. Database workers publish detached results through the existing entity bridge and never mutate native objects.

The old `ApocBRFixedPower` class and the three `IsoGridSquare` power-getter overrides are removed. Clients and server now use native generator coverage, with no custom client Java or new packet format. The old class family is recorded in `tools/retired-class-families.json`; it must not be retained as an active override when deploying. The native IsoGenerator replacement is no longer marked retired.

## Source lifecycle

Initial sources enter a bounded queue. At most four active pre-warm jobs call the existing zombie-cleanup helper `ApocBRLobbyStreaming.keepCellLoaded(x,y)` every server frame, renewing those cells until the helper confirms full main-thread integration. No generator is created and no tile placement decision is made before that confirmation. Each job has a 120-second deadline starting on slot admission. Generator management does not activate virtual zombie population or block the Lua/server thread.

Statuses/logs distinguish QUEUED_FOR_PREWARM, PREWARM_BEGIN, PREWARM_LOADING, PREWARM_READY, READY and specific CREATION_DENIED reasons. Once materialized, a source does not keep its cell permanently loaded; later native unload/reload follows normal streaming. Saved known instances remain suspended until their startup pre-warm completes, even if the database catalog has already finalized.

On a valid integrated square it reuses its saved tagged native generator or constructs a normal `IsoGenerator` from `Base.Generator`, its native cell and square. The constructor adds/registers/transmits the object, so it is not added twice. The manager tags it `ApocBRManagedGeneratorId`, connects/activates it, initializes fuel to 10 litres and condition to 100, updates surrounding electricity, and synchronizes native state. A normal player generator occupying the tile is not adopted or overwritten. Duplicate saved managed instances are reconciled. Before new creation the square must have a supported floor and must not be solid or solid-transparent, matching native floor-placement checks without treating a transient player as a permanent obstruction. Saved instances also require a supported floor; their own collision flags do not reject reuse. A missing square after completed pre-warm is a placement failure, not an unloaded-chunk wait.

`IsoGenerator.addToWorld/removeFromWorld` track loaded tagged objects. Runtime protection uses a Java identity binding to the configured source, not just a mod-data marker. Marker repair is included in maintenance, and incoming object-mod-data mutations are rejected for bound managed sources. Chunk unload detaches native references; reload rebinds/reconciles the saved instance.

While the startup database catalog is pending, unknown saved managed sources are suspended. ExtractionMapUtils calls `finishStartup()` after a successful read or terminal failure/timeout; loaded saved sources without a matching definition are then retired. Definitions removed or disabled in PostgreSQL therefore do not keep supplying power through saved objects. An absent old source chunk is cleaned when it naturally loads. The optional lobby registration runs independently during the same startup event. Live catalog reload is not implemented.

## Permanent operation and protection

Only registered/bound native generators bypass the vanilla hourly depletion, condition-wear and resulting failure/fire/explosion block. Normal generator coverage, client appearance/sound, server world sounds and indoor toxicity rules remain. Native update still refreshes surrounding electricity when adjacent chunks load. Healthy sources are not repeatedly marked for hot save or synchronized each frame.

Java rejects deactivation, disconnection, fuel/condition changes, remote generator synchronization, object-mod-data changes and ordinary removal/destruction paths for managed objects. The incoming generator payload is consumed before rejection and authoritative native state is synchronized back, with rejection logs/corrections bounded to once per source per two seconds. Sledgehammer rejection occurs before its removal broadcast. Manager-owned retirement bypasses its own protection deliberately. Unexpected destructive code paths can be recovered by reconciliation while the source square is available; this is not a general guarantee against arbitrary trusted mods changing world internals.

ExtractionMapUtils additionally guards shared timed-action `isValid` and server `complete` for shutdown, unplug and pickup. Pickup is rejected before vanilla creates/equips the inventory generator; rejecting world removal afterward alone would risk duplication. Native actions and packets outside managed generators retain their existing paths.

## Configuration and persistence

The existing `LobbyFixedPower` boolean (default false) enables a lobby generator; existing saved values are not overwritten by that default. Exactly three new sandbox options select its source: `LobbyGeneratorX`/`LobbyGeneratorY` default -1 (unconfigured) and `LobbyGeneratorZ` defaults 0. `LobbyEnabled` is still required. Lobby rectangle/fence coordinates no longer determine electrical coverage. The lobby definition ID is `extraction-lobby`.

The backend's V48 adapts `mod_data.fixed_power_area` to `(server_id, world, area_id, enabled, x, y, z, revision)`. The entity facade's `listForServer` captures engine server ID/world, and ExtractionMapUtils registers `persisted-generator:<area_id>` for enabled rows. It validates the collection first, rejects duplicate source tiles, and rolls back admitted definitions on batch rejection. At most 256 rows/255 enabled persisted definitions reserve one possible lobby slot. Startup retries/deadlines are unchanged.

V47 rectangles are preserved in `mod_data.fixed_power_area_legacy_v47`; only exact single-tile entries migrate automatically, deduplicated deterministically by tile (enabled entries first, then area ID). Multi-tile entries require an explicit source coordinate chosen by the backend/operator. V47 itself is unchanged. V48 grants the engine SELECT on the current table and removes its archive access. See [backend contract](../../../../apocalipsebr-manager/manager/docs/fixed-power-areas.md), [mod startup](../../../../ApocalipseBR-ExtractionMapUtils/.docs/features/persisted-fixed-power.md) and [lobby configuration](../../../../ApocalipseBR-ExtractionMapUtils/.docs/features/lobby-fixed-power.md).

## Diagnostics and validation

`[ApocBRManagedGenerators]` logs initialization, admitted definitions, creation, READY with tile/fuel/condition/connection/activation and `transport=vanilla_generator`, waits, conflicts, rejected changes, suspension and retirement. Mod prefixes remain `[ApocExtractionFixedPower]` and `[ApocExtractionLobbyPower]`, distinguishing registration from Java READY. `[ApocExtractionManagedGenerator]` records denied normal timed actions. IDs are sanitized in Java and database-loader logs.

- `py -3 tools/test_managed_generators.py`: passed the production manager plus extracted native update/setter/sync/remove methods against engine doubles, covering deferred creation, deduplication/reuse, healthy no-save churn, long time jump, rejected setters and remote payload, marker repair, owner-thread enforcement, ordinary depletion, occupied tiles, unregister and stale-source retirement. `test_fixed_power.py` delegates to this replacement fixture.
- `py -3 tools/test_mod_entities.py --version 42.22.2`: passed 318 Java checks and Lua repository lifecycle/collection checks.
- ExtractionMapUtils Fengari harnesses `fixed-power-areas.lua`, `lobby-power.lua`, `managed-generator-protection.lua`: passed startup/rollback/deadline and server action protection, including no pickup inventory grant.
- Backend `ManagedGeneratorMigrationTest`: passed three tests against isolated real PostgreSQL with distinct roles; V47-to-V48 archive/migration, constraints, engine read permissions and denied writes/DDL are covered.

The [original streaming audit](../reports/2026-11-11-fixed-power-stock-client-streaming-audit.md) remains historical evidence for abandoning the rectangle getter design. Final target build results are recorded below. No deployment or live database migration was performed. Still test: real Kahlua exposure, paused startup streaming, save/restart/reconnect/chunk unload/reload, source disable/removal, stock-client lights/appliances, ordinary generators, player actions/packets and native sound/toxicity. Coverage is deliberately vanilla radius/floor range and respects exterior-generator settings.

Final exact-build validation: `42.22.2/patchApocalipseBr.ps1 -BuildOnly` passed with 366 sources / 1049 class files, retained in `42.22.2/artifacts/build_20261111_123010`. BuildOnly did not deploy anything.

## Validation/pre-warm correction (2026-11-11)

Registration now rejects out-of-range coordinates, unavailable world metadata and coordinates outside map bounds with ID, actual tile and specific reason. Pre-warm failure, timeout, missing square after warm completion, missing floor, blocked tile and existing generator cause terminal CREATION_DENIED statuses. A denied definition is not silently retried; unregister/register or a corrected server restart is required. Unexpected runtime failures log exception type and bounded sanitized detail.

The mod emits per-option CONFIG_REJECTED messages with value/type/range and distinguishes bridge rejection from exceptions. Packaged defaults remain false and -1/-1/0. See [the mod hotfix](../../../../ApocalipseBR-ExtractionMapUtils/.docs/hotfixes/managed-generator-validation-and-prewarm.md) for example logs. The production manager fixture covers forced pre-warm readiness/renewal/capacity, timeout, invalid map cells and placement rejection; the Lua fixture covers explicit configuration diagnostics. Final exact-build verification is recorded below. Live empty-server streaming still needs runtime verification.

Pre-warm/validation final build: `patchApocalipseBr.ps1 -BuildOnly` passed (366 sources / 1049 classes), retained at `42.22.2/artifacts/build_20261111_130826`. No deployment occurred.
