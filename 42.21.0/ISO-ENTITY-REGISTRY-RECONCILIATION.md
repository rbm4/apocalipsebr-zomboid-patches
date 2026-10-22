# Audited array access and explicit mutation notification, 2026-10-05

Array access by verified engine readers no longer makes an otherwise clean square
ineligible for the lazy ID cache. PZArrayList.apocbrReadOnlyElements returns the same
live backing array without declaring an uncontrolled escape. It does not copy,
retain, hash or scan the array. Known membership/order writers must use the tracked
list API or call apocbrElementsChanged immediately after an explicit raw edit and
before any ID/index consumer or callback. That notification advances the mutation
version and invalidates the existing position index. It never repairs registry IDs.

Audited server readers now use this path: IsoGridSquare property calculation,
underground removal (actual removal still uses remove), object queries, render-offset
processing and its internal rendering readers; IsoChunk.saveObjectState;
LoadGridsquarePerformanceWorkaround.LoadGridsquare; LootRespawn's square traversal;
and IsoGenerator.updateFridgeFreezerItems(IsoGridSquare). Array slots are read only.
Object/item property edits do not themselves change list ordinals; membership edits
made by callbacks through normal list methods still mark the list dirty. The exact
array reference, order, loop bounds, callback placement and client getElements calls
are preserved. The temporary inventory-object sort is not a square-list reader and
is unchanged. The new overrides' bodies otherwise match exact-version vanilla.

Existing list add/remove/set/clear/bulk/reallocation paths already notify through
objectIndexChanged. No audited direct square-array slot writer was found in these
flows, so no additional engine writer hook was necessary. Other public getElements
callers, including unaudited vanilla/mod code, remain uncontrolled escapes and retain
fallback. Java array writes cannot be intercepted automatically; disabling this last
fallback would require an explicit new mutation-notification contract for all writers.
This patch does not promise coverage of arbitrary third-party retained-array writes.

The production haveFire reader is exercised repeatedly in the fixture: 1,000 read
passes leave the version unchanged and incur zero warmed ID-index reads. An explicit
raw reorder plus notification refreshes both affected objects and permits subsequent
cache hits. Public getElements still disables caching. Source contracts verify the
new loading/loot/generator overrides change only array acquisition. The expected
production result is increased cacheHits and fewer rawArrayFallback calls for these
flows; verify with a fresh process because already escaped arrays cannot be declared
safe retroactively. No global reconciliation or scheduling change was introduced.

---

# Clean-square lazy ID cache, 2026-10-05

The server getter can return its initialized cached ID without object-index reads
when the square identity, primary-list identity, mutation version, coordinates and
floor status match the snapshot taken before the previous successful vanilla check.
Each object remembers its own snapshot; no shared clean flag is reset by a getter.
PZArrayList's existing structural/index invalidation path increments a long mutation
version for normal, bulk, iterator and sublist edits. Object reset releases cached
square/list references. Coordinate comparison also catches direct field changes.

Exposed backing arrays bypass caching until replacement ends exposure. Custom
getObjectIndex, isFloor or equality implementations bypass caching. Client calls
always execute the original vanilla getter body and clear server cache references.
Uninitialized or detached IDs are not cached. Exceptions leave the cache invalid.
Snapshots precede getter/map callbacks, so mutations during calculation force a
later check. ID formulas, scheduling phases, lazy map repair and collision behavior
remain those of vanilla, including its handling of same-index coordinate movement;
this optimization does not introduce a new registry freshness guarantee.

Cache telemetry is enabled by default and adds interval counters
isoObjects.entityId.cacheHits, vanillaChecks and rawArrayFallback. These count getter
calls, not saved milliseconds. Set -Dapocbr.telemetry.entityIds.enabled=false before
startup to disable their per-getter telemetry-map work. Raw-array-heavy production
may show little adoption.

The fixture checks unchanged-square avoided reads, independent refresh of affected
objects, coordinate/list changes, raw arrays, detach/re-add, custom getters, client
fallback, repeated container removal, and 2,000 randomized mutations against the
unmodified vanilla body, comparing both IDs and registry keys. No global registry
validation or scheduled repair was reintroduced. Live telemetry and multiplayer
transfer/reconnect tests remain necessary.

