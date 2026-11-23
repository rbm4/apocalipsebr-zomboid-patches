package cloud.apocalipse.pz.agent.features.telemetry;

import cloud.apocalipse.pz.agent.AgentModule;
import cloud.apocalipse.pz.agent.HookCheck;
import cloud.apocalipse.pz.agent.features.telemetry.OptimizationTelemetry.Phase;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import static net.bytebuddy.matcher.ElementMatchers.*;

/** Times original methods only while the dedicated server loop is running. */
public final class ServerPhaseTelemetryModule implements AgentModule {
    private record Target(Phase phase, String type, String method, String descriptor) {
        Target(Phase phase, String type, String method) { this(phase, type, method, "()V"); }
    }

    private static final List<Target> TARGETS = List.of(
        new Target(Phase.MAP_PRE, "zombie.network.ServerMap", "preupdate"),
        new Target(Phase.MAP_POST, "zombie.network.ServerMap", "postupdate"),
        new Target(Phase.MAP_SAVE, "zombie.network.ServerMap", "SaveAll"),
        new Target(Phase.MAP_CELL_LOAD, "zombie.network.ServerMap$ServerCell", "RecalcAll2"),
        new Target(Phase.MAP_CELL_UNLOAD, "zombie.network.ServerMap$ServerCell", "Unload"),
        new Target(Phase.MAP_CELL_UPDATE, "zombie.network.ServerMap$ServerCell", "update"),
        new Target(Phase.MAP_CELL_VEHICLES, "zombie.network.ServerMap$ServerCell", "loadVehicles"),
        new Target(Phase.RCON, "zombie.network.RCONServer", "update"),
        new Target(Phase.COLLISION_STATE, "zombie.MapCollisionData", "updateGameState"),
        new Target(Phase.SIMULATION, "zombie.gameStates.IngameState", "update", "()Lzombie/gameStates/GameStateMachine$StateAction;"),
        new Target(Phase.VEHICLES, "zombie.vehicles.VehicleManager", "serverUpdate"),
        new Target(Phase.DOWNLOADS, "zombie.network.PlayerDownloadServer", "update"),
        new Target(Phase.BACKUP, "zombie.core.backup.ZipBackup", "onPeriod"),
        new Target(Phase.TRADING, "zombie.core.TradingManager", "update"),
        new Target(Phase.WAR, "zombie.network.WarManager", "update"),
        new Target(Phase.SAFEHOUSE, "zombie.iso.areas.SafeHouse", "update"),
        new Target(Phase.NETWORK_PLAYERS, "zombie.network.NetworkPlayerManager", "update"),
        new Target(Phase.WORLD_MAP, "zombie.worldMap.WorldMapVisitedServer", "update"),
        new Target(Phase.ASYNC_FILES, "zombie.fileSystem.FileSystemImpl", "updateAsyncTransactions"),
        new Target(Phase.STEAM_PUMP, "zombie.core.znet.SteamUtils", "runLoop"),
        new Target(Phase.WORLD, "zombie.iso.IsoWorld", "update"),
        new Target(Phase.CELL, "zombie.iso.IsoCell", "update"),
        new Target(Phase.MOVING_SETUP, "zombie.MovingObjectUpdateScheduler", "startFrame"),
        new Target(Phase.MOVING, "zombie.MovingObjectUpdateScheduler", "update"),
        new Target(Phase.CONTACTS, "zombie.CollisionManager", "ResolveContacts"),
        new Target(Phase.WORLD_PHYSICS, "zombie.core.physics.WorldSimulation", "update"),
        new Target(Phase.BUILDINGS, "zombie.iso.IsoWorld", "updateBuildings"),
        new Target(Phase.WORLD_DATABASES, "zombie.iso.IsoWorld", "updateDBs"),
        new Target(Phase.WORLD_MAINTENANCE, "zombie.iso.IsoWorld", "updateThread"),
        new Target(Phase.ENTITY, "zombie.entity.GameEntityManager", "Update"),
        new Target(Phase.STATIC_UPDATERS, "zombie.iso.IsoCell", "ProcessStaticUpdaters"),
        new Target(Phase.CORPSES, "zombie.iso.objects.IsoDeadBody", "updateBodies"),
        new Target(Phase.SIM_MISC, "zombie.gameStates.IngameState", "UpdateStuff"),
        new Target(Phase.MANAGERS, "zombie.gameStates.IngameState", "updateManagers"),
        new Target(Phase.FIRE, "zombie.iso.objects.IsoFireManager", "Update"),
        new Target(Phase.WORLD_SOUNDS, "zombie.WorldSoundManager", "update"),
        new Target(Phase.SCRIPTS, "zombie.scripting.ScriptManager", "update"),
        new Target(Phase.VIRTUAL_ZOMBIES, "zombie.VirtualZombieManager", "update"),
        new Target(Phase.ZOMBIE_POPULATION, "zombie.popman.ZombiePopulationManager", "updateMain"),
        new Target(Phase.COLLISION_MAIN, "zombie.MapCollisionData", "updateMain"),
        new Target(Phase.LOOT, "zombie.LootRespawn", "update"),
        new Target(Phase.CLIMATE, "zombie.iso.weather.ClimateManager", "update"),
        new Target(Phase.MAP_SAVE_COMPLETIONS, "zombie.network.ServerChunkLoader", "updateSaved"),
        new Target(Phase.MOVING_POST, "zombie.MovingObjectUpdateScheduler", "postupdate"),
        new Target(Phase.SPOTTED_ROOMS, "zombie.iso.IsoCell", "ProcessSpottedRooms"),
        new Target(Phase.ITEMS, "zombie.iso.IsoCell", "ProcessItems", "(Ljava/util/Iterator;)V"),
        new Target(Phase.ISO_OBJECTS, "zombie.iso.IsoCell", "ProcessIsoObject"),
        new Target(Phase.GAME_TIME, "zombie.GameTime", "update", "(Z)V"),
        new Target(Phase.RAIN, "zombie.iso.objects.RainManager", "Update"),
        new Target(Phase.META, "zombie.meta.Meta", "update"),
        new Target(Phase.ANIMALS_SYNC, "zombie.popman.animal.AnimalController", "update"),
        new Target(Phase.RADIO, "zombie.radio.ZomboidRadio", "update"),
        new Target(Phase.NATIVE_PATH_MAIN, "zombie.pathfind.nativeCode.PathfindNative", "updateMain"),
        new Target(Phase.JAVA_PATH_MAIN, "zombie.pathfind.PolygonalMap2", "updateMain"),
        new Target(Phase.HUTCHES, "zombie.popman.animal.HutchManager", "updateAll"),
        new Target(Phase.FISHING, "zombie.iso.FishSchoolManager", "update")
    );

