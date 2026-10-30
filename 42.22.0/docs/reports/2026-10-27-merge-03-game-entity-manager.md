# Merge review 03/48: GameEntityManager

Status: **approved and applied; lifecycle fixture passed; target-JAR/runtime pending**. Reviews 01 and 02
are approved and applied. See the [tracker](../features/2026-10-27-subsystem-port-tracker.md).

## Inputs and candidate

- [Old vanilla](../../../42.21.0/decompiled/zombie/entity/GameEntityManager.java).
- [Old patch](../../../42.21.0/src/zombie/entity/GameEntityManager.java).
- [Target vanilla](../../decompiled/zombie/entity/GameEntityManager.java).
- [Applied diff against target vanilla](2026-10-27-merge-03-game-entity-manager.diff).

The old patch hash still matches the original audit. The three-way merge has no
textual conflicts. The user approved the combination with "yes". The exact target
vanilla baseline was committed separately as `02dec09`, then the approved diff
was applied to [target GameEntityManager](../../src/zombie/entity/GameEntityManager.java).
The behavioral patch remains separate from the baseline commit; no push occurred.

## Corrected subsystem attribution

The initial tracker incorrectly attributed square-scoped registry reconciliation
and demand-driven retirement to the current GameEntityManager, based on a
historical registry report. The audited source does not contain that
implementation. Its old-versus-vanilla diff contains only the update batching,
two telemetry scopes and three blank lines. The tracker and per-class ownership
now place it under **OPT-ENTITY**. This correction does not remove source code or
propose reintroducing an absent subsystem.

## Existing optimization versus vanilla

Vanilla calls `engine.updateSimulation()` once for each elapsed 100 ms simulation
tick. Our patch calls it once when any ticks elapsed. The copied `EntitySimulation`
records the elapsed tick count; the due-member index and `MetaSimulationThrottle`
select work and provide effective owed ticks to the patched craft/fluid/resource
systems. This avoids repeated complete system passes after a long frame.

Retain the existing frame update and two timing scopes:
`simulation.entities.frame` and `simulation.entities.simulation`. Timing remains
secondary; batching is the behavioral optimization. For zero ticks there is no
simulation pass, and the native client guard still suppresses server simulation.

This is an existing intentional scheduling change, not proof of equivalence to
every sequence of repeated vanilla callbacks. Verify aggregate elapsed work and
dependent-system coverage before claiming the complete subsystem is ported.

## What 42.22.0 changes

1. `GetEntity` calls the stored entity's ID getter under the existing map lock.
   If that ID differs from the requested key, it re-reads the map before returning.
2. Meta loading calls `GetEntity(storedID)` rather than accessing the map directly,
   so it crosses the same lookup check.
3. `UnregisterEntity` calls `engine.removeEntity(gameEntity)` for every non-null
   entity, before checking `addedToEntityManager`. This permits pending-add
   cancellation even when dictionary registration is absent.
4. Meta release checks `isAddedOrScheduledForEngine()` as well as removal flags,
   keeping scheduled entities out of immediate pool release.

These are the manager-side integration changes for the lifecycle fixes retained
in reviews 01 and 02. Keep their order and guards intact.

## Applied decision and validation

Keep every upstream lookup/unregistration/meta-release change and reapply only
our single-pass batching plus timing scopes. The candidate does this cleanly;
the blank lines inherited from the old patch have no behavior.

The installed source matches the approved diff. `python -B tools/test_entity_manager_lifecycle.py`
passed **31 target lifecycle assertions**. It extracts the actual target Update,
GetEntity, RegisterEntity and UnregisterEntity methods without rewriting their
logic. They execute with the complete patched EngineEntityManager and actual
EntitySimulation/MetaSimulationThrottle classes. Native entities/components,
engine dispatch, clock input, collections/pool, telemetry and world services are
explicit test doubles. The target GameEntity membership predicate is extracted.

Coverage includes zero/one/multiple elapsed ticks, retained effective ticks,
meta phase totals, client simulation suppression, timing-scope closure, lookups
whose ID getter changes/removes the requested registry entry, unregistered
pending-add cancellation, ordinary and duplicate unregistration, immediate and
deferred meta release, and meta loading via the refreshed lookup with component
transfer/dirty-chunk marking. Java assertions run enabled.

These checks validate selected production methods and their manager interaction,
not complete-class linkage, every offload path, full crafting callback behavior
or native concurrency. Full target-JAR compilation and runtime loading, unloading
and save/quit validation remain integration requirements.

Next decision: [review 04, CraftLogicSystem](2026-10-27-merge-04-craft-logic-system.md).
