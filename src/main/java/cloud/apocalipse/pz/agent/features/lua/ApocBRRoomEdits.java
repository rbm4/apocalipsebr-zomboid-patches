package cloud.apocalipse.pz.agent.features.lua;

import cloud.apocalipse.pz.agent.config.AgentConfiguration;
import cloud.apocalipse.pz.agent.features.optimization.building.ScopedRoomCommit;
import zombie.buildingRooms.BuildingRoomsEditor;

/** Explicit opt-in for Lua mods committing edits to an existing registered room. */
public final class ApocBRRoomEdits {
    private ApocBRRoomEdits() { }

    public static boolean tryApply(BuildingRoomsEditor editor, int x, int y, int z) {
        return AgentConfiguration.scopedRoomCommitEnabled() && LuaRuntime.isServerLuaThread()
            && ScopedRoomCommit.tryApply(editor, x, y, z);
    }
}
