# 42.21.0 client: world map polygon buffer offsets

An independent opt-in zombie locomotion sampler is documented in
[client zombie capture](../42.21.0/CLIENT-ZOMBIE-SAMPLER.md). Its builder produces
a separate diagnostic archive from exact vanilla sources; this world-map script
continues to install only the geometry patch.

Client-only loose class override for the exact 42.21.0 game build. Baseline:
`../42.21.0/decompiled/zombie/worldMap/WorldMapGeometry.java`, copied unchanged into
this target's `decompiled` directory before patching `src`.

The local client log reports native reveal completing at `0,0` through `767,767`,
then `WorldMapRenderer.Drawer.fillPolygon` throwing `IndexOutOfBoundsException`
while opening/zooming the minimap. Subsequent `forgot to call endRun()` errors
affect other draw calls because the exception interrupted the polygon draw.

Vanilla `triangulate` stores `indexBuffer.position()` through a signed `short`
cast in the already-`int` field `firstIndex`. At offset 32768 it becomes -32768.
At higher offsets it can also wrap to a different positive location. Road zoom
variants share the cell index buffer, so ordinary small polygons can start above
that offset even though each polygon itself has very few triangles.

The patch removes that single narrowing cast. It preserves the field descriptors,
short vertex indices, per-polygon counts, zoom variants, packet formats, storage,
and the server-side character/persistence patches. It does not suppress rendering
exceptions or change/reduce Chernarus geometry. Runtime logging of the actual
offending offset is not available in the supplied trace, so the reproduction
proves this defect, while the live minimap retest confirms whether it is the
remaining cause on the user's client.

## Verify and install

Fully close the game before installation. From the repository root:

```powershell
py -3 -B tools/test_worldmap_geometry_indices.py
./42.21.0-client/patchWorldMapClient.ps1 -DryRun
./42.21.0-client/patchWorldMapClient.ps1
```

Use `-PZDir` if the client is installed elsewhere. The script compiles against the
selected game's real JAR using the repository JDK 25. Only the four generated
`WorldMapGeometry` class-family files are installed. Existing loose overrides are
backed up under this target's ignored `backups` directory. It does not modify the
game JAR or launch/stop the game. Dry run never installs anything.

The client launcher must resolve loose classes before `projectzomboid.jar` on
its classpath; use the existing ApocBR patched-client launch configuration. A Lua
Workshop mod cannot install this Java override. Installing only on a separate
dedicated server will not fix a client's renderer.

## Validation

The regression compiles and executes the actual vanilla and patched geometry
classes with a deterministic Clipper boundary. Vanilla reproduces negative and
wrapped offsets. The patch reads the correct triangles at offsets around 32767,
65535 and up to 200000, retaining ordinary geometry, zoom variants and reset
sentinels. It does not exercise native Clipper/OpenGL or visually validate maps.

After installing, restart completely, join Chernarus, toggle the minimap and zoom
through the previously failing levels; repeat on the full map. Leave forced reveal
enabled. Confirm the first polygon-buffer exception and the subsequent unfinished
render-run errors no longer appear. No terrain reset is required by this change.
