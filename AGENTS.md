# AGENTS.md

## Purpose

This repository contains version-specific Java monkey patches for Project Zomboid. It also contains decompiled Project Zomboid Java source for supported game builds.

The patches replace complete game classes at runtime through loose `.class` files placed before `projectzomboid.jar` on the Java classpath. A patch must therefore remain binary and behaviorally compatible with the exact game build it targets.

Treat this repository as production game-server and game-client code. Preserve vanilla behavior unless the requested change explicitly requires altering it.


## Repository model

Each game build has its own top-level version directory, such as:

```text
42.19.0/
42.20.0/
42.20.1/
42.20.3/
42.21.0/
42.22.0/
```

Some directories have a suffix such as `-client`. These are separate patch targets and must not be assumed to match the unsuffixed server directory.

A modern version directory generally contains:

```text
<version>/
  decompiled/zombie/   Unmodified decompiled source for that exact game build
  src/zombie/          Complete Java source files that override vanilla classes
  docs/README.md      Version documentation index, grouped by category and date
  docs/features/      Maintained feature, architecture and configuration guides
  docs/reports/       Investigations, measurements, reviews and implementation maps
  docs/issues/        Diagnoses, known limitations, fixes and reproduction records
  patch*.ps1           Windows compilation and deployment scripts
  patch*.sh            Linux compilation and deployment scripts where available
```

Older version directories may use several focused patch scripts or may not contain the same complete layout. Inspect the selected directory before assuming its workflow.

## Source-of-truth rules

1. Identify the exact target game version before investigating or editing code.
2. Use `<version>/decompiled/zombie/` as the source of truth for vanilla behavior, signatures, fields, imports, and call flow for that version.
3. Use `<version>/src/zombie/` only for patched replacement classes.
4. Never edit files under `decompiled/` when implementing a patch.
5. Never use a source file from another version as the starting point without first comparing it against the target version.
6. Do not rely on memory, online API documentation, or another Project Zomboid build when the target decompiled source can answer the question.
7. Decompiled code may contain synthetic names, awkward control flow, or decompiler artifacts. Avoid cleanup-only rewrites because they make compatibility review harder and can change behavior.

Use the version explicitly requested by the user or implicitly identified by the
task's paths, active files, logs or existing patch context. If no version is
identified, select the latest available **server** version automatically: compare
unsuffixed numeric version directories numerically, excluding `-client` and other
suffixes. At this update that default is **42.22.0** (pre-release migration target;
patches are not yet ported or deployment-validated); discover directories again
instead of treating this number as permanent. Do not ask the user to select a
version merely because they omitted it. Explicit client work still targets its
separate client directory and exact baseline.

## Version documentation and index

All new version-specific documentation belongs under `<version>/docs/`, including
feature architecture, configuration, investigations, telemetry explanations and
issue resolutions. For current work start with the [42.22.0 documentation index](42.22.0/docs/README.md)
and [42.21.0 to 42.22.0 compatibility audit](42.22.0/docs/reports/2026-10-27-port-42-21-to-42-22.md).
The [42.21.0 index](42.21.0/docs/README.md) remains the previous build's feature reference;
do not treat its implementations or measurements as verified on 42.22.0.
Do not create new guide copies in the version root or a nested `docs/docs/`.

- `features/`: maintained behavior, ownership contracts, configuration, schema
  references and companion reference SQL. Explain the implemented behavior and
  distinguish future design from current guarantees.
- `reports/`: dated source investigations, performance captures, reviews,
  comparisons and implementation proposals. Preserve their evidence and historical
  scope; a proposal is not proof that a feature has shipped.
- `issues/`: reproductions, failure diagnosis, operational troubleshooting and
  resolutions. State whether the issue remains open, mitigated or resolved.

Name documents `YYYY-MM-DD-descriptive-topic.md` using the document's creation or
report date, with lowercase hyphen-separated topics. Companion documentation SQL
uses the same dated naming convention and category. Actual executable migrations,
source files and build tools retain their existing project-specific locations
and naming contracts; do not rename runtime assets just to match documentation.
`docs/README.md` is the undated index exception. Keep a maintained guide's original
filename date when editing it and record material later verification dates in
its content; create a separate dated report for a new investigation. For migrated
documents use the original Git addition date when available, and disclose any
fallback rather than inventing historical dates.

Each version's `docs/README.md` must link every version document and SQL companion,
group entries into features/reports/issues and sort newest date first within each
category. Include the date and meaningful title. Add links from relevant feature
guides to their reports/issues and back from reports/issues to the owning feature
when that relationship is known. Creating, moving, renaming or retiring a document
must update the index and all affected references in the same change. Preserve
relative source, SQL, cross-version and external-repository links when moving
files. Validate local link targets and index coverage; documentation-only changes
do not require Java compilation or gameplay fixtures.

## Creating a new Java monkey patch

The standard workflow is:

