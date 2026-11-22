# SpriteConfig persistence and quarantine recovery (42.22.0 server)

See the [2026-10-29 reconstruction/deserialization audit](../reports/2026-10-29-player-reconstruction-deserialization-audit.md)
for weight semantics, remaining load-time state differences and verification limits.
The [item weight round-trip repair](2026-10-29-player-item-weight-roundtrip.md)
documents the subsequent capture/reconstruction fix and mutation tests.

Ported on 2026-10-29 from the authorized 42.21.0 additions:
[SpriteConfig codec](../../../42.21.0/docs/features/2026-10-28-player-sprite-config-persistence.md)
and [discard/reload policy](../../../42.21.0/docs/features/2026-10-28-player-save-discard-recovery.md).
These changes do not implement the broader
[cross-build character recovery design](../reports/2026-10-28-cross-build-character-reconstruction.md).

## SpriteConfig save and restore

The target [native SpriteConfig](../../decompiled/zombie/entity/components/spriteconfig/SpriteConfig.java)
still saves script presence, a dictionary script reference, script version and
`isMultiSquareMaster()`. Its sync loader uses target world format 251. The custom
codec uses a structured map instead of native binary serialization, so no old
world-version literal or dictionary short is carried into this addition.

[ApocBRPlayerRelationalCodecs](../../src/zombie/savefile/ApocBRPlayerRelationalCodecs.java)
now supports `SpriteConfig` on carried items, including Moveables. It captures
`hasScript`, and when present `scriptFullType`, exact long `scriptVersion`, and
`wasLoadedAsMaster` sampled through native `isMultiSquareMaster()`.
The full script name is the key used by target
[ScriptsDictionary](../../decompiled/zombie/world/ScriptsDictionary.java).
Existing snapshot code detaches the map on the capture owner thread; projection
and JDBC workers never receive a live script or world object.

[Reconstruction](../../src/zombie/savefile/ApocBRPlayerRelationalReconstruction.java)
resolves `ScriptsDictionary.spriteConfigs.get(fullType)`, calls native
`readFromScript`, restores the saved master flag and rebuilds owner-derived caches
through `initObjectInfo`. Missing scripts/malformed state fail explicitly.
Script-version differences use current scripts, matching native 42.22.0 load.
Absent scripts clear any existing script and master flag. Runtime object, face
and tile caches are never persisted. Target primary/secondary hand restoration
and all existing dictionary provenance/namespace rules are preserved.

No schema migration is required: existing `entity.SpriteConfig` component/value
nodes are used with schema version 1 / codec version 4. This is additive readable
coverage, not permission to infer missing state from older detached captures.

## Rejected saves and startup upgrade

[ApocBRPersistenceJournal](../../src/zombie/savefile/ApocBRPersistenceJournal.java)
archives a rejected operation before advancing the active checkpoint. It does not
write persistent player reload markers. Once active operations finish, the player
can load the last committed database state. This abandons the whole failed save;
it does not silently drop one item or publish a partial character graph. Progress
present only in rejected saves is lost, including possible inventory transfers.

At initialization, while holding the journal owner lock and before workers start,
legacy `quarantine/*.pending` markers are checked using the existing checksum,
key/filename and referenced-archive contract. Each becomes
`<marker>.<operation UUID>.released` through atomic rename. The original archive
and marker bytes are preserved. Migration logs `PLAYER_REJECTION_RELEASED` with
`recovery=last_committed; reloadBlocked=false`. It is restart-idempotent and scans
outstanding markers, not every historical record's contents. It neither executes
old captures nor writes to PostgreSQL. Invalid markers/filesystem errors remain
explicit integrity failures.

[Coordinator](../../src/zombie/savefile/ApocBRPersistence.java) runtime rejection
logs retain UUID/kind/attempts and sanitized exception details, adding
`discarded=true; reloadBlocked=false; recovery=last_committed` for player saves.
SQLState 22/23 failures isolate immediately; other operation-specific failures
retain the existing bounded retry behavior. Real transport loss, active per-player
saves, audit/sale handling, receipt semantics and startup/shutdown budgets retain
their previous behavior. `blockedPlayers` reports zero for rejected-save guards;
it does not count active saves. Missing/corrupt committed characters can still
fail normal reconstruction; this policy does not manufacture a replacement.

## Inspection and deployment

The standalone [InspectQuarantine.java](../../tools/InspectQuarantine.java) is also
available for this target. Use a full JDK 25 and the matching deployed classpath,
not the stripped bundled runtime's source launcher:

```bash
JOURNAL='/home/pzuser/Zomboid/Saves/Multiplayer/servertest/postgres-journal'
COPY=$(mktemp /tmp/apocbr-quarantine.XXXXXX.record)
cp -- "$JOURNAL/quarantine/79ecc031-2c3e-48a4-b5ae-367c14323a42.record" "$COPY" &&
/usr/lib/jvm/java-25-openjdk-amd64/bin/java \
  -cp '/opt/pzserver/java:/opt/pzserver/java/*:/opt/pzserver:/opt/pzserver/*' \
  /tmp/InspectQuarantine.java "$COPY"
```

Copy the tool to `/tmp` first. Output includes player identity, item type/UUID/ID,
inventory path and components, highlighting `SPRITECONFIG_ITEM`. This is read-only;
the inspector does not connect to PostgreSQL or replay an operation.

Deploy with the target's normal patch script and restart to activate the codec
and automatically release old rejection markers. No new configuration flag or
manual deletion of quarantine is required. This task performs no deployment.

## Verification

- `python tools/test_sprite_config_42_22.py`: 17 focused checks using target
  production sources and the exact target decompiled native SpriteConfig class,
  compiled with the available game dependency JAR. Covers capture completeness,
  detached snapshot stability, long script version/name, native save-byte
  equivalence after restore, missing/absent scripts, master flag, version mismatch
  and rejection of missing old state. It reuses selected fixture definitions;
  it does not run or claim the full 42.21.0 login suite on this target.
- `python tools/test_postgres_resilience.py --version 42.22.0`: 728 checks across
  runtime, missing schema, shutdown, offline and startup. Production coordinator,
  journal and adapters run against fake JDBC/engine initialization. Covers legacy
  marker release, bulk archives, no false receipts, crash/restart, active successor
  guards, rejected-player readability and unchanged transport-loss handling.
- `./42.22.0/patchApocalipseBr.ps1 -DryRun`: attempted, but the current default
  installed dependency JAR is now world format 249 (`javap IsoWorld.WorldVersion`),
  not the target's 251. Full compilation fails with 435 errors in the broader
  target source tree against this older JAR. No installed classes were changed.
  Other discovered local JARs expose world versions 240 and 247. Full target-JAR
  compilation remains pending availability of the exact 42.22.0 installation.

The focused tests compile target persistence sources with explicit fixture
boundaries; the remaining game dependency JAR is the available world249 build.
The focused SpriteConfig runner also compiles the complete target native class,
so native byte equivalence uses the 42.22.0 baseline rather than the older class.
These are scoped regressions, not evidence of whole-engine binary compatibility.
Use `APOCBR_TEST_GAME_JAR` and the patch script's `-PZDir` to select an exact target
installation when it is available.

Runtime save/reconnect, furniture placement, client synchronization and real
database validation remain necessary. Old archive replay and broader historical
dictionary compatibility remain separate work.
