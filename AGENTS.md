# AGENTS.md

## Purpose

The top-level Project Zomboid version directories and their patch/decompiled trees are **legacy**. They preserve the original implementation and exact-build vanilla references; they are not the active destination for new feature work.

Active development is the Maven-based Byte Buddy agent in the repository root (`src/main/java`, `src/main/resources`, and `src/test/java`). Future work will gradually port behavior from the legacy patches into this agent over multiple sessions. Treat the original game behavior as compatibility evidence, but express migrated behavior through the agent's instrumentation, advice, feature modules, and tests rather than by adding more complete-class replacements.

Treat the agent as production game-server code. Preserve vanilla behavior unless a requested migration explicitly changes it. Do not assume that a legacy patch has already been migrated merely because its source or documentation exists.

## Active Byte Buddy migration workflow

These rules take precedence over the historical version-directory workflows and checkpoints below:

- Treat every top-level game-version directory, including its `src/`, `decompiled/`, `docs/`, build scripts, and tools, as legacy/reference material. Do not add or port implementation there unless the user explicitly requests legacy patch maintenance.
- Implement current work in the root Maven project. Keep production code in `src/main/java`, runtime configuration in `src/main/resources`, and focused tests in `src/test/java`.
- Declare every JVM system property used by production code in `cloud.apocalipse.pz.agent.config.AgentConfiguration`, with a Javadoc comment identifying its owning feature and supported `-D` argument. Read properties through that class rather than calling `System.getProperty` at feature call sites. Add future JVM argument settings there; keep environment-variable names and their lookup in the same class when they participate in application configuration.
- Port incrementally. Work only on the class, behavior, or explicitly bounded group selected for the current task. Do not start a broad migration, process a queue of classes, or infer approval for later ports. The user orchestrates separate future sessions.
- For a port, identify the relevant legacy patch and its exact vanilla baseline. Separate old patch intent from vanilla behavior, then map only the intended behavior into the existing Byte Buddy architecture. Do not copy a legacy replacement class wholesale into the agent or recreate whole-class replacement behavior by default.
- Before editing, inspect the agent's entry point, feature registration, related advice/modules, existing tests, and current documentation. Reuse the established instrumentation and lifecycle patterns. If a behavior has no safe or clear instrumentation boundary, explain the constraint and ask for direction rather than silently expanding the port.
- Preserve the original method's behavior and call order where the migration is observational or additive. For intentional behavior changes, make the changed contract explicit and test both the hook behavior and continued execution of vanilla behavior as appropriate.
- Use the relevant legacy game build as evidence for signatures and behavior; do not make a version directory the default implementation target. Compare other builds only when the task involves a version difference or the exact baseline is uncertain.
- Until an individual port is complete, the server may still run the legacy patched Java class rather than the vanilla class. For instrumentation that touches such a class, distinguish the vanilla JAR baseline from the actual patched runtime classpath; test both shapes (including loose class overrides when they take precedence over a patched archive) so neither current runtime compatibility nor the intended vanilla target is assumed.
- When a legacy replacement conflicts with or obstructs a Byte Buddy hook, make an explicit per-class migration decision with the goal of retiring/removing that replacement in favor of the Byte Buddy hook once its behavior is covered. Do not silently weaken or skip instrumentation for one class shape, or treat permanent compatibility with the legacy replacement as the default. Until it is retired, retain a regression test for the patched runtime shape as well as the vanilla baseline.
- Add or update active project documentation only under the repository-root `.docs/`. Use `.docs/README.md` as its index and organize future material there (for example, architecture, migrations, features, and reports). Do not create new documentation in version-directory `docs/` or duplicate a guide there. Existing version-directory documents remain historical references unless explicitly requested.
- Validate the current Maven project with the smallest relevant tests, normally `mvn test`; use `mvn package` when validating the shaded agent artifact. Do not run legacy patch scripts or deploy into a game installation for an agent-only change unless explicitly required.
- Keep each port independently reviewable: record its source behavior, agent implementation points, compatibility decisions, tests, and remaining runtime gaps in the appropriate `.docs/` material when documentation is part of the task.

## Repository model

Top-level version directories are legacy snapshots, such as:

```text
42.19.0/
42.20.0/
42.20.1/
42.20.3/
42.21.0/
42.22.0/
```

Some legacy directories have a suffix such as `-client`; those snapshots are distinct from their unsuffixed counterparts. Consult the matching snapshot when tracing old behavior, but do not infer that the active agent has separate versioned source trees.

Legacy version directories may contain:

```text
<version>/
  decompiled/zombie/   Unmodified decompiled source for that exact game build
  src/zombie/          Complete Java source files that override vanilla classes
  docs/               Historical, version-specific documentation
  patch*.ps1           Windows compilation and deployment scripts
  patch*.sh            Linux compilation and deployment scripts where available
```

These trees explain the legacy class-replacement system. Their presence does not make that system the current implementation or documentation workflow. The active root project is built with Maven; consult its `pom.xml` and current agent code for actual dependencies, packaging, instrumentation, and tests.

## Source-of-truth rules