1. Select the target `<version>` and determine whether the patch is server-side, client-side, or required on both sides.
2. Locate the original class under `<version>/decompiled/zombie/`.
3. Trace its callers, callees, threading context, and client/server guards in the same version.
4. Copy the complete original file to the identical relative path under `<version>/src/zombie/`.
5. Make the smallest viable change in the copied `src` file. Preserve its package, class name, inheritance, interfaces, constructors, methods, fields, visibility, and nested classes unless the feature specifically requires a compatible change.
6. Check whether the target `src` file already exists. If it does, extend the existing patch rather than replacing it with a fresh vanilla copy and losing prior modifications.
7. Compile with the selected version's own patch script and the real `projectzomboid.jar` as the classpath.
8. Review the diff against both the target decompiled source and any previous patched version.
9. Run a dry deployment when supported, then test the affected behavior in the appropriate single-player, client, dedicated-server, or multiplayer environment.

Example path mapping:

```text
<version>/decompiled/zombie/network/GameServer.java
                         ->
<version>/src/zombie/network/GameServer.java
```

Modern `patchApocalipseBr.ps1` scripts recursively discover every `.java` file under `<version>/src/zombie`, compile them with the game JAR, and deploy the resulting class families as loose classpath overrides. This means placing a correctly packaged source file in `src/zombie` is enough for it to be picked up automatically. Always verify the selected version's script because older directories can differ.

### Copying and committing a large vanilla baseline

When a target replacement source is too large to recreate safely with the file editing tools, use `tools/Copy-PZPatchBaseline.ps1` before editing it. The script copies one exact target-version file from `<version>/decompiled/zombie/` to the identical path under `<version>/src/zombie/`, verifies the SHA-256 hash, stages only that copied destination, and creates a local baseline commit containing only that file. It never pushes.

Run it from the repository root with a path relative to `<version>/decompiled/zombie/`:

```powershell
.\tools\Copy-PZPatchBaseline.ps1 -Version 42.21.0 -RelativePath "iso/IsoGridSquare.java"
.\tools\Copy-PZPatchBaseline.ps1 -Version 42.21.0 -RelativePath "network/GameServer.java"
```

The script deliberately refuses to overwrite a changed destination. It may resume when an existing destination is still byte-identical to vanilla, which safely recovers an interrupted stage or commit. Use it only before the target patch file is edited. Run it once per source so each vanilla baseline has its own reviewable commit. After the baseline commit succeeds, make the behavioral patch as a separate working-tree change and review the target vanilla to target patched diff. Do not amend the baseline commit with patch changes, and do not use the script to commit unrelated files.

The script accepts an optional single-line commit subject:

```powershell
.\tools\Copy-PZPatchBaseline.ps1 -Version 42.21.0 -RelativePath "characters/IsoPlayer.java" -CommitMessage "Add 42.21.0 IsoPlayer vanilla baseline"
```

## Investigation workflow

Before changing behavior:

1. Search only within the target version first.
2. Find the owning class and the entry point that invokes it.
3. Trace both sides of multiplayer behavior. A method name alone does not prove whether it runs on the client, server, or both.
4. Search for all reads and writes of modified fields and all calls to modified methods.
5. Check synchronization, worker-thread ownership, queues, object pools, and lifecycle ordering before changing performance-sensitive or asynchronous code.
6. Check `GameClient.bClient`, `GameServer.bServer`, dedicated-server checks, and related guards.
7. For network changes, trace packet declaration, serialization, transmission, receipt, validation, and state application.
8. For loading changes, trace request, background work, main-thread integration, unload, reuse, and persistence paths.
9. Compare the same class in the immediately previous and next available versions when porting a feature or diagnosing an upstream change.

Useful searches include class names, method names, packet identifiers, log text, Lua event names, and field access. Search for behavior and call sites, not only filenames.

## Lookup shortcuts by subsystem

All paths below are relative to `<version>/decompiled/zombie/`. Exact classes can change between builds, so confirm them in the target version.

### Startup and main game loop

- `GameWindow.java`: process startup, core loop, and high-level lifecycle.
- `gameStates/IngameState.java`: in-game enter, update, render, and exit flow.
- `iso/IsoWorld.java`: world initialization and global world lifecycle.
- `GameTime.java`: game clock and time-driven simulation.

### Simulation and object updates

- `MovingObjectUpdateScheduler.java` and `MovingObjectUpdateSchedulerUpdateBucket.java`: moving-object update frequency and scheduling.
- `iso/IsoCell.java`: active cell contents and broad world simulation updates.
- `iso/IsoMovingObject.java`: common moving-object behavior.
- `characters/IsoPlayer.java`, `characters/IsoZombie.java`, and `characters/IsoGameCharacter.java`: player, zombie, and character simulation.
- `ai/`, `ai/states/`, and `pathfind/`: state machines, behavior, collision queries, and pathfinding.
- `popman/ZombiePopulationManager.java`: virtual zombie population and chunk-level population integration.

### Chunk loading, streaming, and unloading

