# PlayerID -1 Packet Audit

Date: 2026-09-07

## Root Cause

`zombie.network.fields.character.PlayerID.parsePlayer()` resolves client packets on the server like this:

- If `playerIndex != -1`, use `GameServer.getPlayerFromConnection(connection, playerIndex)`.
- If `playerIndex == -1`, fall back to `GameServer.IDToPlayerMap.get(onlineId)`.

That fallback is correct for some server/client and remote-player references, but it is unsafe for client-originated packets where the `PlayerID` is the actor being mutated. A modified client can send `playerIndex = -1` plus another player's `onlineId` and make the packet resolve to a victim.

## Patched In This Pass

- `src/zombie/network/packets/character/PlayerDamagePacket.java`
  - Already rejected unowned client-originated damage updates.
  - Now also logs the claimed max-weight value when a spoofed packet is rejected.
  - Payload carries `maxWeight`, `corpseSicknessRate`, and full `BodyDamage`.

- `src/zombie/network/packets/character/PlayerStatsPacket.java`
  - Added overlay patch.
  - Rejects server-side packets unless the resolved player is exactly one of the sending connection's player slots and `connection.hasPlayer(onlineId)` is true.
  - Payload carries stats, full nutrition/body weight, time since last smoke, and main body-damage fields.

- `src/zombie/network/packets/SyncPlayerStatsPacket.java`
  - Added overlay patch.
  - Rejects server-side packets unless the resolved player is owned by the sending connection.
  - `syncParams == -1` carries full nutrition, including body weight.
  - Logs `syncParams` and whether the packet attempted to send a nutrition payload.

## High-Risk Unpatched Candidates

These vanilla packets also use `PlayerID` as an actor-like mutable target and should get the same connection-owner guard if they are accepted from clients in this build:

- `VariableSyncPacket`
  - Sets arbitrary variables on the resolved player and rebroadcasts them.
  - Abuse impact: forced animation/state variables or client-visible state pollution on another player.

- `SyncPlayerFieldsPacket`
  - Can mutate recipes, traits, already-read books, main body-damage fields, reading state, and fitness.
  - Abuse impact: character-state corruption or unauthorized progression/state edits.

- `PlayerEffectsPacket`
  - Mutates medication/effect timers: sleeping tablets, beta blockers, depression, pain.
  - Abuse impact: forced player effect state changes.

- `EatFoodPacket`
  - Loads nutrition for the resolved player during parse.
  - Abuse impact: possible nutrition/body-weight mutation if the packet is client-accepted server-side.

- `SyncItemActivatedPacket`
  - Mutates activated item state and uses for the resolved player's inventory item.
  - Abuse impact: remote inventory/item state manipulation. There is already a `src` overlay, but it only checks consistency, not ownership.

- `RequestItemsForContainerPacket` / `ContainerID`
  - Uses a top-level requester `PlayerID`, and `ContainerID` can also resolve `PlayerInventory` and `InventoryContainer` through `PlayerID`.
  - Abuse impact: information or loot-generation side effects if a spoofed player is used as the fill/exploration actor.

## Legitimate Remote-Player Flows To Treat Carefully

Do not blindly require ownership for every `PlayerID` in these packets; some fields are intended to name another player:

- `BodyDamageUpdatePacket`
  - `currentPlayer` should be owned by the connection.
  - `remotePlayer` is intentionally the target being observed.

- `RequestMedicalCheckPacket`
  - `requester` should be owned by the connection.
  - `target` is intentionally another player.

- `RequestTradingPacket` and `TradingUI*Packet`
  - One participant should be owned by the connection; the other can be remote.

- Vehicle packets such as `VehicleEnterPacket`, `VehicleExitPacket`, `VehicleSwitchSeatPacket`, and `VehicleCollidePacket`
  - The acting/passenger player should be connection-owned, but seat occupant or vehicle references can legitimately point elsewhere depending on the packet.

## Safer Patch Pattern

For packets where the `PlayerID` represents the actor being changed by a client packet, reject before applying any payload:

```java
private boolean isOwnedByConnection(IConnection connection, IsoPlayer player) {
    if (connection == null || player == null) {
        return false;
    }

    byte index = playerId.getPlayerIndex();
    if (index < 0 || index >= 4) {
        return false;
    }

    return GameServer.getPlayerFromConnection(connection, index) == player && connection.hasPlayer(player.getOnlineID());
}
```

This deliberately treats `playerIndex = -1` as invalid for actor-mutation packets while preserving remote-player references in observer/request packets.
