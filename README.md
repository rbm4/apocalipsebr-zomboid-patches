# ApocalipseBR Project Zomboid Agent

A Byte Buddy Java agent for the ApocalipseBR Project Zomboid Build 42 dedicated
server. It adds server diagnostics, performance changes, hotfixes, and gameplay
and persistence integrations by transforming selected game classes as the JVM
loads them. The root Maven project builds a shaded agent for the original
**Project Zomboid 42.22.2** game JAR.

Start with the [documentation index](.docs/README.md) for architecture, feature
configuration, migration status, and validation reports.

## How the agent works

The server JVM loads `target/apocbr-agent.jar` through `-javaagent`.
[ApocBrAgent.premain](src/main/java/cloud/apocalipse/pz/agent/ApocBrAgent.java)
calls [AgentRuntime](src/main/java/cloud/apocalipse/pz/agent/AgentRuntime.java),
which installs one Byte Buddy pipeline using the
[shared module registry](src/main/java/cloud/apocalipse/pz/agent/AgentModules.java).
Each feature module owns its target classes and method signatures and installs
its hooks through Byte Buddy advice or bytecode visitors. Vanilla execution
reaches agent-owned code through those hooks. The original game archive stays
intact.

[AgentConfiguration](src/main/java/cloud/apocalipse/pz/agent/config/AgentConfiguration.java)
defines supported JVM settings and environment variables. The default
`prod` profile uses [application.yaml](src/main/resources/application.yaml).
Set `-Dapocbr.profile=local` to apply the
[local overlay](src/main/resources/application-local.yaml), which points the
telemetry and restart base URLs at localhost. Feature guides describe activation
flags and service prerequisites.

## What's in the agent

| Area | Implemented scope |
| --- | --- |
| Telemetry and server operations | Tick and Lua callback timing, JVM and world metrics, packet log rate limits, and asynchronous Workshop checks with restart notification. [Lag diagnostics](.docs/features/server-lag-diagnostics.md) · [Packet diagnostics](.docs/features/network-diagnostics.md) |
| Performance and compatibility | Server simulation, line of sight, cell and chunk streaming, parked-vehicle scheduling, zombie packet preparation and transmission, Java pathfinding, and asset-loading hotfixes. [Feature catalog](.docs/features/README.md) |
| Lua and NPC runtime | Server Lua APIs, bounded service workers, mod entity persistence, and NPC actors with game hooks and a persistence coordinator. [Server Lua](.docs/features/server-lua.md) · [Lua entity persistence](.docs/features/lua-mod-entities.md) · [NPC engine](.docs/features/npc-engine.md) |
| PostgreSQL | Account, dictionary, and optional player persistence hooks, plus experimental chunk persistence and chunk object mutations. [PostgreSQL](.docs/features/postgresql.md) · [Experimental chunk storage](.docs/features/postgresql-chunks.md) · [Chunk object mutations](.docs/features/chunk-object-mutations.md) |

Feature activation and validation vary. The
[migration status](.docs/migrations/README.md) records which paths are wired,
which have been exercised, and what remains open for live-server acceptance.

## Build and run

Use **JDK 25**, **Maven**, and a local copy of the original
`projectzomboid.jar` for build 42.22.2. Run the following from the repository
root with the absolute path to your archive.

```sh
mvn "-Dpz.game.jar=/absolute/path/to/projectzomboid.jar" clean package
```

The platform defaults are
`Z:/SteamLibrary/steamapps/common/ProjectZomboid/projectzomboid.jar` on Windows
and `/opt/pzserver/java/projectzomboid.jar` on Linux. Pass `pz.game.jar`
explicitly so tests that read the JVM property pick up the same archive.

Maven writes the shaded agent to `target/apocbr-agent.jar`. It bundles the
agent dependencies and leaves the game JAR external. Put the `-javaagent`
argument before the server main class in your dedicated-server launch command.

```text
-javaagent:/absolute/path/to/apocbr-agent.jar
```

Launch from the matching game installation so native libraries and game
resources are available. Resolve game classes from the original archive and
exclude patched archives and directories containing loose game-class overrides
from the classpath. See the [architecture and launch guide](.docs/architecture/README.md)
for the original-JAR launch setup.

## Testing

- **Routine development** uses JUnit 5 through Maven Surefire. Run
  `mvn "-Dpz.game.jar=/absolute/path/to/projectzomboid.jar" test`, or add
  `-Dtest=LagDiagnosticsHookTest` to run a focused example. The suite includes
  feature tests with doubles, transformation checks against original game
  bytecode, and targeted execution of transformed methods. Cover the expected
  feature result, vanilla call-through or deliberate skip, failure behavior,
  and lifecycle cleanup where applicable.
- **Packaging** uses the build command above to produce the shaded JAR. When
  the artifact changes, verify packaged linkage on the original-JAR classpath.
  Keep build, linkage, and live acceptance results distinct.
- **Dedicated-server integration** is opt-in through Maven Failsafe. Run
  `mvn verify -Pserver-integration "-Dpz.game.jar=/absolute/path/to/projectzomboid.jar" "-Dpz.it.home=/absolute/path/to/server"`.
  The standard suite starts one disposable dedicated-server JVM with the
  packaged agent and a test-only observer. It checks original-JAR class origins
  and declared hooks, discovers independent `ServerAssertion` implementations
  through Java's service loader, and captures evidence under
  `target/server-integration/`. Routine `test` and `package` runs need only
  the original archive and use no dedicated-server process.

The manually triggered [GitHub Actions workflow](.github/workflows/server-integration.yml)
runs the Linux suite against the expected Steam build and original JAR digest.

Read the [validation guide](.docs/validation/README.md) for the evidence expected
for a port and the [integration guide](.docs/validation/server-integration.md)
for runtime prerequisites, isolated services, available scenarios, and coverage
limits.

## Repository layout

| Path | Purpose |
| --- | --- |
| [`pom.xml`](pom.xml) | Java 25 Maven build, agent shading, and integration profile |
| [`src/main/java/`](src/main/java/) | Agent entry point, module registry, hooks, and feature code |
| [`src/main/resources/`](src/main/resources/) | Runtime defaults and bundled SQL resources |
| [`src/test/`](src/test/) | JUnit tests, game doubles, and the isolated server harness |
| [`.docs/`](.docs/README.md) | Active architecture, feature, migration, and validation guides |
| [`42.22.2/`](42.22.2/docs/README.md) and [`42.22.2-client/`](42.22.2-client/) | Legacy patches, same-build vanilla references where available, and historical deployment material |

The root Maven project is the destination for feature work. To port a legacy
behavior, compare the matching `decompiled/` and `src/` sources, implement the
required vanilla hooks in a registered module, and declare each installation
contract with `hookChecks()`. Use [AGENTS.md](AGENTS.md) and the
[feature migration template](.docs/migrations/feature-template.md) to document
the scope, evidence, and open runtime checks.

Maintained for the Brazilian Project Zomboid multiplayer community
[Apocalipse \[BR\]](https://apocalipse.cloud).
