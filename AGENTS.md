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