1. For active agent code, treat the root Maven source and tests as the implementation source of truth.
2. When porting behavior, use the relevant legacy `decompiled/` file as the vanilla baseline and the corresponding legacy `src/` file to identify the old patch's intent. Do not edit either tree for an agent migration.
3. Identify the exact legacy build only as needed to establish the behavior and signatures being ported. There is no implicit “latest game version” target for agent work.
4. Compare other legacy builds when behavior differs, the task is a version comparison, or the applicable baseline is uncertain. Do not copy a source file from another build without checking those differences.
5. Decompiled source can contain synthetic names, awkward control flow, and decompiler artifacts. Use it as evidence, not as code to transplant or clean up.
6. Do not rely on memory or a different game build when the relevant legacy source in this repository can answer the question.

## Version documentation and index

All new or actively maintained project documentation belongs under the root
`.docs/` directory, with `.docs/README.md` as the index. The documentation layout
will evolve with the Byte Buddy migration; keep each new document in the
appropriate project-level category and link it from the index. Do not create new
guides or update indexes in version-directory `docs/` for current agent work.

Version-directory documentation is legacy and remains useful as historical
evidence. Preserve its original scope and links when consulting it; do not imply
that a legacy guide or measurement describes the current agent unless it has been
verified during migration. Only update legacy docs when a task explicitly asks
for legacy patch documentation maintenance.

## Legacy Java monkey-patch workflow (reference only)

This workflow applies only when the user explicitly requests maintenance of the
legacy class-replacement system. It is not the workflow for porting behavior into
the Byte Buddy agent. In that case:

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

For an agent migration, first identify the requested legacy behavior and its
vanilla baseline, then trace how the current root agent is structured to observe
or implement it. Avoid broad exploration of unrelated version directories.
Before changing behavior:

1. Inspect the requested legacy patch and vanilla baseline, plus the corresponding root-agent feature and tests. Keep searches bounded to those owners and their direct call sites.
2. Find the owning class and the entry point that invokes it.
3. Trace both sides of multiplayer behavior. A method name alone does not prove whether it runs on the client, server, or both.
4. Search for all reads and writes of modified fields and all calls to modified methods.
5. Check synchronization, worker-thread ownership, queues, object pools, and lifecycle ordering before changing performance-sensitive or asynchronous code.
6. Check `GameClient.bClient`, `GameServer.bServer`, dedicated-server checks, and related guards.
7. For network changes, trace packet declaration, serialization, transmission, receipt, validation, and state application.
8. For loading changes, trace request, background work, main-thread integration, unload, reuse, and persistence paths.
9. Compare another legacy build only when the port depends on a version difference or the exact behavior is uncertain.

Useful searches include class names, method names, packet identifiers, log text, Lua event names, and field access. Search for behavior and call sites, not only filenames.

## Lookup shortcuts by subsystem

The paths below are legacy source-navigation shortcuts, relative to
`<version>/decompiled/zombie/`. Use them only to understand behavior being
ported; exact classes vary by build, and the active implementation belongs in
the root Maven agent.

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

## Historical version-directory porting records (legacy)

The dated checkpoints and queue details in this section record earlier work on
the legacy class-replacement system. They are historical evidence, not current
task instructions, approval to resume a queue, or a destination for new ports.
The active direction is the incremental Byte Buddy workflow above.

A legacy patch is not automatically valid for a newer build even if the class name is unchanged.

### Current 42.22.2 carry-forward checkpoint

On 2026-11-08 the user authorized copying the current 42.22.1 patched sources and
build scripts and applying the audited GameServer changes. The scripted copy
verified 351 files: all 339 sources, two dependency JARs, eight tools and both
build scripts. Target vanilla remains unchanged. GameServer differs only in two
upstream revision literals; Windows/Linux script labels and the retirement
manifest target now identify 42.22.2. The actual target Windows BuildOnly passed
with javac25.0.4: 339 sources,984 classes, revision3d39148846/world251. Artifacts
are retained under 42.22.2/artifacts/build-2026-11-08 (ignored). No deployment,
launcher edit, database migration or commit was performed. Storage/event labels
remain inherited42.22.0; cleanup/runtime and other inherited limitations remain
open. See the target execution report and receipts in its documentation index.
Current NPC architecture/authority contracts below continue to apply to the
carried42.22.2 implementation; older checkpoint counts remain historical.

### Historical 42.22.1 carry-forward checkpoint

On 2026-11-04 the user authorized copying the complete 42.22.0 working tree into
42.22.1, applying the audited GameServer merge and committing the progression.
The 42.22.1 vanilla tree is preserved. All 305 sources are carried forward;
GameServer alone incorporates changed upstream source (integrity startup and
revision logging). Libraries, tools and historical documentation are copied;
ignored old backups remain historical copies. The copy receipt and execution
report are indexed in 42.22.1/docs/README.md. Build scripts identify 42.22.1.
The Windows script's -BuildOnly option performs real compilation and retains
artifacts; it is distinct from -DryRun and does not deploy to the installation.
The actual target build passed on 2026-11-04: javac25.0.4, 305 sources, 902 class
files, installed JAR revision71e8410c42/world251. Retained classes, ZIP and hash
manifest are under 42.22.1/artifacts/ (ignored); the build receipt is committed
under docs/reports/. All archived class hashes and GameServer integrity-startup
bytecode were verified. This build did not modify the game installation.
Persistence/event labels remain exact 42.22.0 carry-overs. Do not globally replace
them: exact 42.22.1 dictionary provenance requires a coordinated backend schema,
publication, historical-read and namespace policy migration. Runtime validation
and inherited cleanup/concurrency/reconstruction gaps remain open. The older
42.22.0 checkpoint below is history, not a request to restart its review queue.

### Current 42.22.0 migration checkpoint

