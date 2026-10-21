# PostgreSQL character login origin and lobby teleport

Status: implemented for server Build 42.21.0; awaiting live multiplayer validation.

## Ownership and behavior

`ApocBRPlayerLogin` is exposed to Lua only on the dedicated server. It has no
Java login callback and is not called by `ServerPlayerDB.finishPostgresPlayer`.
ExtractionMapUtils' Lua login hook sends `LoginLobbyState`; its server
handler queries the getter, which lazily captures the server player's marker
on that first request. Client arguments do not supply the marker or destination.

The reserved character mod-data key `ApocBR.LastConnectedServerId.v1` stores a
positive numeric ID from `apocbr.postgres.serverId`. Capture the saved value once
into a weak per-player context, then stamp the current server into the live
character. Later tag changes or duplicate capture cannot erase the original
comparison after the first getter. No credentials, identities, schema changes or extra JDBC requests
are introduced. Existing relational snapshot/capture/reconstruction persists the
character's Lua mod-data (`lua.modData`) through the normal worker.

This is a per-character last connection marker **as last persisted**, not a
synchronous connection audit. Before its next successful relational checkpoint,
a crash can lose the updated marker and cause conservative repeat relocation.
Existing characters without a valid marker have unknown origin and use the Lua
boundary/water fallback. Do not infer last login from `source_server_id` or
`source_dataset_id`: those identify payload encoders and import datasets.
Install this patch and ExtractionMapUtils on participating servers that should update
the marker. It is stamped only when the Lua command first queries it. IDs must
be stable and unique per server. Server mods must reserve this mod-data key.
The value is read from live character mod-data at request time, not captured
before all client traffic; it is a relocation hint, not an authentication or
cross-server authorization record.

## Server Lua API

- `ApocBRPlayerLogin.getPreviousServerId(player)`: previous saved ID, or 0 for
  unknown/untracked/non-server-thread calls.
- `ApocBRPlayerLogin.hasChangedServer(player)`: true only for a known ID that
  differs from the server captured at login.
- `ApocBRPlayerLogin.teleportToLobby(player, x, y, z)`: game-thread-only native
  `GameServer.sendTeleport`, requiring a living connected player, network AI and
  finite coordinates within the configured placement range. Returns acceptance;
  repeated requests for an accepted player acknowledge without resending.
  Lobby authorization and destination selection belong to trusted server Lua.

`ApocalipseBR-ExtractionMapUtils` owns the Lua login hooks and command handler;
it queries ExtractionMapUtils' closed-lobby/invalid-position rules and supplies the trusted
sandbox destination; its client consumes `serverTeleported=true` without issuing
another teleport. A temporarily unavailable connection requests bounded retry.
Without this Java API, the mod retains its prior client placement fallback.
Single player remains local placement.

`GameServer.sendTeleport` sends the existing vanilla Teleport packet and resets
movement/anti-cheat state; its client handler exits a vehicle and applies the
coordinates. This requires only server patches, with no new packet format or
client Java patch. The client still performs native packet application.

## Verification

Passed:

- `python tools/test_player_login_origin.py`: previous-ID capture, same/different
  servers, malformed/unknown markers, preservation after tag mutation, thread and
  connection guards, and duplicate teleport suppression using engine fixtures.
- `python tools/test_postgres_players.py`: existing player/profile/database tests.
- `python -B tools/test_postgres_player_reconstruction.py`: 531 reconstruction
  checks, including structured component capture/restoration.
- `42.21.0/patchApocalipseBr.ps1 -DryRun`: full server source compilation against
  the configured game JAR; no deployment.
- Extraction mod: `tests/login-position.lua`, `tests/login-lobby-state.lua`,
  `tests/login-and-maps.lua`, `tests/lobby-perimeter.lua` using fengari.

Still required in game: successful relational checkpoint of the marker, A-to-B
and B-to-A transfers, same-server reconnect after checkpoint, unknown markers,
operation/closed-lobby rules, boat exits, terrain streaming, native movement and
anti-cheat acceptance. No live server or database was modified.
