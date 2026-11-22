# Opt-in dedicated-server integration suite

## Purpose and boundaries

Unit tests remain the default. They exercise feature logic quickly, but game
test doubles and transformation-only checks cannot prove that the shaded agent
links and installs its hooks during actual server startup. This optional suite
starts the real dedicated server with the packaged agent and keeps one process
alive while independently reported assertions run against it.

The server runs in a child JVM, not in memory inside JUnit. Native libraries,
world/resource loading, static state, background threads and game exit calls
require process isolation. Both the original JAR and its matching runtime
installation are required. No proprietary game binaries are copied into Git.

Integration coverage is not mandatory for ordinary feature/port work. Sessions
specifically assigned to integration testing can extend this suite as features
become ready. Passing this suite does not make all ports runtime-validated.

## Running

Run from the repository root with Java 25 and Maven:

```powershell
$env:JAVA_HOME = (Resolve-Path ./jdk).Path
mvn verify -Pserver-integration "-Dpz.game.jar=Z:\SteamLibrary\steamapps\common\ProjectZomboid\projectzomboid.jar"
```

The Windows JAR path above is also the default. `pz.it.home` defaults to its
parent directory. Linux defaults match the production installation layout, but
the test uses its own configuration and world, not production data:

```sh
mvn verify -Pserver-integration \
  -Dpz.game.jar=/opt/pzserver/java/projectzomboid.jar \
  -Dpz.it.home=/opt/pzserver
```

Use a separate test installation/host when the production installation is busy;
this launches an additional resource-consuming server. Linux launch defaults
must be verified against that installation's native library layout. Matching
resources must exist at `pz.it.home/media`.

| Test setting | Default / purpose |
| --- | --- |
| `pz.game.jar` | Original Windows JAR path above, or `/opt/pzserver/java/projectzomboid.jar` on Linux |
| `pz.it.home` | Windows JAR directory, or `/opt/pzserver` on Linux; child working directory and resources |
| `pz.it.java` | Maven test JVM's `java.home/bin/java[.exe]`; explicit child executable override |
| `pz.it.nativePath` | Home, home/win64 or home/linux64, home/java, joined with the OS path separator |
| `pz.it.heap` | `2g` maximum child heap |
| `pz.it.startupSeconds` | `240` maximum readiness wait |
| `pz.it.shutdownSeconds` | `90` maximum graceful shutdown wait |
| `pz.it.serverLogs` | `true`; stream child output to Maven with a `[PZ]` prefix; `false` keeps only the raw log |
| `pz.it.color` | ANSI green/red/yellow result lines by default, unless `NO_COLOR` exists; explicit `true`/`false` overrides that default |
| `pz.it.useTaskConfiguration` | `false`; when `true`, forwards explicit runner `apocbr.*` properties and recognized service environment settings to the child |

These are test-harness settings read only by test code. Production configuration
continues to belong in `AgentConfiguration`. Do not pass production database or
API settings into ordinary isolated runs. The configured-services task below
explicitly opts into its selected external settings.

### VS Code configured-services task

The global file `C:\Users\ricar\AppData\Roaming\Code\User\tasks.json` contains
**Integration test ApocBR agent - configured services**, also the default test
task. Use VS Code's **Tasks: Run Test Task**, or select that label from **Run Task**.
It runs Maven `verify -Pserver-integration` from this repository, with the original
JAR, Java 25, streamed output and colors. It copies the PostgreSQL properties,
database connection, local profile, shared API credential and bridge environment
credential from the existing dedicated-server task, and explicitly enables the
account hooks with `apocbr.postgres.serverDatabase.enabled=true`.

The task sets `pz.it.useTaskConfiguration=true`. `IntegrationLaunchSettings`
then forwards its `apocbr.*` properties into the child JVM instead of forcing
PostgreSQL off or replacing the API endpoint/credential with the local receiver.
The task explicitly sets `apocbr.telemetry.endpoint` to
`https://plataforma.apocalipse.cloud/api/v1/telemetry`, overriding the local
profile's localhost default. The shared bridge server key remains the telemetry
credential fallback. Edit the telemetry endpoint/API-key settings in this task
when changing its configured telemetry service.
Recognized PostgreSQL, telemetry and restart environment overrides retain their
normal runtime precedence. The legacy `APOCBR_BRIDGE_API_KEY` is carried from the
template; active telemetry uses the declared telemetry key or bridge server-key
property, not that legacy environment name.

