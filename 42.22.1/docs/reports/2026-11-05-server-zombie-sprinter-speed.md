# Server-controlled sprinter speed: evidence and implementation proposal

Date: 2026-11-05. Target: **42.22.1 dedicated server**, with stock multiplayer clients.
Status: source investigation and proposal; no movement implementation or deployment.

## Recommendation

Keep the existing server zombie authority/session system, but replace its generic
sprinter fallback with a **build-specific, measured gait model**. Detecting a
sprinter is already possible. What is missing is the conversion from that body's
gait and pursuit state into animation-equivalent distance per second.

First port the diagnostic sampler to the exact target client and capture native
owner movement for `sprint1` through `sprint5`, including close pursuit/lunge.
Then add validated rates to `ServerZombiePace`, use the same resolved gait for
physical integration and packet presentation, and make owner calibration specific
to that gait. Do not invent a new universal sprinter speed or multiply 2.4 by
`speedMod` without measurements.

Owning feature reference: [server-controlled zombie interactions (historical
42.21.0 guide)](../../../42.21.0/docs/features/2026-10-22-npc-zombie-control.md).
The [NPC architecture contract](../../../42.21.0/docs/features/2026-10-20-npc-engine-architecture.md)
separates this native capability from human perception and tactical policy.

## What the current code actually does

| Concern | Source evidence | Consequence |
| --- | --- | --- |
| Classification | Vanilla `IsoZombie.doSprinter()` assigns `speedType=1`; `doZombieSpeedInternal2()` creates `sprint1..5`. `getSpeedTypeFromWalkType()` maps the `sprint` prefix to type 1. | No new detection mechanism is needed. Keep body type, selected variant and effective motion mode distinct. |
| Server field intake | `NetworkZombiePacker.applyZombie()` applies packet speed modifier and walk type, then calls `setSpeedTypeFromWalkType()` before `received()`. | Owner observations can use the applied body's gait. They remain client-reported data, not trustworthy offline calibration ground truth. |
| Effective sprint predicate | `getZombieWalkTowardSpeed()` selects sprint for `(sandbox speed == 1 && !inactive) || speedType == 1`. | Preserve the actual precedence: `inactive` does not negate the second branch. A blanket `!inactive && speedType==1` check changes native semantics. |
| Fallback number | `ServerZombiePace.fallbackMotion()` calls `getZombieWalkTowardSpeed(.025,1,step)`, then multiplies length by 30. Native sprint replaces length with `.08`. | Every effective sprinter receives **.08 × 30 = 2.4 tiles per real second**, before any accepted calibration. This is a helper-derived estimate, not measured animation speed. |
| Measured support | `measured()` accepts only standing, active type 2, walk type `"2"`, speedMod approximately 1, without sprint override. Constants are 1.0475 walking and 1.5227 lunging. | Neither constant supports sprinters. Other types/variants remain fallback cases. |
| Close pursuit | `ServerZombieNativeAdapter.move()` enables `lunging` only when `ServerZombiePace.measured(zombie)` is true. | Sprinters never receive this adapter's Lunge mode or separate close-pursuit rate; moving packets use WalkToward with the running bit. |
| Calibration | `ServerZombieController.received()` accepts displacement samples from 0.1 to 4 tiles/s and intervals of 200–1500 ms. `ServerZombiePace.sample()` accepts ratios 0.5–1.25, requiring three consistent samples. | Against 2.4, only **1.2–3.0 tiles/s** can establish sprinter calibration. A native rate above 3.0 is rejected regardless of consistency; above 4 is rejected even earlier. These are acceptance limits, not measured sprinter limits. |
| Calibration lifetime | `usable()` expires after two seconds for acquisition. The adapter stores `paceScale` as a final acquisition ratio and reapplies it to later fallback motion. | Expiry does **not** refresh or expire that ratio inside an active session. Changes of variant, mode or modifier can inherit an unrelated scale. |
| Time and collision | Session and adapter cap elapsed time at .15 s; movement spends one budget across up to 32 substeps of at most .1 tile. | Preserve real-time integration, corner remainder and collision checks. If future supported rates exceed the 3.2-tile maximum budget per call, review the work cap explicitly. |

Primary code:
[pace resolver](../../src/zombie/apocalipse/zombies/ServerZombiePace.java),
[controller and observation intake](../../src/zombie/apocalipse/zombies/ServerZombieController.java),
[native movement and packet adapter](../../src/zombie/apocalipse/zombies/ServerZombieNativeAdapter.java),
[session timing](../../src/zombie/apocalipse/zombies/ServerZombieSession.java),
[packer](../../src/zombie/popman/NetworkZombiePacker.java),
[exact vanilla IsoZombie](../../decompiled/zombie/characters/IsoZombie.java).

