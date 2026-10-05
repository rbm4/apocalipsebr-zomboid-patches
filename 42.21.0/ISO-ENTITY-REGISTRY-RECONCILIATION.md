# Server IsoObject registry reconciliation, 42.21.0

Implemented 2026-10-05 as the prerequisite to scheduling decoupling. ProcessIsoObject
and its ID-derived phase selection remain unchanged in this pass.

## Consumer boundary

GameEntityNetwork.parse, GameEntityID.parse (including ResourceID), and network
Kahlua resource/component decoding all use GameEntityManager.GetEntity without
checking freshness. Outgoing serialization and lifecycle methods request IDs but
do not otherwise guarantee refresh of surviving objects shifted by square edits.

GetEntity now reconciles pending registered-server-IsoObject changes under the
existing registry map lock before returning either a hit or a miss. Registration
and unregistration reconcile first as well. A registered object's direct ID getter
also routes changed keys through this batch boundary, avoiding incremental key
collisions during permutations. Normal client/single-player getter behavior remains
vanilla. Java subclasses implementing their own getEntityNetID are not enrolled.

## Mutation tracking and cost

ServerIsoEntityRegistry tracks only registered IsoObjects inheriting the native ID
contract. It associates them with their square's primary PZArrayList. Normal list
edits mark that registered bucket dirty; successive edits coalesce. No world-wide
list scan occurs when there are no pending edits or raw-array watches. setSquare
rebinds membership, and square coordinate setters mark the list dirty. A direct
native square-field reassignment is detected when the old watched list is edited.
Refresh rebinds its tracking to the new list.

getElements exposes a retained mutable array. Such lists require validation of
their registered members before every registry boundary, even when no method
reported an edit. This deliberately costs O(watched registered members and their
index lookups); it is not an O(1) lookup guarantee. Replacing the backing array on
growth ends that old-array watch. Unregistration and Reset release tracking.
Arbitrary direct writes to square coordinate fields without any tracked mutation
are not newly intercepted; coordinate setters and normal object movement are.

## Reconciliation semantics

The original coordinate/index formula and floor ordinal zero remain. The server
getter computes its object index once per invocation and can be explicitly
invalidated for same-index square changes. Reconciliation first determines every
changed object's ID while suppressing incremental registry writes, removes only
old keys still owned by those objects, then installs new keys. Swaps and longer
cycles do not recursively collide with their own old mappings. An unrelated
incumbent is never overwritten: the collision is reported.

Only registration may publish an unregistered server IsoObject. This prevents
an old cached ID on remove/re-add from creating a phantom map entry before actual
engine registration. A tracked object detached before unregistration still leaves
the engine cleanly; an invalid detached ID is not offloaded to meta storage.
No auto-engine removal happens merely because an ID lookup observes detachment.

ID evaluation failures retain pending work for retry. Registry operations acquire
the map lock before the helper monitor; collection notification methods never
acquire the registry map lock. No async worker, packet format change, scheduler
bucket, new throttle, or live deployment is included.

## Validation and remaining runtime checks

`python tools/test_iso_entity_registry.py` compiles the production collection and
helper, extracted production registry/registration/unregistration methods and
IsoObject getter/setter methods with engine stubs. It checks new-ID lookups before
getter calls, normal removal/insertion, raw-array edits, cyclic permutations,
floor IDs, API and direct-field square movement, coordinate setter changes,
same-instance re-add, duplicate identities, genuine occupied keys, detached
cleanup, validation/ID exceptions and retry, reset, custom IDs, and client behavior.
It also runs randomized valid lifecycle/mutation sequences and compares every
mapping with an independently derived coordinate/index ID.

The existing simulation/collection and using-player fixtures cover shared
collection behavior. The vanilla IsoObject baseline is local commit 09ec7cd.
Full game-JAR dry-run compilation checks linkage. These stubs do not execute
native world loading, actual component packet callbacks, Lua integration, or
meta persistence: verify crafting/resource networking, unload/reload and meta
conversion, object movement and server restart in multiplayer before declaring
runtime compatibility. Scheduling decoupling is a separate subsequent change.