The user explicitly authorized exact carry-over of the 50 replacements with
byte-identical vanilla baselines and 200 ApocalipseBR-only support files after
the source audit. Those 250 files were initially copied unchanged; review38 has since adapted eight support sources.
Follow the [subsystem tracker](42.22.0/docs/features/2026-10-27-subsystem-port-tracker.md)
and its per-class copy receipt. Copied does not mean compiled or ported.

The user requested discussion of **each merge individually before applying it**.
The numbered queue contains all 48 changed replacements, including clean text
merges. Present old patch intent, upstream changes and a concrete candidate for
one class; record the user's decision and apply only the accepted class before
presenting the next. Removed-class designs and copied-support adaptations remain
separate work afterward unless the user changes the order. Review 01 (GameEntity)
is approved/applied, with the target using-player fixture passing; full-class
compilation/runtime remain pending. Review 02 (EngineEntityManager) is also
approved/applied; its target engine-removal fixture passes. Review 03
(GameEntityManager) is approved/applied with the target lifecycle fixture passing.
Review 04 (CraftLogicSystem) is approved/applied with the target crafting fixture
passing. Review 05 (SpriteConfig) is approved/applied with source verification
only (diagnostic suppression, preserved 251 loader). Review 06 (ItemContainer)
is approved/applied with its target fixture passing. The user accepts staged
integration dependencies being completed by later ports; each later class still
requires individual review. Review 07 (InventoryItem) is approved/applied, with
the resolved length check, new-encoding metadata 251 and parser fixture passing.
Its PostgreSQL reconstruction integration remains later work, as the user stated.
Review 08 (IsoGridSquare) is approved/applied with the grid fixture passing.
Review 09 (IsoObject) is approved/applied with the object fixture passing; its
cache is enabled only on mutation-tracked lists, so current plain square lists
use native fallback. The collection adapter remains explicit later work.
Review 10 (LoadGridsquarePerformanceWorkaround) is approved/retired to target
vanilla; no target override exists. Its class family is recorded in
`42.22.0/tools/retired-class-families.json`. The manifest is not yet consumed by
deployment scripts; stale loose-class backup/cleanup must be implemented and
verified before deployment. No installed files were changed.
Review 11 (LootRespawn) is approved/retired to vanilla and recorded in the
cleanup manifest. The user explicitly retired the reader patch only; the broader
cache remains in scope. Review 12 (IsoChunk) is approved/applied on 2026-10-28 after vanilla baseline
commit 345374b. Its extracted unload fixture passed 437,322 assertions across
6,000 differential scenarios; exact-JAR/native-runtime checks remain pending.
Review 13 (ServerMap) is approved/applied on 2026-10-28 after vanilla baseline
commit 1340c3c; its
extracted queue fixture passed 799,768 assertions across 3,000 differential
scenarios. It preserves native void Load2, failed-cell cleanup, partial Unload
and exact target QueuedSaveAll. Applied source equals the reviewed candidate.
Full native/save/shutdown and exact-JAR/runtime checks remain pending.
Review 14 (IsoWorld) is approved/applied on 2026-10-28 after vanilla baseline
2264d72. Candidate equality and source assertions passed; no runtime fixture.
Review 15 (IsoCell) is approved/applied on 2026-10-28 after vanilla baseline
e150592. Candidate equality and 1,592 extracted cadence assertions passed.
Its IsoThumpable predicate source dependency is supplied by applied review 19;
other staged integrations remain pending. Review 16 scheduler is approved/applied on 2026-10-28
after exact baseline af72c24; applied-candidate equality and 550 lifecycle/
algorithm assertions passed with engine doubles. Review 17 IsoAnimal is approved/applied on
2026-10-28 after baseline 25bf0ca; candidate equality and source checks passed,
full animal updateLOS/behavior/native validation remains pending. Review 18
DesignationZoneAnimal is approved/applied on 2026-10-28 after baseline fbf8f6f;
candidate equality and 104,003 topology assertions passed. It preserves native
doMeta, whose decompiled nested baby loop remains flagged for exact-JAR/runtime
verification before any fix. Review 19 IsoThumpable is approved/applied on
2026-10-28 after baseline 70adec5; candidate equality and 200,000 no-work
assertions passed. It supplies the IsoCell predicate source dependency while
preserving all target door/network changes. Review 20 IsoGenerator is approved/
retired to native on 2026-10-28; its family is in the cleanup manifest, which
remains unconsumed/unverified. No installed files were changed. Broader square
caches remain in scope. Review 21 IsoDoor is approved/applied on 2026-10-28
after baseline d334b7d; candidate equality/source checks passed, no Java fixture
added. Review 22 IsoDeadBody is approved/applied on 2026-10-28 after baseline
c64153c; candidate equality and 12,500 wakeup assertions passed with engine
doubles, native bone validation retained. Full decoder/dictionary/native
integration remains pending. Review 23 IsoMannequin is approved/applied on
2026-10-28 after baseline 70a0b84; candidate equality and 16 encoder/provenance
assertions passed, metadata251 matches new encoding. Historical249 stays historical.
Review 24 IsoPlayer is approved/applied on 2026-10-28 after baseline 89ce60b;
candidate equality and 5,008 extracted expiry/bookkeeping assertions passed.
Native scheduler counts are preserved; full LOS/movement/network/backend
validation remains pending. Review 25 IsoGameCharacter is approved/applied on
2026-10-28 after baseline 75f3e3a; candidate equality and 25 inventory/spatial
helper assertions passed, full character/backend/zombie authority/native tests
pending. Review 26 PathFindBehavior2 is approved/applied on 2026-10-28 after
baseline cca1b6d; candidate equality and 14,003 finite-path assertions passed.
Native farming avoidance/retry remains unchanged; zombie-AI caller/full runtime
pending. Review 27 ZombieCountOptimiser is approved/applied on 2026-10-28
after baseline a1ce877; candidate equality/source checks passed, native
all-multiplayer relevance retained. Review 28 NetworkZombieAI is approved/applied
on 2026-10-28 after baseline e1fb420; candidate equality and 294 cache/resolver
assertions passed. Native server-null fallback stays dynamic from path goals;
full prediction/bookkeeping/packet/worker integration remains pending. Review 29
ZombiePacket is approved/applied on 2026-10-28 after baseline 1df7643; candidate
equality and 1,792 population assertions passed. Native 56-size/OnlinePlayer/
ByteBufferWriter retained; old packer ByteBuffer snapshots need adaptation.
Review 30 BaseVehicle is approved/applied on 2026-10-28 after baseline 65ac5ff;
candidate equality passed. The temporary review31 VehicleParts candidate plus
applied target vehicle sources passed 60,018 extracted-method assertions.
Review31 is approved/applied on 2026-10-28 after baseline 0c6bcc0; candidate
equality and 60,018 assertions passed again; idle-device helper source supplied.
Full native vehicle/physics/Lua/runtime/JAR validation remains pending.
Review32 VehiclesDB2 is approved/applied on 2026-10-28 after baseline d9e1c1a;
candidate equality and 83 isolated JDBC-proxy helper assertions passed. The user
explicitly defers vehicle PostgreSQL stored-coordinate repair/decode-binding gaps
while that backend stays disabled in production (source property default false;
production configuration not verified/changed). Resolve before enabling, not a
reason to stop current source ports. Review33 WorldDictionary is approved/applied
on 2026-10-28 after baseline eb232bd; candidate equality/source checks passed,
native log251 retained. Its dictionary gate is separate from vehicle storage.
Review34 PlayerDamagePacket is approved/applied on 2026-10-28 after baseline
c0502d7; candidate equality and 26 packet/native-field assertions passed with
engine doubles. Internal sentinel -1 adds no wire byte; full native validation
remains pending. Review35 PlayerEffectsPacket is approved/applied on 2026-10-28
after baseline 2c2180d; candidate equality and 62 ownership assertions passed.
Calendar gate retained. Review36 PlayerStatsPacket is approved/applied on
2026-10-28 after baseline 006e586; candidate equality and 42 ownership assertions
passed. Review37 SyncPlayerStatsPacket ownership guard is approved/applied on
2026-10-28 after baseline 0210374; candidate equality and 20 assertions passed.
The prior retirement recommendation is WITHDRAWN: CanModifyBodyStats and
pre-parse authorization already existed in 42.21.0 and do not enforce target
ownership; native OnlinePlayer still resolves global IDs. User requires guarding
against cross-connection target manipulation. Recommend keeping the adapted
owner guard is applied; no admin exception or retirement authorized.
Review38 PlayerHitPlayerPacket requires coordinated NPC combat adaptation: new
PlayerHit/Hit/envelope protocol, removed WeaponHit/shot IDs/setter APIs and
server roll lifecycle. The user approved preparing a coordinated candidate and
selected a 500ms exact-payload guard, accepting identical rapid-attack suppression.
The user approved review38 and the stock-client capacity tradeoff on 2026-10-28.
All 16 files are applied and equal the reviewed candidate; 1,725 assertions passed
after application with native packet/Hit sources, engine doubles and extracted
allocation methods. Baselines: PlayerHitPlayerPacket 22a379e, PlayerHitPacket
4999063, Hit 8a6561f, GameServer a5e1000. Fixed NPC IDs 768..1023 retain 64 active
NPCs, reserve whole connection slots leaving 192 real connection slots, and
permit 256 fresh incarnation IDs per process. Do not recycle IDs; skips and
post-allocation failures consume IDs. Exhaustion rejects activation; restart
required. These are stock-client player replicas, outside the live-player registry;
no real bot connection is created. The 500ms exact-payload guard can suppress
identical legitimate rapid attacks; altered/later replay is not comprehensively
prevented. Full restart required for ID policy changes.
GameServer now contains the review48 integration and review38 slot reservation.
Future changes MUST preserve both. Do not overwrite it with another baseline. WeaponHit's old
accessor intent is covered by new native Hit adapters; removed design resolved,
with stale WeaponHit.class cleanup tracked but unimplemented. There are 303 target
Java files, 45 reviewed merges, 3 changed-class retirements, 2 resolved removed
targets, 0 pending original changed reviews and 1 remaining removed-target design.
Review39 PlayerHitSquarePacket is approved/applied on 2026-10-28 after baseline
ae6e61a. The single missed() diagnostic call preserves all other target text;
applied-candidate equality and source checks passed, with no new fixture.
Review40 RequestTradingPacket/NPC trading handshake is approved/applied on
2026-10-28. Baselines: RequestTradingPacket 2e1008f, AcceptTradingPacket 960fd49,
RejectTradingPacket 67deb11. All four files equal the approved candidate and 131
assertions passed again. Sender is ConnectionPlayer, destination OnlinePlayer;
resolve only reserved NPC trading destinations in the packet-specific overrides.
Global OnlinePlayer and PlayerRequestPacket remain vanilla. Native real-player
pending-response checks stay intact. NPC replies use native AcceptTrading/
RejectTrading setData(NPC,visitor); NPC sessions bypass TradingManager intentionally.
Review41 TradingUIAddItemPacket is approved/applied on 2026-10-28 after exact
baseline 300b619; candidate equality and 105 assertions passed again. Native
sender-inventory lookup, item=null reset, item consistency, exact ordinary trade
pair checks and version251 loader remain intact; NPC destination/offer hooks use
the applied review40 adapter. Missing item IDs fail native consistency before
adapter cancellation, leaving existing offers unchanged.
Review42 TradingUIRemoveItemPacket is approved/applied on 2026-10-28 after exact
baseline a78d82e; candidate equality and 120 assertions passed again. Keeps native
fields, Integer ID, fixed body and ordinary trade-pair check. Existing NPC hook
withdraws offer IDs without deleting inventory and unseals even absent IDs.
Review43 TradingUIUpdateStatePacket is approved/applied on 2026-10-28 after exact
baseline d26bfff; candidate equality and 195 assertions passed again. Ordinary
native pending-request cleanup/pair/state logic stays intact; NPC state handling
uses the applied adapter and unchanged consent/sale/failure policy. Removed
Trading field intent is resolved through all narrow trade packet overrides;
stale Trading.class cleanup is tracked (five families total), not implemented.
PZArrayList is the sole remaining removed-target design. Full trade runtime/
durability/credit validation is still open; no production-ready claim.
Review44 ChatServer is approved/applied on 2026-10-28 after exact baseline
196d5da; candidate equality and 43 extracted-method assertions passed again.
Preserves native unknown-channel/membership/author and whisper ownership guards.
Only filtered general player chat reaches the old backend hook after native send;
trusted NPC say/shout/range helpers keep explicit real-recipient routing. Real
backend worker, fanout/filtering/network/UI and full class/JAR remain untested;
helper true is an attempt, not delivery acknowledgement.
Review45 AdvancedAnimator is approved/applied on 2026-10-28 after exact baseline
2b55f41; candidate/source equality passed. Adds only the old Files.notExists guard
for optional missing folders, preserving native Collections.emptyList and other
logic. No new fixture for that small diagnostic guard; no crash-fix claim.
Review46 LuaManager is approved/applied on 2026-10-28 after exact baseline
aca4626; candidate equality and 33 assertions passed again (21 cache,12 order/
exposure). Restores requested-key cache source semantics, server-only existing
APIs and decompiler typing/logging repairs. No shipped 42.22 bytecode/JAR/Kahlua
or API implementation verification; event-control metadata followup remains.
Review47 IngameState is approved/applied on 2026-10-28 after exact native
baseline d95d7a9; source/candidate equality passed. Same 19 old telemetry scopes,
all target flow/tutorial/exit changes kept. No runtime overhead/capture validation.
Review48 GameServer is approved/applied on 2026-10-28; the numbered queue is complete.
Two-file diff includes GameServer startup/login/NPC/backend/telemetry hooks plus
NetworkZombiePacker API adaptation (one reused writer on snapshotBuffer). Against
current GameServer reservation source, not vanilla; DO NOT call baseline helper
on either existing custom source. Preserve review38 slot reservation and all target
silent-player, disconnect, role, inventory/save251 and chat changes. One RCON
conflict resolved with native var118 and old timing scope. Candidate/source hashes
in review48 JSON. tools/test_game_server_port.py passes 824 counted assertions:
38 actual target async coordinator,18 extracted login dispatch,768 native writer/
packet-body snapshots; plus deferred ConnectPacket and source ordering contracts.
No entire GameServer/Packer compile, actual native player activation, live SQL,
RakNet/world or schema/durability validation. Both files equal the reviewed diff and all fixture checks passed after application.
GameServer is merged-unverified, partial prerequisite count0 and the packer known
API gap is adapted. Physical source count stays303 (both files already exist).
It does NOT complete migration: PZArrayList/List cache design, remaining copied
relational/dictionary support gaps, event metadata, full target JAR/build/native
validation, disabled/deferred vehicle PG repairs and cleanup implementation remain.
No production settings/game files/DB changed. Review49 two-field reconstruction adaptation is approved/applied on2026-10-28;
source equals candidate and target native storage/load contracts passed. Fifteen copied
support files are adapted; all initial copied gaps are addressed. Full compile
discovered two further copied API gaps (ServerHumanTransport and ServerZombiePath). Review50 new
source_dataset game_build42.22.0 insertion metadata is approved/applied on2026-10-28;
exact candidate equality passed. Existing
ON CONFLICT DO NOTHING preserves reused dataset metadata. Review51 is approved/applied on2026-10-28: dictionary insert/reuse/load42.22.0/251 plus early locked-namespace
metadata validation. Exact candidate equivalence and8 extracted JDBC-double cases/source contracts
passed after application. User requests viability/running before historical-data
migration planning; keep that work deferred.
Existing older namespaces fail22023 without automatic rollover/relabel; default
namespace excludes build. Old payload provenance/decoding still needs a separate
upgrade plan; do not imply changing namespace migrates historical payloads. Discuss support/design work individually instead of declaring migration done.
Installed JAR identified via javap Core42.22 and world251; SHA recorded in
2026-10-28-build-check.json. Full303-source Java25 compile failed with4 errors:
IsoDoor redundant decompiler default,2 removed transport id references,1 native
path addRequest boolean argument. Review52 IsoDoor default removal is approved/applied on2026-10-28; exact two-line
equivalence passed. Applied source equals the previously compiled overlay, leaving
3 errors. Review53 transport is approved/applied on2026-10-28: packet.player.set replaces
old packet.id/setPlayerIndex, keeping native2-byte field layout and ID range.
Exact candidate equivalence and773 exact-native Player/ConnectionPlayer checks
with engine/network doubles passed after application;
303-source overlay with53 leaves only ServerZombiePath boolean API error.
No full packet or live client execution; incoming ownership resolution unchanged.
Review54 native path addRequest(...,false) is approved/applied on2026-10-28. False preserves old custom-Mover default and target non-player policy.
303-source Java25 overlay compilation succeeds: exit0, zero errors,895 classes.
Actual approved303-source compilation also passes, producing895 classes. No known
copied API compile gaps remain (15 copied support files adapted). Prior collision
ThreadLocal helper/map byte-identical to42.21 fix; target four-thread/80000-query
regression passed via test_pathfind_collision_threads.py --version42.22.0 (space
before version value). PathFindBehavior2 static scratch is still shared as in old
patch; do not claim all vectors/native world state are thread-safe. Audit report
2026-10-28-pathfinding-thread-local-audit.md documents limits. Review55 event-control
build-prefix metadata is approved/applied on2026-10-28; exact candidate equality
passed (42.22.0/event-control-v2). Review56 proposes two existing-source edits:
square.objects uses MutationTrackedArrayList(2), helper gains default/capacity
constructors. NOT applied.33 extracted cache/constructor assertions and full303-
source candidate build passed (895 classes). Activates existing server entity-ID
cache only; identity-position index/PZArrayList design remains pending. No list
thread-safety or measured performance claim. Remaining:
List-cache and full runtime/deployment-cleanup validation still pending. No deployment
or runtime. Latest user instruction now prioritizes cross-build recovery and ends
individual reviews; the later checkpoint overrides earlier deferral/queue notes.
Live client UI, full factory/GameServer composition, real journal/SQL
receipt durability and native threading remain unverified. No installed files or
production settings changed.
Runtime startup now reaches WorldDictionary mirror but DB rejects target metadata:
PROVISION_NAMESPACE SQLState23514 world_dictionary_namespace_game_build_check.
Manager V6 pinned42.21.0/249; new manager V34 admits exact pairs42.21/249 and42.22/251
without rewriting namespace rows. See target issues/2026-10-28-dictionary-build-
constraint-startup.md. Backend must apply game-runtime Flyway before server retry;
no live schema/deploy/restart executed by this source fix.
On2026-10-28 the user STOPPED the individual review queue and shifted to runtime
validation/cross-build character recovery. Do NOT prepare further numbered reviews
or request approval for pending review56; it remains unapplied. Prioritize making
42.21 relational characters recover on42.22. Source investigation is recorded in
42.22.0/docs/reports/2026-10-28-cross-build-character-reconstruction.md; recovery
is not implemented. The user raised selective item omission as a possibility,
not authorization to delete source snapshots or live character data. Keep source
provenance/version and graph dependencies coherent; do not merely suppress errors.
Review51 currently blocks historical dictionary namespace reads and must be
revisited separately from target publication checks for cross-build recovery.
Upstream List traversal obsoletes these audited raw readers, not the separate
identity/mutation cache adapter that remains in scope.
Prioritize optimizations, NPCs and PostgreSQL, with telemetry verification
secondary, while retaining all subsystems in scope. Update this checkpoint as
decisions are implemented so it does not become a stale approval instruction.

