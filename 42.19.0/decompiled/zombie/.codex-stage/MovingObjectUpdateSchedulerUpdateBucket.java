// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie;

import java.util.ArrayList;
import zombie.characters.IsoZombie;
import zombie.debug.DebugLog;
import zombie.debug.DebugType;
import zombie.iso.IsoMovingObject;
import zombie.iso.IsoWorld;
import zombie.iso.objects.IsoDeadBody;
import zombie.network.ServerMap;
import zombie.util.Type;
import zombie.vehicles.BaseVehicle;

public final class MovingObjectUpdateSchedulerUpdateBucket {
    public int frameMod;
    ArrayList<IsoMovingObject>[] buckets;

    public MovingObjectUpdateSchedulerUpdateBucket(int mod) {
        this.init(mod);
    }

    public void init(int frameMod) {
        this.frameMod = frameMod;
        this.buckets = new ArrayList[frameMod];

        for (int i = 0; i < this.buckets.length; i++) {
            this.buckets[i] = new ArrayList<>();
        }
    }

    public void clear() {
        for (int i = 0; i < this.buckets.length; i++) {
            ArrayList<IsoMovingObject> bucket = this.buckets[i];
            bucket.clear();
        }
    }

    public void add(IsoMovingObject o) {
        int index = o.getID() % this.frameMod;
        this.buckets[index].add(o);
    }

    public void update(int frameCounter) {
        GameTime.getInstance().perObjectMultiplier = this.frameMod;
        ArrayList<IsoMovingObject> fullSimulation = this.buckets[frameCounter % this.frameMod];
        ApocBRServerTelemetry.recordMovingBucketStart(fullSimulation.size());

        for (int i = 0; i < fullSimulation.size(); i++) {
            IsoMovingObject isoMovingObject = fullSimulation.get(i);
            if (isoMovingObject == null) {
                continue;
            }

            BaseVehicle vehicle = Type.tryCastTo(isoMovingObject, BaseVehicle.class);
            if (vehicle != null && !ServerMap.instance.isVehicleUpdateReady(vehicle)) {
                // A queued bucket can retain a vehicle for one frame while its
                // ServerCell is worker-owned. Do not run BaseVehicle.update() on
                // partially attached chunk/square/list state.
                continue;
            }

            if (isoMovingObject instanceof IsoDeadBody) {
                ApocBRServerTelemetry.recordMovingBucketDeadBody();
                IsoWorld.instance.getCell().getRemoveList().add(isoMovingObject);
            } else {
                IsoZombie zombie = Type.tryCastTo(isoMovingObject, IsoZombie.class);
                if (zombie != null && VirtualZombieManager.instance.isReused(zombie)) {
                    ApocBRServerTelemetry.recordMovingBucketReusedZombie();
                    DebugLog.log(DebugType.Zombie, "REUSABLE ZOMBIE IN MovingObjectUpdateSchedulerUpdateBucket IGNORED " + isoMovingObject);
                } else {
                    long apocBrMovingObjectStart = System.nanoTime();
                    isoMovingObject.preupdate();
                    ApocBRServerTelemetry.recordMovingBucketPreupdate(System.nanoTime() - apocBrMovingObjectStart);

                    apocBrMovingObjectStart = System.nanoTime();
                    isoMovingObject.frameStep();
                    ApocBRServerTelemetry.recordMovingBucketFrameStep(System.nanoTime() - apocBrMovingObjectStart);

                    apocBrMovingObjectStart = System.nanoTime();
                    isoMovingObject.update();
                    long apocBrMovingObjectUpdateNanos = System.nanoTime() - apocBrMovingObjectStart;
                    ApocBRServerTelemetry.recordMovingBucketUpdate(zombie != null, apocBrMovingObjectUpdateNanos);
                    ApocBRServerTelemetry.recordMovingBucketType(isoMovingObject.getClass().getSimpleName(), apocBrMovingObjectUpdateNanos);
                }
            }
        }

        GameTime.getInstance().perObjectMultiplier = 1.0F;
    }

    public void postupdate(int frameCounter) {
        GameTime.getInstance().perObjectMultiplier = this.frameMod;
        ArrayList<IsoMovingObject> fullSimulation = this.buckets[frameCounter % this.frameMod];

        for (int i = 0; i < fullSimulation.size(); i++) {
            IsoMovingObject isoMovingObject = fullSimulation.get(i);
            if (isoMovingObject == null) {
                continue;
            }

            BaseVehicle vehicle = Type.tryCastTo(isoMovingObject, BaseVehicle.class);
            if (vehicle != null && !ServerMap.instance.isVehicleUpdateReady(vehicle)) {
                continue;
            }

            IsoZombie zombie = Type.tryCastTo(isoMovingObject, IsoZombie.class);
            if (zombie != null && VirtualZombieManager.instance.isReused(zombie)) {
                DebugLog.log(DebugType.Zombie, "REUSABLE ZOMBIE IN MovingObjectUpdateSchedulerUpdateBucket IGNORED " + isoMovingObject);
            } else {
                isoMovingObject.postupdate();
            }
        }

        GameTime.getInstance().perObjectMultiplier = 1.0F;
    }

    public void updateAnimation(int frameCounter) {
        GameTime.getInstance().perObjectMultiplier = this.frameMod;
        ArrayList<IsoMovingObject> fullSimulation = this.buckets[frameCounter % this.frameMod];

        for (int i = 0; i < fullSimulation.size(); i++) {
            IsoMovingObject isoMovingObject = fullSimulation.get(i);
            if (isoMovingObject == null) {
                continue;
            }

            BaseVehicle vehicle = Type.tryCastTo(isoMovingObject, BaseVehicle.class);
            if (vehicle != null && !ServerMap.instance.isVehicleUpdateReady(vehicle)) {
                continue;
            }

            IsoZombie zombie = Type.tryCastTo(isoMovingObject, IsoZombie.class);
            if (zombie != null && VirtualZombieManager.instance.isReused(zombie)) {
                DebugLog.log(DebugType.Zombie, "REUSABLE ZOMBIE IN MovingObjectUpdateSchedulerUpdateBucket IGNORED " + isoMovingObject);
            } else {
                try (GameProfiler.ProfileArea var6 = GameProfiler.getInstance().profile("Update Anim")) {
                    isoMovingObject.updateAnimation();
                }
            }
        }

        GameTime.getInstance().perObjectMultiplier = 1.0F;
    }

    public void removeObject(IsoMovingObject object) {
        for (int i = 0; i < this.buckets.length; i++) {
            ArrayList<IsoMovingObject> bucket = this.buckets[i];
            bucket.remove(object);
        }
    }

    public ArrayList<IsoMovingObject> getBucket(int frameCounter) {
        return this.buckets[frameCounter % this.frameMod];
    }
}


