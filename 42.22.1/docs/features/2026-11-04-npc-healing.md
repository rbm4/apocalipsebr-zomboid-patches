# NPC self-treatment and bleeding

Server target: **42.22.1**. Implemented as an opt-in `healing` module; no existing
profile revision, NPC instance, database, installation or live server is changed.
The subsequent [Umbrella group patrol](2026-11-04-npc-group-patrol.md) seeds one
three-member group through V42-V43 and registers these settings in the manager's
catalog. Combat authorization and damage rolls retain their existing owners.

## Ownership and activation

`healing/HealingSettings` is immutable module configuration. `HealingModule` owns
selected policy and a fresh worker-side execution handle. It receives only the
detached `HumanHealingPort.Snapshot` and emits leased commands. `MedicalExecution`
owns one native transaction's start, deadline, terminal result and cancellation.
Its world adapter is injected, so policy/transaction fixtures execute production
code without constructing the game world.

`ServerHuman` composes one `ServerHumanHealing` adapter per opted-in incarnation.
Each activation creates fresh state. Profile module registration validates
parameters and ordering; no actor key/name selects healing behavior. Register
`healing` after survival and player retaliation, but before engagement and routine
activity. Example priorities for a future published profile:

```text
survival=10, retaliate-player=11, healing=20, engage-zombies=100, walk=1000
```

This is a configuration example, not an applied migration. The backend publisher's
module catalog/validation and actual profile publication must be coordinated in
the later rollout. Existing standalone profiles also remain unchanged.

## Thread contract

2026-11-05 behavior update: [moving passive recovery and state speech](2026-11-05-squad-cohesion-healing-and-state-speech.md).
Only an actual dressing execution owns the medical movement pause.

`ServerHumanFactory.update` services medicine on the registered server thread in
both asynchronous and synchronous startup modes. It does so under the existing
factory monitor, serializing this feature with factory-owned NPC ticks. New
native wound reads, inventory initialization/consumption, body-part treatment,
blood effects and medical packet construction/delivery run in that server-thread
adapter. The worker does not run Lua timed actions, body-part updates or native
medical transactions.

The server-owned module publishes one token/renewal/cancellation command per actor;
results and observations are immutable volatile values. A 500 ms lease expires
when execution stops renewing. Commands never accumulate. Each handle can cancel
only its own token, and a new incarnation gets an independent adapter. Transport
reannouncements enqueue connection identities in a bounded 256-entry mailbox;
only the server thread reads connection state and sends medical packets.

The [current async authority split](2026-11-05-npc-async-authority.md) runs all
native NPC execution on the server owner. The worker now receives only detached
selection/navigation values and cannot hold the factory while requesting Lua.

## Treatment and survival recovery

Policy requires a living, mobile actor, a fresh medical sample, fresh available
zombie awareness without truncation, and no remembered nearby zombie within
`clearTiles`. Partial bounded coverage can establish observed local clearance;
it does not prove the region empty. The native adapter also refuses work during
dialogue/trade, following recent damage or outgoing combat, or while an active
player retaliation target remains assigned. Player-hostility/cover policy is
separate future work; this module does not invent hostile players from proximity.

Treatment stops walking and clears tactical aim. Before commitment the adapter
revalidates position/floor, activity, life/mobility, traversal, lease, recent
damage, the actual item identity and the selected wound. New danger cancels the
policy request; damage or native invalidation cancels directly at the next world
service. Suspend, preemption, removal and shutdown cancel owned work. Supplies
are not consumed on an ordinary cancellation.

The most strongly bleeding unbandaged body part is selected first. Clean
`AlcoholBandage`, `Bandage`, `AlcoholRippedSheets` and `RippedSheets` are accepted
in that order. Infected items are refused. Inventory discovery is limited to the
first 256 items in the top-level inventory; nested bag management is later work.

One completed dressing consumes one actual item and calls native `setBandaged`
with its power, alcohol flag and full item type. It treats that body part, not
every wound. Remaining open parts continue bleeding and require another dressing.
Treatment does not grant an instant health refill. Safe passive recovery restores
logical health in the medical service using actual elapsed time capped at 150 ms
per call. It requires no movement lease or stationary pose and can run while
walking. Unsafe/unavailable observations grant no recovery. Native body-part health deficits also
shrink proportionally during recovery so hidden native damage does not remain
permanent after logical health has healed. Wound timers and infection are not
erased by this projection.

The survival module composes its own fresh healing policy over the same capability
while `RECOVERING`. Thus its higher priority cannot starve the action needed to
reach reengagement health. It cancels treatment before escape/defense or release
to `SAFE`. It never treats while executing an escape route. Profiles without
the module retain their existing behavior.

## Bleeding and physical-state authority

Server medicine observes unbandaged native bleeding times and damage scalers.
Its nominal real-second rate derives from 42.22.1 `BodyPart.DamageUpdate`:

```text
sum(0.2857143 * part.damageScaler * 48 * part.bleedingTime / 10) / 100
```