When porting a legacy patch between version directories (legacy maintenance only):

1. Diff the old version's `decompiled` file against its patched `src` file to isolate the intended patch.
2. Diff the old vanilla file against the new version's vanilla file to identify upstream changes.
3. Copy the new version's vanilla file into the new version's `src` tree.
4. Reapply the intent of the patch, not a blind textual copy.
5. Recheck callers, signatures, synchronization, packet formats, serialization, and lifecycle behavior in the new build.
6. Compile and test against the new build's JAR.

Never copy an old patched class wholesale into a new version directory.

## Legacy patch build and verification records

The dated implementation notes and patch-script procedures in this section
describe legacy version-directory work. For current Byte Buddy changes, use the
root Maven build and tests described in the active migration workflow above.

2026-10-29 priorities5/6: user authorized subtype initialization and health/time
baselines for42.22. General Moveable/Radio sprite setup precedes saved projection;
weapon parts clear before attachments, current categories/magazine consistency
finalize afterward. Radio device parent/allocation, map rotation/zoom caches,
EntityScriptInfo script and UiConfig skin now resolve explicitly. Body-part last
state, nutrient clamps, clothing clock and food server sentinel match load
baselines. Low weight clamps without replaying damage; embedded animal milk
normalizes without milking effects, wild restoration preserves stress while
applying tame migration rules, fractional position baselines agree. Native corpse
icon hook runs after components. No schema/capture change or deployment. See
42.22.0/docs/features/2026-10-29-player-reconstruction-subtypes-and-health.md and
updated matrix TRACK-06/07/08/09/11. Subtype/health156 (includes lifecycle56),
visual/progression143, weight1763, SpriteConfig17 and resilience691 plus auxiliary
cases pass. Target DryRun303 sources/895 classes passes. Priority7 absent/empty
semantics and actual world/corpse/animal/first-tick validation remain open.

