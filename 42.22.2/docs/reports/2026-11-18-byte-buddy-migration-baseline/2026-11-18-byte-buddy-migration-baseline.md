# Byte Buddy migration: 42.22.2 source baseline and impact inventory

Date: **2026-11-18**. Target: **42.22.2 dedicated-server source patches**.
Status: **source comparison and artifacts captured; migration decisions pending**.

This report preserves the current vanilla-to-patched delta for a later migration
session. It covers the whole base implementation, including NPCs, PostgreSQL,
optimizations, telemetry and supporting integrations. It does not move, refactor,
compile, deploy or retire implementation classes. Existing version directories,
patch scripts, game archives and launchers are left in place.

The owning implementation context is the [agent foundation](../../features/2026-11-17-byte-buddy-agent-foundation.md)
and [Workshop restart hook](../../features/2026-11-17-workshop-update-restart-agent.md).
Those describe the initial agent, not completion of the base migration.

## Resume here

1. Run the snapshot verifier before relying on the captured artifacts:

   ```powershell
   py -3 42.22.2/docs/reports/2026-11-18-byte-buddy-migration-baseline/generate.py --verify
   ```

   On Linux use `python3` instead of `py -3`. The verifier expects the captured
   source/context bytes; checkout line-ending conversions may report raw-hash
   drift even when LF-normalized Java content is equivalent. Both raw and
   LF-normalized Java hashes are retained for diagnosing that distinction.

2. Open the [per-source inventory](2026-11-18-class-inventory.md), then the diff
   linked for each source being considered. Do not infer a move is safe merely
   because its source is classified as added.
3. Consult the [manifest](manifest.json) for original paths, classification,
   SHA-256 hashes, hunk counts and textual additions/removals.
4. Use the [custom-class reference map](custom-class-references.json) as a search
   index for dependencies and manual integrations, then resolve actual methods,
   fields and execution ownership in source.
5. Decide migration boundaries in a new report or implementation change. Keep this
   dated snapshot unchanged; a newer baseline should have its own dated folder.

The [generator/verifier](generate.py) uses only Python's standard library and Git.
Without `--verify` it captures a snapshot only if `manifest.json` does not already
exist. It refuses to overwrite an existing snapshot. Verification applies diffs
only inside a disposable temporary directory, never to the workspace.

## Provenance and scope

Captured on branch `feature/bytebuddy-migration`, with repository HEAD
`7bda5a6cc113ef340a3be3b2a689a87b07eff06b`. The **working tree**, not just HEAD,
is the source being captured. At capture, the relevant pre-existing changes were
`pom.xml` and untracked `.vscode/`. Their content hashes and those of current root
Java sources, bundled libraries and patch entry points are in the manifest.
The manifest is the precise provenance record; HEAD alone is insufficient to
reconstruct uncommitted context.

Comparison inputs:

- Baseline: `42.22.2/decompiled/zombie/**/*.java`.
- Implementation: `42.22.2/src/zombie/**/*.java`.
- Root `src/main/java` and `src/test/java`: context hashes only. They have no
  vanilla baseline and are outside the versioned-source diff set.
- Non-Java resources, Lua/client companions, SQL migrations, launcher scripts and
  database runtime state: not compared as feature implementations here. Existing
  patch scripts, retired-family metadata and library JARs are fingerprinted as
  context, not declared migrated.

The vanilla tree contains **3,107 Java sources**. Only counterparts of our
implementation sources are compared. Vanilla-only sources are **not deletions**:
their absence from `src` means they are supplied by the game, not removed by us.
This is a source baseline; it does not establish that the decompiler precisely
reproduces shipped bytecode or that these files match every installed server.
See the inherited [42.22.2 source port/build report](../2026-11-08-port-execution.md)
for that earlier verification's historical scope.

## Measured comparison results

| Measure | Result |
| --- | ---: |
| Implementation Java source files | 375 |
| Existing vanilla counterparts with differences | 109 |
| Added implementation sources without a vanilla counterpart | 266 |
| Unchanged counterparts | 0 |
| Per-source diff assets | 375 |
| Unified diff hunks | 689 |
| Added text lines, including complete custom classes | 30,561 |
| Removed text lines | 1,950 |
| Total per-source diff bytes | 2,438,629 |

Counts describe source files, not compiled classes; nested and anonymous classes
may produce additional runtime classes. Large added-line totals include the full
contents of custom sources and do not measure Byte Buddy conversion effort.
The older documentation index's 339-source build count is historical; this
snapshot independently enumerates 375 sources and does not rewrite that history.

### How each diff was produced

The generator reads each source and corresponding vanilla file, normalizes only
CRLF to LF in disposable comparison inputs, then executes:

```text
git -c core.autocrlf=false diff --no-index --no-ext-diff --no-textconv --no-color --no-prefix --no-renames --text --unified=5 vanilla patched
```

