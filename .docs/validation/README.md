# Validation

Use the smallest relevant Maven tests, normally `mvn test`. Use `mvn package`
when validating the shaded agent artifact. Do not run legacy patch/deployment
scripts for an agent-only feature.

The optional [real-server integration suite](server-integration.md) runs with
`mvn verify -Pserver-integration`. It starts one isolated original-JAR server and
runs assertions registered in separate test files. It is not mandatory for
routine feature work; use it in sessions assigned to integration validation.

## Original-JAR checks

The global `Build ApocBR agent for original PZ JAR` task supplies:

```text
mvn -B -ntp "-Dpz.game.jar=Z:\SteamLibrary\steamapps\common\ProjectZomboid\projectzomboid.jar" clean package
```

Its `JAVA_HOME` uses the repository `jdk`. The existing telemetry transformation
test uses `pz.game.jar` to inspect original `GameServer` bytecode. The historical
patched-runtime test requires both `pz.game.jar` and `pz.game.install`; it is
intentionally skipped by the original-JAR build task. Record skips explicitly.
A successful transformation does not establish successful execution in the game.

For each port, verify the intentional contract against vanilla, including call
order, exception behavior, server/client guards, lifecycle and mutation ownership.
Use the actual feature implementation in regression tests. For hot paths, bound
allocations, state, queues and metric cardinality; record measurement limitations.

## Runtime acceptance

Run the original-JAR launch task only when a server session is intended. Confirm
the actual build/class origins, agent linkage and expected-target diagnostics.
Exercise the feature's relevant simulation, networking, loading/reuse, save and
shutdown paths. Compare telemetry under equivalent workloads before claiming
performance gains. The launch currently selects the existing `servertest` world.

Do not infer runtime validation from packaging or structural test doubles, and do
not modify the original JAR to validate a feature. Record deployment and runtime
results separately in [reports](../reports/README.md).
