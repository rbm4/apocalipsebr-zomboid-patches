package zombie.entity;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import zombie.ApocBRServerTelemetryLite;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.network.GameServer;
import zombie.util.list.PZArrayList;

/** Registered server IsoObjects only. Registry boundary owns the manager map lock. */
public final class ServerIsoEntityRegistry {
    private static final class Entry {
        final IsoObject object;
        long id;
        Bucket bucket;
        Entry(IsoObject object, long id) { this.object = object; this.id = id; }
    }
    private static final class Bucket {
        final PZArrayList<IsoObject> list;
        final IdentityHashMap<Entry, Boolean> members = new IdentityHashMap<>();
        Bucket(PZArrayList<IsoObject> list) { this.list = list; }
    }
    private static final IdentityHashMap<IsoObject, Entry> entries = new IdentityHashMap<>();
    private static final IdentityHashMap<PZArrayList<IsoObject>, Bucket> buckets = new IdentityHashMap<>();
    private static final IdentityHashMap<Entry, Boolean> pending = new IdentityHashMap<>();
    private static final IdentityHashMap<Bucket, Boolean> dirty = new IdentityHashMap<>();
    private static final IdentityHashMap<Bucket, Boolean> exposed = new IdentityHashMap<>();
    private static final ThreadLocal<Boolean> refreshing = ThreadLocal.withInitial(() -> false);
    private static final ClassValue<Boolean> nativeIdentity = new ClassValue<>() {
        @Override protected Boolean computeValue(Class<?> type) {
            try { return type.getMethod("getEntityNetID").getDeclaringClass() == IsoObject.class; }
            catch (ReflectiveOperationException | SecurityException exception) { return false; }
        }
    };

    public static boolean isRefreshing() { return refreshing.get(); }

    public static synchronized void register(IsoObject object, long id) {
        if (!GameServer.server || !nativeIdentity.get(object.getClass())) return;
        unregister(object);
        Entry entry = new Entry(object, id);
        entries.put(object, entry);
        bind(entry);
    }

    private static void bind(Entry entry) {
        IsoGridSquare square = entry.object.getSquare();
        if (square != null) {
            PZArrayList<IsoObject> list = square.getObjects();
            entry.bucket = buckets.computeIfAbsent(list, Bucket::new);
            entry.bucket.members.put(entry, Boolean.TRUE);
            if (list.apocbrElementsExposed()) exposed.put(entry.bucket, Boolean.TRUE);
        }
    }

    private static void detach(Entry entry) {
        if (entry.bucket != null) {
            entry.bucket.members.remove(entry);
            if (entry.bucket.members.isEmpty()) {
                buckets.remove(entry.bucket.list);
                dirty.remove(entry.bucket);
                exposed.remove(entry.bucket);
            }
            entry.bucket = null;
        }
    }

    public static synchronized void unregister(IsoObject object) {
        Entry entry = entries.remove(object);
        if (entry != null) { detach(entry); pending.remove(entry); }
    }

    static synchronized boolean isDetached(IsoObject object) {
        Entry entry = entries.get(object);
        return entry != null && entry.id == -1L && object.getObjectIndex() == -1;
    }

    public static synchronized void squareChanged(IsoObject object) {
        Entry entry = entries.get(object);
        if (entry == null) return;
        detach(entry);
        bind(entry);
        pending.put(entry, Boolean.TRUE);
        object.apocbrInvalidateEntityNetID();
    }

    public static synchronized void listChanged(PZArrayList<?> list) {
        Bucket bucket = buckets.get(list);
        if (bucket != null) {
            dirty.put(bucket, Boolean.TRUE);
            if (!bucket.list.apocbrElementsExposed()) exposed.remove(bucket);
        }
    }

    public static synchronized void listExposed(PZArrayList<?> list) {
        Bucket bucket = buckets.get(list);
        if (bucket != null) exposed.put(bucket, Boolean.TRUE);
    }

