# NPC actors, gameplay adapters and persistence

Date: 2026-11-21. Status: **implementation in progress; the complete NPC port and runtime acceptance remain open**. The initial planner slice now connects the relocated factory/actor graph, native gameplay hooks and a durable NPC store path. Compilation and transformation do not establish full gameplay or database acceptance.

## Source and runtime ownership

The [dependency map](../reports/2026-11-21-npc-dependency-map.md) remains the compatibility checklist. Its 191 custom seed sources were relocated from `42.22.2/src` into `cloud.apocalipse.pz.agent.features.npc`, preserving human subpackages and placing related code under `zombies`, `pathfind` and `persistence`. Existing planner changes were retained. No native replacement class was copied into production packages; legacy trees were not edited.

Original target: `Z:/SteamLibrary/steamapps/common/ProjectZomboid/projectzomboid.jar`, SHA-256 `3c4a7c5f149d784758c6fb5f8fa85f3a21d572e38bc081400e89035f8120d287` (42.22.2).

`NpcModule` participates in `AgentModules.all()` and preflights enabled targets without defining game classes. `NpcRuntime` connects the simulation callback to `NpcEngine` and `NpcActorRuntime`. The factory receives the session's existing planner, avoiding a second worker. Native actions, world capture, inventory, Lua and checkpoint capture stay on the registered server owner. The worker receives only detached planning jobs.

`ServerHumanFactory` orchestrates registration, profile validation, activation, mutation admission, actor updates, visibility, deactivation, mortality/respawn and checkpoint capture. `ServerHuman` constructs the original `IsoPlayer` at zero coordinates, detaches constructor additions from cell/world membership, configures its identity and composes the migrated adapters. It does not create a connection/account or insert actors in native player ownership maps. Failed adapter initialization releases acquired body capabilities.

Presentation IDs remain 768–1023, with 64 active actors and a 256-ID process budget. Failed IDs are not recycled, including across factory/world session replacement. Logical identity, retirement and activation counts remain separate from wire identity.

Readiness requires server mode, transport, current cell and the installed membership adapter. A cell change creates a fresh factory/planner generation. Cleanup stops admissions/planning, closes activities, captures checkpoints, releases group/zombie/corpse state, removes presentations and stops store/journal workers. It attempts other actors after an individual cleanup failure. Off-owner/JVM fallback stops detached services without native body access or a fabricated final checkpoint.

## Connected original-game boundaries

All installation contracts are declared in `hookChecks()` for automatic discovery by the opt-in integration suite. No gameplay classes are forced into startup.

| Original boundary | Agent behavior |
| --- | --- |
| `GameServer.main(String[])`, after `LoginQueue.update()` | Production runtime callback in the active simulation branch, before later backup/social/world-map work; packet-only iterations do not supply this callback. |
| `ServerMap.QueuedQuit()` entry, main return/throw and before `System.exit(int)` in main and `ServerMap.preupdate()` | Console quit checkpoints and removes native actors on the simulation thread before JVM shutdown starts. The native quit hook stops NPC workers before vanilla teardown. Shared-pool shutdown also invokes the NPC callback. External JVM shutdown can reach the quit hook off-owner, where actor checkpoint and removal still require the simulation thread. |
| `IsoWorld.KillCell()` / `IsoCell.Dispose()` entry | Stop the native session before world disposal; later readiness can create another generation. |
| `GameServer.getFreeSlot()` | Preserve slot ordering while excluding connection slots 192–255, including not-yet-issued NPC IDs. |
| `PlayerDataRequestPacket.processServer(...)` | Consume known NPC requests and announce only to relevant connected recipients; otherwise continue ordinary lookup. |
| `IsoCell.objectList` initialization | Wrap the original set before the constructor publishes the cell/starts workers. Direct, bulk and iterator mutations track membership tokens, including remove/re-add. The existing field/type remains unchanged. |
| `IsoObject.setSquare/reset/addToWorld/removeFromWorld/removeFromSquare` | Weak identity lifetime revisions replace the added legacy member, including conditional square/removal increments. Furniture scanning uses its supported untracked-list fallback. |
| `PMMover.set(PathFindRequest)` | Handle only agent-owned walk-only movers, copy level/bound restrictions, disable crawl/thump/fence capabilities; retain ordinary requests and the other overload. |
| Declaring `PlayerRequestPacket.parse(...)` | Resolve only destinations for six exact trading families after native sender parsing, covering inherited request/accept/reject parses. |
| Six trading `processServer(...)` methods | Consume handled/rejected NPC sessions through migrated authorization/activity/durable-sale logic; retain ordinary player-to-player behavior. |
| `PlayerHitPlayerPacket.parse/set/processTarget/process` | Reset agent-owned packet state; check raw player target type; fingerprint original bytes; resolve only the victim; sanitize NPC target/fall effects. |
| `PlayerHitPacket.processServer` / relay call | Filter invalid/duplicate NPC claims before common attack calculations, rebuild closest-target state and remove rejected relays, after native parse/consistency/anti-cheat. |
| Declaring default `HitCharacter.processServer(...)` | Guard intake and import actual before/after native health loss once; ordinary packets retain the native default body. No nonexistent child override is assumed. |
| `Hit.roll/process`, `BodyPart.ReduceHealth` | Retain rolled body-part/force data and pre-clamp NPC health scaling; bypass only delayed-disconnect handling. The separate PvP logging check remains native. Authored committed-hit subclasses retain no-reroll behavior. |
| `NetworkZombieManager.updateAuth/moveZombie` | Acquire/retain NPC/corpse control using native ownership-list bookkeeping and reject conflicting reassignment. |
| Packer `updateAuth/applyZombie/getZombieData`, `ZombiePacket.set`, character `hitConsequences` | Preserve frame ordering, accepted-owner receive handling and native packet preparation; append eligible controlled relays and NPC/corpse overlays. Frame relay selection is shared across viewers. |

