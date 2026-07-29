// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.network.anticheats;

import zombie.core.raknet.UdpConnection;
import zombie.debug.DebugType;

public class AntiCheatChecksumUpdate extends AbstractAntiCheat {
    public static final long DIFFERENT_CHECKSUM_STATE_TIMEOUT = 8000L;

    private boolean isDifferentChecksumTimeoutExpired(UdpConnection connection) {
        if (connection.checksumState != UdpConnection.ChecksumState.Different) {
            return false;
        } else if (connection.checksumTime + 8000L >= System.currentTimeMillis()) {
            return false;
        } else {
            DebugType.Multiplayer.warn("Timed out connection because checksum was different");
            connection.checksumState = UdpConnection.ChecksumState.Init;
            return true;
        }
    }

    @Override
    public boolean update(UdpConnection connection) {
        super.update(connection);
        return !this.antiCheat.isEnabled() || !this.isDifferentChecksumTimeoutExpired(connection);
    }
}
