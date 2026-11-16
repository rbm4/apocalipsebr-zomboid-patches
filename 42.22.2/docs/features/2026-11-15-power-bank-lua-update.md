# Power-bank server subclass and Lua update callback

Created 2026-11-15. Target 42.22.2 dedicated server. Status implemented foundation with isolated checks, gameplay verification pending.

Updated 2026-11-15: the companion [F-003](../../../../ApocalipseBR-ISA/.docs/features/F-003-budgeted-energy-and-backup-history.md) implements aggregate scheduling, bounded history and exclusive backup fuel accounting. Its contract below supersedes the original immediate callback/128-segment foundation described in the historical sections.

## Current budgeted contract and backup bridge

The due `OnPowerBankUpdate` callback calls `deferUpdate()` and transfers work to the companion's owning-thread queue. Deferred banks keep their last physical supply and stop redispatching each native frame. Queue service calls `prepareSimulationUpdate()` for native dirty coverage, then `checkpointUpdate(hour)` for partial work or `completeUpdate(hour, interval)` at completion. Both persist monotonic game-time checkpoints; only completion releases deferral and sets the next ordinary deadline. Failed queue work uses `failScheduledUpdate()` to suspend supply and retry after a game minute. No worker thread receives live objects.

The companion limits each tick to eight jobs/256 state-machine steps with 32 steps per job and a soft two-millisecond elapsed guard. Physical coverage, battery/container finalization and native food activation lifecycle remain synchronous and non-preemptible. These limits are not live-server performance measurements. Intermediate bank checkpoints do not broadcast; completed state is coalesced. The user-facing Update request is queued as well.

`PowerBankBackup`, exposed only on the server, enables after companion hooks register. It persists an exclusive bank-coordinate owner in source ModData, rejects banks/permanent ApocBR managed sources as backups, and rejects a second owner. Normal generators retain native accounting when the bridge is disabled or no owner is present. An owned loaded source calls `OnPowerBankBackupUpdate(source, owner, now)` at most every ten game minutes, allowing the same queue to service the owner's persistent global-object state while its bank chunk is unloaded. An invalid/removed association releases ownership.

`IsoGenerator` skips its own fuel/wear deduction only for controlled backups. The companion integrates fuel exhaustion, running-hour wear rolls and hysteretic failsafe switching chronologically, then commits final native fields with `applyISABackupState`. That server-only method clamps/validates values, advances the native lastHour clock, calls native activation only on a real change and syncs once for changed state. Native source sound, toxicity, backfire/fire/explosion and electricity refresh code remains present. Historical environmental fire/explosion replay is not introduced. Consumption uses a continuous cached-rate approximation; see F-003 for observation, retention and accuracy policies.

Production history retains 4,096 change records with exact adjacent-rate compaction and incremental legacy pruning. A bank older than retained history explicitly loses unverifiable stored charge at the retained boundary, rather than replaying arbitrary old samples. Bank-local effective supply history retains 256 transitions for future food work; it is not yet an appliance timeline including other overlapping generators. Clients continue to receive native generator IDs, fields and packet formats and need no Java patch. Deploy this Java patch and its matching companion Lua together.

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

Subsequent budget/backup implementation verification on 2026-11-15: the expanded production fixture passed deferred acknowledgement/checkpoint/recovery, exclusive ownership and the extracted production backup-state commit method. Exact-JAR BuildOnly passed with 375 Java files/1,068 classes, retained at `../../artifacts/build_20261115_214211`. The companion suite passed 36 tests, all 35 Lua files passed syntax compilation, and packaging passed. No deployment or live timing/streaming validation was performed.
