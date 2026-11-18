# Workshop update restart agent

## Hook and ownership

The [Maven agent](2026-11-17-byte-buddy-agent-foundation.md) transforms
`zombie.Lua.LuaManager$GlobalObject$ItemQueryJava.inform(String)` at initial class
load. It recognizes only `CheckModsNeedUpdate: Mods need update`. Advice submits
to `cloud.apocalipse.pz.workshop.ModUpdateRestartNotifier` before the original
notification. It does not change Workshop item-state detection, the timestamp
correction, original chat/logging or Steam request cleanup. Unrelated messages
do not trigger a request. Submission/linkage failures are contained in the advice
so they cannot prevent the original method from running.

The classloader still selects the current loose patched class or game JAR class.
No existing `42.22.2/src/zombie` source is changed. This hook depends on the nested
class name, method descriptor and message remaining present in each supported
build. It does not periodically invoke `checkModsNeedUpdate`; an existing caller
must initiate each check. The agent is intended for the dedicated server launch.

The callback thread performs only bounded admission and starts one named virtual
thread. HTTP client creation, I/O and JSON parsing run on that worker. There are
no game references, Lua callbacks, queue or shared dependency on the existing
`zombie.apocbr.api` dispatcher. Gson is relocated in the shaded JAR to avoid
conflicting with game JSON libraries.

An atomic gate permits at most one active worker. Concurrent detections are
coalesced. Attempts are at least 60 seconds apart; failures are retried only by a
later detection, without a background retry timer. Acknowledged requests suppress
further attempts until this game process restarts, including `already_in_progress`.
If the backend countdown fails or is cancelled while the game survives, this
process does not automatically re-arm after acknowledgement. Virtual threads are
daemon threads: shutdown does not wait to flush the notification.

## HTTP contract and configuration

The call matches `Apocalipse-Music-uploads/.github/workflows/publish-workshop.yml`,
which runs `ApocalipseBRRadio/scripts/workshop/request_restart.py`:

```text
POST https://plataforma.apocalipse.cloud/api/server/mod-update/restart
Content-Type: application/json
X-API-Key: <configured integration credential>

{"message":"Restart para update de mods"}
```

The default host is taken from the manager's
`src/main/resources/setup-nginx-https.sh`; its HTTPS `server_name` is
`plataforma.apocalipse.cloud` and it proxies to the manager. The path is declared
in `ModUpdateRestartController`. The older `server.base-url` production property
still names `apocalipse.cloud`; the agent uses the explicit HTTPS Nginx host.
These are source configuration facts, not a live endpoint verification.

| Setting | Resolution order |
| --- | --- |
| Enabled | `-Dapocbr.restart.enabled`, default `true` |
| URL | `-Dapocbr.restart.url`, then `PZMANAGER_RESTART_URL`, then the URL above |
| API key | `-Dapocbr.restart.apiKey`, then `PZMANAGER_MOD_UPDATE_TOKEN`, then existing `-Dapocbr.bridge.serverKey` |

Explicit empty values are invalid, not requests to fall back to another key.
Missing/invalid credentials disable the notifier with a fixed diagnostic. URL
configuration requires HTTPS, the exact path, and no embedded credentials, query
or fragment. The API key is sent verbatim and never logged. Restart settings are
captured at JVM startup. No keys are embedded in source or copied from tasks.json.
The existing Lua API worker continues to use its original configuration.

The request has a 5-second connect timeout, 30-second request timeout, no redirect
following and a 4096-byte response cap. Both HTTP and JSON status must match:

- HTTP 202 and `status: accepted`: countdown accepted.
- HTTP 200 and `status: already_in_progress`: duplicate acknowledged.
- Everything else: failed attempt, without logging URL, token or response body.

The manager's `docs/mod-update-restarts.md` documents that this endpoint starts
its existing 10-minute restart flow **for all enabled servers**, not just the
calling game process. Backend countdown ownership and duplicate handling remain
unchanged. Its music-pack worker and other restart side effects also remain owned
by the manager. The agent does not issue RCON or local shutdown commands.

## Verification

On 2026-11-17, Maven unit tests exercised production transport against loopback
HTTP, request method/header/body, redirect refusal, response size and status
validation, virtual-thread execution, admission coalescing, successful suppression
and failure cooldown. Production configuration still requires HTTPS.

A fresh JVM runs the packaged agent against structural game-class doubles and
an injected fake transport, checking helper linkage, original method execution,
ignored messages, duplicate suppression and telemetry advice binding without
external network calls:

```text
mvn package
java "-Dapocbr.restart.enabled=false" -javaagent:target/apocbr-agent.jar -cp "target/apocbr-agent.jar;target/test-classes" cloud.apocalipse.pz.agent.features.workshop.AgentSmokeMain
```

Use `:` rather than `;` in the classpath on Linux. In PowerShell, quote the complete
`-Dapocbr.restart.enabled=false` argument. The disable setting keeps normal startup
configuration inactive; the fixture installs its own fake notifier. Telemetry
delivery is also inactive unless its endpoint and API key are configured. Test
game classes live only in `target/test-classes` and are not packaged in the agent JAR.

`javap -p` against the installed original game JAR confirmed the exact nested
class and private `inform(String)` descriptor. Full PZ startup, real Steam callback
execution and Linux deployment remain unverified. No live restart API was called.
