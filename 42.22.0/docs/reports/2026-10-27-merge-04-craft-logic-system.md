# Merge review 04/48: CraftLogicSystem

Status: **awaiting individual user discussion; not applied**. Reviews 01–03 are
approved and applied. See the [tracker](../features/2026-10-27-subsystem-port-tracker.md).

## Inputs and candidate

- [Old vanilla](../../../42.21.0/decompiled/zombie/entity/components/crafting/CraftLogicSystem.java).
- [Old patch](../../../42.21.0/src/zombie/entity/components/crafting/CraftLogicSystem.java).
- [Target vanilla](../../decompiled/zombie/entity/components/crafting/CraftLogicSystem.java).
- [Unapplied candidate diff](2026-10-27-merge-04-craft-logic-system.diff).

The only upstream change removes an empty `if (GameServer.server) { }` block
after the craft-start/history calls. That deletion changes no statements with
effects. The three-way merge is clean and preserves the deletion. No target
replacement or baseline commit has been created for this class.

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

## Proposed decision and validation

Keep the upstream empty-block deletion and all three existing optimization
changes. Preserve recipe start/completion behavior, resource validation, detached
drying cleanup order and client exclusion exactly as expressed by the candidate.

If accepted, verify production selection/throttle/elapsed methods together for
loaded versus meta entities, zero/one/multiple owed ticks, phase crossings,
completion boundaries and detached drying cleanup before throttling. Exercise
valid/invalid resources and recipe transitions, not just the timing formula.
The existing old-version simulation fixtures provide reference coverage but do
not by themselves validate this target crafting implementation. Target-JAR
compilation and runtime crafting/drying/load-unload remain integration checks.

Decision requested: apply this candidate to CraftLogicSystem only.
