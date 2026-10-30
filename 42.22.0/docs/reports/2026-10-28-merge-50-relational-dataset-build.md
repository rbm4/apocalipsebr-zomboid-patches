# Follow-up review 50: relational dataset build metadata

Created 2026-10-28. Status: **approved/applied; full persistence/runtime unverified**. Review49 is applied.
See the [subsystem tracker](../features/2026-10-27-subsystem-port-tracker.md),
[candidate diff](2026-10-28-merge-50-relational-dataset-build.diff) and
[preimage/verification record](2026-10-28-merge-50-relational-dataset-build.json).

The copied [relational database helper](../../src/zombie/savefile/ApocBRPlayerRelationalDatabase.java)
still passes game_build42.21.0 when persist inserts a source_dataset row.
[Target Core](../../decompiled/zombie/core/Core.java) declares GameVersion(42,22,"")
and getVersionNumber adds .0. The candidate changes that one insertion value to
42.22.0, matching this version-specific source target.

Keep dataset IDs, existing rows, worldVersion handling, transaction locks, receipt
claims, snapshot encoding and schema unchanged. The insert retains ON CONFLICT(id)
DO NOTHING, so this affects newly inserted datasets only: existing or reused older
dataset metadata is not rewritten. This does not implement cross-build dataset
migration, reject mixed-build reuse, or certify imported data compatibility.
Dictionary namespace metadata remains a separate review because it also gates
runtime-view selection. No database or installed game files are modified here.

Verification: exact target version declaration, the insertion site and its
conflict policy were checked; the one-line candidate applies cleanly. Applied on2026-10-28 after user approval; exact one-line candidate equality passed.
Source-only verification; no new test for the literal edit, SQL execution, full-JAR compile
or persistence/reconstruction round-trip. Next: review dictionary build/world-version selection.