    public static synchronized boolean queueChange(IsoObject object) {
        Entry entry = entries.get(object);
        if (entry == null) return false;
        pending.put(entry, Boolean.TRUE);
        return true;
    }

    public static synchronized void idChanged(IsoObject object, long id) {
        Entry entry = entries.get(object);
        if (entry != null) entry.id = id;
    }

    private static long currentID(IsoObject object) {
        int index = object.getObjectIndex();
        IsoGridSquare square = object.getSquare();
        return index == -1 || square == null ? -1L : ((long)(object.isFloor() ? 0 : index) << 40)
            + ((long)square.getZ() << 32) + ((long)square.getY() << 16) + square.getX();
    }

    /** Called only under GameEntityManager's map lock, never from a list mutation. */
    static synchronized void flush() {
        if (!GameServer.server || isRefreshing() || pending.isEmpty() && dirty.isEmpty() && exposed.isEmpty()) return;
        long started = System.nanoTime();
        long checked = 0, fallback = 0;
        // A retained raw array has no mutation notifications. Validate its registered
        // members at each lookup boundary, including cache hits (not just misses).
        IdentityHashMap<Bucket, Boolean> work = new IdentityHashMap<>(dirty);
        work.putAll(exposed);
        dirty.clear();
        try {
        for (Bucket bucket : work.keySet()) {
            boolean raw = bucket.list.apocbrElementsExposed();
            if (!raw) exposed.remove(bucket);
            for (Entry entry : bucket.members.keySet()) {
                checked++;
                if (raw) fallback++;
                IsoGridSquare square = entry.object.getSquare();
                if (currentID(entry.object) != entry.id || square == null || square.getObjects() != bucket.list) {
                    pending.put(entry, Boolean.TRUE);
                }
            }
        }
        } catch (RuntimeException | Error exception) {
            dirty.putAll(work);
            throw exception;
        }
        if (!pending.isEmpty()) {
            ArrayList<Entry> changed = new ArrayList<>(pending.keySet());
            pending.clear();
            long[] next = new long[changed.size()];
            refreshing.set(true);
            try {
                // Calculate all IDs first; suppress vanilla's incremental map writes.
                // Removing moving keys before insertion also handles swaps/cycles.
                for (int i = 0; i < changed.size(); i++) {
                    IsoObject object = changed.get(i).object;
                    object.apocbrInvalidateEntityNetID();
                    next[i] = object.getEntityNetID();
                }
                for (int i = 0; i < changed.size(); i++) {
                    Entry entry = changed.get(i);
                    if (entry.id != next[i]) GameEntityManager.removeIsoRegistryKey(entry.id, entry.object);
                }
                for (int i = 0; i < changed.size(); i++) {
                    Entry entry = changed.get(i);
                    if (next[i] != -1L) GameEntityManager.putIsoRegistryKey(next[i], entry.object);
                    entry.id = next[i];
                    IsoGridSquare square = entry.object.getSquare();
                    if (entry.bucket != null && (square == null || square.getObjects() != entry.bucket.list)) {
                        detach(entry);
                        bind(entry);
                    }
                }
            } catch (RuntimeException | Error exception) {
                for (Entry entry : changed) pending.put(entry, Boolean.TRUE);
                throw exception;
            } finally { refreshing.set(false); }
            ApocBRServerTelemetryLite.count("entities.registry.refreshed", changed.size());
        }
        ApocBRServerTelemetryLite.count("entities.registry.checked", checked);
        ApocBRServerTelemetryLite.count("entities.registry.rawArrayChecked", fallback);
        ApocBRServerTelemetryLite.recordPhase("entities.registry.reconcile", System.nanoTime() - started);
    }

    public static synchronized void reset() {
        entries.clear(); buckets.clear(); pending.clear(); dirty.clear(); exposed.clear(); refreshing.remove();
    }
}