2026-10-29 priorities3/4: user authorized RGB/visual finalization and descriptor/XP
baselines for42.22. Snapshot optional itemRgb/Capture item.rgb preserve separate
native channels; historical missing RGB falls back to known col for custom items.
Per-item synchWithVisual runs after component restoration. Local XP baseline uses
native total-XP correction and replaces checker maps before native refresh.
Authoritative descriptor publication occurs after tracking and before equipment;
temporary copies do not publish. Next ID is savedID+1 because constructors use
postincrement. LogLevelPerk replay intentionally suppressed during synthetic load.
See target features/2026-10-29-player-reconstruction-visuals-and-progression.md;
matrix TRACK-02/05 updated; priorities5/6 were subsequently implemented above,
priority7 remains open. Visual/progression143 (includes
lifecycle56), mutation1763, SpriteConfig17 and resilience691 plus auxiliary cases
pass. Full303-source/895-class target DryRun passes. No deployment.

2026-10-29 follow-up: the user authorized starting the reconstruction reliability
work. Component membership/equipment lifecycle tranche is implemented for42.22:
item.entityComponents records explicit empty membership, grouped restoration uses
native release and final connect, hand owners/useHandWeapon use native load helpers.
Temporary reconstruction uses register=false; ServerPlayerDB promotes equipment
after tracking attaches. No deployment. Target lifecycle56, updated weight1672,
SpriteConfig17 and resilience691 plus auxiliary cases pass. The installed JAR now
exposes world251 and the303-source/895-class DryRun passes; prior world249 failures
below are historical. See target features/2026-10-29-player-reconstruction-lifecycle.md
and matrix TRACK-03/04. Remaining report tracks still require separate work.

