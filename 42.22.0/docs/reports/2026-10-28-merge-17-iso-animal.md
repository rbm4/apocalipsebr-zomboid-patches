# Review 17/48: IsoAnimal

Created 2026-10-28. Status: **approved/applied on 2026-10-28**. Scheduler review 16 is now
applied. See the [tracker](../features/2026-10-27-subsystem-port-tracker.md).

## Inputs and proposal

- [Old vanilla](../../../42.21.0/decompiled/zombie/characters/animals/IsoAnimal.java).
- [Old patch](../../../42.21.0/src/zombie/characters/animals/IsoAnimal.java).
- [Target vanilla](../../decompiled/zombie/characters/animals/IsoAnimal.java).
- [Candidate diff](2026-10-28-merge-17-iso-animal.diff).

Keep the existing server perception optimization and two invalid Override annotation
repairs on target vanilla. The three-way merge is clean; the source matches the reviewed candidate after baseline commit `25bf0ca`. Primary owner: OPT-ANIMAL; shared owners: moving-object membership,
animal behavior, server spatial perception and telemetry.

## What the old patch changes

Native updateLOS scans every moving object for each animal. The server patch
queries ServerMovingObjectIndex/ServerAnimalPerceptionGrid instead. Human targets
remain global; zombie candidates are selected within radius 10 only when the
animal definition enables fleeZombies. The grid uses 16-tile buckets and refreshes
zombie positions per scheduler frame, invalidated by relevant lifecycle changes.
Candidates preserve active-lifetime index order rather than promising the original
HashSet traversal order. Other animal objects are not human target candidates.

Add the animal itself to spottedList when it remains in the active collection.
Validate each returned candidate still belongs to that collection. Retain the
physics/vehicle/grapple-only zombie exclusions, one-floor separation check,
current-square requirement and player invisibility/ghost checks. Clients retain
the original full-object traversal and ordinary spotted calls.

On the server, beginServerPerception resets spottedChr and advances lastAlerted
once per perception update. spottedFromServerPerception then processes candidates
without repeating that bookkeeping for every candidate. This is an intentional
behavior contract from the old patch, not proof of strict equivalence to native
per-candidate bookkeeping. Clear scratch candidates and record perception timing/
visited count in finally, including failures. Scratch storage belongs to each
animal; it does not make concurrent/reentrant use safe.

Remove Override on updateStress and initializeStates because the corresponding
parent declarations are private in target vanilla. Retain the methods themselves.
These are decompiler/source-compilation repairs, not gameplay optimizations.

## Upstream retained

Mother reattachment now uses connected designation-zone getAnimalsInZone() and
accepts Collection<IsoAnimal> with foreach traversal. Preserve removal of the old
random connected-zone getZone() method. Preserve the new updateStatsAway animal
log. Do not restore old List-only descriptors or the removed method.

## Dependencies, source checks and remaining validation

IsoCell now supplies indexed membership and the scheduler frame is ported.
The copied BaseAnimalBehavior provides beginServerPerception and
spottedFromServerPerception; ServerAnimalPerceptionGrid supplies the candidate
query. Other animal/designation-zone integrations remain separate reviews.

Source checks passed: no merge conflicts; all added/removed old patch lines
preserved; updateLOS equals its old patched body; target mother-attachment and
away-stat methods unchanged; removed method stays absent. The scheduler/index
fixture passed 550 assertions during review 16 application and exercises spatial
index lifecycle/query behavior, but does not execute this full animal updateLOS
or the real behavior callbacks. No new Java fixture was run for this proposal.

Before runtime validation, cover humans at arbitrary distance, zombies at radius
and floor boundaries, fleeZombies disabled, visibility exclusions, removed/reused
lifetimes, per-frame movement, candidate order, behavior bookkeeping and scratch
cleanup after exceptions. Full target-JAR compilation, AI/stress behavior and
native multiplayer animal integration remain pending. No installation or database
changes were made.

User approved: "yes". Candidate equality and the source checks above passed
after application. No installed files were changed. Approval applies to this class only.
