// Patched MovingObjectUpdateScheduler.java - ApocBR NoCull patch (Build 42.19)
//
// Change: removed ZombieCountOptimiser.deleteZombies() call from postupdate().
//
// Build 42.19 added a server-side zombie cull that runs every frame in
// postupdate(), reducing zombie populations from ~5000 to ~400 on servers
// with many connected players. This patch restores the 42.18 behaviour by
// removing that call. startCount() and incrementZombie() are still called in
// startFrame() as before, but zombies are never actually deleted.
//
// Original: zombie.MovingObjectUpdateScheduler (Build 42.19)
package zombie;

import java.util.ArrayList;

import zombie.characters.animals.IsoAnimal;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.core.math.PZMath;
import zombie.iso.IsoMovingObject;
import zombie.iso.IsoWorld;
import zombie.network.GameServer;
import zombie.popman.ZombieCountOptimiser;
import zombie.vehicles.BaseVehicle;

public final class MovingObjectUpdateScheduler {
    public static final MovingObjectUpdateScheduler instance = new MovingObjectUpdateScheduler();
    final MovingObjectUpdateSchedulerUpdateBucket fullSimulation = new MovingObjectUpdateSchedulerUpdateBucket(1);
    final MovingObjectUpdateSchedulerUpdateBucket halfSimulation = new MovingObjectUpdateSchedulerUpdateBucket(2);
    final MovingObjectUpdateSchedulerUpdateBucket quarterSimulation = new MovingObjectUpdateSchedulerUpdateBucket(4);
    final MovingObjectUpdateSchedulerUpdateBucket eighthSimulation = new MovingObjectUpdateSchedulerUpdateBucket(8);
    final MovingObjectUpdateSchedulerUpdateBucket sixteenthSimulation = new MovingObjectUpdateSchedulerUpdateBucket(16);
    long frameCounter;
    private boolean isEnabled = true;

    public long getFrameCounter() {
        return this.frameCounter;
    }

    public void startFrame() {
        long apocBrStartFrameNanos = System.nanoTime();
        int apocBrObjectCount = 0;
        this.frameCounter++;
        this.fullSimulation.clear();
        this.halfSimulation.clear();
        this.quarterSimulation.clear();
        this.eighthSimulation.clear();
        this.sixteenthSimulation.clear();
        float averageFps = GameWindow.averageFPS;
        if (GameServer.server) {
            ZombieCountOptimiser.startCount();
        }

        for (IsoMovingObject isoMovingObject : IsoWorld.instance.getCell().getObjectList()) {
            apocBrObjectCount++;
            if (GameServer.server && isoMovingObject instanceof IsoZombie isoZombie) {
                long apocBrGuiNanos = 0L;
                if (GameServer.guiCommandline) {
                    long apocBrGuiStart = System.nanoTime();
                    isoZombie.updateForServerGui();
                    apocBrGuiNanos = System.nanoTime() - apocBrGuiStart;
                }

                long apocBrOptimiserStart = System.nanoTime();
                ZombieCountOptimiser.incrementZombie(isoZombie);
                ApocBRServerTelemetry.recordMovingStartFrameServerZombie(apocBrGuiNanos, System.nanoTime() - apocBrOptimiserStart);
            } else {
                if (isoMovingObject.getCurrentSquare() == null) {
                    long apocBrSquareStart = System.nanoTime();
                    isoMovingObject.setCurrentSquareFromPosition();
                    ApocBRServerTelemetry.recordMovingStartFrameSquareFix(System.nanoTime() - apocBrSquareStart);
                }

                String apocBrTypeName = isoMovingObject == null ? "null" : isoMovingObject.getClass().getSimpleName();
                switch (this.getUpdateSchedulerSimulationLevelForObject(isoMovingObject, averageFps)) {
                    case FULL:
                        this.fullSimulation.add(isoMovingObject);
                        ApocBRServerTelemetry.recordMovingStartFrameBucket(apocBrTypeName, "full");
                        break;
                    case HALF:
                        this.halfSimulation.add(isoMovingObject);
                        ApocBRServerTelemetry.recordMovingStartFrameBucket(apocBrTypeName, "half");
                        break;
                    case QUARTER:
                        this.quarterSimulation.add(isoMovingObject);
                        ApocBRServerTelemetry.recordMovingStartFrameBucket(apocBrTypeName, "quarter");
                        break;
                    case EIGHTH:
                        this.eighthSimulation.add(isoMovingObject);
                        ApocBRServerTelemetry.recordMovingStartFrameBucket(apocBrTypeName, "eighth");
                        break;
                    case SIXTEENTH:
                        this.sixteenthSimulation.add(isoMovingObject);
                        ApocBRServerTelemetry.recordMovingStartFrameBucket(apocBrTypeName, "sixteenth");
                    case null:
                }
            }
        }
        ApocBRServerTelemetry.recordMovingStartFrame(apocBrObjectCount, System.nanoTime() - apocBrStartFrameNanos);
    }
    private UpdateSchedulerSimulationLevel getUpdateSchedulerSimulationLevelForObject(IsoMovingObject isoMovingObject, float averageFps) {
        // Dedicated servers normally force every non-zombie object to FULL. Parked
        // vehicles dominate that list, however, and have no per-frame work while
        // dormant. BaseVehicle keeps all state-changing work on the main thread;
        // this only decides how often that main-thread update is invoked.
        if (GameServer.server && isoMovingObject instanceof BaseVehicle vehicle) {
            return vehicle.apocBrGetServerSimulationLevel();
        }

        if (GameServer.server && isoMovingObject instanceof IsoAnimal animal) {
            return animal.apocBrGetServerSimulationLevel();
        }

        if (this.isEnabled && !GameServer.server) {
            UpdateSchedulerSimulationLevel minSim = isoMovingObject.getMinimumSimulationLevel();
            if (minSim == UpdateSchedulerSimulationLevel.FULL) {
                return minSim;
            } else if (isoMovingObject.getDoRender() && !isoMovingObject.isSceneCulled()) {
                float distance = 1.0E8F;
                int levelSeparation = Integer.MAX_VALUE;
                float alpha = 0.0F;
                float targetAlpha = 0.0F;

                for (int playerIndex = 0; playerIndex < IsoPlayer.numPlayers; playerIndex++) {
                    IsoPlayer player = IsoPlayer.players[playerIndex];
                    if (player != null) {
                        if (player == isoMovingObject) {
                            return UpdateSchedulerSimulationLevel.FULL;
                        }

                        distance = PZMath.min(isoMovingObject.DistTo(player), distance);
                        levelSeparation = PZMath.min(PZMath.abs(isoMovingObject.getZi() - player.getZi()), levelSeparation);
                        alpha = PZMath.max(isoMovingObject.getAlpha(playerIndex), alpha);
                        targetAlpha = PZMath.max(isoMovingObject.getTargetAlpha(playerIndex), targetAlpha);
                    }
                }

                UpdateSchedulerSimulationLevel sim = UpdateSchedulerSimulationLevel.FULL;
                if (alpha < 0.25F && targetAlpha < 0.25F) {
                    sim = sim.less();
                    if (distance > 10.0F) {
                        sim = sim.less();
                    }

                    if (levelSeparation > 1) {
                        sim = minSim;
                    }
                }

                if (distance > 30.0F) {
                    sim = sim.less();
                }

                if (distance > 60.0F) {
                    sim = sim.less();
                    if (averageFps < 20.0F) {
                        sim = sim.less();
                    }

                    if (averageFps < 10.0F) {
                        sim = sim.less();
                    }
                }

                if (distance > 80.0F) {
                    sim = sim.less();
                    if (averageFps < 20.0F) {
                        sim = sim.less();
                    }
                }

                if (averageFps > 25.0F) {
                    sim = sim.more();
                }

                if (averageFps > 35.0F) {
                    sim = sim.more();
                }

                if (averageFps > 45.0F) {
                    sim = sim.more();
                }

                if (averageFps > 55.0F) {
                    sim = sim.more();
                }

                return sim.max(minSim);
            } else {
                return minSim;
            }
        } else {
            return UpdateSchedulerSimulationLevel.FULL;
        }
    }

