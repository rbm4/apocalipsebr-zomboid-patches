# Review 47/48: IngameState

Created 2026-10-28. Status: **candidate ready for individual approval**.
Review46 is applied. This one-class candidate is not applied. See the
[subsystem tracker](../features/2026-10-27-subsystem-port-tracker.md),
[candidate diff](2026-10-28-merge-47-ingame-state.diff) and
[source verification/phase list](2026-10-28-merge-47-ingame-state.json).

## Old custom intent and target baseline

The [old patch](../../../42.21.0/src/zombie/gameStates/IngameState.java) adds
19 ApocBRServerTelemetryLite scopes around existing updates in
[42.21 vanilla](../../../42.21.0/decompiled/zombie/gameStates/IngameState.java).
It measures phases; it does not change update frequency, iteration algorithms,
phase ordering or gameplay policy. Historical metric context is in the
[42.21 telemetry guide](../../../42.21.0/docs/features/2026-10-01-telemetry.md).

[42.22 vanilla](../../decompiled/zombie/gameStates/IngameState.java) selects startup
music through Core.isTutorial() and removes the old Core.lastStand exit assignment.
Retain both changes. All target control flow is used as the base for instrumentation.

## Candidate

Add the same 19 phase keys: 17 resources alongside existing profiler areas,
plus wrappers around GameTime.update and IsoWorld.update. They cover game time,
scripts, world sounds, fire, rain, meta, virtual zombies, collision, population,
native/Java pathfinding, loot respawn, world, entities, animal sync, radio, misc,
OnTick and managers. The companion JSON lists their exact names.

Keep all underlying calls, pause/branch conditions, profiler scopes and catch
clauses unchanged. Native and Java pathfinding remain mutually selected by the
original condition. No retired LootRespawn override is restored: this only times
the native LootRespawn.update call.

The [existing helper](../../src/zombie/ApocBRServerTelemetryLite.java) returns a
new Scope, samples System.nanoTime and records on close whenever a scope is
reached. It is not a client/output-disabled no-op. The candidate adds no such
gating or new configuration. Nested phase durations can overlap and should not
be summed as exclusive costs. No runtime overhead or speedup is measured here.

These are the IngameState paths when executed, not proof of dedicated-server
emission. Main server-loop/telemetry bootstrap hooks and the broader GameServer
integration remain review48. This patch does not initialize the helper or change
runtime deployment/telemetry settings.

## Verification and decision

The candidate applies cleanly to exact target vanilla. All 19 names match the
old patch. Reversing only the added scope/resources restores the complete target
source text, including its tutorial and exit changes. The helper's Scope/phase
API was source-reviewed. No new fixture is added for these observational wrappers;
no full-JAR compilation, real simulation execution, telemetry capture, timing
accuracy or overhead validation is claimed.

Recommendation: apply this one-class instrumentation candidate after its exact
native baseline. Then prepare review48 GameServer, preserving the already-applied
review38 connection-slot reservation when integrating its remaining custom hooks.
Do not overwrite that existing source with another vanilla baseline.
