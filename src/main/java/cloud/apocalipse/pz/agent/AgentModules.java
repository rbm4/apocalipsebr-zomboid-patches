package cloud.apocalipse.pz.agent;

import cloud.apocalipse.pz.agent.features.postgres.PostgresModule;
import cloud.apocalipse.pz.agent.features.postgres.ServerShutdownModule;
import cloud.apocalipse.pz.agent.features.hotfixes.XmlCaseModule;
import cloud.apocalipse.pz.agent.features.npc.NpcModule;
import cloud.apocalipse.pz.agent.features.optimization.entity.EntityRemovalModule;
import cloud.apocalipse.pz.agent.features.optimization.entity.EntitySimulationModule;
import cloud.apocalipse.pz.agent.features.optimization.los.ServerLosModule;
import cloud.apocalipse.pz.agent.features.optimization.lua.LuaDispatchModule;
import cloud.apocalipse.pz.agent.features.optimization.player.proximity.PlayerNearVehicleModule;
import cloud.apocalipse.pz.agent.features.optimization.player.thermal.ThermalClothingModule;
import cloud.apocalipse.pz.agent.features.optimization.zombie.culling.ZombieCullingModule;
import cloud.apocalipse.pz.agent.features.optimization.zombie.packet.ZombiePacketPreparationModule;
import cloud.apocalipse.pz.agent.features.optimization.zombie.transmission.ZombieAsyncSendModule;
import cloud.apocalipse.pz.agent.features.pathfinding.JavaPathfindingModule;
import cloud.apocalipse.pz.agent.features.telemetry.TelemetryModule;
import cloud.apocalipse.pz.agent.features.telemetry.LuaCallbackTelemetryModule;
import cloud.apocalipse.pz.agent.features.telemetry.ServerPhaseTelemetryModule;
import cloud.apocalipse.pz.agent.features.workshop.WorkshopModule;
import cloud.apocalipse.pz.agent.features.workshop.AsyncWorkshopModule;
import java.util.List;

/** Single module registry shared by runtime installation and read-only hook diagnostics. */
public final class AgentModules {
    private static final List<AgentModule> MODULES = List.of(
        new XmlCaseModule(), new WorkshopModule(), new TelemetryModule(), new PostgresModule(), new cloud.apocalipse.pz.agent.features.postgres.dictionary.DictionaryModule(), new cloud.apocalipse.pz.agent.features.postgres.dictionary.DictionaryDecodeModule(), new NpcModule(), new cloud.apocalipse.pz.agent.features.lua.LuaModule(), new cloud.apocalipse.pz.agent.features.postgres.player.PlayerModule(), new cloud.apocalipse.pz.agent.features.lua.login.AsyncAccountsModule(),
        new ServerShutdownModule(), new AsyncWorkshopModule(), new LuaCallbackTelemetryModule(),
        new ServerPhaseTelemetryModule(), new JavaPathfindingModule(),
        new ZombieCullingModule(), new LuaDispatchModule(), new PlayerNearVehicleModule(),
        new ZombiePacketPreparationModule(), new ThermalClothingModule(),
        new EntityRemovalModule(), new EntitySimulationModule(), new ServerLosModule(), new ZombieAsyncSendModule());

    private AgentModules() { }

    public static List<AgentModule> all() { return MODULES; }
}