    @Override public String name() { return "telemetry-server-phases"; }

    @Override public List<HookCheck> hookChecks() {
        return TARGETS.stream().map(target -> new HookCheck(target.phase().name(), target.type(), target.method(),
            ServerPhaseTelemetryModule.class.getName(), "finishPhase", false)).toList();
    }

    @Override public AgentBuilder configure(AgentBuilder builder) {
        for (Target target : TARGETS) {
            builder = builder.type(named(target.type())).transform((dynamic, type, loader, module, domain) ->
                dynamic.visit(Advice.withCustomMapping().bind(PhaseIndex.class, target.phase().ordinal())
                    .to(Timing.class).on(named(target.method()).and(hasDescriptor(target.descriptor())))));
        }
        return builder;
    }

    @Retention(RetentionPolicy.RUNTIME)
    public @interface PhaseIndex {}

    public static final class Timing {
        @Advice.OnMethodEnter
        public static long enter() {
            return TickDiagnostics.activeOnCurrentThread() ? System.nanoTime() : 0;
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(@Advice.Enter long started, @PhaseIndex int phase) {
            if (started != 0) finishPhase(phase, started);
        }
    }

    private static final Phase[] PHASES = Phase.values();

    public static void finishPhase(int phase, long started) {
        PHASES[phase].recordSince(started);
    }
}