`NpcChat` constructs native say/shout messages with original channel/member/range eligibility, without fake accounts or arbitrary broadcasts. Factory ticks reinstall trusted `ApocBRHumanAPI` version 5 and the seating facade when the Lua environment changes. Announcements use the existing agent Lua/events provider. These trusted server APIs do not authorize arbitrary client commands.

## NPC persistence

The five relocated `ApocBRNpc*` adapters hydrate profiles, instances, dialogues, relationships, speech and mortality through `PostgresRuntime.borrowBackground()`. Capture/application and JDBC remain separate. The store retains its scope advisory-lock connection and epoch fencing. Persistence requires at least three shared pool leases; the retained session must not consume the entire pool. This minimum is not a throughput sizing recommendation for all services.

`features.postgres.NpcPersistence` owns a server/world-scoped durable journal and replay worker. Startup replay precedes store admission. Pending checkpoint/flag/relationship/speech values are removed only after durable journal capture. Receipt claim and SQL writes share a PostgreSQL transaction; acknowledgement follows commit. Unknown operations remain unacknowledged. Unhealthy persistence pauses activity/mutations. Store shutdown precedes journal shutdown and both precede shared-pool release.

Sales retain stable UUIDs, native item/bundle IDs and MoneyBundle conversion. Local durable capture precedes inventory consumption, native removal notification, the real player's native save request and finalization. Nonmoney items retain the explicit discard contract. The external credit consumer and overall player-persistence migration remain separate dependencies.

The decoder recognizes relocated legacy sales, including the older record without `bundleItemIds`; compatibility tests retain journal-entry and sale UUIDs. Legacy shared journals are not silently imported, drained or discarded. The existing legacy PostgreSQL cutover guard still applies: format compatibility does not establish completed cutover.

The agent queries existing backend-owned tables; it performs no schema migration. The administrator bootstrap [grant script](../../src/main/resources/db/npc-engine-grants.sql) covers `public`, `player_rel`, `npc_engine`, existing tables/sequences and future objects created by `apocalipsebr`. Run only after objects exist, as an authorized grantor. Other object creators need their own default privileges. Grants do not confer ownership/DDL rights. No grants or database changes were applied in this session.

## Startup configuration

All settings are declared/read through `AgentConfiguration`.

| Setting | Default / purpose |
| --- | --- |
| `-Dapocbr.npc.enabled=true` | Master switch; default false. Disabled installs no NPC hooks/workers. |
| `-Dapocbr.npc.actors.enabled=false` | Default true; false retains only planner lifecycle. |
| `-Dapocbr.npc.async=false` | Default true; native authority stays server-owned in both modes. |
| `-Dapocbr.guide.enabled=true` | Guide bootstrap; default false. |
| `apocbr.guide.name/x/y/z/facing/female/outfit/profile` | Legacy-compatible settings; defaults include profile `lobby-guide`, position `14294,11862,0`, facing `E`. |
| `-Dapocbr.npc.persistence.enabled=true` | Default false; requires PostgreSQL, NPC schema/grants and pool budget. |
| `apocbr.npc.serverKey` | Native server name fallback; combined with native world name. |
| `apocbr.npc.journalDirectory` | `<cache>/apocbr-agent/npc-journal`; SHA-256 server/world scope directory appended. |
| `apocbr.npc.persistence.checkpointMs` | 30000, clamped to 1000–60000. |
| `apocbr.guide.trade.enabled` | true; also requires healthy durable persistence and native player-save availability. |
| `apocbr.npc.language/logLevel/playerHitTrace/zombieControl/combat.testWeapon` | Preserved defaults: `PTBR`, `ERROR`, `true`, `true`, `AUTO`. |

