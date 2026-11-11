# Server fixed-power regions

Status: implemented for 42.22.2 server; in-game validation pending.

Trusted server Lua can call `ApocBRFixedPower.setRegion(id, x1, y1, x2, y2, z1, z2)` and `removeRegion(id)`. Bounds are inclusive, order-independent and limited to X/Y 0..1000000 and Z -32..31. A successful set replaces that ID atomically; at most 256 named regions exist. Overlaps remain powered until all covering regions are removed. Regions are memory-only: their owner must register them again at each startup. No safehouse purchase or persistence is implemented.

The server checks the current immutable region snapshot in `IsoGridSquare.haveElectricity()` and both `hasGridPower` overloads, including exterior squares; checks outside regions retain vanilla behavior. Registration does not load chunks or mutate tiles. Java updates are synchronized and readers use a volatile immutable snapshot. Mod code must execute registration on the server side; clients cannot register regions through this bridge.

The ExtractionMapUtils mod registers its existing lobby rectangle at server startup when `LobbyFixedPower` and `LobbyEnabled` are true. It includes all floors. Client power checks and lighting are not patched or synchronized by this feature, and server power is not a guarantee that all appliances, client interactions or lighting work. Replacing/removing regions while squares are already loaded does not force a lighting or appliance refresh. No runtime toggle behavior is promised.

Validation: `py -3 tools/test_fixed_power.py` passed with a stub server flag; `42.22.2/patchApocalipseBr.ps1 -BuildOnly` compiled 355 sources against the installed 42.22.2 JAR (1032 classes). Dedicated-server and multiplayer appliance, lighting, shutdown, chunk-reload and reconnect scenarios remain untested. No deployment was performed.
