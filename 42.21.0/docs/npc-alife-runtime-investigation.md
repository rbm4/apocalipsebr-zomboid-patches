# Installed Project A-Life runtime and persistence comparison

Status: source investigation and proposed design; no engine or database change.
This document precedes the PostgreSQL NPC migration/schema decision.

## Inspected source

The installed mod is Workshop `3803984183`, `ProjectALifeNPCs`, authored by Vice.
Its local `42.20/mod.info` reports modversion `1.4.5` and versionMin `42.21.0`.
The observations below describe this installed source snapshot, not every A-Life
release or the unrelated STALKER simulation. No live behavior was measured.

Source root:
`Z:/SteamLibrary/steamapps/workshop/content/108600/3803984183/mods/ProjectALifeNPCs/42.20/media/lua/`.
Line references below are relative to that root.

## What the source establishes

| Area | Verified implementation | Evidence |
| --- | --- | --- |
| Logical identity | One common actor record contains UID, revision, generation, lifecycle, profileId, factionId, world position, activity, intent and memory. | `server/ProjectALife/Core/ALifeActorRegistry.lua:470` |
| Lifecycle | Dormant/spawning/active/dead; spawning advances generation. Activation and dormancy validate revision/generation. Dormancy can retain position and shell state. | `ALifeActorRegistry.lua:760` |
| World representation | Shell creation delegates to `createZombieShell`; the default adapter uses `createZombie`, then hydrates/fences the shell. | `server/ProjectALife/Shells/ALifeShellAdapter.lua:80`, `:126` |
| NPC template catalog | Catalog parses separate faction and NPC-template registries. NPC profiles contain appearance, faction, stats, awareness, profession, role, behavior preset and other data. | `shared/ProjectALife/Records/ALifeCatalog.lua:42`; `ALifeCustomProfiles.lua:32` |
| Behavior preset | Separately resolves `actor.memory.behaviorProfile`; presets provide mode, roamWhenIdle and permitted behavior overlays. | `shared/ProjectALife/Records/ALifeBehaviorProfiles.lua:171` |
| Module registry | Registered evaluator functions have IDs, kinds, dispatch phases, priorities, optional primary status and enablement. Order is ascending numeric priority, with ID as tie-breaker. | `shared/ProjectALife/Behaviours/ALifeModuleRegistry.lua:46`, `:69`, `:112` |
| Module eligibility | Global switches, selected primary conduct, actor overrides, behavior-preset overlays and extra module rules determine eligibility. | `ALifeModuleRegistry.lua:187`; `ALifeModuleRules.lua` |
| Arbitration | Evaluate the cached phase chain in order. Return the first non-nil result. Catch evaluator failures and retain lastError. | `ALifeModuleRegistry.lua:214` |
| Decision sequencing | Threat response can return before ordinary decision modules. Decision contexts include threat, mode, orders and capability callbacks. There are special-case gates beyond the registry. | `server/ProjectALife/Decisions/ALifeDecisionLoop.lua:691`, `:1015`, `:1051`, `:1090` |
| Scheduling | Shared runtime ticks with subsystem budgets. Decision scheduling uses hot and round-robin lanes with debt accounting. | `server/ProjectALife/Core/ALifeRuntime.lua:757`; `ALifeDecisionLoop.lua:2473` |
| Perception | Shared candidate/visibility machinery uses NPC awareness, threat scoring, remembered contacts, retaliation and environment checks. | `server/ProjectALife/Senses/ALifePerception.lua:1157` and active wrapper `:1718` |
| Relations | Faction/group/support rules, grudges, reputation, player profession, authored stance and explicit orders contribute to relationship/hostility decisions. | `server/ProjectALife/Decisions/ALifeRelations.lua:32`, `:144`, `:185` |
| World persistence | A versioned snapshot is stored through `ModData.getOrCreate("ProjectALife.World.v1")`; save preparation filters which records are retained. | `server/ProjectALife/Core/ALifePersistence.lua:35`, `:163`, `:242` |
| Creator persistence | Custom NPC profiles have a separate server-authoritative store, `ProjectALife.CustomProfiles.v1`, plus text/INI backup and migration machinery. | `server/ProjectALife/Records/ALifeCustomProfileAuthority.lua:8` |

These NPCs use common runtime/record/shell machinery. Their differences come
from authored data, selected presets/modules, affiliations, memories and world
context. They are not each implemented as a separate Java NPC subclass. This
does not imply identical behavior or one undifferentiated ruleset.

Two uses of "profile" must not be conflated: the actor's `profileId` identifies
its NPC template, while `memory.behaviorProfile` selects a behavioral preset.
Registered modules are a further layer. Relationship state is another layer.

The inspected priorities are registered module-definition values, not proof of
a database-authored per-profile scheduling system. The installed runtime has
phase-specific ordering, local module timing/state and additional policy gates;
it is not simply a utility score across every action. Target scoring in perception
and module priority arbitration are distinct mechanisms.

## Consequences for our engine