The rate is scaled by `GameTime.getTrueMultiplier`, divided by profile health
capacity, clamped and submitted as the
trusted `HumanMutation.Bleeding` replacement observation. `HumanStateReducer`
applies ongoing health loss through the existing bounded `Advance` command.
Observation is not a second weapon/zombie hit. Native damage import still rolls
and imports each attack once. God-mode profiles do not acquire an ongoing bleed.
Death remains terminal. Periodic native `splatBloodFloor` attempts are throttled
to once every two seconds; vanilla chunk blood creation owns its random chance
and relative `AddBlood` replication.

This reproduces native-derived bleeding in the existing NPC clock. It is not
full vanilla player physiology: elapsed simulation steps remain capped at
150 ms, and native `BodyDamage.Update`/`BodyPart.DamageUpdate` are not additionally
run. Full wound aging, bandage dirt/wear, clotting, fractures/sutures, embedded
bullets, sickness and infection progression are not implemented by this module.
Bandaging a bite does not cure infection.

## Stock-client presentation

`ServerHumanMedicalPacket` builds the exact 42.22.1 `StatePacket` format with
`PlayerActionsState`, enter/exit stages, an action substate, typed `Variables`,
`PerformingAction=Bandage`, `BandageType`, empty override hand models and
`EventBandage`. Regions match native `ISHealthPanel.getBandageType`: Head,
UpperBody, LowerBody, LeftArm, RightArm, LeftLeg and RightLeg. The actor remains
the existing reserved remote-player replica; no fake client request or new client
Java class is used.

Completion sends native `BodyPartSync` using the vanilla `ISApplyBandage` mask.
Action exits clear playback on completion/cancellation. New/reannounced relevant
viewers receive the current action; a viewer returning after an interruption
receives its pending exit. Health/item effects never depend on a client animation
event. Terminal state is set before commitment, preventing repeated polling or
cancellation from replaying it. A transport exception cannot make a committed
dressing free: item consumption precedes packet delivery. An exception can leave
partial native/presentation effects; the factory reports it and stops that
actor's activities rather than silently retrying the transaction.

No changes were made to vanilla source files under `decompiled/`. Wire behavior
was traced through target `StatePacket`, `PZNetKahluaTableImpl`, `Variables`,
`PlayerActionsState`, `ConnectionPlayer`, `BodyPartSyncPacket`, and installed
42.22.1 `ISApplyBandage.lua` / `ISHealthPanel.lua` / player animation nodes.

## Configuration and persistence boundary

| Module parameter | Default | Allowed |
| --- | --- | --- |
| `bandages` | 3 | 0-32 initial Base.Bandage items per incarnation |
| `bandageMs` | 4200 | 1000-60000 |
| `sheetsMs` | 5600 | 1000-60000 |
| `quietMs` | 5000 | 1000-120000 since damage/outgoing combat |
| `clearTiles` | 6 | 2-32, at most the profile perception radius |
| `recoveryPermillePerSecond` | 2 | 0-10; baseline health fraction per authorized resting second, divided by profile health capacity |

The current inventory remains transient, like the existing combat loadout.
Initial supplies are created once per incarnation, never replenished by a tick.
Reactivation/restart creates fresh supplies; durable supply conservation requires
the later inventory persistence work. Set `bandages=0` for externally supplied
actors. Persistent scavenging/resupply is outside this module.

Health and aggregate injuries/bleeding continue using existing physical-state
columns and version 1. No schema migration is required by the new mutation.
Detailed native body-part wounds, visual dressings and remaining medical items
are not persisted by that schema. A restored aggregate injury is treatable with
one dressing and an appropriate representative pose; it is not a reconstructed
anatomical wound list. Native wound observations become authoritative for the
current incarnation once native wounds are present. Native IDs, leases, action
handles and monotonic deadlines are never persisted.

## Verification and remaining runtime checks

`py -3 tools/test_human_healing.py` compiles the 42.22.1 production policy,
transaction, state reducer, survival module, native adapter and packet builder
against explicit engine/network doubles. It covers one-shot completion, early
polling, commit failure, cancellation, wounds on multiple parts, finite supplies,
sheets duration, native bleeding import, gradual recovery, native health
projection, quiet windows, death, protection, stale sensing, worker commands,
viewer reannouncement/reentry, stale tokens, independent actors and the full
low-health survival-to-reengagement cycle. These checks do not substitute for
native client playback.

Verified 2026-11-04: **69** production-code fixture assertions passed. The actual
`42.22.1/patchApocalipseBr.ps1 -BuildOnly` compilation passed with **318 sources /
930 class files** against the installed target JAR. The retained output is
`42.22.1/artifacts/build_20261104_211400/`; no deployment was performed. Passing
that build's `classes` directory as the optional argument to the fixture also
passed **10** profile-registration assertions against the actual compiled engine
and game JAR, including unchanged built-in profiles, fresh per-actor module state,
priority validation, clearance limits and rejected parameters.

Dedicated-server validation remains necessary with both startup
modes: two nearby clients, each anatomical pose, a late/rejoining viewer, renewed
damage during treatment, no supplies, multiple wounds, blood decals, survival
escape/recovery, disconnect/despawn, worker stall and orderly shutdown.

Related: [NPC architecture](../../../42.21.0/docs/features/2026-10-20-npc-engine-architecture.md),
[physical state](../../../42.21.0/docs/features/2026-10-21-npc-state.md),
[survival](../../../42.21.0/docs/features/2026-10-21-npc-survival.md),
[current version index](../README.md).
