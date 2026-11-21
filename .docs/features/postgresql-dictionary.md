# PostgreSQL dictionary setup

Status: **setup implemented; full dictionary decoding and player reconstruction
remain in progress**. Target: original 42.22.2 JAR, root Maven agent.

## Contract

The user confirmed that the legacy 42.22.2 cross-server profile flow works:
servers publish their own dictionary and keep foreign dictionaries in memory for
source-aware item decoding. This port preserves that architecture. It does not
introduce a central ID allocator or replace vanilla allocation with foreign IDs.

`features.postgres.dictionary.DictionaryModule` is registered in `AgentModules`.
It injects `DictionaryRuntime.afterRebuild(Object)` immediately after the original
`DictionaryData.save()` call inside `WorldDictionary.init()`, before backup
deletion and inside vanilla's existing error handling. A failed native save
never publishes. Publication failure prevents the remaining successful startup
path, as in the legacy patch. Disabled setup leaves the original method untouched.
The helper also skips clients, non-server processes and no-save worlds.

Preflight inspects original class bytes without defining game classes and rejects
an unexpected save anchor count. No fields, methods or types are added to signed
game classes. Protected native dictionary fields/loaders are accessed through
an agent-owned reflection adapter, exercised against the original archive.

## Publication and foreign loading

- Capture the completed native serialized cache into detached typed registries
  and entries; use native dictionary entry decoders without changing the cache
  or counters. Preserve string/script registers and signed script version hashes.
- Publish namespace/history, revision, registry/identity state, overrides, ready
  view, journal and notification in one transaction. Preserve existing ownership
  checks, history comparisons and unchanged-view reuse.
- Preserve the legacy persisted build label `42.22.0`/world251/format1 and supported
  historical `42.21.0`/world249/format1 reads. These are database compatibility
  identities, not a claim that the current archive is 42.22.0. Do not relabel old
  views as 42.22.2.
- Rotate the namespace only for supported prior-build or allocation-history
  conflicts. Preserve old history. Write the configured/replacement UUID marker
  atomically before replacement publication, so failed publication/restart reuses
  the same incarnation. Transport, ownership and schema failures do not rotate.
- Start a dedicated physical PostgreSQL `LISTEN apocbr_dictionary_changed` session
  outside the query pool. Subscribe before initial discovery and reconcile after
  every reconnect, notification and a 60-second fallback interval. Preserve the
  legacy discovery query: latest ready view per foreign namespace/server/save,
  excluding the local server ID, with a 256-view discovery cap.
- Load each uncached exact server/namespace/view with bounded counts and ordered
  keyset pages. Entries and overrides each use `max(1000, ceil(count/10))` page
  rows, at most ten data pages, and no OFFSET or final sentinel query. Check every
  expected page length, ready view, format/build pair and typed snapshot before
  making it visible. Partial loads roll back and never enter the cache.
- Keep completed historical views available when heads change. Cache limits are
  256 exact views and one million entries; snapshots retain the legacy 128-register,
  200,000-entry and one-million-override read limits. Capacity failure is explicit.

The first foreign reconciliation runs on the listener, as in the legacy source;
local publication does not synchronously wait for all foreign heads. The future
profile reader must prepare each referenced exact view before reconstruction.
`DictionaryCache.ready` neither waits nor opens JDBC. Merely appearing in foreign
head metadata is not a substitute for preparing a specific payload view.

## Configuration and lifecycle

All properties are declared/read through `AgentConfiguration`:

| JVM setting | Behavior |
| --- | --- |
| `apocbr.postgres.enabled=true` | Shared PostgreSQL master; default false. |
| `apocbr.postgres.dictionary.enabled=true` | Setup defaults on with the master; independent of account enablement. |
| `apocbr.postgres.serverId=<positive ID>` | Existing registered `player_rel.server.id`; validated when enabled native server setup executes. |
| `apocbr.postgres.dictionary.saveKey=...` | Optional 1..128 character save identity; default `Core.gameSaveWorld`. |
| `apocbr.postgres.dictionary.namespaceId=<UUID>` | Optional explicit namespace; default deterministic UUID from server ID and save key. |
| `apocbr.postgres.dictionary.publicationTimeoutSeconds=30` | Range 5..300 seconds. Source queries retain five-second per-statement timeouts. Dictionary query lease network timeout is publication timeout plus 15 seconds. |

Use the shared PostgreSQL URL/environment and pool settings from the
[PostgreSQL guide](postgresql.md). The extra listener session is not counted in
poolSize. Dictionary setup also works with account hooks disabled.