- `iso/WorldStreamer.java`: client and single-player background chunk streaming.
- `iso/IsoChunk.java`: chunk data, load, save, object integration, and reuse lifecycle.
- `iso/IsoChunkMap.java`: per-player chunk window and chunk swapping.
- `iso/IsoCell.java`: adding and removing chunks and squares from the active world.
- `network/ServerMap.java`: server-side loaded-cell and chunk ownership.
- `network/ServerChunkLoader.java`: server chunk loading and save worker flow.
- `network/PlayerDownloadServer.java`: server delivery of map chunks to clients.
- `network/ClientChunkRequest.java` and `network/ClientServerMap.java`: client chunk requests and server-map state.
- `popman/ZombiePopulationManager.java`: population handling during chunk load and unload.

When investigating load or unload bugs, follow all of these phases: request, disk read or network receive, parse, main-thread add, simulation activation, save, removal, object reuse, and pool return.

### Networking and multiplayer

- `network/GameServer.java`: server packet dispatch, connection lifecycle, and authoritative multiplayer behavior.
- `network/GameClient.java`: client packet dispatch and replicated state handling.
- `network/PacketTypes.java`: packet registration and identifiers.
- `network/packets/`: packet payload parsing, validation, and processing where present.
- `network/PlayerDownloadServer.java`: map transfer from server to clients.
- `network/ServerMap.java`: server world visibility and loaded areas.
- `network/NetworkPlayerManager.java`: player network update management.
- `network/ServerWorldDatabase.java`, `network/ServerOptions.java`, and `network/RCONServer.java`: accounts/world database, configuration, and remote administration.
- `core/raknet/`: lower-level RakNet integration and connection transport.

Never change only one end of a packet contract without confirming the other end. Preserve field order, primitive widths, optional-field conditions, protocol version behavior, and validation.

### Save data and persistence

- `savefile/`: player database and save-file helpers.
- `network/ServerWorldDatabase.java`: multiplayer database state.
- `iso/IsoChunk.java`: map chunk serialization.
- `vehicles/VehiclesDB2.java`: vehicle persistence and its worker thread.
- `world/`: world dictionaries and persistent global world data.
- Search for `ByteBuffer`, `load`, `save`, `loadFromDisk`, `saveToDisk`, and version-number checks to locate serialization boundaries.

Backward compatibility matters. Do not reorder or reinterpret serialized fields without tracing format-version handling.

### PostgreSQL migrations and engine role permissions

Whenever a feature adds or changes PostgreSQL tables, sequences, or schemas, include the engine role's permissions in the migration/deployment work. The engine connects to `game_runtime` as `pz_server_01`; backend migrations normally run as `apocalipsebr`. Objects created by the backend do not automatically grant access to the engine role.

For `public` and `player_rel`, preserve the following grants. Execute them in `game_runtime` as an administrator or a role authorized to grant these privileges, after the schemas and objects exist:

```sql
GRANT USAGE, CREATE ON SCHEMA public, player_rel TO pz_server_01;
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public, player_rel TO pz_server_01;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public, player_rel TO pz_server_01;

ALTER DEFAULT PRIVILEGES FOR ROLE apocalipsebr
IN SCHEMA public, player_rel
GRANT ALL PRIVILEGES ON TABLES TO pz_server_01;

ALTER DEFAULT PRIVILEGES FOR ROLE apocalipsebr
IN SCHEMA public, player_rel
GRANT ALL PRIVILEGES ON SEQUENCES TO pz_server_01;
```

- Include explicit grants for newly created objects in each migration, or verify that the actual object-creating role's default privileges already cover them. Default privileges affect future objects only; they do not repair existing objects.
- Default privileges belong to the role that actually creates the objects. If migrations create objects as `postgres` or another role, configure the same defaults with `FOR ROLE` naming that role. Defaults for `apocalipsebr` alone do not cover other creators.
- Apply the same schema, table, sequence, and creator-default policy when introducing a new engine-accessed schema. Include required function privileges when a feature introduces restricted routines.
- `ALL PRIVILEGES` does not confer ownership or the right to alter/drop another role's tables. Keep schema migrations under the migration owner; if the engine must execute DDL, explicitly design and document ownership requirements instead of granting superuser access.
- Validate migrations in an isolated PostgreSQL database with distinct migration and engine roles. Check the feature's actual queries as `pz_server_01`, including inserts using sequences and access to newly created objects; successful migration as an administrator does not verify engine access.
- Document the required grants, object-creating role, and any administrator bootstrap step with the feature. Grants take effect when committed and require no PostgreSQL configuration reload. Do not apply changes to a live database merely to validate a patch.

### Characters, zombies, and AI

- `characters/`: players, zombies, body damage, stats, skills, and character state.
- `characters/IsoPlayer.java`: player-specific update and interaction behavior.
- `characters/IsoZombie.java`: zombie-specific simulation and network behavior.
- `ai/` and `ai/states/`: state-machine logic.
- `popman/`: virtual population and spawning.
- `VirtualZombieManager.java`: creation and reuse of zombie instances.

