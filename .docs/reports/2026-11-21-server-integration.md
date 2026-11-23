# Initial real-server integration validation — 2026-11-21

Implemented the opt-in `server-integration` Maven profile and a shared JUnit
dynamic-test suite. Separate `ServerAssertion` providers register through test
ServiceLoader resources. The initial provider verifies five injected calls in
three original game classes using a test-only observer agent in the child JVM.
See the [run and extension guide](../validation/server-integration.md).

## Execution

Windows, repository JDK 25.0.4, original game build **42.22.2 / 3d39148846**.
Selected JAR:
`Z:\SteamLibrary\steamapps\common\ProjectZomboid\projectzomboid.jar`.
SHA-256: `3c4a7c5f149d784758c6fb5f8fa85f3a21d572e38bc081400e89035f8120d287`.

```text
mvn -B -ntp verify -Pserver-integration -Dpz.game.jar=<original JAR above>
```

- Unit phase: 50 tests, 49 passed, 1 skipped, no failures/errors. The remaining
  skip is the historical patched-runtime telemetry check requiring
  `pz.game.install`; it is outside this original-JAR suite.
- Integration phase: 1 named dynamic test, passed, no skips; about 38 seconds
  including startup and shutdown. One actual server process for that suite run.
- Verified `GameServer.main` loop hooks, `Event.trigger` timing hook, and
  `ItemQueryJava.inform` Workshop hook, with each target sourced from the selected
  original JAR. The lazy Workshop class was explicitly loaded without initialization.
- Vanilla readiness was reached, `quit` was processed, saving/shutdown completed,
  and the child exited with status 0.
- A preliminary run also passed. It incidentally captured a real telemetry
  payload and exposed the fixture's incorrect HTTP 200 acknowledgment; the
  receiver was corrected to the production contract's HTTP 201. The final run
  ended before the first 30-second telemetry delivery interval; delivery is not
  an assertion in this initial suite.
- Repeated packaging exposed reuse of the already shaded project JAR; the profile
  now forces fresh JAR creation. A subsequent profile-enabled `package` check
  passed with tests skipped after the successful test run. No additional server
  was started by that packaging check.

Final integration artifacts are retained locally (ignored) at
`target/server-integration/run-7988671095057199386/`; Failsafe reports are under
`target/failsafe-reports/`. That run recorded agent SHA-256
`1e941c72cc93e79232fc098f2c0463f4dce0de8621c5ac09b27673eae946b79b`.
Later packaging produces its own artifact hash; use the per-run manifest for
runtime provenance.

## Limits

No Linux execution, client login/logoff, player-profile load, PostgreSQL access,
Steam query/download or actual restart request was tested. Startup emitted
game map/asset warnings, including missing mannequin zone properties and a
duplicate room meta ID; these were not treated as instrumentation failures.
The initial test checks installation and readiness, not the absence of every
game warning or complete feature acceptance. No production save or database
was used, and no game archive or legacy override was deployed.

## Follow-up: discovered hook contracts and console feedback

The module registry now lives in `AgentModules.all()`, shared by installation
and the test observer. Every module declares `HookCheck` contracts. The child
exports those initialized declarations and disabled reasons; the suite generates
one named test per declaration without a second test target list. The observer
also detects transformed `zombie.*` target classes missing an active declaration.
This guard does not infer omitted hooks inside an already covered class, and
the declarations remain installation checks rather than feature behavior tests.

Server output now streams with `[PZ]` prefixes and is simultaneously retained in
the raw log. Assertions print RUN, green PASS, red FAIL, yellow SKIP, elapsed time
and totals. Startup and shutdown also report outcomes. Test-only settings control
streaming and colors; an explicit color setting overrides `NO_COLOR`.

Final follow-up validation used the original JAR above with
`mvn -B -ntp verify -Pserver-integration -Dpz.game.jar=<original JAR> -Dpz.it.color=true`:

- Unit phase: 52 tests, 51 passed, 1 historical patched-runtime check skipped.
- Integration phase: 9 dynamic tests, **6 passed, 3 explicitly skipped**, no
  failures/errors, one server startup and normal shutdown. Skips are the three
  declared PostgreSQL entry-point checks, disabled by the fixture configuration.
- Runtime artifacts: `target/server-integration/run-7290882621763098693/`.
  Agent SHA-256: `706ef243241b65fdfd2aef6013e8d7fcad46d9b1cd4ea16c7b027a2bc9d6aa1c`.
- Failsafe XML confirms emitted ANSI sequences. Maven batch/redirection stripped
  them from the outer captured log; interactive terminals can preserve colors.
- Unit coverage also checks inventory identities and color/default override
  handling. The existing PostgreSQL registration unit test now uses the shared
  registry instead of reflection into the old private field.

