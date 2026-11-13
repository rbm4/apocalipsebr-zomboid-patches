package zombie;

import java.util.AbstractSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import zombie.iso.IsoMovingObject;
import zombie.network.GameServer;

/** Preserves the exposed Set API while observing every successful lifecycle mutation. */
public final class ServerMovingObjectSet extends AbstractSet<IsoMovingObject> {
    private final HashMap<IsoMovingObject, ServerMovingObjectIndex.Member> members = new HashMap<>();
    private final ServerMovingObjectIndex index = new ServerMovingObjectIndex();

    public ServerMovingObjectIndex index() { return this.index; }
    public ServerMovingObjectIndex.Member member(IsoMovingObject object) { return this.members.get(object); }

    @Override public int size() { return this.members.size(); }
    @Override public boolean contains(Object object) { return this.members.containsKey(object); }

    @Override public boolean add(IsoMovingObject object) {
        Objects.requireNonNull(object, "moving object");
        if (this.members.containsKey(object)) return false;
        long started = GameServer.server ? System.nanoTime() : 0L;
        this.members.put(object, this.index.add(object));
        if (GameServer.server) {
            ApocBRServerTelemetryLite.count("movingObjects.classified", 1L);
            ApocBRServerTelemetryLite.recordPhase("simulation.movingObjects.classify", System.nanoTime() - started);
        }
        return true;
    }

    @Override public boolean remove(Object object) {
        ServerMovingObjectIndex.Member member = this.members.remove(object);
        if (member == null) return false;
        this.index.remove(member);
        return true;
    }

    @Override public Iterator<IsoMovingObject> iterator() {
        Iterator<Map.Entry<IsoMovingObject, ServerMovingObjectIndex.Member>> delegate = this.members.entrySet().iterator();
        return new Iterator<>() {
            private ServerMovingObjectIndex.Member current;
            @Override public boolean hasNext() { return delegate.hasNext(); }
            @Override public IsoMovingObject next() {
                this.current = delegate.next().getValue();
                return this.current.object;
            }
            @Override public void remove() {
                delegate.remove(); // Enforces Iterator.remove's normal state/fail-fast rules.
                ServerMovingObjectSet.this.index.remove(this.current);
            }
        };
    }

    // AbstractCollection/AbstractSet bulk operations use add/remove or iterator.remove,
    // so addAll, removeAll, retainAll, removeIf and clear all preserve index integrity.
}
