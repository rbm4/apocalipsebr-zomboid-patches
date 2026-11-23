# Server packet diagnostics

`AgentModules` installs `features.networkdiagnostics.NetworkDiagnosticsModule`
by default. Roll back to vanilla logging at the next JVM start with
`-Dapocbr.networkDiagnostics.enabled=false`.

The port follows the 42.22.2 legacy changes in `ApocBRNetworkDiagnostics`,
`GameServer`, `ConnectionPlayer`, and `INetworkPacket`. It preserves the
original packet parsing, consistency checks, synchronization and dispatch.

| Original 42.22.2 entry point | Agent behavior |
| --- | --- |
| `GameServer.mainLoopDealWithNetData(ZomboidNetData)` | Wraps the existing `PacketType.onServerPacket(ByteBufferReader, UdpConnection)` call and restores the prior thread-local packet type on exit, including when dispatch throws. |
| `ConnectionPlayer.parse(ByteBufferReader, IConnection)` | Routes the invalid-index error call through the limiter. The existing return and player lookup paths still execute as defined by vanilla. |
| `INetworkPacket.logInconsistentPacket(IConnection, PacketType)` | Uses the limiter for server warnings and formats the packet description only for emitted messages. The caller still synchronizes inconsistent packets. |

The first occurrence logs immediately. Further occurrences for the same
connection GUID, packet type, and diagnostic are counted for 60 seconds. The
next occurrence at or after the interval logs the total, the count since the
last emitted entry, and the number suppressed. Invalid slots stay at error
severity and inconsistent packets stay at warning severity. The limiter keeps
up to 4,096 least-recently-used buckets for the life of the JVM. It needs no
periodic task or persistence. Non-server calls use the original messages.

## Validation

Windows with Java 25 and the original game JAR at
`Z:/SteamLibrary/steamapps/common/ProjectZomboid/projectzomboid.jar` was used.
Original JAR SHA-256
`3C4A7C5F149D784758C6FB5F8FA85F3A21D572E38BC081400E89035F8120D287`.

```powershell
mvn "-Djar.forceCreation=true" package
$gameJar = 'Z:/SteamLibrary/steamapps/common/ProjectZomboid/projectzomboid.jar'
$agentJar = (Resolve-Path target/apocbr-agent.jar).Path
$testClasses = (Resolve-Path target/test-classes).Path
$fixture = 'cloud.apocalipse.pz.agent.features.networkdiagnostics.NetworkDiagnosticsOriginalMain'
java "-javaagent:$agentJar" -cp "$gameJar;$agentJar;$testClasses" $fixture $gameJar true
java "-javaagent:$agentJar" "-Dapocbr.networkDiagnostics.enabled=false" -cp "$gameJar;$agentJar;$testClasses" $fixture $gameJar false
```

The unit test covers the cadence boundary, aggregation keys, and eviction.
Both packaged fixture runs passed with original class origins. Two identical
Equip receives produced one error and one warning with the feature enabled,
and two of each with it disabled. Both runs reached vanilla synchronization
twice. The fixture also verifies lazy warning formatting, context cleanup
after a dispatch failure, and vanilla logging when diagnostic context fails
or the server flag is off. It runs without opening a socket or starting
`GameServer.main`. Deployment and validation with connected clients remain
pending.
