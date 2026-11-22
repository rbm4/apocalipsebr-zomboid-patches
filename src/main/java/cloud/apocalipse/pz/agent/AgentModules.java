package cloud.apocalipse.pz.agent;

import cloud.apocalipse.pz.agent.features.postgres.PostgresModule;
import cloud.apocalipse.pz.agent.features.postgres.ServerShutdownModule;
import cloud.apocalipse.pz.agent.features.npc.NpcModule;
import cloud.apocalipse.pz.agent.features.optimization.lua.LuaDispatchModule;
import cloud.apocalipse.pz.agent.features.optimization.player.proximity.PlayerNearVehicleModule;
import cloud.apocalipse.pz.agent.features.optimization.player.thermal.ThermalClothingModule;
import cloud.apocalipse.pz.agent.features.optimization.zombie.culling.ZombieCullingModule;
import cloud.apocalipse.pz.agent.features.optimization.zombie.packet.ZombiePacketPreparationModule;
import cloud.apocalipse.pz.agent.features.pathfinding.JavaPathfindingModule;
import cloud.apocalipse.pz.agent.features.telemetry.TelemetryModule;
import cloud.apocalipse.pz.agent.features.workshop.WorkshopModule;
import java.util.List;

/** Single module registry shared by runtime installation and read-only hook diagnostics. */
public final class AgentModules {
    private static final List<AgentModule> MODULES = List.of(
        new WorkshopModule(), new TelemetryModule(), new PostgresModule(), new cloud.apocalipse.pz.agent.features.postgres.dictionary.DictionaryModule(), new cloud.apocalipse.pz.agent.features.postgres.dictionary.DictionaryDecodeModule(), new NpcModule(), new cloud.apocalipse.pz.agent.features.lua.LuaModule(), new cloud.apocalipse.pz.agent.features.postgres.player.PlayerModule(), new cloud.apocalipse.pz.agent.features.lua.login.AsyncAccountsModule(),
        new ServerShutdownModule(), new JavaPathfindingModule(),
        new ZombieCullingModule(), new LuaDispatchModule(), new PlayerNearVehicleModule(),
        new ZombiePacketPreparationModule(), new ThermalClothingModule());

    private AgentModules() { }

    public static List<AgentModule> all() { return MODULES; }
}
