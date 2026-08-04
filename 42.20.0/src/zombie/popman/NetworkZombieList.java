// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.popman;

import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
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
        // ApocBR: was a LinkedList<IsoZombie> - contains()/remove() calls against it (see
        // NetworkZombiePacker.postupdate() and NetworkZombieManager) are O(n) per call, and
        // NetworkZombiePacker calls contains() once per zombie in the auth delta per connection
        // per tick, i.e. O(m*n). IsoZombie has no equals()/hashCode() override (identity
        // semantics), so a LinkedHashSet preserves the exact same add/remove/contains/iteration
        // behavior (including insertion-order iteration) while making contains()/remove() O(1).
        public final LinkedHashSet<IsoZombie> zombies = new LinkedHashSet<>();
        final IConnection connection;

        public NetworkZombie(IConnection connection) {
            this.connection = connection;
        }
    }
}