Edit database/API settings in this global task, following the run-task template.
The JDBC URL belongs in `options.env.APOCBR_POSTGRES_URL`, not a command-line
`-Dapocbr.postgres.url=...` argument. A captured failing Windows run received only
the URL through `?user=...`; its `&password=...` suffix was lost on the shell/Maven
command path despite the task's strong quoting. Passing the entire URL as an
environment value avoids that boundary and uses the agent's existing environment
override. The configured task's complete value was subsequently verified in a
real child JVM by fingerprint comparison without opening a connection or printing
credentials. Other ordinary `-Dapocbr.*` settings still pass through the task.
There is no `.integration-test.properties` loader. Credentials stay in the local
task configuration and are not copied into repository documentation. Task values
are a snapshot of the template, so update the integration task too when changing
the run task. Legacy PostgreSQL flags copied from that template do not activate
ports that the current agent has not implemented.

This opt-in task uses its configured database and APIs. Account bootstrap can
create/update `integration-admin` and server startup can make other account/role
queries or writes; it is not a read-only connection check. Supply the intended
test database with the real schema and grants before executing it. Its connection
configuration was wired and tested with synthetic settings; no connected-service
run was performed while adding the task. Integration runs with no opt-in keep the
original isolated defaults. Both modes retain a unique world/cache and force
PostgreSQL journal state into that run's artifact directory. Restart notification
remains off unless explicitly enabled in the supplied task settings.

`mvn test` and ordinary `mvn package` never run this suite. Use `verify` with the
profile so the shaded artifact exists before Failsafe executes. An explicit
integration run fails if its JAR/resources are absent; it does not report a
misleading success through skipped tests.
The profile forces a fresh unshaded project JAR before shading, so repeated
`verify` runs do not feed the previous shaded artifact back into the shader.

For colored interactive output, use `-Dpz.it.color=true` if your environment sets
`NO_COLOR`. Maven batch mode or output redirection may strip ANSI sequences even
when the test emits them; use an ANSI-capable interactive terminal, omit `-B`,
and, if necessary, set Maven's `-Dstyle.color=always`. PASS/FAIL/SKIP text remains
readable when colors are removed. The raw `console.log` is unaffected.

## Shared lifecycle

1. `ServerIntegrationIT` starts `ServerTestContext` once in `@BeforeAll`.
2. The context creates a unique directory under `target/server-integration/`,
   writes an isolated server configuration, allocates test ports and starts a
   loopback HTTP receiver. Test TCP/UDP ports are checked before launch; as with
   most external-process fixtures there is a brief allocation-to-bind race.
3. It packages only the test observer classes into `hook-probe.jar`, then launches
   the original game JAR with the production agent first and observer agent second.
   The child classpath contains only the selected game JAR and probe JAR. The JVM
   makes agent classes available through the agent mechanism. Unit-test doubles
   and JUnit classes are absent from the child.
4. The probe reads the same `AgentModules` registry used by the production agent,
   writes the initialized module/hook inventory, loads enabled checks marked
   `loadBeforeStartup` without initialization, then invokes real `GameServer.main`.
   Currently Workshop `ItemQueryJava` is the only explicitly loaded lazy target.
5. The context waits for the vanilla `*** SERVER STARTED ****` message and checks
   child liveness and transformation/probe errors. This is a startup boundary,
   not a guarantee that a player has joined or every subsystem is ready.
6. `@TestFactory` generates a separate named dynamic test for every declared
   `HookCheck`, and discovers registered `ServerAssertion` providers for additional
   scenarios. They run sequentially against the same server. Disabled module
   checks are explicitly skipped with the configuration reason. Failures do not
   intentionally abort later assertions. Each result prints a colored status,
   name, and (for passes) elapsed time, followed by a suite summary.
7. `@AfterAll` sends `quit` through the child's console, waits for normal exit,
   and fails on a shutdown timeout or nonzero exit. Startup failure also invokes
   cleanup. A finally block terminates a remaining child and closes the receiver.

