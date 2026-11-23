# Byte Buddy agent foundation

The repository-root Maven project builds a startup Java agent for incremental
migration from complete game-class replacements. The initial bootstrap was verified
without transformations. The agent now includes the [Workshop update restart
hook](2026-11-17-workshop-update-restart-agent.md), whose HTTP work uses a separate
virtual thread. Game classes still come from the existing patch deployment.

## Build and local verification

Use Maven 3.9+ with JDK 25 from the repository root:

```text
mvn clean package
java -javaagent:target/apocbr-agent.jar -version
```

The second command should print:

```text
[ApocBR-Agent] Loaded on Java 25; Workshop update notification hook registered.
```

The deployable artifact is `target/apocbr-agent.jar`, containing our entry point
and Byte Buddy 1.18.17 plus a private relocated Gson parser.
`target/original-apocbr-agent.jar` is Maven Shade's thin
backup and is not the deployment artifact. Maven compiles only root
`src/main/java`; it does not compile or package versioned patch sources,
decompiled game sources or the game JAR. The existing patch scripts retain those
responsibilities. Production needs Java 25, not Maven or a compiler.

Our classes use `cloud.apocalipse.pz`, outside the game's `zombie` packages.
The manifest provides `Premain-Class`; there is no standalone application main
or dynamic-attachment entry point. Future transformations must explicitly match
the supported game build's classes and methods. Packaging is independent of
whether those classes originate in a vanilla JAR or a loose replacement; their
signatures and behavior remain version-specific.

## Coexistence and Linux startup

Keep the current unsigned runtime, loose classes, dependency classpath and launch
arguments described in the [runtime deployment guide](../../../42.22.1/docs/features/2026-11-04-unsigned-runtime-deployment.md).
The agent is an additional JVM startup option, not a replacement classpath entry:

```text
-javaagent:/opt/pzserver/agents/apocbr-agent.jar
```

For an explicit Java invocation, place that option before the main class.
For a systemd service wrapping the current startup script, an illustrative drop-in
can supply it without changing the script's unsigned game-JAR selection:

```ini
[Service]
Environment="JAVA_TOOL_OPTIONS=-javaagent:/opt/pzserver/agents/apocbr-agent.jar"
```

Use the actual installed absolute path. Preserve any existing `JAVA_TOOL_OPTIONS`
options in the configured value; systemd does not expand a shell variable there.
The variable is inherited by Java processes started by the service, so check the
actual launch path, environment filtering and any extra Java subprocesses before
deploying. Do not also add the same agent argument through the launcher JSON or
Java command line. The JVM must have its `java.instrument` module available.

Deploy or replace the agent while the server is stopped, then start it normally.
The startup banner confirms agent loading only; this foundation does not prove a
game hook was matched. Removing the startup option and restarting disables the
agent while leaving existing patches active. No service, launcher or installation
files are changed by the Maven build, and no production deployment is performed
as part of this foundation.

## Verification on 2026-11-17

Maven 3.9.11 with Java 25.0.1 built the shaded JAR successfully. Both the explicit
`-javaagent` invocation and the equivalent `JAVA_TOOL_OPTIONS` invocation printed
the agent startup banner and exited successfully on Windows. Archive inspection
confirmed the `Premain-Class`, multi-release manifest flag, bundled Byte Buddy and
absence of `zombie/` game classes. Linux service inheritance and actual PZ startup
remain unverified; no game process was launched for these smoke checks.

The subsequent Workshop hook verification is recorded in its linked feature guide.

For the future base-code migration, the [2026-11-18 source baseline and impact
report](../reports/2026-11-18-byte-buddy-migration-baseline/2026-11-18-byte-buddy-migration-baseline.md)
preserves vanilla comparisons, custom-class additions and migration questions.
It is an investigation artifact, not evidence that the base migration has shipped.

The first incremental game-code measurement port is the
[vanilla-backed server telemetry baseline](2026-11-18-byte-buddy-server-telemetry-basics.md).
