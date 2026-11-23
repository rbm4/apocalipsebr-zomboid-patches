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

## Playerless streaming measurements

Set `-Dpz.it.streaming=true` to run the streaming assertion on the same
isolated child as the startup checks. The fixture uses a fixed world-generation
seed and four adjacent 64×64-tile server cells in Muldraugh. It requires an empty
server. Each of six cycles requests those cells, renews relevance for three
fully loaded simulation frames, withdraws the requests, and waits until the
cells have detached and the unload saves have drained. The first cycle records first-generation
work separately. The remaining five reload the saved area and contribute
to the reported median.

The `players30` profile requests the native relevance footprint of 30
synthetic positions in five clusters of six. Every renewal invokes
`ServerMap.characterIn(int,int,int)` with a 13-chunk grid width. The
positions are inside their overlap regions and request 45 distinct server
cells, with up to 2,880 chunks and 270 relevance calls per renewal. The five
clusters and their coordinates remain fixed between runs. The test still
uses an empty server, so this measures streaming volume and overlapping
relevance requests. Player LOS, player-driven spawning, packet downloads
and client state are outside the scenario.

```powershell
mvn verify -Pserver-integration "-Dpz.it.streaming=true" "-Dpz.it.streamingProfile=players30" "-Dpz.it.heap=8g"
```

Run both sides of a comparison with the same profile and heap. The workload
ID, expected cell count, synthetic position count and heap are recorded in
`runtime.properties`. The existing four-cell fixture remains the default.
The test-only agent advises the original `ServerMap.preupdate()` and
`postupdate()` boundaries. After preupdate clears frame relevance, the driver
calls the same native `loadOrKeepRelevent(int,int)` method as the Lua streaming
bridge. It leaves disk load, worker recalc, cell integration, unload, chunk
save and release to the engine. It checks that every requested cell exposes
chunks, stays loaded during renewal, disappears after withdrawal, and that the
native integration, detach, save enqueue, worker save and release counts agree.
The request is a file in the run directory. The control code and its advice
live in the probe JAR and run only when explicitly enabled.

Use matched runs to evaluate a single optimization. The following example uses
the existing LOS worker implementation because cell integration and unload call
the LOS suspend/resume barrier:

```powershell
$env:JAVA_HOME = (Resolve-Path ./jdk).Path
mvn verify -Pserver-integration "-Dpz.it.streaming=true" "-Dapocbr.optimization.serverLos.enabled=false"
# Use the baseline directory printed by the first run.
$streamingBaseline = "target/server-integration/run-..."
mvn verify -Pserver-integration "-Dpz.it.streaming=true" "-Dapocbr.optimization.serverLos.enabled=true" "-Dpz.it.streamingBaseline=$streamingBaseline"
```

The isolated streaming fixture forwards explicit boolean
`apocbr.optimization.<feature>.enabled` switches and records them in
`runtime.properties`. External service settings stay isolated. The comparison
checks the original game hash, seed, fixture ID, phase coverage and measured
chunk workload. The agent hash is recorded for each run so the same procedure
can also compare two builds. Change one optimization at a time and use identical
game files, JVM settings and host conditions. Repeat runs in alternating order
before attributing modest deltas to a patch.