### Inventory, items, and crafting

- `inventory/`: item containers, item instances, weapons, clothing, and inventory operations.
- `inventory/types/`: concrete item behavior.
- `inventory/ItemContainer.java`: container mutation and queries.
- `scripting/`: parsed item, recipe, vehicle, and other script definitions.
- `crafting/` and `entity/`: Build 42 crafting and entity-component systems where present.

### Vehicles

- `vehicles/BaseVehicle.java`: central vehicle simulation and interactions.
- `vehicles/VehicleManager.java`: vehicle networking and management.
- `vehicles/VehiclesDB2.java`: persistence and background database work.
- `vehicles/`: vehicle parts, physics integration, interpolation, and passenger behavior.

Vehicle behavior often crosses physics, character collision, networking, and persistence. Trace all four areas.

### Lua integration and mod-facing behavior

- `Lua/LuaManager.java`: Java classes and global methods exposed to Lua.
- `Lua/LuaEventManager.java`: regular Lua event registration and triggering.
- `Lua/LuaHookManager.java`: cancellable hooks.
- `Lua/MapObjects.java`: callbacks for map objects by sprite.
- `UsedFromLua.java` and `HiddenFromLua.java`: exposure markers.

For a Lua event, search for both its registration and every `triggerEvent` call to determine callback arguments and execution side. Vanilla Lua implementations themselves live in the game's `media/lua/` tree and may not be present in the decompiled Java source.

### Rendering and client-only behavior

- `core/SpriteRenderer.java`, `core/textures/`, and `core/skinnedmodel/`: rendering pipeline and GPU resources.
- `iso/fboRenderChunk/`, `iso/LightingJNI.java`, and `iso/LosUtil.java`: chunk rendering, lighting, and line of sight.
- `ui/`: Java-side UI classes.
- `gameStates/`: menus and game-state transitions.

Do not deploy rendering or UI classes as server patches unless the same class is genuinely required there.

### Climate, world systems, and randomized content

- `iso/weather/` and `iso/weather/fx/`: climate and weather effects.
- `erosion/`: erosion simulation and chunk data.
- `randomizedWorld/`: procedural stories and randomized world content.
- `iso/objects/`: world object implementations such as doors, generators, fires, and appliances.
- `radio/`: radio simulation and broadcasts.
- `worldMap/`: world map data and UI support.

## Version porting

A patch is not automatically valid for a newer build even if the class name is unchanged.

### Current 42.22.0 migration checkpoint

The user explicitly authorized exact carry-over of the 50 replacements with
byte-identical vanilla baselines and 200 ApocalipseBR-only support files after
the source audit. These 250 files are staged without compatibility adaptations.
Follow the [subsystem tracker](42.22.0/docs/features/2026-10-27-subsystem-port-tracker.md)
and its per-class copy receipt. Copied does not mean compiled or ported.

The user requested discussion of **each merge individually before applying it**.
The numbered queue contains all 48 changed replacements, including clean text
merges. Present old patch intent, upstream changes and a concrete candidate for
one class; record the user's decision and apply only the accepted class before
presenting the next. Removed-class designs and copied-support adaptations remain
separate work afterward unless the user changes the order. Current review 01
is GameEntity and is awaiting discussion; no changed replacement is applied.
Prioritize optimizations, NPCs and PostgreSQL, with telemetry verification
secondary, while retaining all subsystems in scope. Update this checkpoint as
decisions are implemented so it does not become a stale approval instruction.

When porting a patch:

1. Diff the old version's `decompiled` file against its patched `src` file to isolate the intended patch.
2. Diff the old vanilla file against the new version's vanilla file to identify upstream changes.
3. Copy the new version's vanilla file into the new version's `src` tree.
4. Reapply the intent of the patch, not a blind textual copy.
5. Recheck callers, signatures, synchronization, packet formats, serialization, and lifecycle behavior in the new build.
6. Compile and test against the new build's JAR.

Never copy an old patched class wholesale into a new version directory.

## Build and verification

Follow the script that belongs to the target directory. For modern Windows version directories, a typical direct verification command is:

```powershell
.\<version>\patchApocalipseBr.ps1 -PZDir "C:\path\to\ProjectZomboid" -DryRun
```

The root launcher can discover unsuffixed numeric version directories and their `patch*.ps1` scripts:

```powershell
.\patch.ps1 -Version <version> -PZDir "C:\path\to\ProjectZomboid" -DryRun
```

Linux versions with a shell script can be checked similarly:

```bash
./<version>/patchApocalipseBr.sh --pz-dir /path/to/project-zomboid --dry-run
```

Check the actual script parameters before running it. A dry run can still compile code, inspect the game installation, and provision tooling. Do not deploy to a live server or client unless the user explicitly requests deployment.

Minimum verification for a patch:

- The patched source compiles against the exact target `projectzomboid.jar`.
- Package and output class paths match the original class.
- Nested and anonymous class files are produced as expected.
- The diff contains the intended behavior change and retains prior patches.
- Relevant client/server and single-player paths are considered.
- Network or persistence formats remain compatible unless an intentional coordinated migration exists.
- Runtime logs show no linkage errors such as `NoSuchMethodError`, `NoSuchFieldError`, `VerifyError`, or `ClassNotFoundException`.

There is no substitute for runtime testing of timing, threading, streaming, networking, and persistence changes.

## Tools and algorithm regression tests

`tools/` contains development and diagnostic utilities, not deployed game code. Inspect the relevant script before running it; scripts in this folder have different side effects and prerequisites.

### Python test architecture and prerequisites

The current `tools/test_*.py` scripts target **42.21.0** explicitly. They are standalone Python entry points, not a pytest suite, and do not accept a version selector. Do not assume they validate another build when porting a patch.

Each script uses Python's standard library to create a temporary Java fixture, compile production Java sources or methods extracted from production sources, and execute Java assertions. Some fixtures also compile or extract the exact target-version vanilla implementation for differential comparison. Small engine stubs supply dependencies without initializing the game or loading native libraries. Temporary source and class files are cleaned up automatically.

Prerequisites are Python 3 and the repository JDK 25 at `jdk/bin/javac.exe` and `jdk/bin/java.exe`. The scripts currently use those Windows executable paths and compile with `--release 25`; installing a JDK on `PATH` alone does not satisfy them. They require the relevant `42.21.0/src` and `42.21.0/decompiled` files. If the repository JDK is missing, inspect the target patch script's tooling setup rather than changing Java versions to make a fixture pass.

Run scripts directly from the repository root, for example:

```powershell
python .\tools\test_unload_simulation_algorithms.py
python .\tools\test_player_packet_algorithms.py
python .\tools\test_moving_object_algorithms.py
```

The scripts resolve repository paths from their own location. Compiler errors, Java assertion failures, and Python contract assertions must produce a failing process exit code. Read the failure output; do not rely only on a printed assertion count. These tests do not deploy patches, modify the game JAR, or need a running server.

### Choosing relevant tests

Use this map as a starting point, then inspect the script's production source list and harness for the exact coverage:

| Script under `tools/` | Main coverage |
| --- | --- |
| `test_moving_object_algorithms.py` | Moving-object scheduler, buckets and membership indexes; lifecycle changes and animal perception grid. |
| `test_simulation_algorithms.py` | Entity simulation and active-user indexes, Lua event dispatch, mutation-tracked lists, animal synchronization coverage, and corpse nearby-player queries. |
| `test_using_player_algorithms.py` | Active-user traversal compared with vanilla, including production entity mutation methods, callback mutations, ordering, lifecycle cleanup and sparse active membership. Imports fixture utilities from `test_simulation_algorithms.py`. |
| `test_player_packet_algorithms.py` | Inventory update indexing, player spatial queries, sound stress indexing, zombie packet flags/contracts, and extracted expiry/path-threshold logic. |
| `test_zombie_auth_algorithms.py` | Zombie ownership indexes, authorization coverage and cadence, including disconnect and reassignment behavior. |
| `test_packet_ownership_guard.py` | Packet receive direction, player ownership and online-ID sentinel validation, spoof rejection, and production player-stat deserialization. |
| `test_server_humans.py` | Human factory lifecycle, identity retention across relevance/reconnects, observation/actuation, idle/emote packet fields and the production chat bridge. See `42.21.0/docs/features/2026-10-19-server-humans.md`. |
| `test_human_brain.py` | Production greeting state machine and action execution: selection, transitions, timing, terminal outcomes, cancellation, incarnation changes and repeat suppression. |
| `test_human_roaming.py` | Indoor route planner/controller, bounded search, collision/occupancy/unload checks, building/floor confinement, movement speed and packet replication. Shares the human lifecycle fixture. |
| `test_human_trading.py` | Production native NPC trade adapter, mixed-item destruction, money-only receipt capture, ownership/consent, duplicates, cancellation and persistence refusal. |
| `test_unload_simulation_algorithms.py` | Entity removal versus vanilla, animal-zone topology and geometry invalidation, sound expiration, thumpable no-work predicates, vehicle telemetry batching, and source contracts preserving vehicle/thumpable update behavior. |
| `test_vehicle_idle_algorithms.py` | Idle vehicle part selection versus vanilla under live activation, callback mutations and nested updates; capability hooks, elapsed-minute cooling, same-call square reuse, first-model indexing and work-counter batching. Imports the method extractor from `test_unload_simulation_algorithms.py`. |
| `test_iso_entity_registry.py` | Production registry/lifecycle methods, square mutation hooks and server ID getter: shifted lookups before getters, raw arrays, cyclic reorder, re-add, detachment, coordinate changes, collision ownership, exceptions/retry, reset and client behavior. |

For cross-cutting collection or lifecycle changes, run every affected fixture, including consumers of a shared helper. For example, changing `MutationTrackedArrayList` can affect simulation, sound and unload tests. A filename alone does not define the complete dependency set.

