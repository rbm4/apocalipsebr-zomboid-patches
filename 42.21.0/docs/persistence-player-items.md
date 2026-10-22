# Player-scoped item lifecycles (42.21.0 server)

The current player's inventory is the ownership boundary. Bags recursively own
their contents and weapons own their attachments. Equipment and mod-data
references do not make another player's/world's items part of this tree.

The simulation-thread tracker seeds items from this tree at registration. For
PostgreSQL logins, the detached server profile includes `persisted_item_id` beside
each item's native ID. This metadata seeds the actual existing database UUID;
it is not added to any client packet. Initial imports obtain existing aliases
through their worker receipt, with the existing deterministic initial identity
used when no alias exists.

Each successful live capture resolves identity in this order:

1. The same Java instance in the previously observed owned tree retains its UUID,
   even if its native ID changed. Exact-instance matches are reserved first, so
   another item taking its previous ID cannot steal its identity.
2. A newly reconstructed instance can retain the previous UUID if its native ID
   matches an item in the immediately preceding owned tree and the previous
   instance is no longer present. This never merges two simultaneously owned
   objects or revives a lifecycle absent from the previous capture.
3. An incoming item without continuity evidence receives a new UUID. Neither item
   type nor matching serialized state establishes identity.

Weak references track engine objects only on the simulation thread. Membership
and identity metadata are bounded by the existing 10,000-item reconciliation
limit. Detached nodes carry only the resolved UUID. The newest capture therefore
retains identity through dirty-queue coalescing, native-ID changes during an
outage, serialization and journal restart without consulting an old engine
instance. Old journal nodes without the optional field continue using the
existing native-alias projection behavior.

Reconciliation changes the `item.native_id` field while preserving the item UUID
and UUID-derived container, visual, component and reference identities. In the
same write transaction, old aliases for deleted/reassigned items are retired
before any new alias is inserted. This includes ID swaps. The existing operation
receipt covers the transaction; rollback does not acknowledge the capture.

An observed departure removes the item's subtree and its links from the current
graph through the normal full-graph delta, and retires its active aliases. The
tracker forgets that lifecycle. If the object or native ID later returns, it
receives a new UUID. Historical snapshots and shared `object_identity` records
are retained; they do not represent current ownership or an active alias.

A structured item/container reference outside the accepted owned graph projects
as a typed null rather than rejecting the full player capture. The runtime
mod-data table is never modified. Owned references still target their accepted
UUIDs. Loading cannot reconstruct the external reference: Lua null entries are
absent/nil. This is the explicit boundary policy, not global world-item tracking.

`LIVE_METRICS.nativeIdChanges` counts net native-ID changes observed on continuing
tracked items. Temporary compression changes restored before capture do not
count. `externalRefsSkipped` counts out-of-scope structured references projected
as null, including repeated observations/replay attempts. These are cumulative
process counters, not counts of unique items or successful database commits.

Detection follows the existing graph cadence (normally 30 seconds) and final
samples. A leave-and-return entirely between observations cannot be identified
as a departure. A replacement with both a new Java instance and a new native ID
has no continuity evidence and becomes a new lifecycle. Client-only history is
not inferred. No native engine reassignment hooks, client changes, global item
registry, or schema migration are required.

Validation covers UUID seeding, bag/child reassignment, ID swaps and alias batch
ordering, departure subtree deletion, same-object return, native-ID reuse,
reconstruction, dirty-queue coalescing, serialized and journal-restart identity,
and owned/external reference behavior. Fixtures use real production classes with
fake JDBC; a deployed multiplayer transfer/drop/reload trial remains necessary.
