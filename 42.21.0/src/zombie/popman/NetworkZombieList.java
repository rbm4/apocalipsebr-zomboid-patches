// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.popman;

import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedList;
import java.util.function.Predicate;
import zombie.characters.IsoZombie;
import zombie.network.IConnection;

public class NetworkZombieList {
    final LinkedList<NetworkZombieList.NetworkZombie> networkZombies = new LinkedList<>();
    private final IdentityHashMap<IConnection, NetworkZombieList.NetworkZombie> networkZombiesByConnection = new IdentityHashMap<>();
    public Object lock = new Object();

    public NetworkZombieList.NetworkZombie getNetworkZombie(IConnection connection) {
        if (connection == null) {
            return null;
        }

        synchronized (this.lock) {
            NetworkZombieList.NetworkZombie existing = this.networkZombiesByConnection.get(connection);
            if (existing != null) {
                return existing;
            }

            NetworkZombieList.NetworkZombie created = new NetworkZombieList.NetworkZombie(connection);
            this.networkZombies.add(created);
            this.networkZombiesByConnection.put(connection, created);
            return created;
        }
    }

    public static class NetworkZombie {
        public final LinkedList<IsoZombie> zombies = new SynchronousLinkedList<>();
        final IConnection connection;

        public NetworkZombie(IConnection connection) {
            this.connection = connection;
        }
    }

    private static class SynchronousLinkedList<E> extends LinkedList<E> {
        @Override
        public synchronized boolean add(E element) {
            return super.add(element);
        }

        @Override
        public synchronized boolean addAll(Collection<? extends E> collection) {
            return super.addAll(collection);
        }

        @Override
        public synchronized boolean remove(Object element) {
            return super.remove(element);
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
        public synchronized boolean contains(Object element) {
            return super.contains(element);
        }

        @Override
        public synchronized boolean isEmpty() {
            return super.isEmpty();
        }
    }
}