The previous feature and platform limits still apply. PostgreSQL declarations
currently cover primary entry points, not every account advice/call path.

## Follow-up: global configured-services task

Added **Integration test ApocBR agent - configured services** to the user's
global VS Code `tasks.json` as the default test task. Its PostgreSQL connection,
feature flags, profile, shared API key and API environment credential were copied
from the existing dedicated-server template and compared without printing values.
Account instrumentation is explicitly enabled. Existing tasks were preserved.

The test launcher now supports explicit `pz.it.useTaskConfiguration=true`:
runner `apocbr.*` settings and recognized service environment overrides reach
the child JVM. Plain runs preserve isolated defaults. Journal state remains
run-owned. Two focused unit tests cover forwarding/default isolation and service
environment filtering; the full isolated `verify -Pserver-integration` run also
passed with normal server shutdown. No configured database/API run was performed
to validate this task; its external settings are intentionally not runtime-verified.
See the [task configuration guide](../validation/server-integration.md#vs-code-configured-services-task).

The configured-services task subsequently received an explicit telemetry endpoint
override for `https://plataforma.apocalipse.cloud/api/v1/telemetry`. The production
default transport unit test now selects/restores its own profile, and a regression
test covers the explicit endpoint overriding the local profile. The unit suite
passed with the same local-profile and endpoint flags as the task; this did not
start a server or contact the configured remote database/telemetry service.

## Follow-up: PostgreSQL bootstrap diagnostics

A user-supplied connected-services log showed PostgreSQL bootstrap unavailability
before readiness, followed by a vanilla shutdown exception on the partly initialized
world. The retained-log path was diagnostic output, not the cause. The original
bootstrap reused login's denial-on-unavailability helper, discarding the failure
reason. Bootstrap now acquires its own bounded workflow and reports a sanitized
SQLState/stage for acquisition failure, or a separate runtime initialization stage.
Login denial behavior and the refusal to fall back to SQLite remain unchanged.
Raw driver messages/causes, credentials and row details remain excluded.

Two fake-database regression cases cover SQL/runtime acquisition failures,
credential redaction and successful retry after workflow cleanup. The focused
20-test database run passed. Failed-startup cleanup no longer adds a duplicate
nonzero-exit assertion, and the suite reports assertions as not run instead of
printing a zero-pass/zero-failure summary. Logs and Failsafe reports are retained.
No attempt was made to connect to the user's configured database to diagnose
the actual connection failure; the original log did not contain its SQLState.

## Follow-up: cold-pool acquisition budget

Initial JSON inspection found one strongly quoted JDBC property and an explicit
configuration-forwarding flag. No PostgreSQL environment override was present in
the inspection shell. That JSON inspection alone was insufficient to establish
what survived the Windows command boundary; the captured Failsafe report below
subsequently demonstrated actual truncation.

An implementation bug was found: `ForegroundDatabase.acquisitionMillis()` always
capped acquisition at 250 ms, including login/bootstrap workflows configured for
5000 ms total and 3000 ms network operations. Hikari starts physical connections
asynchronously, so a cold pool could time out before its first connection or
driver error became available. The limit now uses the owning workflow's network
budget and remaining total deadline. Ordinary 500/250 ms foreground workflows
retain their original cap; nested scopes cannot expand the parent's budget.

A real-Hikari regression test holds first physical connection creation past
400 ms and then releases it, verifying successful acquisition and lease return.
Additional cases verify configured/default budget clamps and bounded, cycle-safe
SQLState extraction through cause/next-exception wrappers without exposing raw
messages or retaining causes. Enabled account startup prints effective limits
and pool size for configuration diagnosis.

Focused database tests (23) and the complete unit suite plus shaded packaging
passed with the original JAR and local profile. No external database connection
or configured-services startup was performed. This corrects a demonstrated
local cold-pool failure mechanism; whether it resolves the user's particular
remote connection remains unverified.

The captured failing Failsafe JVM's JDBC property was then compared in memory to
the local task value: host, database and user matched, but the entire password
query parameter was absent (without exposing the values). This confirmed a real
task-to-Maven argument-loss problem in addition to the acquisition-budget defect.
The task now stores the full URL in `options.env.APOCBR_POSTGRES_URL` and omits the
JDBC `-D` argument; its other settings and unrelated tasks are preserved.

`IntegrationConfigurationSmokeMain` verifies a SHA-256 match for the complete
environment value in a parent and a real child JVM using the actual forwarding
code. It loads no game classes and opens no connection. The task's actual
configuration passed this smoke check without printing credentials. A synthetic
query-separator/encoded-password regression also passed in the focused test
suite. Database acceptance after these changes remains untested; no remote
database or API connection was opened by these checks.
