# Review 33/48: WorldDictionary

Created 2026-10-28. Status: **approved/applied on 2026-10-28**. Review 32 VehiclesDB2 is
applied with vehicle PostgreSQL gaps explicitly deferred by the user; that backend
is intended to stay disabled in production. See the
[tracker](../features/2026-10-27-subsystem-port-tracker.md).

## Inputs and proposal

- [Old vanilla](../../../42.21.0/decompiled/zombie/world/WorldDictionary.java).
- [Old patch](../../../42.21.0/src/zombie/world/WorldDictionary.java).
- [Target vanilla](../../decompiled/zombie/world/WorldDictionary.java).
- [Candidate diff](2026-10-28-merge-33-world-dictionary.diff).

Keep dictionary publication after native persistence and scope-aware ID decoding.
The merge is clean; old patch additions/removals are unchanged. Preserve the new
native log metadata version 251. Applied source equals the reviewed candidate after exact vanilla baseline `eb232bd`. Primary owner:
DB-DICT; shared owners: payload provenance, item/entity/sprite/object decoding,
local dictionary publication and PostgreSQL connector lifecycle.

## Existing hooks and contracts

After native rebuilding, readable export and data.save succeed, call
ApocBRWorldDictionaryMirror.afterRebuild before deleting the current backup.
Local dictionary files and native rebuild still allocate the local IDs. The mirror
publishes immutable local history rather than installing foreign IDs as the
current local dictionary. Publication prepares/publishes local encoding origin
for subsequent newly encoded payloads through the existing helpers.

The copied mirror enables publication only for server + apocbr.postgres.enabled,
apocbr.postgres.dictionary.enabled (default true), and save-enabled mode.
This is separate from apocbr.postgres.vehicles.enabled (default false). Disabling
vehicle storage does not disable dictionary publication. No flags/configuration
are changed by this review. A publication failure can reach the native startup
catch and prevent loading after local persistence; do not describe this hook as
best-effort or guaranteed nonblocking startup telemetry.

For item/entity info and type/debug-key lookup, and sprite/object names by ID,
use the active prepared source dictionary when ApocBRWorldDictionaryDecode.current
is nonnull. Otherwise call the exact native dictionary lookup. Type-to-local-ID
methods remain native. Foreign info resolution requires corresponding valid local
script/entity definitions; unprepared or invalid source context is not silently
replaced with unrelated local IDs. Source-view preparation and buffer provenance
must happen at the owning payload routing/decode boundaries.

## Upstream retained

42.22.0 changes the dictionary rebuild Log.Info world version from 249 to 251.
Keep that literal. The version change does not authorize relabeling old payload
versions or dictionary histories. This class does not complete the deferred
vehicle-buffer decode binding from review32 or other routing integrations.

## Verification and remaining checks

Source checks passed: zero conflicts, exact old patch added/removed lines,
native log251 retained, and mirror order after save/before backup deletion.
No new Java fixture was run for this proposal; the only upstream change is log
metadata. Copied mirror/decode/snapshot/database support is staged, not proof of
complete target compatibility.

Validate startup publication/disabled paths/failure behavior, dictionary view
history and ownership, prepared source decoding, nested scope restoration,
missing definitions and full native payload round-trips. Real connector/pool,
listen/notification lifecycle, schema compatibility, full target-JAR compilation
and native multiplayer/persistence checks remain pending. No installation,
configuration or live database was changed.

User approved: "yes". Applied-candidate equality and source checks passed.
Full publication/routing/native integration remains pending. No installed files,
configuration or live database were changed.
