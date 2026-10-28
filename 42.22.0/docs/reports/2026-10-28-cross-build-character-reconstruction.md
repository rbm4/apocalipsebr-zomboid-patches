# Cross-build character login:42.21.0 relational state on42.22.0

Created2026-10-28. Status: **source investigation and recovery design; not implemented or runtime validated**.
The user stopped the individual review queue and prioritizes runtime compatibility.
Review56 remains unapplied. See the
[subsystem tracker](../features/2026-10-27-subsystem-port-tracker.md) and
[build checkpoint](2026-10-28-build-check.md).

## What already permits the upgrade

[RelationalReader](../../src/zombie/savefile/ApocBRPlayerRelationalReader.java)
loads a complete compatible schema/codec head and validates graph counts. Its
head validation does not reject source_world_version249 simply because the target
engine is251. [Reconstruction](../../src/zombie/savefile/ApocBRPlayerRelationalReconstruction.java)
creates fresh target objects from typed fields. createSavedItem uses saved full_type
(and the dynamic Moveable sprite special case), not source_registry_id, to call
the current factory. Numeric dictionary renumbering alone need not lose these items.

Therefore42.21 characters do not necessarily fail just because their build differs.
Compatibility depends on available target definitions, field/type/layout changes,
embedded payloads and persisted source provenance. The source dictionary is an
ID-to-stable-identity map, not an archive of executable item/mod definitions.

## Confirmed failure paths

| Path | Current behavior | Needed recovery behavior |
| --- | --- | --- |
| Source dictionary view | Applied51 load SQL accepts only42.22.0/world251 namespaces; reader prepares every referenced opaque payload source before reconstruction | Permit read-only loading of explicitly supported historical source namespaces using exact namespace/view/server provenance; retain strict target publication checks |
| Target item unavailable or runtime class changed | validateItemType throws, aborting the entire character | Classify known missing definitions separately from unsupported type/schema conversion and unexpected failures; migrate supported item types |
| Dependent graph references | ref and structured references require every referenced object; ownership checks require coherent bags, locations and attachments | Build a detached dependency-aware recovery graph; skipping an item requires handling its dependent state and references, not only continuing the item loop |
| Opaque native payload | Source IDs map to current names, but missing definitions and changed script hashes fail; source binary layout still matters | Preserve original payload version/provenance and use only supported bounded decoders; explicitly quarantine unsupported components |
| New saved state | Sync.registerPostgresPlayer seeds source dataset, source_world_version and item identity aliases, with initialSync skipped | Establish a target-state persistence baseline after recovery before allowing normal delta tracking to represent the migrated graph |

The source dictionary can be used only if its exact namespace/view and related
rows still exist and are accessible. Local source cannot establish that production
retained those rows. Ordinary typed item restoration may not need an old dictionary;
opaque payloads with old numeric IDs can. Do not replace the active target dictionary
with the old one or relabel old namespace metadata to masquerade as target data.

## Recovery policy to implement

1. Read and validate a preserved source snapshot, its schema/codec, ownership,
   row counts and exact provenance before filtering. Corruption, ambiguous ownership,
   malformed graphs and budget overruns remain errors.
2. Inventory source definitions against target full_type/stable registry keys.
   Surviving definitions with different numeric IDs stay valid. Apply explicit
   mappings for known renamed definitions or fields and supported type changes.
3. Preserve supported character health/progression/state. Do not substitute a new
   blank player when reconstruction fails. Missing optional content is different
   from unsupported core-character structure.
4. Produce a detached recovery graph and a structured omission/quarantine report.
   Missing item definitions may be omitted from the reconstructed inventory, but
   dependent worn/hand/attached rows, weapon parts, components, visuals, containers
   and structured references need consistent treatment. Missing bag definitions
   require a policy for surviving contents: salvage/reparent or omit their subtree.
   No default for that destructive choice is implemented here.
5. Retain the old snapshot and original payloads. Validate the recovered graph and
   target player before committing a new target-version checkpoint and activating
   login/normal saves. Record dropped objects and reasons with source provenance.
   Retry must not duplicate items or repeatedly apply migration effects.

Do not implement recovery as a broad catch-and-ignore: database failures, programming
errors, arbitrary missing reflective fields and linkage failures are not evidence
that an item is safely disposable. Do not guess a replacement from the same numeric
ID in the target dictionary. Do not drop dependency rows from the original database
as part of a login attempt.

## Database write risk found in the login/save trace

The source snapshot is not automatically immutable. Original-data preservation
above is a recovery requirement, not a current persistence guarantee.
ServerPlayerDB.finishPostgresPlayer reconstructs first, then registers live tracking.
A failure during relational read/source dictionary preparation/reconstruction
occurs before that registration on this path; those reads do not patch character
rows. This does not prove every login/bootstrap/audit path is write-free.

After successful reconstruction, registerPostgresPlayer binds tracking to the
existing persisted character and item identities; initialSync=skipped means no
initial full import, not that saving is disabled. Mutation registration activates
sampling. Graph reconciliation is enabled by default with a30000ms default cadence,
and finalSample can request reconciliation on save/disconnect sooner. The mutation
repository applies DELETE/INSERT/UPDATE deltas to the existing head snapshot rows,
updates their counts/metadata and advances head revision while keeping the same
snapshot ID. A transaction can commit an internally coherent but semantically
incomplete reconstructed state. Old source_world_version is also seeded into the
tracking candidate, so there is no automatic clean target-version checkpoint.

Consequently an apparently successful cross-build login against writable original
data can persist omitted/defaulted state and item removals. Same mods and nominal
classes reduce compatibility failures but do not establish safe field/default/
component restoration. Use a separate database copy or independently restorable
complete backup, not only a world-file backup, for first runtime validation.
Disconnect is not a read-only rollback and an old snapshot ID is not a backup.

## Runtime validation focus

Start from a preserved42.21 complete relational character snapshot with known
inventory/progression and exact old dictionary view; use a separate target test
world/database copy before allowing normal autosaves to advance its head. Verify
unchanged items whose IDs moved, one missing item definition, a missing bag with
surviving contents, equipment/weapon parts, structured item references, opaque
payload provenance, failed migration rollback, reconnect/retry and save/reload.
Compare both what was recovered and what was omitted. A42.21 native SQLite blob
without a complete relational snapshot requires a separate native import path;
it is not automatically covered by this relational recovery design.

No runtime, database or migration execution took place in this investigation.
No source behavior or dictionary policy was changed. All known review54 build
results remain compilation evidence, not cross-build character runtime proof.
