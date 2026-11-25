# Client chunk-map cap

The launcher can opt in by passing `-Dapocbr.optimization.clientChunkMap.enabled=true`
as a JVM argument alongside `-javaagent`. The switch defaults to `false` and is
read when the agent installs its hooks. Remove it or set it to `false`, then
restart the client to restore vanilla sizing.

## Behavior

The [42.19.0 client patch](../../42.19.0-client/src/zombie/iso/IsoChunkMap.java#L91)
forced a width of 13 chunks in `CalcChunkWidth()`. The same-build vanilla
reference is preserved at commit `879ac9d` under
`42.19.0/decompiled/zombie/iso/IsoChunkMap.java`. It selected a debug override or
calculated an odd width from screen resolution, capped at 19. The original
42.22.2 JAR retains that sizing path.

The agent runs exit advice on `zombie.iso.IsoChunkMap.CalcChunkWidth()V` after
vanilla calculates the width. It caps widths above 13 when `GameServer.server`
is false and updates the paired tile width. Smaller vanilla and debug-selected
windows keep their existing size. This applies to multiplayer clients and local
play. The client computes the dimensions before LOS initialization and world
allocation. Both swap buffers and their consumers use the same dimensions for
that world session.

At 1920×1080 and above, the normal 19×19 window (152×152 tiles) drops to 13×13
(104×104 tiles). That is 169 instead of 361 potential chunk slots per player,
about 53% fewer slots. Visible map coverage and streaming headroom can shrink
at far zoom or while driving. The vanilla player-connect packet reports the
selected width, which the server uses in its existing per-connection range
calculation.

## Validation

`mvn -Dtest=ClientChunkMapHookTest package` exercises the installed agent against
the original 42.22.2 Windows game JAR. The forked fixture verifies both calculated
dimensions and allocated swap buffers, explicit and implicit disablement, smaller
windows, debug overrides and the dedicated-server guard.
The shaded `target/apocbr-agent.jar` also passed the same probe in all three
startup modes with only the original game JAR and isolated probe on the classpath.

Original JAR SHA-256 on the validation host is
`3C4A7C5F149D784758C6FB5F8FA85F3A21D572E38BC081400E89035F8120D287`.
Live acceptance remains open for launcher startup, multiplayer join/rejoin,
far-zoom coverage, high-speed streaming and same-route frame-time comparison.