The current registered `HumanProfile` selects one allowlisted `HumanBehavior`
factory. It establishes useful lifecycle/ownership boundaries, but selecting
`guide` or `idle` does not yet provide A-Life-style module composition. Perception
is currently a capability boundary with a visitor snapshot, not a general
environment/relationship sensing system. Do not persist those two current
behavior IDs as the full long-term behavior model.

Keep the implemented logical lifecycle, activity controller and bounded action
executor. Extend autonomous policy through a shared module runtime:

1. Scheduler determines due sensing/evaluation work under explicit budgets.
2. Perception publishes a read-only context/blackboard for that NPC.
3. Relationship policy interprets affiliations and durable semantic memory.
4. Ordered eligible reaction/decision modules propose action cycles.
5. Arbitration selects one plan according to phase, priority and interruption rules.
6. The existing executor runs the plan and publishes completion/failure/cancellation.
7. The winning module updates its own per-NPC state/cooldown from that outcome.

The activity controller remains above this pipeline: an autonomous module cannot
take over a character owned by dialogue/trade or override lifecycle shutdown.
Profiles should configure module bindings, not override engine methods to bypass
authority. Registered Java interfaces can cover eligibility/evaluation, plan
construction, outcome handling and state initialization. Shared perception and
relationship services should remain reusable rather than duplicated in each module.

The runtime needs explicit preemption and minimum-hold rules, cooldowns,
evaluation intervals, stable tie-breaking and work budgets. Otherwise a
first-applicable priority chain can repeatedly restart greetings, starve roaming,
or flap between targets. Evaluation and execution are separate concerns; continuing
an owned plan should not re-send its actions on every decision cycle.

## Proposed PostgreSQL/form model

These are candidate entities, not an executed migration or final DDL:

| Entity | Responsibility |
| --- | --- |
| `behavior_module` | Engine-supported module IDs, phase, capability requirements, defaults and parameter metadata for form generation. Implementation stays allowlisted in Java. |
| `behavior_profile` | Named, described, versioned configuration that administrators publish. |
| `behavior_profile_module` | Ordered bindings with enabled, priority, evaluation interval, cooldown, interruption/hold policy and validated JSONB parameters. |
| `perception_profile` | Typed sensing distances, visibility/floor policy and sampling intervals. |
| `faction`, `faction_relation` | Affiliations and authored baseline relations, separately from personal memory. |
| `npc_template` | Optional reusable appearance/stat/content defaults and selected behavior/perception profiles. |
| `npc_instance` | Logical NPC identity scoped to server/world, desired profile/template and administrative lifecycle intent. |
| `npc_runtime_state` | Separately versioned observed lifecycle, activation generation, position, applied profile revision and checkpoints. |
| `npc_memory`, `npc_relationship` | Versioned durable semantic facts, outcomes, affiliations/grudges/reputation where supported. |

Module bindings need real relational rows for useful forms, ordering, filters and
constraints. JSONB belongs to validated module-specific parameters/semantic
payloads, with a config version and engine-provided schema; an opaque blob for
the entire NPC would obscure editable fields and relationships. Administrative
desired revisions and engine-observed checkpoints require separate ownership so
a stale worker cannot overwrite an operator's edit.

Module registration remains code. PostgreSQL selects supported module IDs and
parameters; a database row must not introduce arbitrary Java class names,
reflection, Lua snippets or executable callbacks. Profile publication validates
module dependencies, exclusive lanes and required capabilities before activation.

For Spring Boot, a profile form can present name/description/revision, perception
selection, an ordered module table, and fields generated from each module's
parameter schema. Instance maintenance and relationship views should be separate
from reusable profile editing. Runtime diagnostics can report applied revision,
selected module, next evaluation and last outcome through sampled checkpoints;
there is no SQL query in a sensing/action tick.

## Initial guide seed and rollout

The first seed should reproduce the existing guide as an authored composition:
visible/same-floor visitor perception within the current eight-tile policy;
greet-eligible visitors before idle roaming; one-second attention, concurrent
wave/hello for 2.5 seconds, operation announcement with a four-second inter-phrase
pause, three-second return cooldown and two-minute visitor cooldown. Roaming
retains the three-second idle pause and current safe indoor movement policy.
Dialogue/trade retain their separate exclusive ownership and existing validation.
Announcements continue to read cached operation data through their content adapter.

This is an equivalence target, not a claim that these module bindings exist yet.
The module scheduler/contracts should be defined before freezing the persistence
schema and seed. The existing guide behavior can serve as the regression reference
while its decisions move into the shared pipeline.

Seeding a reusable guide profile does not authorize a migration to guess
server/world instance keys. Startup reconciliation must explicitly decide how
the existing JVM-created logical key `guide` is adopted, preventing duplicate
Ricardao instances. Logical activation generations and revision checks should
follow the observed A-Life distinction; native presentation ID handling still
requires our own transport-compatible solution.

No installed A-Life files were modified. No migration, engine behavior change,
backend form or live database operation was performed in this investigation.
Validation consisted of source-path/line inspection and documentation review.

Related: [implemented profiles/lifecycle](../NPC-PROFILES-LIFECYCLE.md),
[mandatory architecture contract](../NPC-ENGINE-ARCHITECTURE.md).
