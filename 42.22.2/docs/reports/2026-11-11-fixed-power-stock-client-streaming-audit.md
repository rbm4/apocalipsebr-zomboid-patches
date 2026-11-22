# Fixed power: stock-client chunk-streaming audit

Historical review of the rectangle implementation. The subsequent [managed native-generator implementation](../features/2026-11-11-fixed-power-regions.md) replaces that runtime path. Statements below about what is not implemented describe the pre-refactor review checkpoint.

Date: 2026-11-11. Target: exact 42.22.2 decompiled baseline and current server replacements. Scope: source review of server-to-client chunk serialization and stock client power interpretation; no live packet capture or gameplay test.

## Verdict

The current server fixed-power patch does **not** meet the requirement that an unpatched Java client recognize the registered rectangle as powered. It overrides Java getter results on the server, without changing data recognized by the stock client's chunk loader. There is no per-square electricity-on boolean in this baseline's square serialization. Re-streaming the current chunk does not transmit the server override.

The intended requirement is server-side implementation with unmodified client Java. Requiring a client patch is not an accepted solution. A compatible replacement must carry power through state that the stock client already understands; that replacement has not been implemented by the current region registry or persistence flow.

## Electricity is computed, not stored on the square

[IsoGridSquare.haveElectricity and deprecated setter](../../decompiled/zombie/iso/IsoGridSquare.java#L9983) show the distinction:

```java
return !SandboxOptions.getInstance().allowExteriorGenerator.getValue() && this.has(IsoFlagType.exterior)
    ? false
    : this.chunk != null && this.chunk.isGeneratorPoweringSquare(this.x, this.y, this.z);
```

The setter ignores its `haveElectricity` argument and updates light-switch objects. It assigns no power state. The current patched getter adds `ApocBRFixedPower.contains(x,y,z)` as an early return; the registry stores coordinate predicates in server JVM memory and does not mutate squares or chunk generator metadata.

[hasGridPower and its offset overload](../../decompiled/zombie/iso/IsoGridSquare.java#L12091) combine `!isNoPower()` with [SandboxOptions.doesPowerGridExist](../../decompiled/zombie/SandboxOptions.java#L1291), a world-age versus global shutoff setting comparison. The server patch overrides these getters too; the unchanged client still uses its original implementations. Room/zone restrictions can remove grid eligibility but do not restore grid electricity locally after global shutdown.

`haveElectricity()` also participates in square hashing ([source](../../decompiled/zombie/iso/IsoGridSquare.java#L4412)). A hash contribution is not serialization of the getter result and provides no client-side assignment of power.

## Complete chunk transport path

1. [PlayerDownloadServer.update](../../decompiled/zombie/network/PlayerDownloadServer.java#L237) finds an integrated server chunk and calls `SaveLoadedChunk` for a download request.
2. [IsoChunk.SaveLoadedChunk and Save](../../decompiled/zombie/iso/IsoChunk.java#L4393) produce the normal chunk bytes. Square data is written through [gs.save](../../decompiled/zombie/iso/IsoChunk.java#L4463).
3. [IsoGridSquare.save](../../decompiled/zombie/iso/IsoGridSquare.java#L2849) writes erosion, objects, overlay/roof state, bodies, Lua mod data, burnt/trap/sheet-rope data and visibility. Neither the registry nor `haveElectricity()`/`hasGridPower()` is serialized as a square power flag. [IsoGridSquare.load](../../decompiled/zombie/iso/IsoGridSquare.java#L3142) correspondingly restores no such flag. Storing an arbitrary value in square mod data does not make the stock native power getters consult it.
4. [PlayerDownloadServer.WorkerThread.sendChunk](../../decompiled/zombie/network/PlayerDownloadServer.java#L437) sends `SentChunk` data. [GameClient](../../decompiled/zombie/network/GameClient.java#L1581) dispatches it to `WorldStreamer.receiveChunkPart`.
5. [WorldStreamer.loadReceivedChunks](../../decompiled/zombie/iso/WorldStreamer.java#L213) decompresses received bytes and calls `DoChunk`, then [DoChunkAlways](../../decompiled/zombie/iso/WorldStreamer.java#L471) loads them. [IsoChunk.LoadOrCreate](../../decompiled/zombie/iso/IsoChunk.java#L2323) takes the received buffer into `LoadFromBuffer`.
6. The client then evaluates power locally. [IsoLightSwitch.hasElectricityAround](../../decompiled/zombie/iso/objects/IsoLightSwitch.java#L428) calls local grid/generator getters. [LightingJNI.checkLights](../../decompiled/zombie/iso/LightingJNI.java#L251) recomputes light availability from local power/switch state. Streaming an activated light-switch object alone therefore does not establish continuous native square electricity.

Unloaded/on-disk chunk downloads and cached chunk reuse do not add a missing field to this format. Adding bytes or repurposing unused square bits solely on the server cannot give a stock reader semantics it does not implement; format changes must not be assumed compatible.

## What the stock chunk format does carry

[IsoChunk.Save](../../decompiled/zombie/iso/IsoChunk.java#L4491) serializes `generatorsTouchingThisChunk` as a count followed by generator `(x,y,z)` coordinates. [Chunk load](../../decompiled/zombie/iso/IsoChunk.java#L3637) restores that list. [isGeneratorPoweringSquare](../../decompiled/zombie/iso/IsoChunk.java#L5129) evaluates each position through [IsoGenerator.isPoweringSquare](../../decompiled/zombie/iso/objects/IsoGenerator.java#L721).

This is a real vanilla-compatible carrier of local power, but it is generator-derived coverage, not an independent square power flag:

- Geometry is a radius and vertical range; it does not carry arbitrary rectangle bounds.
- `haveElectricity()` applies the client's exterior-generator setting.
- [checkForMissingGenerators](../../decompiled/zombie/iso/IsoChunk.java#L5144) removes a position when its loaded source square has no active generator. [IsoGenerator.chunkLoaded](../../decompiled/zombie/iso/objects/IsoGenerator.java#L665), invoked during [chunk integration](../../decompiled/zombie/iso/IsoChunk.java#L3878), performs those checks. Synthetic positions without backing active objects are not a validated durable solution.
- Normal [generator synchronization](../../decompiled/zombie/iso/objects/IsoGenerator.java#L598) sends actual object fuel, condition, activation and connection state; receiving it recomputes surrounding electricity. The current fixed-region API does not produce those objects or packets.

A server-managed stock-object solution could be investigated separately, but its footprint, effects outside exact boundaries, object lifecycle and generator side effects would need explicit design and validation. This review does not establish that an exact arbitrary rectangle, without generator semantics and without client changes, is expressible by the native protocol.

## Consequence for the implemented feature

[The region API guide](../features/2026-11-11-fixed-power-regions.md), backend table, scoped entity query and startup policy remain server-side building blocks. They persist intent and change server simulation checks. They do not currently deliver a region power state to a stock client. The previous description of this as merely a possible client limitation was too weak: the requested streamed flag is absent in this baseline.

No runtime source was changed for this review. No compilation or gameplay result is claimed. Verification consisted of tracing both serializers/readers, packet dispatch, chunk integration, client power consumers and the patched getters in the same target version. A stock-client-compatible presentation/power mechanism remains an unresolved implementation requirement.

## Follow-up: managed native generators are feasible in principle

The normal client generator path remains available after global grid shutoff; shutdown of the grid does not itself disable generator electricity. A server-created **stock `IsoGenerator` object**, backed by a real loaded source square, is a plausible client-compatible carrier. It must serialize as the native generator type, not as an unknown custom subclass.

Evidence in the same baseline:

- [The item/cell/square constructor](../../decompiled/zombie/iso/objects/IsoGenerator.java#L101) adds a normal special object and transmits it to clients on the server. [AddItemToMapPacket.processClient](../../decompiled/zombie/network/packets/AddItemToMapPacket.java#L54) integrates received objects into the client world.
- [Generator load/save](../../decompiled/zombie/iso/objects/IsoGenerator.java#L432) preserves connected, activated, fuel, condition and last-hour state. The client can therefore load the same object in a later chunk stream or reconnect.
- [Generator sync](../../decompiled/zombie/iso/objects/IsoGenerator.java#L598) supplies activation/fuel/condition/connection to already connected clients and recomputes coverage on activation change.
- [update](../../decompiled/zombie/iso/objects/IsoGenerator.java#L164) returns early on clients, before fuel/condition consumption. A server patch can bypass depletion for its explicitly tagged managed objects while keeping real fuel/condition values at native maxima. [setFuel](../../decompiled/zombie/iso/objects/IsoGenerator.java#L513) clamps fuel to 0..10 and load clamps it too; numeric infinity is neither needed nor an appropriate representation.
- Fuel alone is insufficient: server update also consumes condition, shuts the object off, and can cause backfire/fire/explosion at low condition. Managed objects would require scoped no-consumption/no-wear behavior, not periodic refills that can miss a large game-time step.

This approach still has native coverage geometry: radius and vertical range come from sandbox settings, shared by client and server. It may need multiple sources, can spill beyond the requested rectangle and remains subject to the exterior-generator option. Exact arbitrary rectangle clipping is not established by this design.

A complete implementation must own stable tagged source identities, placement on valid loaded squares, creation/reconciliation on streaming, persistence, disabling/removal, normal object synchronization and protection against player shutoff, pickup, removal or changes received through generator synchronization. It must explicitly decide how to handle native client sound/rendering and server world sounds/indoor toxicity. A server-only patch cannot simply assume that bypassing consumption also suppresses client sound or generator presentation.

This follow-up establishes feasibility from source, not a shipped managed-generator implementation or live-client test. Current logs still report `client_stream=false`; switching that claim requires an implemented native object lifecycle and tests with stock client Java.
