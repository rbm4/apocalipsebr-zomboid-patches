// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie;

import java.util.ArrayList;
import java.util.List;
import java.util.IdentityHashMap;
import zombie.characters.IsoZombie;
import zombie.debug.DebugLog;
import zombie.debug.DebugType;
import zombie.iso.IsoMovingObject;
import zombie.iso.IsoWorld;
import zombie.iso.objects.IsoDeadBody;
import zombie.network.GameServer;
import zombie.util.Type;
import zombie.util.list.PZArrayUtil;

public final class MovingObjectUpdateSchedulerUpdateBucket {
    private final UpdateSchedulerSimulationLevel simulationLevel;
    private final int frameMod;
    private final List<IsoMovingObject>[] buckets;
    private final IdentityHashMap<IsoMovingObject, Position> positions = new IdentityHashMap<>();
    private final boolean[] dirty;

    private static final class Position {
        final int phase;
        int index;

        Position(int phase, int index) {
            this.phase = phase;
            this.index = index;
        }
    }

    public MovingObjectUpdateSchedulerUpdateBucket(UpdateSchedulerSimulationLevel simulationLevel) {
        this.simulationLevel = simulationLevel;
        this.frameMod = simulationLevel.getFrameMod();
        this.buckets = PZArrayUtil.newInstance(List.class, this.frameMod, ArrayList::new);
        this.dirty = new boolean[this.frameMod];
    }

    public void clear() {
        for (List<IsoMovingObject> bucket : this.buckets) {
            bucket.clear();
        }
        this.positions.clear();
        java.util.Arrays.fill(this.dirty, false);
    }

    public void add(IsoMovingObject o) {
        int index = Math.floorMod(o.getID(), this.frameMod);
        if (GameServer.server) {
            if (this.positions.containsKey(o)) {
                return;
            }
            this.positions.put(o, new Position(index, this.buckets[index].size()));
        }
        this.buckets[index].add(o);
    }

    // Called only before classification/update. Tombstones keep callbacks from shifting
    // the list under an active update or postupdate traversal.
    public void compact() {
        for (int phase = 0; phase < this.frameMod; phase++) {
            if (!this.dirty[phase]) {
                continue;
            }
            List<IsoMovingObject> bucket = this.buckets[phase];
            int write = 0;
            for (int read = 0; read < bucket.size(); read++) {
                IsoMovingObject object = bucket.get(read);
                if (object != null) {
                    bucket.set(write, object);
                    this.positions.get(object).index = write++;
                }
            }
            bucket.subList(write, bucket.size()).clear();
            this.dirty[phase] = false;
        }
    }

    public void update(int frameCounter) {
        GameTime.getInstance().perObjectMultiplier = this.frameMod;
        List<IsoMovingObject> fullSimulation = this.buckets[Math.floorMod(frameCounter, this.frameMod)];

        try {
            for (int i = 0; i < fullSimulation.size(); i++) {
                IsoMovingObject isoMovingObject = fullSimulation.get(i);
                if (isoMovingObject == null) {
                    continue;
                }
                if (isoMovingObject instanceof IsoDeadBody) {
                    IsoWorld.instance.getCell().getRemoveList().add(isoMovingObject);
                } else {
                    IsoZombie zombie = Type.tryCastTo(isoMovingObject, IsoZombie.class);
                    if (zombie != null && VirtualZombieManager.instance.isReused(zombie)) {
                        DebugLog.log(DebugType.Zombie, "REUSABLE ZOMBIE IN MovingObjectUpdateSchedulerUpdateBucket IGNORED " + isoMovingObject);
                    } else {
                        isoMovingObject.setCurrentSimulationLevel(this.simulationLevel);
                        isoMovingObject.preupdate();
                        isoMovingObject.frameStep();
                        isoMovingObject.update();
                    }
                }
            }

        } finally {
            GameTime.getInstance().perObjectMultiplier = 1.0F;
        }
    }

    public void postupdate(int frameCounter) {
        GameTime.getInstance().perObjectMultiplier = this.frameMod;
        List<IsoMovingObject> fullSimulation = this.buckets[Math.floorMod(frameCounter, this.frameMod)];

        try {
            for (int i = 0; i < fullSimulation.size(); i++) {
                IsoMovingObject isoMovingObject = fullSimulation.get(i);
                if (isoMovingObject == null) {
                    continue;
                }
                IsoZombie zombie = Type.tryCastTo(isoMovingObject, IsoZombie.class);
                if (zombie != null && VirtualZombieManager.instance.isReused(zombie)) {
                    DebugLog.log(DebugType.Zombie, "REUSABLE ZOMBIE IN MovingObjectUpdateSchedulerUpdateBucket IGNORED " + isoMovingObject);
                } else {
                    isoMovingObject.postupdate();
                }
            }

        } finally {
            GameTime.getInstance().perObjectMultiplier = 1.0F;
        }
    }

    public void removeObject(IsoMovingObject object) {
        if (GameServer.server) {
            Position position = this.positions.remove(object);
            if (position != null) {
                this.buckets[position.phase].set(position.index, null);
                this.dirty[position.phase] = true;
            }
        } else {
            for (List<IsoMovingObject> bucket : this.buckets) {
                bucket.remove(object);
            }
        }
    }

    public List<IsoMovingObject> getBucket(int frameCounter) {
        List<IsoMovingObject> bucket = this.buckets[Math.floorMod(frameCounter, this.frameMod)];
        if (GameServer.server && this.dirty[Math.floorMod(frameCounter, this.frameMod)]) {
            // Preserve the public view's non-null entries without compacting mid-update.
            ArrayList<IsoMovingObject> view = new ArrayList<>();
            for (IsoMovingObject object : bucket) {
                if (object != null) view.add(object);
            }
            return view;
        }
        return bucket;
    }
}
