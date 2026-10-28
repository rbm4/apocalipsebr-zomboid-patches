# Merge review 04/48: CraftLogicSystem

Status: **approved and applied; target crafting fixture passed; target-JAR/runtime pending**. Reviews 01-03 are
approved and applied. See the [tracker](../features/2026-10-27-subsystem-port-tracker.md).

## Inputs and candidate

- [Old vanilla](../../../42.21.0/decompiled/zombie/entity/components/crafting/CraftLogicSystem.java).
- [Old patch](../../../42.21.0/src/zombie/entity/components/crafting/CraftLogicSystem.java).
- [Target vanilla](../../decompiled/zombie/entity/components/crafting/CraftLogicSystem.java).
- [Applied diff](2026-10-27-merge-04-craft-logic-system.diff).

The only upstream change removes an empty `if (GameServer.server) { }` block
after the craft-start/history calls. That deletion changes no statements with
effects. The three-way merge is clean and preserves the deletion. No target
replacement existed when the review was prepared. The user approved this merge
with "yes". The exact target vanilla baseline was committed separately as
`a1289d1`, then the approved diff was applied to
[target CraftLogicSystem](../../src/zombie/entity/components/crafting/CraftLogicSystem.java).
The behavioral patch remains separate from the baseline commit; no push occurred.

## Existing optimization

The old patch makes three coordinated changes:

1. Select `getSimulationEntities()` instead of scanning the entire craft bucket.
   The copied EntityBucket/index supplies loaded and due meta candidates in order.
2. Apply `MetaSimulationThrottle.shouldSkip(entity)` after validity checks.
   Detached drying-entity cleanup remains before this guard; the copied index
   deliberately keeps drying entities in its always-checked group.
3. Advance crafting with `getEffectiveGameSecondsThisFrame()` instead of one
   tick's seconds. This accounts for the elapsed/owed ticks supplied by the
   batched manager and meta throttle.

This belongs to OPT-ENTITY and completes a consumer of the batching retained in
review 03. It is an existing intentional scheduling/elapsed-time change, not a
new crafting rebalance or a proof of identical per-tick callback interleaving.

## Applied decision and validation

Keep the upstream empty-block deletion and all three existing optimization
changes. Preserve recipe start/completion behavior, resource validation, detached
drying cleanup order and client exclusion exactly as expressed by the candidate.

The installed source matches the approved candidate. `python -B tools/test_craft_logic_system.py`
passed **21 target crafting assertions**. It compiles the complete target
CraftLogicSystem with the actual EntitySimulation and MetaSimulationThrottle.
Bucket candidate lists, native entities/world queries, recipes, resources and
callbacks are explicit doubles; the real bucket index is not tested here.

Coverage includes loaded multi-tick progress, due and not-due meta work, zero
owed meta ticks, selected versus unselected entities, one update callback pair
per batch, detached drying cleanup before throttling, invalid entity/resources,
empty inputs, client exclusion, progress clamping, next-call completion without
duplicate outputs, dirty-output cancellation, requested stop/input return and
manual-start/history/request cleanup. Java assertions run enabled.

Target-JAR compilation and runtime crafting/drying/load-unload remain integration
checks. This fixture does not certify every recipe, automatic-start path or
callback interleaving. The approved merge survived the power interruption and
the focused fixture was rerun afterward.

Next decision: [review 05, SpriteConfig](2026-10-27-merge-05-sprite-config.md).