`streaming.csv` contains cycle numbers, warmup markers, work counts and phase
calls, total nanoseconds and maximum call duration. The test prints the median
cycle total and maximum individual call duration, with baseline deltas when
requested. Phase timers are inclusive and can overlap. Map pre/post timers cover
all calls while a cycle is active, so their totals also respond to how many
frames a load or save takes. The three wall-time rows end at the server loop's
first observation of loaded, detached and save-drained state. They include
frame scheduling and wait time. The two cell handoff rows include queue wait,
background loading or recalculation, and the next server-thread handoff.
The loader measures native `LoadChunk` on the load worker, with separate
`LoadBrandNew`, `LoadFromDisk` and pending-buffer timings. `chunk.safeRead`
contains the `chunk.io.*` phases for lock lookup and read-lock acquisition,
path, open, size, buffer capacity, byte read, close and lock cleanup. The
file-open CPU sample is recorded where the JVM supports it and overlaps the
file-open wall sample. `chunk.writeWorker` measures native save-worker write
attempts and can be zero when every chunk is unchanged. The
`streaming-read-sizes.csv` file records total and percentile payload sizes
from the actual native reads. The probe requires
generation in the first cycle, matches the attempted load path to each loaded
chunk by count, and fails if a later cycle generates chunks or uses pending
save data after the prior drain. The save worker row separately measures
worker execution. First load refers to fresh game state and does not flush
the operating system's file cache. These timings are diagnostic, with correctness assertions as the
regression gate. The empty fixture covers native streaming without player
relevance, client downloads or live player LOS demand.
The initial Windows runs used Java 25.0.4 and original 42.22.2 JAR SHA-256
`3C4A7C5F149D784758C6FB5F8FA85F3A21D572E38BC081400E89035F8120D287`.
Both LOS settings passed all six cycles with 256 chunks per cycle and exited
cleanly. With LOS disabled, the median cycle took 2.52 s from request to
loaded, 86 ms in cell integration, 51 ms from release to detachment, and
204 ms from release to save drain. Time from the load queue to the recalc
queue was the largest measured load handoff. The LOS barrier was below
0.2 ms per cycle in both runs. The comparison exercises the barrier during
streaming with no player LOS jobs.
The first verified `players30` run loaded 2,880 chunks in all six
cycles. The generation cycle took 16.87 s from request to loaded and made
2,880 native generation calls. Each subsequent cycle read 2,880 saves and
made zero generation calls. Warm request-to-loaded median was 19.92 s.
The inclusive native saved-chunk method accounted for 19.38 s per warm cycle.
That method includes both file access and decode. Warm cell integration and
unload medians were 832 ms and 396 ms per cycle. The production slow-tick
telemetry attributed 1.14 s in one tick to cell integration and 513 ms in
another to cell unload during the simultaneous release. Use the raw phase
and telemetry files to distinguish load latency from work done in a single
simulation tick.
A matched `players30` run with
`-Dapocbr.optimization.entityRemoval.enabled=false` also passed all cycles.
Median cell unload rose from 396 ms with the index enabled to 515 ms with it
disabled. Chunk detachment moved from 381 ms to 501 ms. The initial run
recorded 16,206 indexed removals in one telemetry window. This is a scoped
unload comparison from one pair of runs. Saved-chunk read/decode time also
varied between runs, so total load-time differences lack isolated attribution.
Repeat in reverse order to check the unload effect.
## Vehicle pressure and seeded cell turnover

The `vehicles72` fixture initially loads 72 cells from a fixed Muldraugh
pool. Three warm cycles each select 54 previously generated cells using
`pz.it.vehicleSeed` (default `42220002`). The seed controls the request
and withdrawal order. Each cycle holds the complete selected area for 100
simulation frames, then withdraws relevance from nine cells every ten frames
while renewing the others. The final batch drains before the next cycle.

```powershell
mvn verify -Pserver-integration "-Dpz.it.streaming=true" "-Dpz.it.streamingProfile=vehicles72" "-Dpz.it.heap=8g"
```

The isolated world uses `EnableVehicles=true` and `CarSpawnRate=5`.
The probe also enables the native test-only spawn override while this
scenario is active, so the fixture does not depend on the default spawn
probability. The run verifies at least 15 distinct native vehicles actually
update per cycle and checks the warm vehicle queue, worker SQLite load,
decode and owner-thread application. It also verifies normal chunk
integration, file reload without regeneration, unload and save drain.
`vehicle-pressure.csv` records the actual number of distinct vehicles updated,
held frames and staged withdrawal frames. `streaming.csv` records timed
vehicle stages and their invocation counts. The profile has no clients and
does not inject a synthetic callback delay.

Use the [42.22.2 vehicle audit](../reports/2026-11-22-vehicle-simulation-pressure.md)
for thread ownership, legacy optimization boundaries and the connected
packet and zombie scenarios.

### Chunk integration and detachment profiling

Add `-Dpz.it.chunkLifecycle=true` to the streaming command to time original
call sites inside `IsoChunk.doLoadGridsquare` and `removeFromWorld`. The
`chunk.lifecycle.*` rows contain owner-thread call counts and elapsed time,
including object callbacks, erosion and overlays, population, pathfinding,
and adjacency. Worker execution and file operations keep their own rows.

```powershell
mvn verify -Pserver-integration "-Dpz.it.streaming=true" "-Dpz.it.streamingProfile=vehicles72" "-Dpz.it.chunkLifecycleComparison=true" "-Dpz.it.heap=8g"
```

The comparison enables profiling and repeats the same 54-cell warm
footprint in four cycles. Its observer assigns chunks by coordinate parity
and reverses the assignment between cycles so both arms handle the same
locations. The vanilla arm uses the original operation while the optimized
arm executes the installed production hook. The test checks equal native
chunk work, comparable operation counts, less work, and lower normalized
wall time at the two changed call sites. The fixture also verifies retained
interior links and cleared cross-chunk reverse links, native container
fallback, saved reload, unload and save drain.
Results are in `chunk-lifecycle-comparison.csv` and `streaming.csv`.
See the [chunk lifecycle report](../reports/2026-11-22-chunk-lifecycle.md).