---

# Current status: vanilla lazy IDs restored

The reconciliation implementation described below is retired. Production now uses
exact Build 42.21.0 vanilla GetEntity, RegisterEntity, UnregisterEntity,
checkEntityIDChange and setSquare methods. The getter now has the conservative
clean-square cache described below, with the original vanilla body as fallback. ID getters
refresh changed object ordinals lazily; registry lookup itself does not repair IDs.
ServerIsoEntityRegistry and all collection/square mutation hooks were removed.
No tick-level, coordinate-scoped or global registry reconciliation remains.

IsoObject scheduling, square indexOf acceleration, telemetry and unrelated entity
simulation optimizations remain. Registry-specific telemetry should disappear once
all rebuilt classes are installed and the server is restarted. This is an intentional
return to vanilla semantics, including its existing lazy lookup and collision behavior.

The registry test asserts manager methods and the getter fallback body match
exact-version vanilla and checks lazy old/new ID lookup, registration/removal, and repeated
container-item removals on both server and client. The integrated eager-registry
benchmark is retired because its lookup-before-getter contract no longer applies.
The following sections retain the historical investigation and superseded proposals.

---

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
reconciles first; tracked native IsoObjects now use the local retirement path below
for unregistration. A registered object's direct ID getter
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
their registered members at relevant coordinate/object boundaries even when no
method reported an edit. The coordinate-scoped follow-up below removes the former
world-wide validation at every boundary. Cost is proportional to selected raw
members plus globally notified dirty/pending work; it is not an O(1) guarantee. Replacing the backing array on
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

## Loading regression correction

The initial patch flushed before the RegisterEntity component eligibility guard
and before UnregisterEntity's registered-state guard. GameEntity.addToWorld invokes
RegisterEntity even for ordinary objects with no components, so grid-square loading
could repeatedly scan every exposed registered bucket without registering anything.
That amplification was not represented by the earlier five-registered-object
integrated replay.

The flush now runs only after those guards pass. A production-method loading probe
with 128 exposed registered objects and 4,000 skipped registrations plus 2,000
skipped removals went from 768,000 raw-member validations to zero. Illustrative
single-run fixture time was 37.412 ms before and 0.653 ms after; these are not live
chunk-load measurements or stable timing guarantees. The work-count regression is
asserted by the default registry fixture, which now passes 70,458 assertions.

Actual eligible registrations/unregistrations and registry reads still validate
the full exposed registered set. That remaining O(boundaries * exposed members)
path must be measured under loading-scale populations; this correction does not
claim that all registration amplification is solved. No registry freshness checks
were removed from actual consumers and no live deployment was performed.

## Demand-driven survivor refresh during retirement

Tracked native server IsoObjects no longer perform the global pre-unregistration
flush. Under the map lock, beginRetirement resolves only the retiring object's
current ID, removes its recorded registry key only if still owned by that object,
and releases its tracking immediately. Surviving entries retain their dirty state
until an actual lookup/registration or changed-ID getter requires reconciliation.
No end-of-tick sweep was added: GetEntity continues to guarantee the existing
consumer boundary independently of when unloading occurs.

The retiring-object guard suppresses incidental registry publication by its getter
through the entire engine removal and component/meta-transfer operation. Consumer
callbacks still run normal GetEntity refreshes for survivors. Reentrant removal of
the same retiring object returns to the owning outer operation. The guard is released
in finally, and failures evaluating the ID before retirement preserve the entry for
retry. Custom-ID subclasses and client paths retain their prior lifecycle path.

MetaEntity.alloc continues to obtain the locally current ID, not a stale registry
key. Actual meta registration is still a registry consumer and may reconcile
survivors; the optimization does not suppress it or callback lookups. Physical
square removal, engine notifications and component transfer remain in their original
order. No IDs or packets change format and no scheduling decoupling is included.

