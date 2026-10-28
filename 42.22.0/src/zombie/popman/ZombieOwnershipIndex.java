package zombie.popman;

import java.util.AbstractSet;
import java.util.ConcurrentModificationException;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.NoSuchElementException;
import zombie.characters.IsoZombie;
import zombie.network.IConnection;

/** Ownership only; request queues retain their existing LinkedList/FIFO contract. */
final class ZombieOwnershipIndex {
    final Object lock = new Object();
    private final IdentityHashMap<IConnection, Group> groups = new IdentityHashMap<>();

    Group getNetworkZombie(IConnection connection) {
        if (connection == null) return null;
        synchronized (this.lock) { return this.groups.computeIfAbsent(connection, c -> new Group()); }
    }

    void release(IConnection connection) {
        synchronized (this.lock) {
            Group group = this.groups.get(connection);
            if (group != null && group.zombies.isEmpty()) this.groups.remove(connection);
        }
    }

    static final class Group {
        final OrderedIdentitySet zombies = new OrderedIdentitySet();
    }

    static final class OrderedIdentitySet extends AbstractSet<IsoZombie> {
        private static final class Node {
            final IsoZombie zombie;
            Node previous, next;
            Node(IsoZombie zombie) { this.zombie = zombie; }
        }
        private final IdentityHashMap<IsoZombie, Node> nodes = new IdentityHashMap<>();
        private Node first, last;
        private int version;

        @Override public int size() { return this.nodes.size(); }
        @Override public boolean contains(Object zombie) { return this.nodes.containsKey(zombie); }
        @Override public boolean add(IsoZombie zombie) {
            if (this.nodes.containsKey(zombie)) return false;
            Node node = new Node(zombie);
            node.previous = this.last;
            if (this.last == null) this.first = node; else this.last.next = node;
            this.last = node;
            this.nodes.put(zombie, node);
            this.version++;
            return true;
        }
        @Override public boolean remove(Object zombie) {
            Node node = this.nodes.remove(zombie);
            if (node == null) return false;
            if (node.previous == null) this.first = node.next; else node.previous.next = node.next;
            if (node.next == null) this.last = node.previous; else node.next.previous = node.previous;
            this.version++;
            return true;
        }
        @Override public void clear() {
            if (!this.nodes.isEmpty()) this.version++;
            this.nodes.clear();
            this.first = this.last = null;
        }
        @Override public Iterator<IsoZombie> iterator() {
            return new Iterator<>() {
                Node next = first, current;
                int expected = version;
                private void check() { if (this.expected != version) throw new ConcurrentModificationException(); }
                @Override public boolean hasNext() { this.check(); return this.next != null; }
                @Override public IsoZombie next() {
                    this.check();
                    if (this.next == null) throw new NoSuchElementException();
                    this.current = this.next;
                    this.next = this.next.next;
                    return this.current.zombie;
                }
                @Override public void remove() {
                    this.check();
                    if (this.current == null) throw new IllegalStateException();
                    OrderedIdentitySet.this.remove(this.current.zombie);
                    this.current = null;
                    this.expected = version;
                }
            };
        }
    }
}
