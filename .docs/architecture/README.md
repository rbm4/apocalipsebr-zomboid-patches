# Agent architecture and original-JAR development

## Runtime target

The migration baseline is the original 42.22.2 game JAR. Confirm the installed
build and record its SHA-256 for each port; a Steam installation path can change
content after updates. Use `42.22.2/decompiled/` for baseline behavior and
`42.22.2/src/` to identify legacy intent, not as active implementation directories.

The new global VS Code task in
`C:\Users\ricar\AppData\Roaming\Code\User\tasks.json` is:

`Start PZ Dedicated Server - original JAR servertest JDWP`

It depends on `Build ApocBR agent for original PZ JAR`, which runs Maven clean
package with `pz.game.jar` pointing to the original JAR and without
`pz.game.install`. The launch uses:

```text
-javaagent:Z:/git/Github/apocalipsebr-zomboid-patches/target/apocbr-agent.jar
-cp Z:\SteamLibrary\steamapps\common\ProjectZomboid\projectzomboid.jar
zombie.network.GameServer -servername servertest
```

The process working directory stays at the game installation for native libraries
and game resources. The explicit classpath excludes `.` and the patched archive,
preventing ordinary loose-class overrides from taking precedence. It retains
the local agent profile and JDWP port 5005. Existing global tasks remain available.
This task does not remove installed patches, change the original archive, or
start the server until explicitly run. It reuses the existing `servertest`
configuration and save; it does not create an isolated test world.

## Implementation ownership

- `ApocBrAgent.premain` enters `AgentRuntime`, which assembles the shared
  transformation pipeline and registers feature modules.
- Each `AgentModule` owns feature initialization and instrumentation registration.
  `AgentModules.all()` is the shared module registry. Required `hookChecks()`
  declarations describe installation contracts discovered by the optional
  [server integration suite](../validation/server-integration.md); conditional
  modules expose their actual disabled reason. This metadata does not replace
  feature-specific behavior assertions.
  Registrations include Workshop notification, telemetry, PostgreSQL and the
  [NPC actor/planner runtime](../features/npc-engine.md), plus [server Lua APIs and
  account/API workers](../features/server-lua.md).
  PostgreSQL also registers [dictionary decoding and player authority](../features/postgresql-players.md);
  see the feature guides for configuration, validation limits and remaining
  integrated persistence/NPC migration work.
- Production implementation belongs in `src/main/java`, configuration defaults
  in `src/main/resources`, and focused tests in `src/test/java`.
- Declare JVM properties and participating environment variables in
  `AgentConfiguration`; do not add direct feature-site property lookups.
- The pipeline disables Byte Buddy rebase and class format changes by default.
  The opt-in thermal clothing port uses an explicit first-definition ASM field
  on `Thermoregulator` for per-instance ownership. That field keeps equipment
  references bounded to their regulator's lifetime. See [server optimization ports](../features/server-optimizations.md).

Select advice, invocation instrumentation, or algorithm replacement per behavior.
Preserve vanilla call order for additive hooks. Make intentional skip/replacement
contracts explicit. Preserve server/client guards, mutable ownership, cleanup,
packet validation, and serialization contracts.

Original-JAR-only migration supersedes the earlier patched-runtime coexistence
requirement for new ports. Existing compatibility code/tests are still present;
their cleanup is separate work. Do not add dual-shape machinery without a new
requirement.
