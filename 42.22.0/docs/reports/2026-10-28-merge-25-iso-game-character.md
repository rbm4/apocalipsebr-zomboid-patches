# Review 25/48: IsoGameCharacter

Created 2026-10-28. Status: **approved/applied on 2026-10-28**. IsoPlayer review 24 is applied.
See the [tracker](../features/2026-10-27-subsystem-port-tracker.md).

## Inputs and proposal

- [Old vanilla](../../../42.21.0/decompiled/zombie/characters/IsoGameCharacter.java).
- [Old patch](../../../42.21.0/src/zombie/characters/IsoGameCharacter.java).
- [Target vanilla](../../decompiled/zombie/characters/IsoGameCharacter.java).
- [Candidate diff](2026-10-28-merge-25-iso-game-character.diff).

Keep existing indexed inventory/vehicle-query paths, zombie hit authority hook,
backend perk hooks and telemetry on target vanilla. The three-way merge is clean;
all old added/removed patch lines are preserved. Applied source matches the reviewed candidate after exact vanilla baseline `75f3e3a`.
Primary owner: INTEGRATION; shared owners: player inventory/spatial queries,
zombie authority, backend progression and telemetry.

## Existing optimization and behavior contracts

For server non-animal IsoPlayers, replace recursive inventory traversal with
ServerInventoryUpdateIndex. Tracked per-container routes visit nested containers
and IUpdater items, retaining child-before-parent update order. Nontracked/publicly
replaced item lists use a normal scan. Mutation revision/list-identity changes
trigger fallback from the appropriate cursor position, preserving the inherited
iteration contract. Other characters and clients retain native recursion.
This avoids repeatedly traversing inert inventory entries; correctness depends
on the tracked collection and nested item lifecycle integrations already staged.

Server checkIsNearVehicle uses per-cell spatial queries and the existing strict
vehicle.DistTo(character) < 3.5 threshold (Manhattan in this build), setting the
near-wall crouching state when appropriate. Clients keep native vehicle traversal.
The cache must observe vehicle membership/movement and frame revisions; the later
BaseVehicle port remains part of complete invalidation integration.

Retain health/stats/inventory and bag-modifier telemetry. Bag speed calculations
remain unchanged; measurement alone is not a speedup.

Before hitConsequences damage handling, notify ServerZombieController on server
zombie victims. Its current hit hook releases controlled zombies on weapon_hit;
it is guarded by controller ownership and occurs even before the ignore-damage
branch, as in the old patch. Do not silently move this authority boundary.

Retain previous-level capture and backend onLevelUp after native perk changes,
and onPerksLoaded after XP loading/checker updates. These hooks feed the existing
backend bridge; this review does not establish external delivery or database
persistence. No external service was contacted during preparation.

## Upstream retained

Keep new primaryHandItem/secondaryHandItem field and accessor behavior, held-item
mask/drop networking, new climbing/ladder APIs and realz state, corpse-container
context queries, portal forcing and new combat/hit-reaction changes. Retain the
new tutorial predicate in level-up paths. Do not restore removed imports, fields
or APIs from the old character class. Copied custom subclasses/adapters may still
need their own API adaptation to these new contracts.

## Verification and remaining checks

Source checks: zero text conflicts and identical old patch additions/removals.
The [support fixture](../../../tools/test_game_character_port.py) reconstructs the
candidate and runs **25 assertions** against the actual target inventory index,
tracked list and player spatial-query helpers with engine doubles. It checks
sparse inventory routes, nesting/callback mutation fallback, externally replaced
lists, spatial membership/movement invalidation and proximity behavior. It does
not execute full candidate character methods, zombie authority or backend hooks.

Applied ItemContainer/IsoCell/InventoryItem/Player hooks and copied helpers supply
staged dependencies. Later vehicle/moving-object/packet ports and custom subclass
adaptations remain. Verify live nested inventories/callback order, vehicle
movement/proximity, hit-authority handoff, level-up/load notifications and async
owner/thread interactions. Exact-JAR compilation, real combat and multiplayer
runtime checks remain pending. No installation or database was changed.

User approved: "yes". Applied-candidate equality and 25 inventory/spatial
helper assertions passed again. Full character/native integration remains
pending. No installed files were changed.
