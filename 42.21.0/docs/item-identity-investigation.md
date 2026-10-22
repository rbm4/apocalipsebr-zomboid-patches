# Vanilla item identity investigation (42.21.0)

The resulting implementation is documented in
[player-scoped item lifecycles](persistence-player-items.md). The connector-gap
descriptions below record the behavior before that implementation.

This is a source investigation, not deployed instrumentation. The paths below
refer to the exact `42.21.0/decompiled/zombie/` baseline. They do not establish
that ordinary inventory IDs rotate periodically. They establish initial random
allocation, restoration, temporary mutation, and replacement paths that telemetry
must distinguish before changing PostgreSQL aliases.

## Confirmed assignment paths

| Vanilla location | Behavior | Measurement meaning |
| --- | --- | --- |
| `inventory/InventoryItemFactory.java:140`, `:176` | Assigns `Rand.Next(2146250223) + 1233423` to a newly created item. | Initial allocation, not proof that an existing logical item changed ID. Loading can immediately replace this provisional ID. |
| `inventory/InventoryItem.java:1952` (`load`) | Reads the item ID from the input buffer. `loadItem` at line 1888 can either create an item or receive an existing instance. `save` at line 1666 writes the current ID. | Saved/network identity restoration. Record whether the instance was newly constructed or already tracked; the factory-to-loaded transition is not a logical identity migration by itself. |
| `inventory/CompressIdenticalItems.java:49`, `:68`, `:98`, `:127` | Temporarily sets each compared item's ID to zero so serialized comparisons ignore identity; restores the original ID in `finally`. | Transient serialization state. Never publish zero as a persistent reassignment. Direct field writes bypass `setID`. |
| `inventory/CompressIdenticalItems.java:251` (`load`) | Reconstructs identical items from a shared serialized record, then restores each additional item's individual ID from the trailing ID list. | Reconstruction must be measured after the entire compressed group is loaded. An intermediate ID belongs to the template record. |
| `inventory/InventoryItem.java:3869` (`setID`) | Public setter, with no migration notification. The `id` field is also public at line 215. | Useful observation point, but insufficient coverage: vanilla uses direct assignments, and exposed/mod code may call the setter. |
| `iso/objects/IsoWorldInventoryObject.java:126` (`swapItem`) | Replaces the item Java object and copies the old item's native ID onto the new object. | Explicit old-object/new-object relationship with a retained native ID. A setter-only event sees the replacement's provisional ID, not necessarily the old logical item. Callers include wet/dry transformations and received `SWAP_ITEM` changes. |
| `iso/objects/IsoDeadBody.java:700` (`getItem`) | Creates a corpse inventory item, then overrides its random ID with the corpse object's ID hash when the corpse has an assigned object ID. Caches the resulting item. | Corpse-to-item identity derivation; not a periodic inventory renumbering. |
| `network/packets/SyncClothingPacket.java:194` | When inventory/worn lookup cannot provide an item, creates one and assigns the packet's ID. `processServer` calls this shared processing at line 226. | Server-visible reconstruction of an equipment representation. Do not count its factory ID as a previously persisted identity. |
| `network/packets/connection/ConnectedPacket.java:148`, `:189` | Applies received IDs to bare hands and newly reconstructed attached items in packet parsing. | Client connection reconstruction; relevant context, but a server-only patch cannot instrument execution on the unpatched client. |
| `inventory/InventoryItem.java:2208` (`createCloneItem`) | Debug clone loads the original state, then restores the clone's separately allocated ID. | A new clone intentionally has a distinct identity; do not merge it into the source item. |

`inventory/ItemUser.java` also creates replacement items for use/depletion through
`CreateItem`/`UseItem`. That path adds a newly created object and may remove the
consumed object without carrying its ID over. It is a transformation/creation
relationship, not evidence that the two identities should always be merged.

Compression is exercised by container save/load and network container packets,
so its temporary ID changes can occur frequently. A raw field-write trace can
therefore appear to show regular rotation even though the final ID is unchanged.

## Connector behavior before the lifecycle implementation

`ApocBRPlayerRelationalDatabase.projectLive()` looks up aliases by dataset,
character, and native item ID. An active alias supplies the persistent UUID; a
retired alias produces a new generation. `ApocBRPlayerRelationalCapture.item()`
otherwise derives a UUID from character and native ID. `updateAliases()` retires
deleted item aliases and allocates generations for newly inserted items.

