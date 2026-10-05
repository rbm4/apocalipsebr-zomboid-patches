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
```

Some directories have a suffix such as `-client`. These are separate patch targets and must not be assumed to match the unsuffixed server directory.

A modern version directory generally contains:

```text
<version>/
  decompiled/zombie/   Unmodified decompiled source for that exact game build
  src/zombie/          Complete Java source files that override vanilla classes
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

If the user does not specify a version, inspect the available version directories and ask which build is targeted. Default to the newest version only when the user explicitly permits that choice.

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
| `test_unload_simulation_algorithms.py` | Entity removal versus vanilla, animal-zone topology and geometry invalidation, sound expiration, thumpable no-work predicates, vehicle telemetry batching, and source contracts preserving vehicle/thumpable update behavior. |

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
- `Start-PZGameProfiler.ps1`, `Stop-PZGameProfiler.ps1`, and `Get-PZGameProfilerStatus.ps1`: control or inspect vanilla profiler recordings. Read `tools/PZGameProfiler.README.md` for commands, cache paths and timing units. Start/stop write the watched trigger XML in the selected game cache directory.
- `Analyze-PZGameProfiler.ps1`: analyzes profiler CSV recordings and writes reports. The folder also contains `Analyze-PZGameProfiler-Fixed.ps1` and `script.ps1`; inspect them before choosing an alternate implementation rather than assuming they are interchangeable.
- `Sample-PZClientStutter.ps1`: samples a running client process, with options for thread stacks, GPU counters and profiler analysis. Inspect its parameters and output locations before collecting diagnostics; it is separate from the isolated Java fixtures and server telemetry.

For server metric definitions and optimization status, use the target version's `TELEMETRY.md` and related investigation/follow-up reports alongside the source and tests. Keep those documents current when adding counters, changing timing boundaries or implementing a previously documented proposal.

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