The unload-scale probe removes 128 registered objects with retained arrays and no
consumer callbacks: raw-member validations drop from 8,256 to zero. Illustrative
single-run timing was 4.118 ms before and 2.720 ms after (not a live unload benchmark
or timing guarantee). Tests explicitly check callback freshness, no resurrection,
reentrant removal, retirement failure/retry, and production component offload/reload
with a fixture MetaEntity copying the original current-ID contract. The registry
fixture passes 70,743 assertions; the integrated replay smoke and using-player
fixture pass. Native meta serialization/persistence still needs multiplayer testing.


## Coordinate-scoped raw-array validation, 2026-10-05

Production captures showed that the eligibility and retirement fixes did not solve
eligible loading registration amplification. Seq 6 spent 47,580 ms on 59,576,273
raw-member checks; the next attachment includes seq 17 (18,156 ms / 13,595,603 checks)
and seq 18 (64,578 ms / 54,334,011 checks). The latter stalled one tick for 41.53 s.
These interval counters repeat visits; they are not unique entity populations.

The registry now indexes registered square buckets by the lower 40 coordinate bits
of the unchanged vanilla entity-ID formula. GetEntity validates retained raw arrays
at the requested coordinate before returning hits or misses. Registration and a
changed-ID getter select the object's old tracked bucket and current coordinate.
Dirty buckets and pending entries reported by normal mutations still drain globally:
this preserves cross-square moves, cyclic ordinal changes and exception retry.
Raw exposure alone no longer makes every boundary scan every registered square.
Bucket location membership is updated on coordinate repair and removed on retirement,
unregistration and reset. Signed floor carries are retained by masking the original
coordinate sum; no packet, ID encoding, public getter or client behavior changes.

The local-boundary regression fixture has 1,024 exposed registered objects. A raw
permutation lookup checks four local members; 200 eligible registrations on new
squares check zero unrelated raw members. Existing same-square raw swaps, direct ID
getters, coordinate changes, collision ownership, retry, meta roundtrip and client
fixtures remain. These work counts establish avoided traversal, not live CPU savings.

Container item transactions resolve the square/object/container ordinals directly in
ContainerID.findObject, not through GameEntityManager.GetEntity. Item inventory edits
do not inherently change the owning IsoObject's ordinal. The added fixture executes
the exact vanilla ObjectContainer resolution branch, repeatedly removes/reinserts an
item, checks the stable object ID, then shifts the owning object by removing a world
neighbor and checks updated container resolution and entity lookup. Engine storage is
stubbed; this is not a network transaction end-to-end test. No timeout, security,
serialization, inventory identity or transaction acceptance checks are relaxed.

Vanilla TransactionManager's client path expires zero-duration transactions after
20 seconds (positive-duration transactions use duration + 10 seconds). Observed
38-41 second stalls can therefore disrupt transfers even without a container lookup
bug. This is a supported explanation, not proof of the reported failures: packet/log
captures and live transfer/reconnect tests are still needed. Arbitrary unnotified
square-coordinate field writes remain outside the existing tracking contract.


Validation of this follow-up: registry fixture (77,210 assertions), simulation and
mutable-square-list fixtures, using-player fixture (190,136 assertions), unload
fixture (1,495,716 assertions), and player/packet fixture (45,359 assertions) pass.
Full 42.21 game-JAR dry-run compilation succeeds: 86 sources / 339 class files.
Nothing was deployed. Live validation must cover simultaneous logins/exploration,
repeated chest/bag/vehicle transfers, object removal/reordering, reconnect, and meta
unload/reload. Compare registry visit counts and map-loading peaks under similar
load before claiming a production speedup or that all reported transfer failures
are resolved.


## Investigating persistent exposure

The latest capture still showed 100% raw-array fallback. The local installed class
set does not contain the audited reader API, despite the working tree containing
it. Full deployment/classpath consistency must be checked before assuming a new
reader algorithm failed. Default-enabled, bounded first-exposure telemetry now
identifies the actual Java caller and attributes later fallback calls to it. It
also counts audited reads. See TELEMETRY.md for sample bounds and coverage limits.
No fallback was weakened and no gameplay or registry semantics changed for this
diagnostic. The fixture checks real caller attribution, repeat-read suppression,
retained fallback attribution, audited-reader visibility and the 128-sample cap.
