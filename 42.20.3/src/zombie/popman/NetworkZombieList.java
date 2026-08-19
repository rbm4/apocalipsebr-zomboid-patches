// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.popman;

import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedList;
import java.util.function.Predicate;
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
            synchronized (this.lock) {
                NetworkZombieList.NetworkZombie nz = this.networkZombies.get(connection);
                if (nz != null) {
                    return nz;
                }

                NetworkZombieList.NetworkZombie nzx = new NetworkZombieList.NetworkZombie(connection);
                this.networkZombies.put(connection, nzx);
                return nzx;
            }
        }
    }

    public static class NetworkZombie {
        // Keep this field's erased type as LinkedList for binary compatibility with vanilla
        // NetworkZombieManager/NetworkZombiePacker classes that are not patched in this pack.
        // ApocBR: SynchronousLinkedList wraps all mutating methods in synchronized blocks so
        // ZombieRequestPacket (network receive) can add while NetworkZombiePacker drains.
        public final LinkedList<IsoZombie> zombies = new SynchronousLinkedList<>();
        final IConnection connection;

        public NetworkZombie(IConnection connection) {
            this.connection = connection;
        }
    }

    // ApocBR: LinkedList subclass that synchronizes all methods used by concurrent producers
    // (ZombieRequestPacket) and consumers (NetworkZombiePacker snapshot), while keeping the
    // field's erased type as LinkedList for binary compatibility.
    private static class SynchronousLinkedList<E> extends LinkedList<E> {
        @Override
        public synchronized boolean add(E e) {
            return super.add(e);
        }

        @Override
        public synchronized void add(int index, E element) {
            super.add(index, element);
        }

        @Override
        public synchronized boolean addAll(Collection<? extends E> c) {
            return super.addAll(c);
        }

        @Override
        public synchronized E remove(int index) {
            return super.remove(index);
        }

        @Override
        public synchronized boolean remove(Object o) {
            return super.remove(o);
        }

        @Override
        public synchronized boolean removeIf(Predicate<? super E> filter) {
            return super.removeIf(filter);
        }

        @Override
        public synchronized E poll() {
            return super.poll();
        }

        @Override
        public synchronized void clear() {
            super.clear();
        }

        @Override
        public synchronized boolean contains(Object o) {
            return super.contains(o);
        }

        @Override
        public synchronized boolean isEmpty() {
            return super.isEmpty();
        }
    }
}
