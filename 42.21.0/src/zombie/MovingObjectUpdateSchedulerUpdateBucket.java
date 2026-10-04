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
    private static final String[] telemetryKinds = {"zombie", "vehicle", "animal", "player", "giblet", "other"};
    private static final ClassValue<Integer> telemetryKind = new ClassValue<>() {
        @Override protected Integer computeValue(Class<?> type) {
            for (Class<?> current = type; current != null; current = current.getSuperclass()) {
                switch (current.getName()) {
                    case "zombie.characters.IsoZombie": return 0;
                    case "zombie.vehicles.BaseVehicle": return 1;
                    case "zombie.characters.animals.IsoAnimal": return 2;
                    case "zombie.characters.IsoPlayer": return 3;
                    case "zombie.iso.objects.IsoZombieGiblets": return 4;
                }
            }
            return 5;
        }
    };
    private static final String[][] telemetryPhases = new String[2][telemetryKinds.length];
    private static final String[][] telemetryCounts = new String[2][telemetryKinds.length];
    static {
        for (int pass = 0; pass < 2; pass++) {
            String action = pass == 0 ? "update" : "postupdate";
            for (int kind = 0; kind < telemetryKinds.length; kind++) {
                telemetryPhases[pass][kind] = "simulation.movingObjects." + action + "." + telemetryKinds[kind];
                telemetryCounts[pass][kind] = "movingObjects." + action + "Attempts." + telemetryKinds[kind];
            }
        }
    }

    private static void recordTelemetry(int pass, long[] nanos, long[] attempts) {
        if (nanos == null) return;
        for (int kind = 0; kind < telemetryKinds.length; kind++) {
            if (attempts[kind] > 0) {
                ApocBRServerTelemetryLite.recordPhase(telemetryPhases[pass][kind], nanos[kind]);
                ApocBRServerTelemetryLite.count(telemetryCounts[pass][kind], attempts[kind]);
            }
        }
    }


    private static final class Position {
        final int phase;
        int index;
        final ServerMovingObjectIndex.Member lifetime;

        Position(int phase, int index, IsoMovingObject object) {
            this.phase = phase;
            this.index = index;
            this.lifetime = ((ServerMovingObjectSet)IsoWorld.instance.getCell().getObjectList()).member(object);
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
            this.positions.put(o, new Position(index, this.buckets[index].size(), o));
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

        long[] nanos = GameServer.server ? new long[telemetryKinds.length] : null;
        long[] attempts = GameServer.server ? new long[telemetryKinds.length] : null;
        try {
            for (int i = 0; i < fullSimulation.size(); i++) {
                IsoMovingObject isoMovingObject = fullSimulation.get(i);
                if (isoMovingObject == null || GameServer.server && !this.isActiveLifetime(isoMovingObject)) {
                    continue;
                }
                if (isoMovingObject instanceof IsoDeadBody) {
                    IsoWorld.instance.getCell().getRemoveList().add(isoMovingObject);
                } else {
                    IsoZombie zombie = Type.tryCastTo(isoMovingObject, IsoZombie.class);
                    if (zombie != null && VirtualZombieManager.instance.isReused(zombie)) {
                        DebugLog.log(DebugType.Zombie, "REUSABLE ZOMBIE IN MovingObjectUpdateSchedulerUpdateBucket IGNORED " + isoMovingObject);
                    } else {
                        int kind = nanos == null ? 0 : telemetryKind.get(isoMovingObject.getClass());
                        long started = nanos == null ? 0L : System.nanoTime();
                        try {
                            isoMovingObject.setCurrentSimulationLevel(this.simulationLevel);
                            isoMovingObject.preupdate();
                            isoMovingObject.frameStep();
                            isoMovingObject.update();
                        } finally {
                            if (nanos != null) {
                                nanos[kind] += System.nanoTime() - started;
                                attempts[kind]++;
                            }
                        }
                    }
                }
            }

        } finally {
            GameTime.getInstance().perObjectMultiplier = 1.0F;
            recordTelemetry(0, nanos, attempts);
        }
    }

    public void postupdate(int frameCounter) {
        GameTime.getInstance().perObjectMultiplier = this.frameMod;
        List<IsoMovingObject> fullSimulation = this.buckets[Math.floorMod(frameCounter, this.frameMod)];

        long[] nanos = GameServer.server ? new long[telemetryKinds.length] : null;
        long[] attempts = GameServer.server ? new long[telemetryKinds.length] : null;
        try {
            for (int i = 0; i < fullSimulation.size(); i++) {
                IsoMovingObject isoMovingObject = fullSimulation.get(i);
                if (isoMovingObject == null || GameServer.server && !this.isActiveLifetime(isoMovingObject)) {
                    continue;
                }
                IsoZombie zombie = Type.tryCastTo(isoMovingObject, IsoZombie.class);
                if (zombie != null && VirtualZombieManager.instance.isReused(zombie)) {
                    DebugLog.log(DebugType.Zombie, "REUSABLE ZOMBIE IN MovingObjectUpdateSchedulerUpdateBucket IGNORED " + isoMovingObject);
                } else {
                    int kind = nanos == null ? 0 : telemetryKind.get(isoMovingObject.getClass());
                    long started = nanos == null ? 0L : System.nanoTime();
                    try {
                        isoMovingObject.postupdate();
                    } finally {
                        if (nanos != null) {
                            nanos[kind] += System.nanoTime() - started;
                            attempts[kind]++;
                        }
                    }
                }
            }

        } finally {
            GameTime.getInstance().perObjectMultiplier = 1.0F;
            recordTelemetry(1, nanos, attempts);
        }
    }

    private boolean isActiveLifetime(IsoMovingObject object) {
        Position position = this.positions.get(object);
        return position != null && (position.lifetime == null || position.lifetime.active);
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