By default the fixture uses a fresh `agent-integration` server/world, `Public=false`,
`UPnP=false`, no mods/Workshop downloads, no RCON, Steam disabled, PostgreSQL
disabled, and automatic restart disabled. Agent HTTP endpoints are overridden
to loopback. Inherited `APOCBR_*`, `PZMANAGER_*`, Java option injection variables,
and `CLASSPATH` are removed from the child environment. With the explicit
configured-services opt-in, only recognized service environment overrides are
restored; Java option injection remains removed. The game installation is
used for runtime resources; ordinary game save/config output is redirected via
`-cachedir`. The fixture does not deploy patches or alter the original archive.

## Automatically discovered hook checks

`AgentModules.all()` is the single production module registry. `AgentModule`
requires a `hookChecks()` declaration; enabled modules use the default null
`disabledReason()`, and conditional modules report their actual configuration
state. This metadata is read from the already initialized module instances in
the child JVM, not separately instantiated or inferred from test configuration.
The declarations describe installation contracts only; they do not add runtime
control APIs or change instrumentation/lifecycle ordering.

Every new declaration automatically produces a separate test on the next
integration run. There is no second test-side target list or per-hook test file
to maintain. The probe rejects duplicate module/hook identifiers or empty module
check lists. The lightweight unit checks validate this inventory without starting
a server. Declare additional contracts when adding hooks to an existing module.

Generated tests are labeled `Startup hook`. Missing transformed-class evidence
is reported as SKIP with `Not observed at startup; hook installation unverified`.
This avoids failing startup checks for targets that load later; it does not prove
that an absent target is lazy or correctly instrumented. Observed targets still
fail for an incorrect original-JAR source or missing declared helper invocation.
Probe, transformation and server-health errors remain failures. Scenario-specific
`assertHook` calls remain strict and require evidence. This startup inventory does
not establish complete hook coverage; unobserved hooks need a later trigger or
dedicated validation.