Shared PostgreSQL URL/pool/server-ID configuration remains in `AgentConfiguration`. Credentials stay outside documentation/source. These are startup settings, not hot toggles. Disabling the master switch and restarting rolls back without changing the archive or deleting journals.

## Validation and remaining acceptance

Focused tests cover planning bounds/cancellation/failure, transformed tick/quit/exception paths, actual factory registration/retirement, world reset/replacement, worker cleanup, set mutation paths, conditional object lifetime changes, journal restart/order, receipt retry and legacy sale descriptor evolution. Each declared actor hook is transformed against an explicit original-JAR-first locator and checked for its helper call and unchanged game structure.

The factory fixture uses a test-only unsigned loader for structural doubles plus reference class bytes. It reaches production factory orchestration but does not construct an active native body. A separate fixture executes the transformed original default `HitCharacter.processServer` and verifies ordinary call order and exception propagation. These fixtures are separate from original-JAR transformation/linkage validation, which excludes loose overrides from target resolution.

```powershell
mvn test "-Dtest=NpcEngineTest,NpcHookTest,NpcActorHookTest,NpcAdaptersTest,NpcPersistenceTest" "-Dpz.game.jar=Z:/SteamLibrary/steamapps/common/ProjectZomboid/projectzomboid.jar"
mvn package "-Dpz.game.jar=Z:/SteamLibrary/steamapps/common/ProjectZomboid/projectzomboid.jar"
java -Xverify:all -javaagent:target/apocbr-agent.jar -Dapocbr.npc.enabled=true -Dapocbr.postgres.enabled=false -Dapocbr.lua.enabled=false -cp "target/apocbr-agent.jar;Z:/SteamLibrary/steamapps/common/ProjectZomboid/projectzomboid.jar;target/test-classes" cloud.apocalipse.pz.agent.features.npc.NpcPackagingSmokeMain target/apocbr-agent.jar
java -Xverify:all -javaagent:target/apocbr-agent.jar -Dapocbr.npc.enabled=true -Dapocbr.npc.actors.enabled=false -Dapocbr.postgres.enabled=false -Dapocbr.lua.enabled=false -cp "target/apocbr-agent.jar;target/test-classes;Z:/SteamLibrary/steamapps/common/ProjectZomboid/projectzomboid.jar" cloud.apocalipse.pz.agent.features.npc.NpcPipelineSmokeMain
```

Packaging smoke checks all NPC helper signatures from the shaded artifact, rejects bundled game classes, executes its planner and preflights enabled actor hooks. The separate installed-agent fixture tests composition with telemetry and explicitly disables actor/Lua/account subpaths. Neither launches a real game or opens external services.

Executed on Windows/Java 25: 22 NPC-focused tests passed. Final `mvn package` reported 100 tests, zero failures/errors and one historical patched-runtime skip. Both packaged smoke commands passed. The tested shaded artifact SHA-256 was `f865e7ca865a352492becc1b68f1297eeb06c010638055797985d53869dded91`; it includes concurrent workspace feature work, not an NPC-only release. Evidence remains in `target/surefire-reports`, `target/npc-package-validation.log`, `target/npc-packaging-smoke.log`, `target/npc-pipeline-smoke.log`, `target/npc-default-hit.log` and `target/npc-hook-*.log`.

The selected full port stays open for:

- Executed native-body construction/rollback, visibility/data requests and ordinary-player preservation with active actors.
- Behavioral coverage of trade/intake authorization, damage once, zombie acquire/release and multi-viewer overlays; transformed helper calls alone do not prove semantics.
- Isolated PostgreSQL with distinct creator/engine roles: actual store queries, grants, locking/fencing, checkpoint/restart/outage recovery, sales and pending legacy cutover. Current SQL tests use JDBC doubles.
- Mortality/loot/respawn ordering and failure/replay with real world saves, plus external player-save and credit-consumer acceptance.
- Real server and compatible client/mod/animation assets for movement, combat, dialogue, seating, corpse dragging and shutdown/reload.

No real-server profile, configured-services task, deployment or live database was run. No new integration probe targets or production test-control backdoors were added. Implementation, artifact validation, deployment and runtime acceptance remain separate.
