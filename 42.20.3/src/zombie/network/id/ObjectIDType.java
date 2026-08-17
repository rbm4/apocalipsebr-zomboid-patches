// ApocBR patch: added an explicit free-id queue (freeIds) so ObjectIDManager can reuse a
// released id in O(1) instead of blindly probing idToObjectMap.get(id) in a loop. No field
// used for network/save serialization was touched - freeIds is purely an in-memory runtime
// bookkeeping structure, never written to disk or the wire.
package zombie.network.id;

import astar.datastructures.HashPriorityQueue;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;

public enum ObjectIDType {
    Unknown(-1, false, ObjectID.ObjectIDShort.class),
    Player(0, false, ObjectID.ObjectIDShort.class),
    Zombie(1, false, ObjectID.ObjectIDShort.class),
    Item(2, true, ObjectID.ObjectIDInteger.class),
    Container(3, true, ObjectID.ObjectIDInteger.class),
    DeadBody(4, true, ObjectID.ObjectIDShort.class),
    Vehicle(5, true, ObjectID.ObjectIDShort.class);

    static byte permanentObjectIDTypes;
    private static final HashMap<Byte, ObjectIDType> objectIDTypes = new HashMap<>();
    final HashPriorityQueue<Long, IIdentifiable> idToObjectMap = new HashPriorityQueue<>(Comparator.comparingLong(o -> o.getObjectID().getObjectID()));
    final Deque<Long> freeIds = new ArrayDeque<>();
    final byte index;
    final boolean isPermanent;
    final Class<?> type;
    long lastId;
    long countNewId;

    private ObjectIDType(final int index, final boolean isPermanent, final Class<?> type) {
        this.index = (byte)index;
        this.isPermanent = isPermanent;
        this.type = type;
    }

    static ObjectIDType valueOf(byte index) {
        return objectIDTypes.getOrDefault(index, Unknown);
    }

    long allocateID() {
        this.lastId++;
        this.countNewId++;
        return this.lastId;
    }

    /**
     * How many distinct id values allocateID() can produce without a collision. Note that
     * ObjectIDManager.nextFreeId() truncates every fresh allocation to (short) regardless of
     * this type's wire representation (ObjectIDShort or ObjectIDInteger) - matching the
     * original vanilla allocation code - so this is always 65536, even for Item/Container
     * which use ObjectIDInteger on the wire. Used to know when every value has been issued
     * at least once, at which point new allocations must come exclusively from freeIds
     * instead of a fresh increment+cast.
     */
    long getIdSpaceSize() {
        return 65536L;
    }

    @Override
    public String toString() {
        return String.format("ObjectID type=%s last=%d new=%d free=%d", this.name(), this.lastId, this.countNewId, this.freeIds.size());
    }

    public Collection<IIdentifiable> getObjects() {
        return this.idToObjectMap.getHashMap().values();
    }

    static {
        for (ObjectIDType type : values()) {
            objectIDTypes.put(type.index, type);
            if (type.isPermanent) {
                permanentObjectIDTypes++;
            }
        }
    }
}
