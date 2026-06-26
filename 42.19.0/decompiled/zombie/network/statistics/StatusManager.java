// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.network.statistics;

import java.text.NumberFormat;
import java.util.concurrent.TimeUnit;
import se.krka.kahlua.vm.KahluaTable;
import zombie.GameTime;
import zombie.Lua.LuaManager;
import zombie.characters.IsoPlayer;
import zombie.core.Core;
import zombie.network.GameClient;

public class StatusManager {
    private static final StatusManager instance = new StatusManager();
    private static final KahluaTable statusTable = LuaManager.platform.newTable();

    public static StatusManager getInstance() {
        return instance;
    }

    public KahluaTable getTable() {
        statusTable.wipe();
        if (GameClient.client) {
            statusTable.rawset("serverTime", NumberFormat.getNumberInstance().format(TimeUnit.NANOSECONDS.toSeconds(GameTime.getServerTime())));
            statusTable.rawset("svnRevision", "1aa820d7bb66c4e55513cae04022bdacdac5b34e");
            statusTable.rawset("buildDate", "2026-06-01");
            statusTable.rawset("buildTime", "09:40:02");
            statusTable.rawset(
                "position",
                String.format("( %.3f ; %.3f ; %.3f )", IsoPlayer.getInstance().getX(), IsoPlayer.getInstance().getY(), IsoPlayer.getInstance().getZ())
            );
            statusTable.rawset("version", Core.getInstance().getVersion());
            statusTable.rawset("lastPing", String.format("%03d", PingManager.getPing()));
        }

        return statusTable;
    }
}