Both temporary trees contain only the paths owned by this source inventory.
The result is split at Git's `diff --git` boundaries. Headers are rewritten to
stable `a/zombie/...` and `b/zombie/...` paths; original Git hunks, modes, index
lines and missing-final-newline markers are retained. Each file is stored at
`diffs/zombie/<relative-source>.java.diff`. Added files have `/dev/null` as their
old path. An unchanged file would have an empty diff asset, explicitly recorded
as unchanged; none occurred in this snapshot.

No whitespace, import, formatting or decompiler-repair changes are filtered out.
Consequently, a diff hunk is evidence of changed source text, not automatically
evidence of an intentional gameplay feature. The originals are never rewritten.

## Area of impact

### Custom implementation available for move-preserving review

There are **177 added sources in `zombie/apocalipse/humans`**, **7 in
`zombie/apocalipse/zombies`**, and **5 `ApocBRNpc*` sources in `zombie/savefile`**.
These 189 directly named NPC-related sources are an initial search scope, not a
complete closed NPC dependency set. Shared event, database, speech, sale, world,
packet and utility dependencies also need review.

The 266 additions also include the Lua API bridge, persistence services,
dictionary support, indexes, worker implementations, diagnostics and telemetry.
They are candidates for retaining implementations, subject to compilation and
runtime dependency checks. No source in this report has been certified as
movable unchanged.

### Modified vanilla classes by location

| Location under `zombie` | Modified sources |
| --- | ---: |
| `network` | 39 |
| `entity` | 16 |
| `iso` | 15 |
| `characters` | 8 |
| `popman` | 6 |
| `pathfind` | 5 |
| `vehicles` | 4 |
| `world` | 3 |
| `core` | 2 |
| `inventory` | 2 |
| `Lua` | 2 |
| `savefile` | 2 |
| `gameStates` | 1 |
| `util` | 1 |
| Root scheduler classes and `WorldSoundManager` | 3 |

### Representative integration boundaries to review

| Boundary | Source evidence | Questions for the later migration |
| --- | --- | --- |
| Main loop, login, lifecycle and NPC ID reservation | [GameServer diff](diffs/zombie/network/GameServer.java.diff), [ServerMap diff](diffs/zombie/network/ServerMap.java.diff) | Preserve loop position, tick frequency, server-thread authority, startup prerequisites and shutdown ordering. An advice at `main` entry does not reproduce a recurring loop hook. |
| NPC speech additions and backend chat integration | [ChatServer diff](diffs/zombie/network/chat/ChatServer.java.diff), [custom transport](diffs/zombie/apocalipse/humans/ServerHumanTransport.java.diff) | `sendHumanSay`, `sendHumanShout` and `humanShoutRange` are added game-class APIs. Existing custom callers will not compile against vanilla alone until access is deliberately provided. |
| NPC incoming/outgoing combat and zombie authority | [PlayerHitPlayerPacket diff](diffs/zombie/network/packets/hit/PlayerHitPlayerPacket.java.diff), [Hit diff](diffs/zombie/network/fields/hit/Hit.java.diff), [BodyPart diff](diffs/zombie/characters/BodyDamage/BodyPart.java.diff) | Identify validation, new state, authority, packet target resolution and one-shot effects before choosing advice or method replacement. |
| Trading and player-data requests | [RequestTradingPacket diff](diffs/zombie/network/packets/RequestTradingPacket.java.diff), [PlayerDataRequestPacket diff](diffs/zombie/network/packets/character/PlayerDataRequestPacket.java.diff) | Preserve authorization, target resolution, early-return semantics and complete multi-packet flow. Other trading packet diffs are in the inventory. |
| PostgreSQL authentication and async role snapshots | [ServerWorldDatabase diff](diffs/zombie/network/ServerWorldDatabase.java.diff), [ServerPlayerDB diff](diffs/zombie/savefile/ServerPlayerDB.java.diff) | Added nested `LoginRoles`, thread-local roles, methods and replacement database paths demonstrate that dependencies extend beyond simple entry-point calls. |
| Dictionary, capture/reconstruction and vehicle persistence | [WorldDictionary diff](diffs/zombie/world/WorldDictionary.java.diff), [InventoryItem diff](diffs/zombie/inventory/InventoryItem.java.diff), [VehiclesDB2 diff](diffs/zombie/vehicles/VehiclesDB2.java.diff) | Preserve lifecycle, provenance, mutation tracking and serialization contracts; textual similarity does not establish persistence compatibility. |
| Zombie packet preparation and asynchronous delivery | [NetworkZombiePacker diff](diffs/zombie/popman/NetworkZombiePacker.java.diff), [NetworkZombieManager diff](diffs/zombie/popman/NetworkZombieManager.java.diff) | Trace worker ownership, custom methods called by other patched vanilla classes, and bounded completion/shutdown behavior. |
| Update scheduling, indexes and cache ownership | [MovingObjectUpdateScheduler diff](diffs/zombie/MovingObjectUpdateScheduler.java.diff), [IsoCell diff](diffs/zombie/iso/IsoCell.java.diff) | Establish which changes are narrow hooks versus algorithm replacements; preserve mutation/invalidation and unload behavior. |
| Lua exposure and script compatibility | [LuaManager diff](diffs/zombie/Lua/LuaManager.java.diff), [Event diff](diffs/zombie/Lua/Event.java.diff) | Capture initialization order, exposed names/types and existing Lua callers before moving packages or removing replacement classes. |