These mechanisms do not establish continuity when the same item receives a new
native ID. They also do not establish global identity across player transfers:
the alias scope and default UUID derivation include the character. A transfer can
therefore produce separate database identities for the same logical item.

`UNOWNED_STRUCTURED_REFERENCE` in `Snapshot.copyPlayer()` is a separate check:
the table points to an engine object that has no copied node in that capture's
identity map. It compares Java references, without consulting native-ID aliases
or PostgreSQL. Persistent external references are a legitimate requirement, but
the current player-owned graph does not implement a resolver for them. Removing
the exception alone would leave unresolved references; following every reference
as owned inventory would incorrectly absorb another player's/world's state.

## Measurement points before identity migration

1. Observe completed `loadItem`, compressed-group `load`, `swapItem`, corpse-item
   creation, and server clothing reconstruction. Record operation category,
   before/after native IDs, and whether the object was new, previously observed,
   or explicitly replaced another object. Keep compression comparisons classified
   as transient and observe them only after ID restoration.
2. At the simulation-thread capture boundary, compare previously observed IDs
   for the same live Java instance. This detects net ID changes, including direct
   public-field writes, without reading engine state from workers. It misses
   changes that are reversed between observations; operation hooks provide the
   complementary evidence. Use weak identity tracking or bounded lifecycle
   cleanup so observations do not retain discarded world objects indefinitely.
3. Count separately: allocations, load restoration, transient comparisons,
   same-instance net ID changes, explicit object replacements, duplicate IDs,
   ownership transfers, and unresolved external references. Sample diagnostic
   events with a process-local observation token, reason, type, IDs, and static
   source context. Do not log table values or credentials. A Java identity hash
   alone is neither a unique token nor a durable identifier.
4. Only promote a proven logical relationship to a database migration. Carry
   immutable old/new identity evidence in the journal and update aliases and
   referencing records transactionally with the operation receipt. Do not infer
   continuity from matching item type, inventory position, or serialized state;
   legitimate identical items and clones exist.

## Requested persistence boundary

The current target is the player's owned item/container/attachment tree, not a
global world-item registry. Global continuity across transfers is outside this
scope. The required lifecycle is:

- An item continuously tracked under the player keeps its persistent UUID if its
  native ID changes. Update its native-ID alias; owned children, components and
  references continue to target the same persistent identity.
- Incoming items create a new lifecycle, or update the existing lifecycle when
  there is evidence they are already tracked within this player's tree.
- Once an item is observed leaving that tree, remove its subtree and associated
  links from the current player graph and retire its active aliases. Historical
  snapshots and identity receipts are not current ownership. A later return
  begins a new lifecycle even if the old native ID is reused.
- External mod-data references must not expand ownership or prevent saving the
  rest of the player. They cannot remain live foreign keys to deleted current
  player objects. Their out-of-scope representation needs an explicit codec rule;
  global external-object persistence is not required for this work.
- Native compression's temporary zero IDs are not lifecycle changes. Distinct
  items must not be merged merely because they have identical state or type.

The existing full-graph delta already removes rows absent from a new capture,
but identity selection currently depends on native ID. In addition,
`updateAliases()` handles inserted/deleted item rows and does not handle a
native-ID change on an otherwise unchanged persistent item UUID. Both must be
adapted together, with all obsolete aliases retired before replacement aliases
are inserted so ID swaps can succeed transactionally.

Same-instance tracking at capture boundaries provides evidence of net ID changes
while the item stays observed. Immutable lifecycle evidence must survive dirty
queue coalescing, journaling and replay; otherwise an outage or intermediate
capture replacement can lose the old-to-new relationship. Live engine references
must remain on the simulation thread. Continuity cannot be inferred from a
native ID alone after a recorded departure.

Periodic snapshots cannot detect an item leaving and returning entirely between
observations, nor prove a client's earlier identity when only a reconstructed
new object/ID arrives. Event hooks are needed if those transitions must be
distinguished. A process-local tracker also cannot by itself establish continuity
across restart; existing persisted identity must seed a new tracking session.

The investigation itself changed no engine source, packet format or database
schema. Source inspection is not a measured reassignment rate. Runtime changes
made afterward are described in the linked implementation document.