`GameServer.main` exit and `ServerMap.QueuedQuit` exit close the shared runtime,
including when account hooks are disabled. The shared JVM shutdown hook provides
the final fallback. A registered before-pool-close callback stops the listener,
closes its notification/query sessions, joins its thread with a bounded wait,
then clears local/foreign/cache state. Never close the query pool before its
dictionary worker. No hot-switching contract is provided.

The engine issues no schema DDL. Provision the existing backend dictionary
migrations and registered server row first. Existing `player_rel`/`public` schema,
table, sequence and actual-creator default grants to `pz_server_01` still apply;
see [repository grants](../../AGENTS.md). No production database was contacted.
Real engine-role publication/read/grant acceptance remains to be tested in an
isolated database with separate migration and engine roles.

## Validation and remaining work

Focused tests exercise transactional publication/reuse, rollback, old-build and
ownership rejection, failed batches, namespace-marker retry, counted pagination
up to 200,000 entries, ordered overrides, corrupt/short pages, budgets and cache
retry. Registered matchers transform the exact original WorldDictionary,
GameServer and ServerMap bytes without changing member structure.

An isolated child JVM excludes loose game test doubles, exercises real native
snapshot encoding/capture, and executes a transformed startup fixture into the
production setup code using fake JDBC. It verifies local publication, prepared
foreign dictionaries, notification updates, reconnect recovery, historical cache
retention, startup failure ordering and listener/pool cleanup. It never invokes
GameServer.main. This is not a dedicated-server or real database test.

Native payload provenance attachment, historical item/script/string/object decode
scopes, player reconstruction and the initial client-profile round trip are not
implemented by this setup slice. Continue with the working legacy decoding
contracts; do not infer a need to redesign the cross-server protocol merely from
opaque byteData copying. No deployment or live-runtime acceptance is claimed.

The optional integration suite automatically discovers the three module hook
checks. It was not run for this change. Installation evidence alone will not prove
dictionary SQL behavior or cross-server player login.

### Validation record, 2026-11-21

On Windows with the repository Java 25 runtime, the selected dictionary,
PostgreSQL infrastructure/account and telemetry tests packaged successfully:
61 tests, zero failures/errors, one historical patched-runtime telemetry test
skipped because `pz.game.install` was not supplied. Ten dictionary tests ran with
no skips. The command was:

```text
mvn -B -ntp -Dpz.game.jar=Z:/SteamLibrary/steamapps/common/ProjectZomboid/projectzomboid.jar -Dtest=Dictionary*Test,PostgresHookTest,Telemetry*Test,DatabaseInfrastructureTest,ConnectionAcquisitionTest,AccountReplayTest,PersistenceJournalTest,LegacyJournalGuardTest,AgentConfigurationTest package
```

The final build ran in a stable source snapshot at
`target/dictionary-validation-c269e2291e9349ed99c82b9d9e09faa3` because other work
was changing the shared build outputs. Dictionary production/test sources and
the four changed shared Java owners matched the tested snapshot. Retained evidence
there includes `build.log`, `dictionary-source-sha256.txt`, Surefire reports,
`artifact-smoke.log`, the shaded agent and the disposable fixture save.

Shaded agent SHA-256:
`7f006e6bebf81a888a4c4cff2de85c23c3312c4742a9b8fa182b5f565873ed5a`.
Original game SHA-256:
`3c4a7c5f149d784758c6fb5f8fa85f3a21d572e38bc081400e89035f8120d287`.

A separate `-javaagent` run of `DictionaryOriginalSmokeMain` passed with only
the shaded artifact, selected fixture classes and original game JAR on the
classpath. The fixture handles both original and relocated JDBC interfaces, so
no loose JDBC/Hikari/Gson dependency JARs mask linkage failures. It enables
dictionary setup with accounts disabled, installs fake JDBC before publication,
and exercises the real premain module pipeline, native capture, transformed
startup, foreign pagination/refresh/reconnect and shutdown. Archive inspection
found zero bundled `zombie/` classes and zero unrelocated PostgreSQL classes.

An earlier unfiltered package attempt was not green: it reported an NPC Hit
anchor mismatch and signed-package conflicts in account/telemetry tests. The
combined selected tests above subsequently passed in isolation; this does not
claim a passing complete repository suite. The original full-run log is retained
as `initial-full-package.log` in the snapshot. No production services, original
game files or installed overrides were changed; no dedicated server was started.
