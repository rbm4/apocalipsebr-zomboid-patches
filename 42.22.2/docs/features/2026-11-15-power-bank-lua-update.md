# Power-bank server subclass and Lua update callback

Created 2026-11-15. Target 42.22.2 dedicated server. Status implemented foundation with isolated checks, gameplay verification pending.

## Native compatibility

`src/zombie/apocbr/power/IsoPowerBank.java` extends `IsoGenerator`. It inherits native object name `IsoGenerator`, class ID 22, save layout and outgoing generator-sync serialization. Its only load extension restores the generic ModData marker `ApocBRPowerBank`. Clients require the ISA Lua/content mod and retain stock Java classes.

`src/zombie/iso/IsoObject.java` selects the subclass in the ByteBuffer object factory only when generator class ID 22 has the serialized base-bank sprite `solarmod_tileset_01_0`. It peeks at the native sprite ID without advancing the payload. This runs before object deserialization, so bank inventory and native fields load once into the correct instance. The decoder uses no Lua callbacks or world mutation during worker-thread deserialization. Ordinary generator sprites retain their factory behavior. Arbitrarily reskinning a bank's base sprite would need a corresponding reconstruction rule.

`src/zombie/Lua/LuaManager.java` exposes the subclass and registers `Hook.OnPowerBankUpdate` on the server. The companion mod creates/marks new objects through its existing construction and conversion paths. No new network discriminator or extra generator save trailer is introduced.

## Callback contract

`IsoPowerBank.update()` services native dirty electricity, then invokes `Hook.OnPowerBankUpdate(generator, nowHours)` when its game-time deadline is due. Lua reads/writes generic ModData and calls `applyPowerState(supply, fraction)` to project battery energy onto fuel 0..10. A successful callback calls `completeUpdate(processedHour, intervalHours)` with finite monotonic game time no later than now, and an interval in (0,24] hours.

Incomplete historical work can acknowledge an older boundary, leaving the next deadline due. Missing/erroring handlers suspend supply, warn once and retry after one game minute. A reentrancy guard covers the callback. All callback and application paths run synchronously on the native server update thread. They provide no worker-thread API.

The subclass replaces gasoline update behavior. It avoids fuel depletion, combustion wear/failure, world sound and building toxicity writes. `setActivated` preserves loaded-food aging under preceding coverage, applies new coverage and sends native synchronization on a transition. `applyPowerState` sends native state only when projected fuel, condition, connection or activation changes. Inbound native generator synchronization is consumed and corrected from authoritative server state.

The existing generator patch gains two protected final lifecycle helpers, `updateElectricityIfNeeded` and `updateFridgeFreezerItems`. Ordinary and managed generators retain their existing behavior. The new bank has no dependency on the permanent-source policy of `ApocBRManagedGenerators`.

## Companion simulation and scope

ISA selects native mode by the exposed server capability. Its server hook advances one bank from shared ten-minute production samples and a persistent game-time boundary. The old full-registry charge callback and server toxicity tick loop are disabled in this mode. A callback processes at most 128 history segments. History retention and a global per-frame budget remain pending. Current formulas approximate cached demand/configuration during absence, and client presentation/daily maintenance remain separate.

Vanilla historical food catch-up starts in `decompiled/zombie/inventory/types/Food.java`, `updateAge(boolean)` at line 744, and calls `updateFreezing` before age integration. This phase preserves loaded-food activation ordering through the protected generator helper. It does not replace Food or implement historical outage replay. A subsequent scoped food hook requires a relevance index, completed power-transition history and an explicit result contract. A generic event's boolean result only indicates registered callbacks, so the generator hook instead requires explicit acknowledgement.

See [the companion feature](../../../../ApocalipseBR-ISA/.docs/features/F-002-native-power-bank-update.md) for state, lifecycle and outstanding cases.

## Validation

`py tools/test_power_bank_generator.py` compiles the production subclass with engine doubles and checks server-only payload discrimination without byte consumption, marker restoration, scheduling, reentrancy, fuel projection, change-only sync, packet rejection, unload exclusion and callback failure/recovery. The fixture does not run native world streaming or RakNet.

The companion Lua 5.1 tests exercise production simulation and conversion helpers. Exact-JAR BuildOnly compiles every target patched source without deployment. Dedicated-server/stock-client construction, save/reload, restarts, late join, coexistence with combustion generators, time transitions and performance captures remain unverified.

Final verification on 2026-11-15 passed with `patchApocalipseBr.ps1 -BuildOnly`, javac 25.0.4 and the installed 42.22.2 JAR. All 374 target Java sources compiled into 1067 classes. Retained output is `../../artifacts/build_20261115_194224`. The production subclass fixture passed. All 11 companion Lua tests passed and the ISA PZ Studio build passed. No game installation deployment was performed.
