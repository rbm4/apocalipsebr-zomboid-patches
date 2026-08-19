// ApocBR patch:
// 1) addObject() no longer probes idToObjectMap.get(id) in a loop to find a free id. It
//    uses type.freeIds (see ObjectIDType) to reuse a released id in O(1), and only falls
//    back to a fresh increment+cast while the type has never issued every value in its id
//    space (guaranteed collision-free, no lookup needed).
// 2) If the id space for a type is genuinely exhausted (every value issued at least once
//    AND freeIds is empty), the oldest live object of that type is force-removed from the
//    world (via its own removeFromWorld() override, which also releases its id back into
//    freeIds) instead of hanging the caller forever.
// No network/save wire format was changed: ids are still serialized exactly as before.
package zombie.network.id;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import zombie.ZomboidFileSystem;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;
import zombie.iso.IsoObject;
import zombie.iso.IsoWorld;
import zombie.network.GameClient;

public class ObjectIDManager {
    private static final ObjectIDManager instance = new ObjectIDManager();
    private static final int saveLastIDNumber = 100;
    private static int objectIdManagerCheckLimiter;

    public static ObjectIDManager getInstance() {
        return instance;
    }

    private ObjectIDManager() {
    }

    public void clear() {
        for (ObjectIDType type : ObjectIDType.values()) {
            type.lastId = 0L;
            type.countNewId = 0L;
        }
    }

    public void load(DataInputStream input, int worldVersion) throws IOException {
        byte size = input.readByte();

        for (byte i = 0; i < size; i++) {
            byte index = input.readByte();
            long lastID = input.readLong();
            ObjectIDType.valueOf(index).lastId = lastID + 100L;
            ObjectIDType.valueOf(index).countNewId = 0L;
            DebugType.General.println(ObjectIDType.valueOf(index));
        }
    }

    private void save(DataOutputStream output) throws IOException {
        output.write(ObjectIDType.permanentObjectIDTypes);

        for (ObjectIDType type : ObjectIDType.values()) {
            if (type.isPermanent) {
                output.writeByte(type.index);
                output.writeLong(type.lastId);
            }

            type.countNewId = 0L;
            DebugType.General.println(type);
        }
    }

    private boolean isNeedToSave() {
        for (ObjectIDType type : ObjectIDType.values()) {
            if (type.countNewId >= 100L) {
                return true;
            }
        }

        return false;
    }

    public void checkForSaveDataFile(boolean force) {
        if (!GameClient.client) {
            objectIdManagerCheckLimiter++;
            if (force || objectIdManagerCheckLimiter > 300) {
                objectIdManagerCheckLimiter = 0;
                if (force || this.isNeedToSave()) {
                    DebugType.General.println("The id_manager_data.bin file is saved");
                    File outFile = ZomboidFileSystem.instance.getFileInCurrentSave("id_manager_data.bin");

                    try (
                        FileOutputStream fos = new FileOutputStream(outFile);
                        DataOutputStream output = new DataOutputStream(fos);
                    ) {
                        output.writeInt(IsoWorld.getWorldVersion());
                        this.save(output);
                    } catch (IOException var11) {
                        DebugType.General.printException(var11, "Save failed", LogSeverity.Error);
                    }
                }
            }
        }
    }

    public static IIdentifiable get(ObjectID id) {
        return id.getType().idToObjectMap.get(id.getObjectID());
    }

    public void remove(ObjectID id) {
        ObjectIDType type = id.getType();
        long idValue = id.getObjectID();
        IIdentifiable obj = type.idToObjectMap.get(idValue);
        if (type.idToObjectMap.contains(idValue)) {
            type.idToObjectMap.remove(idValue, obj);
            type.freeIds.addLast(idValue);
        }
    }

    public void addObject(IIdentifiable object) {
        if (object == null) {
            DebugType.General.warn("%s ObjectID: is null");
        } else {
            long id = object.getObjectID().getObjectID();
            ObjectIDType type = object.getObjectID().getType();
            if (id == -1L) {
                if (GameClient.client) {
                    return;
                }

                id = this.nextFreeId(type);
                if (id == -1L) {
                    DebugType.General.printException(
                        new IllegalStateException("ObjectID space exhausted for type " + type),
                        "ObjectIDManager.addObject: dropping " + object + ", could not reclaim any slot",
                        LogSeverity.Error
                    );
                    return;
                }
            }

            type.idToObjectMap.add(id, object);
            object.getObjectID().set(id, type);
        }
    }

    /**
     * O(1) id allocation. While {@code type} has not yet issued every value in its id space
     * at least once ({@code lastId < getIdSpaceSize()}), a fresh increment+cast is always
     * collision-free by construction and needs no lookup. Once the space has wrapped at
     * least once, every further allocation must come from {@code type.freeIds} (ids released
     * by {@link #remove(ObjectID)}), since a blind increment could collide with a still-live
     * object. If freeIds is empty at that point the space is genuinely exhausted, and the
     * oldest live object of that type is force-removed to reclaim a slot instead of hanging.
     */
    private long nextFreeId(ObjectIDType type) {
        if (type.lastId < type.getIdSpaceSize()) {
            return (short)type.allocateID();
        }

        Long reused = type.freeIds.pollFirst();
        if (reused != null) {
            return reused;
        }

        if (this.forceReclaimAny(type)) {
            reused = type.freeIds.pollFirst();
            if (reused != null) {
                return reused;
            }
        }

        return -1L;
    }

    /**
     * Called only when {@code type}'s id space is fully occupied and freeIds is empty.
     * Force-removes an arbitrary live object of that type from the world (through its own
     * removeFromWorld() override, which also releases its id back into freeIds via
     * {@link #remove(ObjectID)}) so the caller can immediately reuse the freed slot instead
     * of looping forever. Returns false if no reclaimable object could be found.
     */
    private boolean forceReclaimAny(ObjectIDType type) {
        for (IIdentifiable victim : type.getObjects()) {
            if (victim instanceof IsoObject isoObject) {
                DebugType.General.warn(
                    "ObjectIDManager: %s id space is exhausted, force-removing %s to free a slot", type, victim
                );

                try {
                    isoObject.removeFromWorld();
                } catch (Exception var4) {
                    DebugType.General.printException(var4, "ObjectIDManager.forceReclaimAny", LogSeverity.Error);
                    continue;
                }

                if (!type.freeIds.isEmpty()) {
                    return true;
                }
            }
        }

        return false;
    }

    public static ObjectID createObjectID(ObjectIDType type) {
        try {
            Constructor<?> ctr = type.type.getDeclaredConstructor(ObjectIDType.class);
            return (ObjectID)ctr.newInstance(type);
        } catch (Exception var2) {
            DebugType.General.printException(var2, "ObjectID creation failed", LogSeverity.Error);
            throw new RuntimeException();
        }
    }
}