### Experimental world simulation

```powershell
mvn verify -Pserver-integration "-Dpz.it.streaming=true" "-Dpz.it.streamingProfile=vehicles72" "-Dpz.it.experimentalWorldSimulation=true" "-Dpz.it.heap=8g"
```

This opt-in scenario packages the experimental classes only into the test
observer JAR. It uses the original 42.22.2 vehicle update and part-update
entry points and forces a parts pass for matching engine doors in both arms.
The worker writes the existing part's elapsed-time field. The field is volatile
in this test JVM, including its direct vanilla save/load accesses. The worker
updates it only if it still matches the value at submission. One pending
result per part uses a volatile completion flag so the server can skip busy
work and consume a completion once without waiting during a scheduled pass.
The server can see the previous completed clock until the worker writes it.
A due result resets the vehicle's brake accumulator when the server consumes
it, which can be a later parts pass. The fixture drains results between
timing windows and before releasing cells.

The Java replacement covers `Vehicles.Update.EngineDoor`, whose vanilla body
is empty. A mod override of that name is bypassed during the experiment for
the matching EngineDoor part. Spawn callbacks and other update hooks are
outside this experiment. The worker executes no Lua, Bullet, or whole-vehicle
simulation. A held-job case verifies that a later server frame sees the part
busy, the worker publishes the native timestamp, and the server consumes it
once. There is no connected-client or packet parity assertion.

`experimental-world-simulation.csv` reports alternating inline and worker
windows within each warm cycle, with scheduler median and p95 time, the p95
span between the fixture's server-map preupdate and postupdate hooks, offers,
admissions, completions, busy observations, and the separate end-of-window
drain count and time. The scheduler and span timings include dispatch
and measurement overhead. They do not cover the entire server tick or measure
a full vehicle update offload. The assertion checks that both arms execute,
without requiring the worker to be faster.

### Parked vehicle cadence comparison

```powershell
mvn verify -Pserver-integration "-Dpz.it.streaming=true" "-Dpz.it.streamingProfile=vehicles72" "-Dpz.it.parkedVehicleComparison=true" "-Dpz.it.heap=8g"
```

After 35 held frames in each cycle, the test observer alternates between the
production selector and the original selector result in four 16-frame
windows. It reverses the order on odd cycles. Both arms run in the same child
JVM against the same loaded world. The test requires equal vehicle-frame
opportunities, checks the actual scheduled callbacks against the original
bucket's frame and object ID, and verifies that motion and pending part work
restore full cadence before restoring the vehicle state. Over the three warm
pairs, the median on/off ratio must be below 1.0 for both vehicle callback
wall time and total scheduler wall time including setup. The test reports
setup and update plus postupdate separately and verifies the cadence counters
in telemetry from the child.
`vehicle-cadence.csv` retains all four windows for each cycle. The statistical
gate is opt-in and represents the controlled parked workload.

To start the server without the optimization installed, omit
`pz.it.parkedVehicleComparison` and pass
`-Dapocbr.optimization.parkedVehicle.enabled=false`. The paired observer is
test-only and requires the production hook to have been installed.

## Moving-object scheduler scenario

The moving-object test uses the 45-cell fixture for one full load and unload.
It holds the loaded area across a telemetry window boundary and delays
one native `BaseVehicle.update()` on the scheduler thread by 750 ms. The vehicle callback still runs. The
fixture verifies normal unload and save drain and looks for the spike in the
actual telemetry received from the child. It checks that `update.vehicle`
records the spike in the window and in that window's slowest tick, and that
the other three vehicle callbacks also report calls. The normal warm-cycle
streaming benchmark remains separate. The test-only delay is available only in the isolated test JVM.

```powershell
mvn verify -Pserver-integration "-Dpz.it.streaming=true" "-Dpz.it.streamingProfile=players30" "-Dpz.it.movingObjects=true" "-Dpz.it.heap=8g"
```

The scenario forwards `-Dapocbr.telemetry.movingObjects.enabled=true` to the
child. Enable this independently on a dedicated server for detailed
attribution. The callback timers are reported as
`simulation.movingObjects.callbacks.<stage>.<kind>`, where the stages are
`preupdate`, `frameStep`, `update` and `postupdate`. Kinds are player,
vehicle, animal, zombie and other. Animals are classified before players
because they extend the player type. At the scheduler level,
`simulation.movingObjects.cpu.update` and `.cpu.postupdate` measure thread
CPU where available. The `startFrame` wall timer measures the preceding
bucket setup independently.

