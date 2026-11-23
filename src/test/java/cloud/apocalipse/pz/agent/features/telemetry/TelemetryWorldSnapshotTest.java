package cloud.apocalipse.pz.agent.features.telemetry;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import zombie.iso.IsoWorld;
import zombie.network.GameServer;

class TelemetryWorldSnapshotTest {
    @Test
    void readsLoadedCurrentCellZombieCountAndOmitsUnavailableCell() {
        IsoWorld.instance.currentCell = new IsoWorld.Cell();
        IsoWorld.instance.currentCell.zombies.add(new Object());
        IsoWorld.instance.currentCell.zombies.add(new Object());

        assertEquals(2, TelemetryWorldSnapshot.activeZombieCount());

        IsoWorld.instance.currentCell = null;
        assertNull(TelemetryWorldSnapshot.activeZombieCount());
    }

    @Test
    void reportsTheLargerVanillaPlayerRegistrySize() {
        try {
            GameServer.Players.clear();
            GameServer.IDToPlayerMap.clear();
            GameServer.Players.add(new Object());
            GameServer.Players.add(new Object());
            GameServer.IDToPlayerMap.put((short) 1, new Object());
            GameServer.IDToPlayerMap.put((short) 2, new Object());
            GameServer.IDToPlayerMap.put((short) 3, new Object());

            assertEquals(3, TelemetryWorldSnapshot.onlinePlayers());

            GameServer.IDToPlayerMap.clear();
            assertEquals(2, TelemetryWorldSnapshot.onlinePlayers());
        } finally {
            GameServer.Players.clear();
            GameServer.IDToPlayerMap.clear();
        }
    }

    @Test
    void readsTheVanillaCoreGameVersion() {
        assertEquals("42.22", TelemetryWorldSnapshot.gameVersion());
    }
}
