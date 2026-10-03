package zombie;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.characters.animals.IsoAnimal;
import zombie.iso.IsoCell;
import zombie.iso.IsoMovingObject;
import zombie.vehicles.BaseVehicle;

/** Type membership is established at active-world insertion, before scheduling. */
public final class ServerMovingObjectIndex {
    public static final int OTHER = 0, ANIMAL = 1, VEHICLE = 2, ZOMBIE = 3, HUMAN = 4;
    private static final int SOUND_FRAME_MOD = Math.max(1, Integer.getInteger("apocbr.animalSoundFrameMod", 4));

    public static final class Member {
        public final IsoMovingObject object;
        public final int kind;
        public boolean active = true;
        int scheduleSlot = -1;
        int typeSlot = -1;

        Member(IsoMovingObject object) {
            this.object = object;
            // Constructors may not have initialized subclass fields yet. Only inspect
            // immutable Java type here; activity checks happen at the frame boundary.
            this.kind = object instanceof IsoAnimal ? ANIMAL
                : object instanceof BaseVehicle ? VEHICLE
                : object instanceof IsoZombie ? ZOMBIE
                : object instanceof IsoPlayer ? HUMAN : OTHER;
        }
    }

    private final ArrayList<Member> scheduled = new ArrayList<>();
    private final ArrayList<Member> targets = new ArrayList<>();
    private final ArrayList<Member> animals = new ArrayList<>();
    private final ArrayList<Member> removed = new ArrayList<>();
    private final ArrayList<IsoMovingObject> targetObjects = new ArrayList<>();
    private final List<IsoMovingObject> targetView = Collections.unmodifiableList(this.targetObjects);
    private boolean scheduledDirty, animalsDirty, targetsDirty;

    Member add(IsoMovingObject object) {
        Member member = new Member(object);
        if (member.kind != ZOMBIE) {
            member.scheduleSlot = this.scheduled.size();
            this.scheduled.add(member);
        }
        if (member.kind == ANIMAL) {
            member.typeSlot = this.animals.size();
            this.animals.add(member);
        } else if (member.kind == ZOMBIE || member.kind == HUMAN) {
            member.typeSlot = this.targets.size();
            this.targets.add(member);
            this.targetObjects.add(object);
        }
        return member;
    }

    void remove(Member member) {
        member.active = false;
        if (member.scheduleSlot >= 0) {
            this.scheduled.set(member.scheduleSlot, null);
            this.removed.add(member);
            this.scheduledDirty = true;
        }
        if (member.kind == ANIMAL) {
            this.animals.set(member.typeSlot, null);
            this.animalsDirty = true;
        } else if (member.kind == ZOMBIE || member.kind == HUMAN) {
            this.targets.set(member.typeSlot, null);
            this.targetObjects.set(member.typeSlot, null);
            this.targetsDirty = true;
        }
    }

    public void drainRemoved(Consumer<Member> consumer) {
        for (int i = 0; i < this.removed.size(); i++) consumer.accept(this.removed.get(i));
        this.removed.clear();
    }

    public void compact() {
        if (this.scheduledDirty) {
            compactMembers(this.scheduled, true);
            this.scheduledDirty = false;
        }
        if (this.animalsDirty) {
            compactMembers(this.animals, false);
            this.animalsDirty = false;
        }
        if (this.targetsDirty) {
            compactMembers(this.targets, false);
            // Keep the public target view object stable across mutations/compaction.
            for (int i = 0; i < this.targets.size(); i++) this.targetObjects.set(i, this.targets.get(i).object);
            this.targetObjects.subList(this.targets.size(), this.targetObjects.size()).clear();
            this.targetsDirty = false;
        }
    }

    private static void compactMembers(ArrayList<Member> members, boolean scheduling) {
        int write = 0;
        for (int read = 0; read < members.size(); read++) {
            Member member = members.get(read);
            if (member != null) {
                members.set(write, member);
                if (scheduling) member.scheduleSlot = write; else member.typeSlot = write;
                write++;
            }
        }
        members.subList(write, members.size()).clear();
    }

    public List<Member> getScheduledMembers() {
        return this.scheduled;
    }

    public static ServerMovingObjectIndex forCell(IsoCell cell) {
        return ((ServerMovingObjectSet)cell.getObjectList()).index();
    }

    public static List<IsoMovingObject> getPerceptionTargets(IsoCell cell) {
        return forCell(cell).targetView;
    }

    public static void updateAnimalSounds(IsoCell cell, long frame) {
        ServerMovingObjectIndex index = forCell(cell);
        int phase = (int)Math.floorMod(frame, (long)SOUND_FRAME_MOD);
        for (int i = 0; i < index.animals.size(); i++) {
            Member member = index.animals.get(i);
            if (member == null) continue;
            IsoAnimal animal = (IsoAnimal)member.object;
            if (Math.floorMod(animal.getID(), SOUND_FRAME_MOD) == phase && !animal.isOnHook()) {
                animal.updateVocalProperties();
                animal.updateLoopingSounds();
            }
        }
    }
}