### Test expectations when changing algorithms

1. Before editing an existing optimization, read its relevant harness and the exact target-version vanilla behavior. Understand which invariants the fixture already checks.
2. For meaningful algorithm changes, extend the nearest relevant fixture or add a focused script when no existing one fits. Exercise actual production code; do not duplicate the proposed algorithm in a Python model and treat that model as proof.
3. Prefer differential checks against vanilla for behavior-preserving changes. Compare observable results such as ordered collections, callback/event order, identity membership, serialized bytes, state transitions and cleanup, rather than only counts or successful completion.
4. Cover the boundaries the change depends on: add/remove/re-add, pool reuse, callback mutation, duplicate identities, direct public-field/list mutation, cache invalidation, exceptions, clock changes, spatial boundaries, and client/server guards as applicable. Use deterministic randomized cases where they help expose combinations.
5. For performance claims, assert relevant work counts or candidate visits where practical. Passing functional tests or large assertion totals does not prove a runtime speedup. Avoid wall-clock thresholds in isolated fixtures.
6. Keep stubs small and explicit. Stub dependencies, not the behavior under test. If extracting a production method is necessary to avoid compiling a huge engine class, make missing signatures fail clearly and retain the production method's semantics.
7. Treat a failing source-contract assertion as a review signal. Do not weaken or remove it merely to accommodate a changed implementation; determine whether the requested behavior actually permits that change and update the reference intentionally.

Run relevant fixtures after the final source changes, then compile with the target version's patch script and real game JAR using `-DryRun`. Fixtures validate isolated algorithm behavior; the game-JAR compile checks compatibility that stubs cannot establish. Neither replaces runtime validation of threading, native physics, Lua/mod integration, streaming, persistence or multiplayer behavior. For performance patches, compare representative telemetry captures after deployment before claiming measured savings.

Report the scripts run, their outcomes, the target-JAR compilation result, and remaining runtime scenarios in the handoff. Assertion totals are supporting evidence, not a substitute for describing what was verified. Documentation-only edits do not require executing the Java fixtures.

### Other utilities

- `Copy-PZPatchBaseline.ps1`: copies and verifies a single vanilla source and creates a local baseline commit. Follow the workflow above; this tool changes Git state and is not a test runner.
- `benchmark_iso_object_replay.py`: synthetic 42.21.0 IsoObject timing replay from one schema-3 snapshot or aggregate fixture; see `tools/BENCHMARK-ISO-OBJECT-REPLAY.md`. Compiles actual processing/lookup methods with engine stubs and verifies call counts/order. Alternative discovery paths exclude live maintenance costs and do not prove gameplay compatibility or server speedup.
- `benchmark_iso_object_integrated.py`: the same aggregate replay with production registry repair, between-frame mock player mutations, packet-style lookup assertions and durable processing-object mutations; see `tools/BENCHMARK-ISO-OBJECT-INTEGRATED.md`. Imports fixture builders from the replay and registry tests. Timing includes mutation/repair work but excludes initial trace and due-list construction; it is not a live scheduling implementation or server benchmark.
- `Start-PZGameProfiler.ps1`, `Stop-PZGameProfiler.ps1`, and `Get-PZGameProfilerStatus.ps1`: control or inspect vanilla profiler recordings. Read `tools/PZGameProfiler.README.md` for commands, cache paths and timing units. Start/stop write the watched trigger XML in the selected game cache directory.
- `Analyze-PZGameProfiler.ps1`: analyzes profiler CSV recordings and writes reports. The folder also contains `Analyze-PZGameProfiler-Fixed.ps1` and `script.ps1`; inspect them before choosing an alternate implementation rather than assuming they are interchangeable.
- `Sample-PZClientStutter.ps1`: samples a running client process, with options for thread stacks, GPU counters and profiler analysis. Inspect its parameters and output locations before collecting diagnostics; it is separate from the isolated Java fixtures and server telemetry.

For server metric definitions and optimization status, find the maintained
telemetry feature guide and related reports through the target version's
`docs/README.md`. Keep those documents and their index current when adding
counters, changing timing boundaries or implementing a previously documented proposal.

## Safety and compatibility constraints

- Do not modify the game JAR. The repository's deployment model uses loose classpath overrides.
- Do not commit generated `.class` files, downloaded JDKs, backups, temporary compiler output, or game files.
- Do not remove existing patch behavior while refreshing a file from vanilla source.
- Avoid broad formatting, renaming, or refactoring of decompiled classes.
- Preserve public and package-visible contracts used by other vanilla classes.
- Treat static mutable state, pools, queues, and background workers as concurrency-sensitive.
- Keep server-authoritative decisions on the server. Do not trust client-provided state without matching vanilla validation.
- Do not log credentials, access tokens, player passwords, or sensitive connection data.
- Make performance changes measurable and bounded. Avoid unbounded queues, scans, allocations, and per-tick logging.

## Agent handoff expectations

For each implemented patch, report:

- Target version and execution side.
- Vanilla source file used as the baseline.
- Patched source files changed.
- Behavioral intent and important invariants preserved.
- Compilation or dry-run command used and its result.
- Runtime scenarios still requiring manual validation.
- Any assumptions, version-specific risks, or related classes that future work should inspect.


## NPC engine object-oriented architecture

All NPC bot engine features, including extensions to existing behavior, must
follow [the NPC engine architecture contract](42.21.0/docs/features/2026-10-20-npc-engine-architecture.md).
Keep lifecycle/composition, behavioral policy, content providers, immutable
commands, execution state, engine capabilities and transport/authority separate.
Use composition and injected ports for role-specific behavior. Do not add NPC
key checks, event/database policy or domain-specific announcements to generic
actuators/executors. Reuse the bounded `Sequence`, `Wait` and `Say` actions for
ordered behavior; preserve cancellation and exclusive dialogue/trade ownership.
Keep per-execution mutable state out of reusable action/content objects. Every
new capability must document its owner, dependencies, failure/cancellation
semantics and production-code regression tests, and compile against the target
build. This rule applies to all NPC features, not only speech.


NPC profile/lifecycle changes must also follow
[the profile and persistence-boundary contract](42.21.0/docs/features/2026-10-20-npc-profiles-lifecycle.md).
Select behavior by registered `profileId`, never logical actor key or display
name. Register immutable versioned profile data separately from allowlisted
factories; create fresh behavior/controller state per NPC. Shared interaction
services use the activity controller, not guide-specific states. The logical
lifecycle owns activation independently of presentation IDs. PostgreSQL intake
must publish validated detached configuration/state to the owning NPC runtime;
do not let database workers construct live actors or mutate world objects.
Profile/module configuration, NPC state and related semantic memories now have
implemented persistence boundaries; consult the indexed NPC persistence guide
instead of assuming they are absent. Persist detached logical facts, never native
IDs, live executors, connections or monotonic timers.

### Component and subsystem map

The engine lives under `src/zombie/apocalipse/humans/` in the selected version.
Use the indexed architecture and subsystem guides before extending a capability.
Keep policy, execution, native authority and persistence as separate collaborators.

| Layer / subsystem | Main components | Responsibility and extension boundary |
| --- | --- | --- |
| Composition and logical lifecycle | `ServerHumanFactory`, `HumanLifecycle`, `ServerHumanProfiles`, `ServerHuman` | Register logical actors, bind immutable profiles, assemble fresh per-actor services, activate/remove/respawn and coordinate checkpoints. Logical identity outlives native presentation IDs. |
| Profiles and registration | `profiles/`, profile/module registries, walking-pattern registry | Validate immutable versioned data separately from allowlisted factories. Select policy by `profileId`, never actor key/name. |
| Simulation scheduling | `ServerHumanSimulationWorker`, factory `update/tick` | Own the independent NPC clock, startup mode, failure stop and shutdown; distinguish it from the vanilla world loop and persistence workers. |
| Behavior and arbitration | `brain/HumanBehavior`, `HumanModuleRuntime`, modules, `HumanActivityController` | Select objectives/priorities and arbitrate autonomous behavior versus exclusive dialogue/trade. Modules own policy; controllers own interruption and leases. |
| Actions and executions | `HumanAction`, `HumanActionExecution` | Reusable immutable action descriptions and fresh mutable execution progress, deadlines, terminal results and cancellation. Reuse bounded `Sequence`, `Wait`, `Say` and supported parallel lanes. |
| Perception and territory | `ServerHumanPerception`, `HumanContactMemory`, `HumanSenses`, `HumanObservation`, `ServerHumanTerritory` | Bounded discovery/visibility, lifetime-qualified contacts and detached observations. Keep sensing independent of hostility and target selection. |
| Walking, navigation and physical movement | `ServerHumanWalking`, `walking/`, route planners, `ServerHumanMovement`, `MovementGrid`, `MovementModifiers` | Patterns/objectives choose destinations; bounded planners propose routes; physical execution owns movement/collision and presentation. Pure condition resolution consumes explicit inputs. |
| Obstacle traversal | `traversal/`, `HumanTraversal`, `TraversalExecution`, `TraversalWorld`, `CrossingReservations` | Classify edges, arbitrate shared crossing/landing leases and own native interactions/commitment. Native obstacle references never become worker-safe just because a DTO holds them. |
| Physical state and survival | `state/`, `HumanStateStore`, `HumanStateReducer`, `HumanMutation`, `ServerHumanPhysiology`, `survival/` | Immutable health/needs snapshots, serialized authoritative reductions and native stat projection; survival owns fight/flight policy and retreat planning. |
| Engagement, tactics and weapons | `HumanEngagement`, engagement modules, `HumanCombatTactics`, `HumanCombat`, `HumanWeaponCombat`, combat/inventory/native adapters | Separate target policy, positioning, execution leases, phase timing, equipment and one-shot hit commits. Preserve cancellation and authoritative target/range/PvP revalidation. |
| Player encounters and retaliation | `encounters/`, `ServerHumanEncounters`, `ServerHumanRetaliation`, `ServerHumanPlayerAttacks`, `ServerHumanPlayerDamage` | Account/affiliation memory, authenticated aggression intake, PvP authorization and native damage import. Keep incoming vulnerability distinct from outgoing retaliation eligibility. |
| Dialogue, trading and assigned tasks | `dialogue/`, `ServerHumanDialogue`, `ServerHumanTrading`, `tasks/`, task/corpse ports and adapters | Own authenticated sessions, interaction exclusivity, task intent and native item/corpse execution. Shared services depend on the generic activity controller. |
| Reactive speech and content | `speech/`, `ServerHumanSpeech`, announcement ports/providers | Detached speech context/decisions, immutable cached catalogs, bounded repetition memory and acknowledged delivery. Speech does not own movement or weapon timing; content providers do not select actors or send packets. |
| Death, loot and respawn | `death/`, `HumanLifecycle`, `ServerHumanDeaths`, reputation port | One death transition, native corpse/loot effects and logical respawn deadlines; fresh runtime/state ownership for each incarnation. |
| Native capabilities and transport | `HumanActuator`, `ServerHumanActuator`, `ServerHumanTransport`, packet adapters | Execute mechanics and replicate validated results. Keep actor-specific content and domain policy out of generic actuators and packet builders. |
| Zombie interaction authority | `zombie.apocalipse.zombies`, `ZombieVictimPort`, `ServerHumanZombieVictims`, corpse-drag authority | Coordinate server zombie control and human-victim/native corpse interactions; respect reaction preemption and authority ownership. |
| Persistence | `zombie.savefile.ApocBRNpcStore` and NPC/profile/relationship/dialogue/speech/mortality persistence adapters | Capture detached facts, stage bounded journal/JDBC work and publish validated results. Database workers do not own live-world mutation or NPC simulation timing. |

