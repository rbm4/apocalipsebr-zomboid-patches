# PostgreSQL module and account hook registration

Date: 2026-11-21. Scope: connect the existing infrastructure to the root agent's
actual instrumentation pipeline. The complete PostgreSQL/player/vehicle/dictionary/
recovery/NPC migration remains open; see the [feature guide](../features/postgresql.md).

## Implementation and source evidence

`AgentRuntime.MODULES` now includes `PostgresModule` alongside Workshop and
telemetry. Registration always occurs; account transformations are conditional on
the master/account flags, and disabled mode leaves native behavior untouched.
The module reads declared `AgentConfiguration` settings during startup.

Original behavior was traced in 42.22.2 `ServerWorldDatabase`, `ServerPlayerDB`,
`GameServer`, `ServerMap`, `Roles`, `Role`, `ServerOptions`, and `PZcrypt` under
`decompiled`. The corresponding patched account/bootstrap, connector/lease,
account-count and shutdown sources supplied legacy intent. The original JAR
fingerprint remains the one recorded in the [source audit](2026-11-21-postgresql-vanilla-audit.md).

`AccountConnectionVisitor` redirects the native account singleton's Connection
reads into agent-owned scoped leases. It does not assign to the game field or
replace complete classes. Advice preserves native account method bodies, while
explicitly replacing PostgreSQL bootstrap/connect/close and the database-backed
account-limit predicate. Native role initialization/password hashing, credential
checks, bans, authentication type and second factor remain native operations.

The account-limit adapter advances JDBC result sets before reading aggregate
counts and fails closed; the dependent player-count/Steam-ID reads use existing
`public.networkplayers`. Account-side lookup avoids constructing a SQLite player
database. Narrow JDBC binding fixes handle vanilla string-bound integer IDs and
userlog amounts. These are explicit account integration decisions; they do not
implement player persistence itself.

Authentication entry and exit advice denies acquisition failures and SQL failures
swallowed by native catch blocks. Ordinary account calls surface caught SQL
failures rather than returning false success. Native exceptions otherwise retain
their execution path. Scopes close on return and exceptions, and nested calls
share one lease/deadline.

The module preflights account bytecode before installation without defining game
classes. The successful-transformation listener and `GameServer.main` startup
gate prevent untransformed account code from silently falling back to SQLite.
`GameServer.main` exit, `ServerMap.QueuedQuit` exit and a JVM shutdown hook close
the pool. Vanilla role saving and shutdown work run before ordinary pool close.
Helpers remain in the agent namespace; native class member structure is retained.

## Current contract and gaps

Account operations currently execute synchronously and commit through native
autocommit semantics. The existing journal/account replay foundation is not yet
connected to a complete replay/recovery worker. A read-only legacy checkpoint
guard rejects pending/invalid journal state before account bootstrap borrows or
writes. It neither discards pending entries nor treats unsupported records as
successfully replayed.

This is not the completed legacy persistence/resilience contract. Player writes,
reconstruction, vehicle/dictionary stores, asynchronous login/admin continuations,
NPC runtime and full journal coordination remain unimplemented. No live database
or game installation was changed, and no actual dedicated server was launched.

## Validation

Final Maven package validation passed: 50 tests discovered, 49 passed, one
legacy-patched-runtime-only telemetry test intentionally skipped. Both the
enabled PostgreSQL premain smoke and the existing disabled-PostgreSQL
Workshop/telemetry smoke passed. Archive inspection confirmed the module is
packaged and no `zombie/` game/test classes are bundled; documentation links
and the working-tree diff whitespace check passed.

`PostgresHookTest` executes class bytes produced by the registered module using
fake JDBC. It verifies:

- Native account body continuation, nested lease sharing and untouched native
  Connection field; pure native methods remain independent of database access.
- PostgreSQL bootstrap/connect/close paths bypass SQLite code, including
  administrator creation with native role/hash contracts.
- Authentication denies caught query failures and acquisition failures.
- Native account-limit count/priority policy and numeric JDBC compatibility.
- Caught mutation failures cannot return a false successful result.
- Global module registration, disabled mode and the transformation gate.
- Pending journal state stops instrumented bootstrap before borrowing.
- Original-JAR account, main-loop entry/exit and shutdown transformations
  preserve actual field/method names, descriptors and access flags.

`LegacyJournalGuardTest` checks pending/invalid checkpoints without changing them.
The original-JAR structural tests are enabled by `-Dpz.game.jar=<original JAR>`;
the unrelated legacy-patched-runtime telemetry check intentionally remains skipped
without `pz.game.install`.

After `mvn package`, this command exercises actual premain/module registration
against structural game doubles and fake JDBC, without opening a database socket:

```powershell
.\jdk\bin\java.exe -javaagent:target/apocbr-agent.jar '-Dapocbr.postgres.enabled=true' '-Dapocbr.postgres.url=jdbc:postgresql://localhost/fixture' '-Dapocbr.profile=local' -cp 'target/apocbr-agent.jar;target/test-classes' cloud.apocalipse.pz.agent.features.postgres.PostgresAgentSmokeMain
```

It verifies account bootstrap/query/denial, shaded helper linkage, that PostgreSQL
registration does not displace telemetry's three simulation ticks/five loops,
and final lease/pool shutdown. These are not live PostgreSQL grants, native
gameplay, reconnect or outage-recovery acceptance tests. Those remain required
before the complete feature can be declared ready for cutover.