These are source-supported investigation boundaries, not approved refactors or
an exhaustive member-level API audit. Multiple features coexist in some classes;
retiring one feature's hook does not permit removing that entire replacement.

### Largest modified-counterpart text deltas

| Source | Added | Removed | Hunks |
| --- | ---: | ---: | ---: |
| `network/ServerWorldDatabase.java` | 1,345 | 955 | 13 |
| `popman/NetworkZombiePacker.java` | 425 | 87 | 4 |
| `network/ServerLOS.java` | 272 | 191 | 4 |
| `network/ServerMap.java` | 184 | 89 | 17 |
| `network/GameServer.java` | 173 | 60 | 23 |
| `MovingObjectUpdateScheduler.java` | 197 | 29 | 4 |
| `MovingObjectUpdateSchedulerUpdateBucket.java` | 187 | 30 | 1 |
| `iso/objects/IsoGenerator.java` | 155 | 54 | 9 |
| `characters/IsoPlayer.java` | 177 | 29 | 16 |
| `savefile/ServerPlayerDB.java` | 151 | 2 | 10 |

This list ranks textual review surface only. Small changes to packet validation
or ownership may be more consequential than much larger source deltas.

## Reference-map limitations

`custom-class-references.json` contains **3,989 lexical reference rows**, including
**729 in modified vanilla sources**, plus explicit import records. Comments and
ordinary string/character literals are masked before matching identifiers to
added source filenames. Imports and declarations are retained and marked; simple
name ambiguity retains all candidate source paths. This is not compiler-resolved
dependency analysis, a call graph, or a list of executable hooks.

The scan finds references to NPC-named custom sources in **16 modified vanilla
sources**. This is a lower-bound navigation aid. It does not find every custom
member added to a vanilla class: the `ChatServer` additions above are a concrete
counterexample. Reflection, Lua bindings, nested types, native dependencies,
custom methods between modified vanilla classes and resource contracts require
separate tracing. Classpath and access cohesion cannot be certified by this map.

## Decisions intentionally left for the next session

- Establish exact game-JAR Maven coordinates, content fingerprint, external
  dependencies and source attachments. The root POM currently declares the
  PostgreSQL/Hikari libraries as `provided`, not the full game compilation API.
- Choose a first NPC dependency closure and compile against the exact vanilla API
  to expose reliance on injected members and visibility changes. A later temporary
  patched compilation API must be explicit if used; it must not hide missing
  future instrumentation contracts.
- Prefer retaining custom package/type/signature contracts for the first move.
  Review signed-package constraints separately before changing the unsigned
  runtime arrangement or moving classes currently sharing vanilla packages.
- Decide per integration whether to use entry/exit advice, conditional skipping,
  method delegation, accessors, injected state, an external state owner, or a
  temporarily retained replacement. No global conversion policy is selected.
- Preserve server-thread native NPC execution and detached planning ownership,
  according to the [current async authority guide](../../../../42.22.1/docs/features/2026-11-05-npc-async-authority.md)
  and [NPC architecture contract](../../../../42.21.0/docs/features/2026-10-20-npc-engine-architecture.md).
- Define class ownership during coexistence: avoid loading stale loose copies of
  migrated helpers, and avoid executing old calls and instrumented hooks twice.
  Include nested/anonymous class-family cleanup and keep other features active.
  Existing retired-family metadata is context, not proof cleanup is implemented.
- Define expected-target diagnostics, compatibility checks and validation per
  feature before retiring old integrations. Identical signatures alone do not
  establish equivalent threading, callers, timing or method semantics.

## Validation performed and limits

The capture tool generated all 375 per-source assets and recorded raw source and
diff SHA-256 hashes. Its independent verification ran `git apply --check`, applied
the complete diff collection to a disposable vanilla tree, and compared every
reconstructed file's LF-normalized SHA-256 against the patched source. All 375
matched. Source inventory coverage, diff coverage and context hashes matched.
Documentation link validation and docs-index registration are part of this change.

No Java compilation, gameplay fixture, database operation, service change or
deployment was required or performed for this source/report task. No claim is
made that the future migration compiles, preserves runtime behavior or has already
been implemented. The user reports successful agent loading on Linux; this report
does not independently validate that deployment or the live restart endpoint.