## Why internal fields do not supply the missing speed number

`speedType` identifies a category, `walkType` selects an animation variant and
`speedMod` is a modifier, not a world velocity. Native `doSprinter()` initializes
speedMod in the 0.85–0.9999 range; the helper's sprint branch ignores that value
when replacing the vector length with .08. That does not prove the animation
system ignores it, or that server displacement should multiply by it.

Vanilla [PathFindBehavior2](../../decompiled/zombie/pathfind/PathFindBehavior2.java)
consumes `getDeferredMovement()` distance. In
[IsoGameCharacter](../../decompiled/zombie/characters/IsoGameCharacter.java),
that method returns zero without an animation player. Calling native pathfinding
or the walk-speed helper cannot recover missing animation root motion on its own.
`getZombieLungeSpeed()` only assigns `running`; it supplies no lunge velocity.

The installed game's `media/AnimSets/zombie/` supplies supporting evidence:
`walktoward/sprintToward.xml`, its network equivalent, and
`pathfind/sprintPathfind.xml` specify playback scale .80. Lunge and network-lunge
`sprint1..5.xml` select `Zombie_Sprint06` at .90. Ordinary pursuit selects variant
clips, for example `sprint1` uses `Zombie_Sprint`. These scales are **not tiles/s**,
and .90/.80 is not a justified world-speed ratio between different clips.
Installed asset provenance/hashes were not verified against an archived 42.22.1
manifest; revalidate and record them before fitting the production model.

The one available local capture,
`C:/Users/ricar/Zomboid/zombie-captures/zombies-2026-10-24T00-56-19.046381200Z-511321780964200.ndjson`,
labels itself build 42.21.0. All **25,660 body-bearing records** have walk type
`2`, speed type 2, speedMod 1.0 and running false. It contains no sprinter evidence.
The two existing measured constants therefore cannot establish a sprinter rate.

## Proposed work, in order

### 1. Produce exact-build reference measurements

[The existing sampler builder](../../../tools/build_zombie_sampler_client.py)
is hardcoded to 42.21.0, including class hashes and old hook anchors. Port its
observational hooks and packet APIs against 42.22.1 vanilla, verify the real JAR,
and package a separate diagnostic client overlay. Reuse its bounded asynchronous
writer; do not install server replacement classes on the diagnostic client.
See the [historical sampler guide](../../../42.21.0/docs/features/2026-10-23-client-zombie-sampler.md).

On an isolated test server with NPC zombie control disabled, capture one
client-owned zombie at a time on clear level ground. Cover:

- All five `sprintN` variants; multiple bodies and repeated sustained runs.
- Straight pursuit, start/stop, turning, path-following and direct close lunge.
- Several controlled speedMod values, including ordinary generated values and 1.0;
  record whether changing it actually changes root displacement.
- Active/inactive and sandbox override cases separately; trees, obstacles and
  reactions as exclusion/transition scenarios rather than straight-run references.
- Different frame rates and normal game-time settings; record wall, simulation
  and animation time so the chosen units are explicit.

Measure distance per elapsed **real second** over continuous unobstructed owner
windows. Compare root, requested, applied and actual movement; exclude remote
correction, collision, separation, target-distance clamping, reaction and blended
transition windows from steady-gait fitting. Do not sum repeated `root_read`
events: reads can reference the same deferred motion. Retain excluded transitions
for a separate startup/turn model if steady-speed parity proves insufficient.

Produce a reproducible summary per variant/mode/modifier, with run counts,
duration, median, spread, exclusions, build and asset fingerprints. Use held-out
runs to evaluate the model. Rates and modifier laws remain **unknown** until then.

### 2. Resolve physical pace and presentation together

Keep ownership in `zombie.apocalipse.zombies`. Evolve `ServerZombiePace` into a
pure resolver consuming detached native inputs and an immutable measured table.
A proposed input includes effective sprint selection, walk type, speedMod,
inactivity, locomotion context and table revision. A proposed result includes
tiles/s, running flag, walk/lunge presentation mode, provenance and confidence.
Unknown combinations must explicitly use the labeled helper fallback.

Use measured lookup/interpolation only inside validated ranges. Fit modifier
dependence from captures; do not assume linearity or scale a captured absolute
rate twice. Retain existing fast-shambler behavior and unsupported physical-state
handoff. A dynamic mode/variant change must resolve a fresh rate and invalidate
incompatible calibration. Snapshot the resolved result once per integration step.