The player/payload port also has a separate packaged-agent probe that deliberately
loads its original-JAR targets without initialization and verifies declared helper
invocations and class origins. That probe is not a server startup or gameplay
scenario; it does not add forced player-class loading to this shared fixture.
See the [player validation record](../features/postgresql-players.md#validation-and-remaining-acceptance).

Each generated test checks post-transformation bytecode observed inside the child
JVM. It verifies the target's code source is exactly the configured original JAR
and finds the declared method-to-helper invocation. Currently enabled checks are:

| Game method | Agent helper |
| --- | --- |
| `GameServer.main` | `TelemetryServerLoopMetrics.beginIteration`, `recordVanillaTick`, `finishVanillaIteration` |
| `Event.trigger` | `TelemetryRegistry.recordLuaEvent` |
| `ItemQueryJava.inform` | `ModUpdateRestartNotifier.onUpdateDetected` |

The lag diagnostics modules also declare checks for the original Workshop
entry point, Steam pump, shutdown thread, event context, Kahlua VM call and
server phase methods. Checks inspect classes observed during normal startup.
The suite does not force these gameplay classes to initialize or enable Steam
to trigger a Workshop query. Focused fixtures cover the query continuation and
Lua attribution paths.

This is stronger than matching a module-registration log: transformation can
fail or match no method after registration. The observer does not transform
bytes itself. It inspects loaded `zombie.*` classes after the production transformer
and records those containing calls into the agent, before JVM class definition.
`HookInstallationAssertion` also checks module-registration messages and rejects
observed transformed target classes with no active declared check. Server
readiness supplies the additional actual-startup check.

This is not automatic inference of intended behavior. An undeclared change inside
an already covered class can escape the target-coverage guard; the hook author
must add the corresponding `HookCheck`. Hooks that do not call agent helpers need
another meaningful assertion strategy. The current PostgreSQL declarations check
primary bootstrap/lifecycle/shutdown entry points, not every account operation;
they are skipped in the default fixture because PostgreSQL is disabled.

This assertion does not trigger Workshop updates or verify its HTTP behavior.
It does not establish PostgreSQL hook installation (disabled here), client
login/logoff, profile loading, persistence, mod downloads, or shutdown data
durability. Telemetry requests are captured if the server runs long enough for
the normal 30-second reporter interval, but delivery is not an initial assertion.

## Adding assertions in a dedicated integration-testing session

Create a public no-argument class in the `integration/assertions` package:

```java
public final class TelemetryDeliveryAssertion implements ServerAssertion {
    public String name() { return "Telemetry emitted by the running server"; }

    public void verify(ServerTestContext server) throws Exception {
        server.await("telemetry delivery", Duration.ofSeconds(60),
            () -> !server.telemetrySnapshots().isEmpty());
        // Parse the captured JSON and assert meaningful scenario-specific values.
    }
}
```

Add its fully qualified class name on its own line in:

```text
src/test/resources/META-INF/services/cloud.apocalipse.pz.agent.integration.ServerAssertion
```

Use a unique descriptive name. Providers are sorted by name for stable reports,
but assertions must not rely on that order or another assertion's success.
Do not start a server in the provider constructor or `verify`. Shared fixture
configuration belongs in `ServerTestContext` before launch; provider constructors
should have no side effects. All providers are instantiated before dynamic tests
are produced. A broken provider fails discovery.

Use bounded `server.await` calls instead of arbitrary long sleeps. Assert
observable results: SQL rows written by the actual hooked game path, a known
profile's applied state, local HTTP payloads after real activity, or a connected
client's outcome. Restore any temporary shared state and do not stop the server.
Dynamic tests do not each receive a fresh JUnit lifecycle/environment.

For a new installation contract, add a `HookCheck` to its production module:

```java
new HookCheck("unique-hook-id", "zombie.package.Target", "gameMethod",
    "cloud.apocalipse.pz.agent.features.feature.Helper", "agentMethod", false)
```

The last flag requests an explicit non-initializing class load before startup;
leave it false for naturally loaded targets. A new module also belongs in
`AgentModules.all()`. Do not add test-side entries for ordinary declared hook
checks. If a target loads only after gameplay, add an appropriate scenario/trigger
and installation assertion for that boundary rather than pretending startup
loads it naturally. Generated checks currently execute at startup, before custom
scenario providers; use `loadBeforeStartup` only when that non-initializing load
is safe. Document any deliberate load/trigger. Test probes remain in test sources
and never enter the production shaded artifact. Feature semantics still require
separate registered `ServerAssertion` providers.

Future PostgreSQL scenarios must provision an isolated real database and actual
schema before startup, with distinct migration/engine roles and grants. Future
full login scenarios need a real client or validated protocol driver; direct
authentication calls cover only the invoked path. Steam/Workshop discovery
needs a deliberate Steam-enabled fixture. Do not silently enable these external
dependencies for all integration runs or add production backdoors to observe them.

## Evidence and troubleshooting

Each `target/server-integration/run-*` directory retains:

- `runtime.properties`: selected runtime paths, Java executable, JAR and agent
  SHA-256, allocated ports.
- `pid.txt`, `console.log`, and the isolated `cache/` world/config/log files.
- `hook-probe.jar` and `evidence/*.properties`: observed method/helper invocations
  and original class code sources; `evidence/inventory.properties` records module
  state and hook contracts; probe failures produce `probe-error.txt`.
- `telemetry-*.json` when snapshots arrive at the local receiver.

JUnit results are under `target/failsafe-reports/`. Keep the console/artifact
path when reporting failures. Missing evidence is reported as unverified/SKIP by
startup inventory checks and fails strict scenario checks. It means the target did not load,
did not receive the expected transformation, or observation failed; diagnose
the logs rather than weakening the check. Readiness failure may instead mean a
native dependency/resource mismatch, invalid game configuration, or occupied
port. Preserve failed artifacts before `mvn clean` removes them.

The `console.log` path printed by a startup assertion points to retained output;
it is not itself a logging failure. For example, PostgreSQL account bootstrap
can fail before any hook assertions run. Connection-acquisition failures now
include a sanitized SQLState and stage without driver messages/credentials;
runtime initialization failures identify that stage separately. A startup failure
reports that assertions did not run, and cleanup does not add a duplicate
nonzero-exit assertion to the original startup failure. A later vanilla shutdown
exception after partial initialization should be distinguished from the first
startup exception. Failsafe's failure report remains necessary for Maven to
report a failed test even though the same child output is streamed to the console.

Record the tested OS, exact game build and hashes with results. A Windows pass
does not establish Linux acceptance. A manually killed Maven/test runner may
prevent JUnit teardown; identify any remaining child using that run's PID and
command line, never terminate unrelated game servers.

Initial Windows execution evidence is recorded in the
[2026-11-21 validation report](../reports/2026-11-21-server-integration.md).
