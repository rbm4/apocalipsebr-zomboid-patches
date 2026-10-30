# Historical dictionaries during the 42.22.0 upgrade

Implemented 2026-10-29 for the dedicated-server PostgreSQL dictionary mirror.
Publication, exact-view reads and ID translation passed focused fixtures; all
303 patch sources compiled in a dry run against the installed world251 game JAR.
Real 42.21 character login and subsequent saving still require runtime testing.

## Startup failure and schema prerequisite

Backend migration V34 permits two namespace metadata pairs: 42.21.0/world249
and 42.22.0/world251, both with dictionary format1. It does not change the build
of an existing namespace. Previously, the Java publisher still required the
existing configured namespace to be 42.22.0/world251. Consequently an upgrade
using the same server/save or explicit namespace failed at LOCK_NAMESPACE with
SQLState22023 even after V34. The application-owned build mismatch message was
also absent from the diagnostic allowlist, hiding the useful reason.

V34 remains necessary for inserting the target namespace. No additional SQL
migration or change to an already-applied migration is required by this fix.
See the backend [runtime datasource guide](../../../../apocalipsebr-manager/manager/docs/game-runtime-datasource.md#build42220-dictionary-namespace-support-v34)
and [V34 migration](../../../../apocalipsebr-manager/manager/src/main/resources/db/game-runtime-migration/V34__Support_42_22_World_Dictionary_Namespaces.sql).

## Publication and restart behavior

[ApocBRWorldDictionaryDatabase](../../src/zombie/savefile/ApocBRWorldDictionaryDatabase.java)
classifies an existing 42.21.0/world249/format1 namespace as a supported older
build. It first validates namespace name and server/save ownership under the
namespace row lock, then rolls back with a typed BuildChangedException.
Unknown builds, mismatched build/world pairs, unsupported dictionary formats,
wrong owners and ordinary SQL failures remain errors.

[ApocBRWorldDictionaryMirror](../../src/zombie/world/ApocBRWorldDictionaryMirror.java)
handles this specific exception through the existing namespace rollover flow.
It writes the configured/replacement UUID pair to the save's
ApocBRWorldDictionaryNamespace.txt before attempting target publication, using
atomic replacement where supported. It logs BUILD_REPLACED and publishes the
current vanilla dictionary into a new 42.22.0/world251/format1 namespace.
The old namespace, build metadata, revisions and views remain intact.

An unchanged restart reuses the marker's replacement and its ready view. If the
replacement publication fails, the marker allows the next startup to retry the
same namespace. Preserve the marker with the save. Changing the explicitly
configured namespace ignores a marker for a different configured UUID, as before.
Allocation-history conflicts retain their existing HISTORY_REPLACED flow.

The target local dictionary still owns allocation and encoding. The previous
dictionary is never installed as the target allocator and old rows are never
relabelled to claim they were encoded by 42.22.0.

## Reading older bytes

Database load accepts exactly the two supported build/world pairs above with
format1. It still requires the payload's exact source server, namespace and ready
view, and limits identity rows to that view's revision. There is no fallback to
the newest view, current namespace or the same numeric ID in another dictionary.

The relational reader already prepares the exact dictionaries referenced by its
structured opaque values on its I/O path. Reconstruction retains each byte
array's source provenance and payload_world_version. Existing decode scopes
translate historical numeric IDs into stable names and resolve those names
against current game definitions; new encodings retain current provenance.
No changes to character rows or payload version numbers are needed for this
dictionary fix.

This enables the dictionary portion of cross-build character restoration. It
does not promise arbitrary old binary layouts are compatible. Missing current
item/entity definitions, changed script hashes, absent source views and malformed
bytes can still fail decoding. Untagged bytes retain the existing native lookup
behavior; the engine cannot infer which historical dictionary encoded them.
Supported typed fields can reconstruct independently of old numeric IDs.
See the [cross-build reconstruction investigation](../reports/2026-10-28-cross-build-character-reconstruction.md)
for remaining recovery and persistence boundaries.

## Validation and runtime checks

Commands from the patch repository root:

```powershell
python tools/test_postgres_world_dictionary.py --version 42.22.0
python tools/test_postgres_dictionary_decode.py --version 42.22.0
python tools/test_dictionary_version_port.py
.\42.22.0\patchApocalipseBr.ps1 -DryRun
```

Publication/rollover fixtures use production classes with JDBC doubles and
temporary marker files. They cover upgrade, preserved history, restart reuse,
counter rewind/remap, failed publication retry, owner/configuration rejection,
view reuse and listener lifecycle. Decode fixtures compile target getters and
scopes, covering old IDs mapped to different current IDs, nested payloads,
version-pair rejection and exact-view preparation. The SQL doubles do not replace
a PostgreSQL integration or game-login test. The installed JAR's IsoWorld.WorldVersion
was verified as251; the full dry build produced896 classes and deployed none.

On a separate database/world copy with V34 applied, start42.22 using the old
configured namespace and confirm BUILD_REPLACED followed by PUBLISHED. Restart
and confirm REUSED. Load a known42.21 character with a tagged opaque payload,
verify inventory/progression/component state, save and reconnect. Check that the
old source namespace/views remain readable and new payloads carry the replacement
namespace. Character saving can advance the existing relational head; the old
snapshot ID alone is not an independent backup.
