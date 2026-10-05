// ApocBR patch:
// 1) addObject() reuses ids released into type.freeIds first, then does a bounded live-map
//    probe through the 16-bit runtime id space. This keeps allocation from hanging while
//    still tolerating restarts, where freeIds is naturally empty but saved objects may reload
//    with old ObjectIDs.
// 2) If the id space for a type is genuinely exhausted after probing every possible runtime
//    id and freeIds is empty, the oldest live object of that type is force-removed from the
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
            type.freeIds.clear();
        }
    }

    public void load(DataInputStream input, int worldVersion) throws IOException {
        byte size = input.readByte();

        for (byte i = 0; i < size; i++) {
            byte index = input.readByte();
            long lastID = input.readLong();
            ObjectIDType type = ObjectIDType.valueOf(index);
            type.lastId = (short)(lastID + 100L);
            type.countNewId = 0L;
            type.freeIds.clear();
            DebugType.General.println(type);
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
     * Reuse released ids first, then probe the 16-bit runtime id space at most once. The
     * persisted lastId can be much larger than the runtime wire space after long-running
     * saves, and freeIds is intentionally not persisted, so exhaustion must be based on the
     * live map rather than the absolute saved counter.
     */
    private long nextFreeId(ObjectIDType type) {
        Long reused = this.pollFreeId(type);
        if (reused != null) {
            return reused;
        }

        long idSpaceSize = type.getIdSpaceSize();
        for (long attempts = 0L; attempts < idSpaceSize; attempts++) {
            long id = (short)type.allocateID();
            if (type.idToObjectMap.get(id) == null) {
                return id;
            }
        }

        if (this.forceReclaimAny(type)) {
            reused = this.pollFreeId(type);
            if (reused != null) {
                return reused;
            }
        }

        return -1L;
    }

    private Long pollFreeId(ObjectIDType type) {
        Long id;
        while ((id = type.freeIds.pollFirst()) != null) {
            if (type.idToObjectMap.get(id) == null) {
                return id;
            }
        }

        return null;
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
