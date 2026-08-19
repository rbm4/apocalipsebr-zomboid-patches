// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.popman;

import java.util.IdentityHashMap;
import java.util.LinkedList;
import zombie.characters.IsoZombie;
import zombie.network.IConnection;

public class NetworkZombieList {
    // ApocBR: was a LinkedList<NetworkZombie> scanned linearly by connection identity
    // in getNetworkZombie() every tick per connection - O(connections) per call. Connection
    // identity (IConnection, e.g. UdpConnection) has no equals()/hashCode() override, so an
    // IdentityHashMap keyed by connection gives the exact same lookup semantics (reference
    // equality) in O(1) instead of O(n).
    final IdentityHashMap<IConnection, NetworkZombieList.NetworkZombie> networkZombies = new IdentityHashMap<>();
    public Object lock = new Object();

    public NetworkZombieList.NetworkZombie getNetworkZombie(IConnection connection) {
        if (connection == null) {
            return null;
        } else {
            NetworkZombieList.NetworkZombie nz = this.networkZombies.get(connection);
            if (nz != null) {
                return nz;
            }

            NetworkZombieList.NetworkZombie nzx = new NetworkZombieList.NetworkZombie(connection);
            this.networkZombies.put(connection, nzx);
            return nzx;
        }
    }

    public static class NetworkZombie {
        // Keep this field's erased type as LinkedList for binary compatibility with vanilla
        // NetworkZombieManager/NetworkZombiePacker classes that are not patched in this pack.
        public final LinkedList<IsoZombie> zombies = new LinkedList<>();
        final IConnection connection;

        public NetworkZombie(IConnection connection) {
            this.connection = connection;
        }
    }
}
