# PostgreSQL and NPC vanilla compatibility audit

Date: 2026-11-21. Implementation status: foundational code only; complete runtime
port remains open. See the [feature contract](../features/postgresql.md).

## Original-JAR evidence

Audited archive:
`Z:\SteamLibrary\steamapps\common\ProjectZomboid\projectzomboid.jar`.

SHA-256:
`3c4a7c5f149d784758c6fb5f8fa85f3a21d572e38bc081400e89035f8120d287`.

The archive contains `META-INF/PROJECTZ.SF` and `META-INF/PROJECTZ.EC`.
Its manifest declares neither `Class-Path` nor `Sealed`. Ordinary unsigned
helpers cannot share the signed game packages in the same class loader. The
selected direction keeps new helpers under agent-owned packages and game
classes structurally vanilla. Package relocation must account for serialization.

## Compiler audit

A disposable javac25 check used only the original JAR plus the historical
HikariCP7.1.0/PostgreSQL42.7.14 libraries. Source lookup was disabled so the
compiler could not silently resolve patched vanilla sources. It compiled
custom-only source inputs, not legacy complete-class replacements.

- Starting with 41 custom `savefile` sources, a conservative lexical custom-type
  closure selected 67 sources. javac reported four errors: missing
  `ServerWorldDatabase.LoginRoles` (two references), `useLoginRoles`, and
  `ServerPlayerDB.encodePostgresClientProfile`.
- An additional diagnostic pass over all 266 custom-only sources reported
  32 errors. It includes unrelated optimization/power sources and is not a
  proposed migration scope. Relevant NPC errors include `ChatServer` human chat
  helpers, `IsoObject.getApocbrLifetimeRevision`, and an `IsoCell` spatial-query
  invalidation method.

These counts describe this compiler invocation, not runtime compatibility or the
complete set of required instrumentation hooks. Reflection, strings, vanilla
modified-method bodies, callback order and serialization can hide further gaps.

## Reproduce

```powershell
py -3 tools/audit_postgres_vanilla.py --game-jar 'Z:\SteamLibrary\steamapps\common\ProjectZomboid\projectzomboid.jar' --javac jdk/bin/javac.exe
py -3 tools/audit_postgres_vanilla.py --game-jar 'Z:\SteamLibrary\steamapps\common\ProjectZomboid\projectzomboid.jar' --javac jdk/bin/javac.exe --all-custom
```

Compiler failure is expected until the legacy custom integrations have been
adapted; the audit returns javac's failure status. Manifests, source hashes,
diagnostics and disposable classes are under ignored `target/postgres-migration-audit/`.
Neither command deploys files or connects to a database.

## Required integration decisions now recorded

- Complete PostgreSQL feature, including unfinished vehicle persistence paths.
- NPC gameplay runtime included, not merely detached NPC storage contracts.
- Agent-owned adapters/state; preserve the vanilla game-class structure and
  the agent's class-format restriction.
- Preserve vanilla behavior except for explicitly migrated feature contracts.
  Keep server-owned native NPC execution and detached planning; do not restore
  the older live-world NPC worker experiment.

Account bootstrap cannot be ported by merely replacing the SQLite connection:
native startup executes SQLite schema/migration SQL, and legacy PostgreSQL
startup deliberately bypasses it. Async login/admin continuation handling,
receipt-backed journal replay, player reconstruction and vehicle store/decode
integration each need explicit instrumentation. A successful source copy or
JDBC connection does not complete this feature.

## Foundation validation

`mvn -B -ntp "-Dpz.game.jar=Z:\SteamLibrary\steamapps\common\ProjectZomboid\projectzomboid.jar" package`
passed during implementation, including the actual original-JAR telemetry
transformation check. The existing patched-runtime-only check was intentionally
skipped because `pz.game.install` was omitted. New infrastructure tests exercise
nested lease ownership, reverse-order cleanup, shared deadlines, expired cleanup,
thread rejection, sanitized SQL failures, pool shutdown and schema validation.

After packaging, running `PostgresPackagingSmokeMain` with only the shaded agent
and test classes on the classpath successfully loaded the relocated PostgreSQL
datasource, Hikari configuration and JDBC DriverManager service. No database
connection was opened. The relocated SLF4J API reported its lack of a logging
provider and used its no-operation logger; feature diagnostics must remain
explicit and sanitized rather than depending on pool logging.

Archive inspection confirmed that no `zombie/` game classes were bundled.
Documentation links were checked. No server launch, database migration, live
database access or deployment was performed. These checks do not establish a
working PostgreSQL/NPC runtime port.

The agent-owned journal port additionally uses the actual legacy journal source
to write an account operation in an isolated class loader, then verifies the
root reader preserves its UUID/parameters and acknowledgement across restart.
Unrecognized legacy operation descriptors fail without changing their checkpoint
or record bytes. This compatibility evidence currently covers account operations
only; pending player/NPC operations must be supported before cutover.

Account replay also verifies receipt and mutations in one transaction: mutation
failure rolls back both, while a simulated lost commit response leaves a receipt
that prevents duplicate mutations on retry. This is fake-JDBC transaction
evidence, not an isolated PostgreSQL engine-role acceptance test.