On 2026-10-29 the user authorized 42.22.0 item weight mutation/reconstruction
repairs and round-trip tests. Target Snapshot now detaches optional raw itemWeight;
Capture stores custom raw weight as item.weight structured state (schema1/codec4,
no DDL). Reconstruction orders uses/condition/custom mass explicitly and applies
raw weight after components. Older rows/journal nodes use the native mass fallback;
historical missing raw weight cannot be recovered. See target features/2026-10-29-
player-item-weight-roundtrip.md. test_player_weight_roundtrip_42_22.py passes 1630
checks through production capture/queue/Delta SQL and extracted restore/native
food methods with engine/JDBC transport doubles. SpriteConfig17 and resilience691
plus auxiliary scenarios pass. Full DryRun against the default world249 JAR still
fails on missing target APIs. No deployment. Native client profile normalization
is unchanged and must be verified separately; this is server relational fidelity.

On 2026-10-29, the user authorized the 42.22.0 port of SpriteConfig relational
capture/reconstruction and the policy that abandons rejected saves without a
persistent player block. The implementation also retires old `.pending` markers
at startup, preserving archives. See
`42.22.0/docs/features/2026-10-29-player-sprite-config-and-quarantine-recovery.md`.
This is separate from the still-open cross-build character recovery design.
`tools/test_postgres_resilience.py --version 42.22.0` selects target production
sources (default remains 42.21.0); `tools/test_sprite_config_42_22.py` is the focused
target capture/restore regression, not a full login fixture.
At that verification the default installed JAR exposed world249, not251. Target
persistence fixtures pass with explicit boundaries (the SpriteConfig test compiles
the exact target native class), but the full 42.22 dry build against this older JAR
fails. Supply a matching target installation for whole-build verification; do not
rewrite target engine code to satisfy the old installed JAR.

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