The callback rows report inclusive wall time and overlap the parent scheduler
phase and any nested Lua. CPU time measures the scheduler owner, and short
operations can fall below the JVM clock resolution. These hooks measure
server-loop execution, so postupdate on an optional animation worker is
outside their scope. The fixture exercises a real vehicle without players
and validates attribution for a controlled stall. It does not reproduce
the production mod set or establish the production stall's cause.

On Windows with the original 42.22.2 JAR, the fixture reported a
758.2 ms slowest tick, 755.4 ms in the scheduler update, and 753.9 ms in
`callbacks.update.vehicle` on that same tick. The window reported 293 CPU
samples for 293 scheduler updates. The injected callback returned normally,
and the fixture completed unload, save drain and graceful shutdown.

## Chunk storage replay

After a completed playerless streaming run, the Windows
[replay script](../../tools/Test-ChunkStorageReplay.ps1) can compare the
retained chunk files with a fresh, loopback-only PostgreSQL instance. Supply
an existing PostgreSQL binaries directory. The script initializes a new
data directory under `target/chunk-storage-replay/`, loads serialized chunk
bytes as `bytea` with a migration role, and reads them with a separate
SELECT-only role. It stops that cluster on completion.

```powershell
./tools/Test-ChunkStorageReplay.ps1 -StreamingRun 'target/server-integration/run-...' -PostgresBin 'Z:/postgresql/bin'
```

The four modes reuse the same chunk payloads and check every returned byte.
The vanilla-shaped file baseline uses one open, length lookup and read per
chunk. The second file mode uses a read-only `FileChannel`. SQL uses
one reused loopback connection with either single-row or 64-row requests,
following `pg_prewarm` of the heap, index and TOAST relations. Each mode runs
one discarded warmup and five measured rounds. The replay prints total access
time and the median of round-level p95 request durations. Those request
percentiles refer to a file, a single SQL row, or a 64-row request,
respectively. The test excludes connection acquisition, persistence writes,
native decoding and simulation. The real-server comparison remains the
acceptance boundary for any storage change.

### Measured results and implementation boundary

The 45-cell Windows run exercised 2,880 native saved reads on each of five
reloads. Native `SafeRead` spent 22.00 s per median warm cycle. Of that,
the file-open phase took 21.34 s, byte reads 480 ms, length queries 86 ms,
and acquiring the read lock 1.4 ms. The final cycle carried 9.34 MB of
serialized bytes in total, with a 2,677 B median chunk, 7,440 B at p95,
and a 20,103 B maximum for that cycle. These values describe the fixture's
world state and its own serialized payloads.

The CPU-instrumented repeat measured 21.83 s of file-open wall time and
1.41 s of loader CPU time per median warm cycle. The metrics overlap and
the Windows thread CPU clock increments at a coarser granularity. The
difference is consistent with off-CPU delay or scheduling, without
identifying a particular filesystem filter or cause. In the same repeat,
four warm cycles rewrote all 2,880 files and the other rewrote 2,869.
The detached replay therefore has different recent-write conditions from
the live reload.

The replay checked 2,880 files totaling 9.36 MB from the completed run. On
local PostgreSQL 17.6 with 256 MB of shared buffers, the median for the full
set was 97 ms with batches of 64 and 342 ms with one query per chunk. The
resident file baselines were 154 ms for the vanilla-shaped calls and 153 ms
for `FileChannel`. The detached file replay preloads the fixture's expected
bytes and runs after the game exits. Its file timings cannot establish the
cause of the much longer file opens in the live loader. The timings also
exclude native decoding and world integration, so the SQL delta is a storage
comparison on this host.

A prospective in-server comparison can use the native
`IsoChunk.LoadChunk(wx, wy, ByteBuffer)` buffer input. Fetch detached bytes
in bounded batches at cell admission, then let the native loader own decode
and handoff. Bound both pending data bytes and in-flight requests. Preserve
the current pending-save buffer's precedence, verify world/coordinate and
revision before consumption, and expire a result when its cell is cancelled
or an intervening write supersedes it. An isolated read-through experiment can
fall back to the current file path on misses and errors while retaining local
saves as authority. Database authority would also require durable revisions,
write ordering, transitional recovery, and tests for shutdown and restart.

Compare throughput and tails with concurrent writes and a working set that
exceeds cache. Prewarming is advisory and cached blocks can be evicted.
Virtual threads are an option for waiting on JDBC, while query count, pool
limits and round-trip time still govern the fetch. Keep decoding on the
native owner. [PostgreSQL pg_prewarm](https://www.postgresql.org/docs/17/pgprewarm.html) · [JDK 25 virtual threads](https://docs.oracle.com/en/java/javase/25/core/virtual-threads.html)

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
- `streaming.csv` and a completion or error marker for opt-in streaming runs.

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
