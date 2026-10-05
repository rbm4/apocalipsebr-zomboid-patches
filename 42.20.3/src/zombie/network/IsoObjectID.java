// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.network;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;
import zombie.core.random.Rand;

public class IsoObjectID<T> implements Iterable<T> {
    public static final short incorrect = -1;
    private final ConcurrentHashMap<Short, T> idToObjectMap;
    private final String objectType;
    private short nextId;
    // ApocBR: thread-local scratch list, see asList().
    private final ThreadLocal<ArrayList<T>> tempTL = ThreadLocal.withInitial(ArrayList::new);

    public IsoObjectID(Class<T> cls) {
        this.idToObjectMap = new ConcurrentHashMap<>();
        this.nextId = (short)Rand.Next(32766);
        this.objectType = cls.getSimpleName();
    }

    public void put(short id, T obj) {
        if (id != -1) {
            this.idToObjectMap.put(id, obj);
        }
    }

    public void remove(short id) {
        this.idToObjectMap.remove(id);
    }

    public void remove(T obj) {
        this.idToObjectMap.values().remove(obj);
    }

    public T get(short id) {
        return this.idToObjectMap.get(id);
    }

    public int size() {
        return this.idToObjectMap.size();
    }

    public void clear() {
        this.idToObjectMap.clear();
    }

    // ApocBR: nextId++ was a non-atomic read-modify-write. With parallel chunk loading
    // (ServerChunkLoader.LOAD_WORKERS) IsoAnimal.load() -> init() allocates online IDs from
    // several threads, so two objects could receive the same ID.
    public synchronized short allocateID() {
        this.nextId++;
        if (this.nextId == -1) {
            this.nextId++;
        }

        return this.nextId;
    }

    @Override
    public Iterator<T> iterator() {
        return this.idToObjectMap.values().iterator();
    }

    public void getObjects(Collection<T> out) {
        out.addAll(this.idToObjectMap.values());
    }

    // ApocBR: the returned list was a single shared instance, so two threads calling asList()
    // concurrently would corrupt each other's result.
    public ArrayList<T> asList() {
        ArrayList<T> out = this.tempTL.get();
        out.clear();
        out.addAll(this.idToObjectMap.values());
        return out;
    }
}