## Legacy tools and algorithm regression tests

`tools/` primarily contains development and diagnostic utilities for the legacy
patches, not deployed game code. These fixtures do not by themselves validate a
Byte Buddy port. Inspect the relevant script before running it; scripts in this
folder have different side effects and prerequisites. For active agent behavior,
prefer focused tests under `src/test/java` and the root Maven build.

### Python test architecture and prerequisites

Most current `tools/test_*.py` scripts target **42.21.0** explicitly. They are standalone Python entry points, not a pytest suite. Exceptions: `test_using_player_algorithms.py` accepts `--version 42.21.0` (default) or `--version 42.22.0`, reads that version's production/vanilla files, and covers the newer pending-add reset predicate. `test_unload_simulation_algorithms.py --version 42.22.0 --engine-only` compiles both target manager classes with engine doubles and exercises removal/cancellation; its default full suite remains 42.21.0. `test_entity_manager_lifecycle.py` targets 42.22.0 explicitly, extracting manager methods and using actual EngineEntityManager/clock/throttle with engine doubles. Do not assume the other fixtures validate another build when porting a patch.

`test_craft_logic_system.py` also targets 42.22.0 explicitly; its bucket, recipe, resource and world collaborators are doubles. `test_item_container_port.py` also targets 42.22.0, using extracted container methods and actual index/list helpers with item/service doubles. `test_inventory_item_port.py` targets 42.22.0 parser/encoder/fluid methods with actual provenance/load-quality helpers and explicit routing/engine doubles. `test_iso_grid_square_port.py` targets 42.22.0 room/lighting/scratch methods and fields with engine doubles; native visibility remains outside its coverage. `test_iso_object_port.py` targets 42.22.0 object cache/getter and decode-wrapper methods with production tracked list and registry/world/routing doubles.

