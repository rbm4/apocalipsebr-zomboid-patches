# Follow-up review 51: dictionary build/world-version metadata

Created 2026-10-28. Status: **approved/applied; full dictionary/runtime unverified**. Review50 is applied.
See the [subsystem tracker](../features/2026-10-27-subsystem-port-tracker.md),
[candidate diff](2026-10-28-merge-51-dictionary-build-version.diff) and
[preimage/verification record](2026-10-28-merge-51-dictionary-build-version.json).

## Proposed changes

The copied [dictionary repository](../../src/zombie/savefile/ApocBRWorldDictionaryDatabase.java)
still inserts42.21.0/world249 namespace metadata and uses those values to select
unchanged publications and load exact source views. Target
[Core](../../decompiled/zombie/core/Core.java) declares42.22.0, and
[IsoWorld](../../decompiled/zombie/iso/IsoWorld.java) declares WorldVersion251.
Change all three sites together to42.22.0/251; dictionary_format_version stays1
because this edit does not change the custom dictionary snapshot format.

Also select game_build, world_version and dictionary_format_version under the
existing namespace row lock, and reject incompatible metadata before validating
history or publishing. Otherwise ON CONFLICT DO NOTHING preserves an old namespace,
allowing publication whose namespace the updated reuse/load filters reject.
Keep the name check, owner/history checks, transaction rollback and revision logic.
The new error is SQLException22023, not HistoryChangedException: the existing
mirror's allocation-history rollover does not automatically bypass it.

## Upgrade limitation requiring an explicit decision

The [mirror](../../src/zombie/world/ApocBRWorldDictionaryMirror.java) derives its
default namespace UUID from server ID and save key, excluding game build, and can
reuse a persisted namespace marker. Upgrading the same server/save can therefore
encounter an existing42.21.0/249 namespace. This candidate **rejects it**, rather
than rewriting its history. A separate configured namespace can start target
publication; configuration or database records are not changed by this review.

The exact source-view load filter will reject historical42.21.0/249 namespaces.
Creating a fresh namespace does not migrate player payload provenance pointing
to old views. Historical decode/import and cross-build payload migration require
separate design and validation before upgrading real data. This candidate only
establishes target version metadata/selection; it does not make old saves or
mixed-version relational datasets compatible, nor resolve deferred vehicle gaps.

## Verification and recommendation

[Fixture](../../../tools/test_dictionary_version_port.py): **8 cases passed**
using the actual extracted candidate guard and JDBC ResultSet doubles: matching
namespace/revision advancement, missing row, wrong name, old/null game build,
wrong world version and wrong custom format. Insert/reuse/load SQL metadata
source checks passed and the candidate applies cleanly. No actual SQL query,
transaction rollback, complete repository/JAR compile, native dictionary capture,
real payload decode or client runtime was executed.

Decision: applied on2026-10-28 after user approval. Exact candidate equivalence
and all8 focused checks passed after application. The user prioritizes making the
port viable/running before planning historical-data migration. That work remains
deferred; the namespace guard remains active.
This is staged source integration; no installed game files, live data or production
configuration changed. The other pending work includes event-control metadata,
List-cache design, full build/runtime verification and stale-class cleanup.