### NPC simulation worker versus world simulation

The default 42.21.0 mode is asynchronous: one daemon
`ApocBR-NPC-Simulation` worker targets 10 Hz independently of vanilla world ticks.
`-Dapocbr.npc.async=false` selects synchronous execution at JVM startup; changing
the property later is not a supported hot switch. `GameServer` still calls the
factory entry point to bootstrap configuration, install the Lua API and attach
the zombie victim adapter. After successful configuration, the worker executes
the existing NPC simulation; it parks between deadlines, never overlaps its own
ticks and does not queue missed ticks. Failure stops it with diagnostics rather
than silently restarting or falling back to sync. `ServerMap.QueuedQuit()` joins
it before world/pathfinding/network teardown.

This scheduling change deliberately separated the bot clock from the main world
loop and required explicit server-thread/NPC-worker access guards plus serialized
factory maps and state reductions. It did **not** convert every world read, native
mutation, Lua callback or packet path into a thread-safe operation. Factory-managed
state stores accept the registered server thread and their dedicated worker;
standalone stores retain their construction-thread owner. Factory locks can make
server callbacks wait for an NPC tick, and returning an actor reference from a
guarded lookup does not lock that actor for the rest of the callback.

New features and changes must account for asynchronous execution from the start:

- Identify every entry thread and mutable owner, including server packet/Lua
  callbacks, NPC ticks, zombie interaction callbacks and persistence workers.
  A class, method name, record, getter or `final` reference is not evidence of
  thread safety. Check nested collections, native pools/caches and object reuse.
- Prefer detached immutable observations/configuration and immutable commands
  with explicit results across ownership boundaries. The documented snapshot
  design remains a migration plan; do not claim it already isolates the worker.
- Do not extend the experiment by casually adding live global reads or mutations
  to worker policy. Use the subsystem's owning adapter, establish its thread
  contract and snapshot/marshal or explicitly synchronize shared state as needed.
  Existing unsafe accesses are technical debt, not a precedent for new features.
- Preserve incarnation/control/revision validation, exactly-once effects,
  exclusive interaction ownership and cancellation when work crosses threads.
  Do not share per-execution mutable state between NPCs or concurrent callers.
- Keep work/queues bounded. Do not create a thread per NPC/action, block the world
  tick on worker results, hold a factory monitor while joining a worker, or hide
  lost effects behind swallowed exceptions. Audit lock ordering before adding locks.
- Verify both startup modes, authorized versus unrelated callers, concurrent
  callbacks, stale/reused entities, failure/cancellation and shutdown. Use
  production-code fixtures plus exact-JAR compilation; runtime streaming, Lua,
  network and slow-tick tests are still required for concurrency claims.

Document each new capability's thread ownership and cross-thread inputs/results
in its dated feature guide, update `docs/README.md`, and link any measured report
or unresolved issue. Use the NPC worker guide and access inventory from the index
as the starting points for threading work. Do not confuse this live-world NPC
worker with the separate immutable asynchronous zombie-packet sending pipeline.
