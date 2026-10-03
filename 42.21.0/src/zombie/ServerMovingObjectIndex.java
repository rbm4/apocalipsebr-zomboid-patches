package zombie;

import java.util.ArrayList;
import java.util.List;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.characters.animals.IsoAnimal;
import zombie.iso.IsoCell;
import zombie.iso.IsoMovingObject;

/** Main-thread, per-frame type views populated by the scheduler's world pass. */
public final class ServerMovingObjectIndex {
    private static final ArrayList<IsoMovingObject> perceptionTargets = new ArrayList<>();
    private static final ArrayList<IsoAnimal> animals = new ArrayList<>();
    private static final int SOUND_FRAME_MOD = Math.max(1, Integer.getInteger("apocbr.animalSoundFrameMod", 4));
    private static IsoCell indexedCell;
    private static int indexedSize = -1;
    private static volatile boolean invalidated = true;

    private ServerMovingObjectIndex() {
    }

    static void beginFrame(IsoCell cell) {
        indexedCell = cell;
        perceptionTargets.clear();
        animals.clear();
        invalidated = true;
    }

    static void classify(IsoMovingObject object) {
        // IsoAnimal extends IsoPlayer: distinguish it before checking human players.
        if (object instanceof IsoAnimal animal) {
            animals.add(animal);
        } else if (object instanceof IsoZombie || object instanceof IsoPlayer) {
            perceptionTargets.add(object);
        }
    }

    static void finishFrameIndex() {
        indexedSize = indexedCell.getObjectList().size();
        invalidated = false;
    }

    public static void invalidate() {
        invalidated = true;
    }

    private static void ensureCurrent(IsoCell cell) {
        int size = cell.getObjectList().size();
        if (indexedCell != cell || invalidated || size > indexedSize) {
            // Additions between startFrame and simulation must be visible to perception.
            beginFrame(cell);
            for (IsoMovingObject object : cell.getObjectList()) {
                classify(object);
            }
            finishFrameIndex();
        } else {
            // Removals are checked by membership at use time; avoid rebuilding for each
            // removal when several animals perceive during one simulation frame.
            indexedSize = size;
        }
    }

    public static List<IsoMovingObject> getPerceptionTargets(IsoCell cell) {
        ensureCurrent(cell);
        return perceptionTargets;
    }

    public static void updateAnimalSounds(IsoCell cell, long frame) {
        ensureCurrent(cell);
        int phase = (int)Math.floorMod(frame, (long)SOUND_FRAME_MOD);
        for (int i = 0; i < animals.size(); i++) {
            IsoAnimal animal = animals.get(i);
            if (Math.floorMod(animal.getID(), SOUND_FRAME_MOD) == phase
                && cell.getObjectList().contains(animal) && !animal.isOnHook()) {
                animal.updateVocalProperties();
                animal.updateLoopingSounds();
            }
        }
    }
}