    public void update() {
        GameTime.getInstance().perObjectMultiplier = 1.0F;
        this.fullSimulation.update((int)this.frameCounter);
        this.halfSimulation.update((int)this.frameCounter);
        this.quarterSimulation.update((int)this.frameCounter);
        this.eighthSimulation.update((int)this.frameCounter);
        this.sixteenthSimulation.update((int)this.frameCounter);
    }

    public void postupdate() {
        // PATCHED (ApocBR 42.19 NoCull): removed ZombieCountOptimiser.deleteZombies() call.
        // Build 42.19 added: if (GameServer.server) { ZombieCountOptimiser.deleteZombies(); }
        // This was culling zombie populations from ~5000 to ~400 on populated servers.
        // Behaviour is now identical to Build 42.18.
        GameTime.getInstance().perObjectMultiplier = 1.0F;
        this.fullSimulation.postupdate((int)this.frameCounter);
        this.halfSimulation.postupdate((int)this.frameCounter);
        this.quarterSimulation.postupdate((int)this.frameCounter);
        this.eighthSimulation.postupdate((int)this.frameCounter);
        this.sixteenthSimulation.postupdate((int)this.frameCounter);
    }

    public void updateAnimation() {
        GameTime.getInstance().perObjectMultiplier = 1.0F;
        this.fullSimulation.updateAnimation((int)this.frameCounter);
        this.halfSimulation.updateAnimation((int)this.frameCounter);
        this.quarterSimulation.updateAnimation((int)this.frameCounter);
        this.eighthSimulation.updateAnimation((int)this.frameCounter);
        this.sixteenthSimulation.updateAnimation((int)this.frameCounter);
    }

    public boolean isEnabled() {
        return this.isEnabled;
    }

    public void setEnabled(boolean enabled) {
        this.isEnabled = enabled;
    }

    public void removeObject(IsoMovingObject object) {
        this.fullSimulation.removeObject(object);
        this.halfSimulation.removeObject(object);
        this.quarterSimulation.removeObject(object);
        this.eighthSimulation.removeObject(object);
        this.sixteenthSimulation.removeObject(object);
    }

    public ArrayList<IsoMovingObject> getBucket() {
        return this.fullSimulation.getBucket((int)this.frameCounter);
    }
}