Each script uses Python's standard library to create a temporary Java fixture, compile production Java sources or methods extracted from production sources, and execute Java assertions. Some fixtures also compile or extract the exact target-version vanilla implementation for differential comparison. Small engine stubs supply dependencies without initializing the game or loading native libraries. Temporary source and class files are cleaned up automatically.

Prerequisites are Python 3 and the repository JDK 25 at `jdk/bin/javac.exe` and `jdk/bin/java.exe`. The scripts currently use those Windows executable paths and compile with `--release 25`; installing a JDK on `PATH` alone does not satisfy them. They require the selected fixture target's `src` and `decompiled` files (usually 42.21.0; see the exceptions above). If the repository JDK is missing, inspect the target patch script's tooling setup rather than changing Java versions to make a fixture pass.

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
| `test_entity_manager_lifecycle.py` | 42.22.0 manager update/lookup/register/unregister methods with actual EngineEntityManager/clock/throttle: batching, changed-ID lookup, pending cancellation, meta load and immediate/deferred release. Engine collaborators are doubles. |
| `test_craft_logic_system.py` | 42.22.0 complete CraftLogicSystem with real clock/throttle and explicit recipe/resource/bucket/world doubles; elapsed progress, meta throttling, drying cleanup and recipe transitions. No real bucket-index or native validation. |
| `test_item_container_port.py` | 42.22.0 extracted container reset/weight/parent/recursive methods plus actual sparse-update/list helpers; callback/list mutation, cycle/depth boundaries and nested query/removal. Native items/services are doubles; fluid/insertion integration remains pending. |
| `test_inventory_item_port.py` | 42.22.0 extracted native item parser/encoder/fluid predicate with actual provenance/load-quality helpers; consumed lengths, failure recovery, metadata and scope contract. Dictionary routing/virtual decode/world are doubles; reconstruction integration remains pending. |
| `test_iso_grid_square_port.py` | 42.22.0 extracted room-seen methods, lighting initialization, slot constant and scratch fields; differential room behavior, slot reuse and thread-local isolation with explicit engine doubles. Full CalcVisibility/native LOS are source-checked only. |
| `test_iso_object_port.py` | 42.22.0 extracted native ID getter/cache and decode wrapper with production mutation-tracked list; stable hits, mutation invalidation, plain/client fallback and scope boundary. Registry/world/routing/decoder are doubles; adapter/native reconstruction remains pending. |
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

For active server metric definitions and optimization status, use or create
documentation under root `.docs/`; keep its index current when migrating
counters, changing timing boundaries, or implementing a documented proposal.
Version-directory telemetry guides are legacy references.

## Safety and compatibility constraints

- Do not modify, replace, rename or delete the original game JAR. The loose-classpath and derived-JAR instructions below describe legacy deployment only; do not deploy an agent migration unless explicitly requested.
- Do not commit generated `.class` files, downloaded JDKs, backups, temporary compiler output, or game files.
- Do not remove existing legacy patch behavior while refreshing a legacy source file from vanilla.
- Avoid broad formatting, renaming, or refactoring of decompiled classes.
- Preserve public and package-visible contracts relied on by vanilla code when instrumenting it.
- Treat static mutable state, pools, queues, and background workers as concurrency-sensitive.
- Keep server-authoritative decisions on the server. Do not trust client-provided state without matching vanilla validation.
- Do not log credentials, access tokens, player passwords, or sensitive connection data.
- Make performance changes measurable and bounded. Avoid unbounded queues, scans, allocations, and per-tick logging.

## Agent handoff expectations

For each Byte Buddy port, report:

- Legacy patch and vanilla source used to establish the behavior.
- Root agent source, instrumentation/advice entry point, and tests changed.
- Behavioral intent, vanilla behavior retained, and any intentional change.
- Maven test/package command used and its result.
- Hook execution context and any thread, class-loader, server/client, or version assumptions.
- Runtime scenarios still requiring manual validation.
- Related legacy classes or behaviors that a future, separately scoped session may port.


## NPC engine object-oriented architecture

All NPC bot engine features, including extensions to existing behavior, must
follow the [legacy NPC engine architecture contract](42.21.0/docs/features/2026-10-20-npc-engine-architecture.md)
as a source of design constraints until its current-agent counterpart is
documented under `.docs/`.
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


NPC profile/lifecycle changes must also respect the legacy
[profile and persistence-boundary contract](42.21.0/docs/features/2026-10-20-npc-profiles-lifecycle.md)
until its current-agent counterpart is documented under `.docs/`.
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

The legacy engine lives under `src/zombie/apocalipse/humans/` in the version
directories. Use it and its guides as migration references. New agent-side code
belongs in root `src/main/java`; document migrated architecture under `.docs/`.
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

The details below describe the legacy version-directory NPC engine. They are
important when preserving or porting its behavior, but do not assume the active
agent has the same worker, lifecycle, or thread model; verify root agent code for
each new task.

**42.22.1 update (2026-11-05):** the current target replaces the live-world worker
with [detached planning and server-owned native authority](42.22.1/docs/features/2026-11-05-npc-async-authority.md).
`ServerHumanFactory.tick()` and native services now execute only on the server
thread. `apocbr.npc.async=true` runs sealed value-only selection/navigation jobs on
the worker; it does not authorize worker access to the factory, actors or Lua.
Do not reintroduce native worker ports or `this::tick` worker callbacks. The
42.21.0 description below records the older experiment, not current ownership.

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
