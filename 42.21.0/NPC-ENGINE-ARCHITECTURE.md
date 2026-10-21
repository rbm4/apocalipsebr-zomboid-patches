# NPC engine object-oriented architecture

Status: architecture contract for all NPC engine features. Registered profiles,
generic activity control, logical lifecycle and injected speech are implemented; live multiplayer playback still
requires validation.

Every new NPC capability and every change to an existing capability must respect
these ownership and dependency boundaries. This applies to AI, speech, movement,
dialogue, trade, transport and future roles. Prefer composition of small contracts
and reusable actions; create inheritance only for an actual shared lifecycle or
substitution requirement. A separate subclass for every combination of NPC role
and ability is not the intended hierarchy.

## Responsibility hierarchy

| Layer | Current objects | Owns |
| --- | --- | --- |
| Lifecycle and composition | `ServerHumanFactory`, `HumanLifecycle`, `ServerHumanProfiles`, `ServerHuman` | Logical identity, profile registry, activation/removal and per-NPC dependency assembly. |
| Behavioral policy | `HumanBehavior`, `GuideBrain`, `IdleBehavior` | Autonomous decisions, selected visitor, valid transitions, attention and cancellation. |
| Active control | `HumanActivityController` | Exclusive ownership between autonomous behavior, dialogue, trade and shutdown. |
| Content ports/adapters | `HumanAnnouncementProvider`, `OperationAnnouncementProvider` | Resolve an immutable announcement from cached domain data, without sending speech or selecting actors. |
| Action definitions | `HumanAction` records, including `Sequence`, `Wait`, `Say` | Immutable descriptions of intended actions and their parameters. |
| Execution lifecycle | `HumanActionExecution` and its child executions | Pending/running/terminal state, monotonic timing, ordered execution and cleanup. |
| Engine capabilities | `HumanActuator`, `ServerHumanActuator` | Execute world operations for the owning NPC on the simulation thread. |
| Physical movement | `ServerHumanMovement`, `MovementGrid` | Follow caller-selected routes, enforce traversal, integrate position and resolve motion presentation; roaming selects routes separately. |
| Movement conditions | `MovementModifiers` | Validate immutable physical/context/effect inputs and resolve permitted pace, speed and animation without sampling the world or simulating health. |
| Physical state | `HumanStateStore`, `HumanStateReducer`, `HumanMutation` | Single-writer logical state and pure mutations; native adapters, decisions and workers consume immutable snapshots. |
| Perception | `ServerHumanPerception`, `HumanContactMemory`, `HumanSenses` | Bounded native discovery/visibility and transient immutable contact memory, independent of threat selection and combat decisions. See [NPC-PERCEPTION.md](NPC-PERCEPTION.md). |
| Engagement and combat boundary | `HumanEngagement`, `EngageZombiesModule`, `HumanCombat`, `ServerHumanCombat` | Pure engagement/target selection, selected-module execution ownership and validated combat handoff. See [NPC-ENGAGEMENT.md](NPC-ENGAGEMENT.md). |
| Weapon execution | `HumanWeaponCombat`, `HumanCombatInventory`, native inventory/approach/network adapters | Per-execution phases and one-shot commits, fixed test inventory, native equip/aim/hit replication and local consequence processing. See [NPC-COMBAT.md](NPC-COMBAT.md). |
| Local combat tactics | `HumanCombatTactics`, `CombatRoutePlanner`, `ServerHumanCombatApproach` | Per-engagement spacing/reaction/retreat policy and bounded placement routes; weapon phases retain attack timing and commitment. See [NPC-COMBAT.md](NPC-COMBAT.md#local-combat-tactics-and-exposure-windows). |
| Transport and feature services | `ServerHumanTransport`, dialogue/trade controllers | Delivery, authenticated requests, exclusive leases and feature-specific authoritative rules. |

Brains depend on ports and action data, not on concrete event controllers,
database clients, networking or global NPC registries. Actuators must not choose
operation content or branch on NPC role/key. Select such dependencies at the
composition root. `ServerHumanProfiles` registers immutable `HumanProfile` data
separately from allowlisted behavior factories and content adapters. The logical
NPC definition selects `profileId` independently of instance key/display name.
Each active actor receives a fresh `HumanBehavior` and controller; profile
revisions are immutable and live bindings retain their revision until reactivation.

`greet()` and the `Emote.greet` flag have been removed. Greeting content is injected
and executed as generic `Parallel(Emote, Say)` presentation lanes. Parallel groups
allow one speech and one animation lane and reject competing capabilities.
Operation-specific `AnnounceOperation`/`announceOperation()` are also absent from
the generic action/actuator contracts. No new content/role conditions belong there.

All profiles use the same outer logical lifecycle and exclusive interaction
controller. `GuideBrain` contains the guide's autonomous roam/greet policy;
`IdleBehavior` is an independent stationary policy. Dialogue/trade services depend
on the controller rather than guide-specific states. See the implemented
[profiles and lifecycle contract](NPC-PROFILES-LIFECYCLE.md), including the planned
PostgreSQL boundary and current presentation-ID limitations. Do not accumulate
unrelated role flags inside `GuideBrain` or put gameplay policy into transport.

## Generic action composition

Movement follows this pattern: behavioral modules decide when to roam,
`ServerHumanRoaming` selects a route and building constraint, and
`ServerHumanMovement` executes it. Transport consumes the movement engine's
snapshot, never a pattern's private state. Future capability services should
similarly own mechanics while modules/patterns supply intent. Do not move
destination-selection or role-specific rules into the physical engine. See
[movement ownership and cancellation](NPC-MOVEMENT.md).

```java
new HumanAction.Sequence(List.of(
    new HumanAction.Say("First phrase"),
    new HumanAction.Wait(4_000_000_000L),
    new HumanAction.Say("Second phrase")
));
```

Production autonomous content uses label keys resolved by `HumanLanguage` from
the cached database catalog (shipped EN/PTBR defaults in standalone mode).
NPC language defaults to PTBR independently of the global Translator. Database
dialogue transitions belong to `HumanDialogueSession`/`ServerHumanDialogue`;
Lua transports identifiers and resolved text through bridge v4. See
[dialogue graphs](docs/npc-dialogue-graphs.md).
`Sequence.speech(lines, pauseNanos)` validates the complete speech plan and
builds these actions, inserting a wait only between phrases. Each `Say` remains
limited to 256 characters. Empty content is a successful no-op; blank, null or
oversized phrases fail before the first phrase is sent. Sequences defensively
copy their children and allow at most 64 nodes with nesting depth at most eight;
speech helpers allow at most 32 phrases.

Execution state belongs to each `HumanActionExecution`, not the shared immutable
action/provider. Two NPCs may reuse a plan without sharing progress or timers.
Each child starts once; the parent advances after success, waits while running,
and fails without executing later children when a child fails. Immediate children
can complete in the same update; plan bounds cap per-update work. Wait timing
begins when that child actually starts, so scheduling gaps do not replay missed
phrases or erase later pauses. Never sleep or create a thread per action.

Cancelling a sequence cancels its current child recursively. Active animation or
movement gets its existing cleanup; pending children never start. Already-sent
speech cannot be recalled. Activity/lifecycle ownership and behavior state transitions control
interruptions: visitor loss, failed tracking, trade, dialogue and despawn all
cancel the sequence through the existing state-machine transition path.

Speech success means the transport accepted delivery, not that a client finished
displaying it. Inter-phrase timing is an explicit policy, not a client playback
acknowledgement. Keep completion, failure and cancellation semantics documented
for every new action/capability.

## Two-phrase operation announcement

After the wave, `GuideBrain` calls its injected provider once. The operation
adapter calls `ApocBREventControl.guideAnnouncementLines()`, which resolves both
phrases using one cached schedule snapshot and one wall-clock reading. It reads
no SQL and sends no packets. Active operation information comes first; next
operation information comes second when both exist. Missing/disabled information
retains the existing empty or single fallback phrase behavior.

The selected guide behavior captures the returned strings into `Say` actions and uses a four-second
pause (`ANNOUNCEMENT_PAUSE_NANOS`). It stays in `SPEAKING` and tracks its visitor
during the pause. A schedule update between phrases does not change the captured
second phrase. Provider/plan failures return the NPC to its post without a retry
storm. `guideAnnouncement()`/`guideGreeting()` remain compatibility text adapters;
NPC execution uses the structured phrase list instead of concatenated text.

## Required verification for future capabilities

Test production action and policy classes against fake capability ports. Include
one-shot behavior, wait boundaries, nested cancellation, child/provider failures,
visitor incarnation changes and interrupted lifecycle paths. Integration fixtures
must also cover the concrete provider/transport/domain boundary being changed.
Do not test a copied algorithm as a replacement for the production implementation.

Current regression coverage includes:

- `python tools/test_human_profiles.py`: 56 configuration, profile, generic-control and lifecycle assertions.
- `python tools/test_human_sequences.py`: 31 composite/wait/provider/snapshot and interruption assertions.
- `python tools/test_human_brain.py`: 72 existing policy/action assertions.
- `python tools/test_lobby_event_control.py`: 256 event-controller assertions, including ordered immutable phrases and cache-only content reads.
- Existing human lifecycle, dialogue, trade and roaming fixtures.
- `42.21.0/patchApocalipseBr.ps1 -DryRun`: actual game-JAR compilation; no deployment.

The target is server-only Build 42.21.0. No vanilla class baseline was replaced
for this change; these are existing custom engine/domain classes. Confirm two
distinct speech displays and the pause, translation rendering, movement/facing,
and interruption behavior on a dedicated server before claiming live validation.

Related: [profiles/lifecycle](NPC-PROFILES-LIFECYCLE.md), [server-owned humans](SERVER-HUMANS.md), [NPC dialogue](NPC-DIALOGUE.md),
[event controller](LOBBY-EVENT-CONTROL.md).


## Shared modules and persistence ownership

Modular roles compose registered `HumanModule` implementations through
`HumanModuleRuntime`; a new bot role must not duplicate scheduling, perception,
action execution, database access or lifecycle code. Eligibility must be pure;
only the selected module may actuate. Interruptions must cancel active actions
and leave reusable policy state. Dialogue/trade/availability remain above module
arbitration. Profiles hold immutable data, never shared mutable policy instances.

Logical NPC state belongs to the NPC engine, with IsoPlayer as a replaceable
presentation. Capture detached mutation snapshots on the simulation thread; the
worker and shared journal own JDBC, durability and retries. Never persist native
IDs, Java references, connections or monotonic deadlines. New semantic memory
requires versioned codecs and explicit restart/cancellation rules. See
[NPC-PERSISTENCE.md](NPC-PERSISTENCE.md) for the implemented schema and ownership fence.

## Centralized physical state

Physical/needs fields are owned by the logical NPC's single-writer reducer store.
Validated commands and native observations become typed `HumanMutation` values.
Capabilities read projections; behavior reads `HumanObservation.state()`. Neither
may keep a competing mutable health/endurance copy. Native body/stats are an
observation/projection boundary, and workers receive detached checkpoints only.
See [NPC-STATE.md](NPC-STATE.md) for CAS/activation validation, inbox bounds,
condition projection, V20 persistence, failure semantics and validation.
