# Java pathfinding lock

The agent locks each game process to the built-in Java `PolygonalMap2`
pathfinder by default. To restore vanilla backend selection for a dedicated
server or client, add `-Dapocbr.pathfinding.forceJava=false` to its JVM arguments
alongside `-javaagent`, before the game main class, and restart.

## Game hooks

`AgentRuntime` installs `JavaPathfindingModule` through the shared registry.
The enabled module verifies its hook sites before installing the transformer.

| Original 42.22.2 entry point | Behavior with the lock enabled |
| --- | --- |
| `PathfindNative.<clinit>()V` | Sets the initial backend flag to Java. |
| `IsoWorld.init()V` | Forces the Java value at the backend assignment, before vanilla starts the selected worker. |
| `PathfindNative.checkUseNativeCode()V` | Skips the runtime backend switch. Changes to `Pathfind.UseNativeCode` leave the running Java worker in place. |
| `ServerMap.QueuedQuit()V` | Stops the Java worker at the original worker-stop call site. The remaining shutdown sequence continues in vanilla order. |

`IngameState.UpdateStuff()` already dispatches path results on the simulation
thread for the server and client. Vanilla request, cancellation, chunk, square,
and vehicle paths use the same backend flag. The game owns the worker and all
its state. The process still loads and frees the native pathfinding library at
the original lifecycle points. Client exit already stops the selected worker.

The baseline is the original Windows 42.22.2 `projectzomboid.jar` at
`Z:/SteamLibrary/steamapps/common/ProjectZomboid/projectzomboid.jar`, SHA-256
`3C4A7C5F149D784758C6FB5F8FA85F3A21D572E38BC081400E89035F8120D287`.
The matching decompiled `IsoWorld`, `IngameState` and `PathfindNative` establish
vanilla startup and switching behavior. The legacy `42.22.2/src/zombie/network/ServerMap.java`
shows the Java worker cleanup needed when the server selects that backend.

## Validation

`JavaPathfindingTest` launches the production agent in fresh JVMs with the
original JAR. It verifies original class origins and bytecode linkage, checks
the initial flag and runtime debug switch with the default and both explicit settings,
and exercises the shutdown helper against a real Java worker. The same smoke
checks passed with the shaded `target/apocbr-agent.jar` as the Java agent and
the original game JAR as the only source of game classes. Full world startup,
in-world path requests and graceful dedicated-server shutdown remain to be
validated in a live game.