Make direct-lunge eligibility independent of whether a fast-shambler constant
exists. Keep route corners as path pursuit, with explicit close-direct conditions.
If initially retaining the current 3.5-tile approximation rather than implementing
native lunge timer/turn behavior, document that limitation. Apply the selected
mode to both movement speed and native Walk 7/8 or Lunge 5/6 packet synthesis.
Preserve target, walk type, speedMod, running-variable presence bits, clipped
destinations, authoritative real coordinates and reaction preemption.

### 3. Repair calibration without making client packets the speed authority

Key samples by body incarnation **and gait/mode/modifier/table revision**.
Reset on owner transfer, discontinuities or incompatible state changes. Avoid
mixing walking, lunging and pathfinding into one fitted ratio. Use repeated valid
motion windows and measured, mode-specific plausibility limits instead of merely
raising the generic 4 tiles/s ceiling or the 1.25 ratio limit.

Measured table entries should determine authoritative baseline pace. If optional
owner calibration remains useful, constrain it around that baseline and give the
active session an explicit expiry/reset policy. Do not freeze an unlabeled scale
for an entire session. Acquisition must still proceed without waiting for samples.
Add bounded diagnostics for gait, mode, base/final speed, calibration age and
fallback reason; avoid per-tick logging.

### 4. Preserve the execution and thread boundaries

The controller is documented as main-simulation-thread-owned and stores mutable
identity maps. Packer update/intake and native movement are its owning paths.
Current `ServerHumanCombat` also calls `engage()`, while NPC execution can run on
the separate NPC worker. Its class comment alone does not establish safe access.
Before adding new gait sampling/state across that boundary, audit actual callers
and marshal bounded incarnation-qualified commands to the controller owner, or
establish an explicit existing synchronization contract. Do not introduce live
world reads into a supposedly pure pace resolver or let persistence workers
mutate bodies. This is a required integration review, not proof of a new race.

No changes to `ServerHumanPerception`, squad policy or NPC movement constants are
needed to make zombies move at the correct speed. If tactical time-to-contact
later needs pace, expose an immutable observation through the sensing boundary;
keep that separate from the authoritative zombie locomotion change.

## Implementation map and acceptance criteria

| Location | Recommended change |
| --- | --- |
| `tools/build_zombie_sampler_client.py`, diagnostic classes | Exact-target sampler support, baseline/hook verification and reproducible capture analysis. |
| `ServerZombiePace` plus immutable gait table/value types | Measured variant/mode rates, validated modifier law, explicit fallback and pure selection. |
| `ServerZombieController` | Gait-qualified observations, resets, plausibility filtering and diagnostics. |
| `ServerZombieNativeAdapter` | Consume one resolved motion result; remove permanent incompatible scale; support measured sprinter lunge and matching packets. |
| `ServerZombieSession` | Preserve cancellation/contact/timing; change only if explicit lunge transition state is needed. |
| `tools/test_server_zombie_control.py` | Add exact-target selection and production-code gait/transition fixtures. |

The existing controller fixture hardcodes 42.21.0 and asserts the helper-derived
2.4 sprinter pace, including unchanged pace after doubling speedMod. It proves
the current fallback contract against doubles, **not** native sprint parity.
Do not report its passing result as 42.22.1 or animation-speed validation.

For implementation, require production-resolver tests for all captured variants,
mode changes, modifier ranges, inactive/override precedence, invalid input and
unknown gait fallback. Test stale samples, reused bodies, owner transfer,
calibration expiry during a session, packet running/state presence bits,
frame-rate independence, corner budget, collision/contact stopping and hit
preemption. Validate both NPC startup modes and stale cross-thread commands where
applicable. Compile against the exact 42.22.1 JAR using its build-only workflow.

Proposed runtime acceptance target: on held-out unobstructed runs, server travel
over a sustained five-second interval is within **5%** of the corresponding native
owner reference, subject to measured capture uncertainty. This is a proposed
threshold, not an achieved result. Compare startup, turns and lunge separately.
Then test two observing stock clients, delayed packets, late viewers and repeated
owner handoffs for drift, sliding and correction snaps. Preserve collision,
reaction priority and exactly-once attacks; increasing speed must not authorize
movement through obstacles or lengthen attack reach.

## Verification of this report

Inspected target patched controller/pace/adapter/session/packet paths and exact
target vanilla character, pathfinding and lunge sources; inspected installed
animation XML and counted gait fields in the available historical capture.
No new sprinter measurement, exact-build sampler, Java change, compilation or
live gameplay validation was performed. Documentation links/index are checked
separately. No exact native sprinter speed is claimed by this investigation.
