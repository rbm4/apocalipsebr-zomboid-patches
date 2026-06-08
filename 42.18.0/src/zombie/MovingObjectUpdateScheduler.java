// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
// ApocBR Patch D: cap idle parked vehicles at QUARTER simulation tier on the client.
// Vehicles hardcode getMinimumSimulationLevel() = FULL, which short-circuits the
// distance/FPS throttle logic, forcing all vehicles to update() every single frame.
// With 40 parked vehicles in view this is 40 full update+postupdate calls per frame.
// By intercepting before that check we force driver-less/idle-engine vehicles onto
// the QUARTER bucket, running update()+postupdate() only every 4 frames and
// distributing them via (objectId % 4), so at most 10 fire per frame instead of 40.
// Server is unaffected (server scheduler always returns FULL regardless).
package zombie;

import java.util.ArrayList;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.core.math.PZMath;
import zombie.iso.IsoMovingObject;
import zombie.iso.IsoWorld;
import zombie.network.GameServer;
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
        this.frameCounter++;
        this.fullSimulation.clear();
        this.halfSimulation.clear();
        this.quarterSimulation.clear();
        this.eighthSimulation.clear();
        this.sixteenthSimulation.clear();
        float averageFps = GameWindow.averageFPS;

        for (IsoMovingObject isoMovingObject : IsoWorld.instance.getCell().getObjectList()) {
            if (GameServer.server && isoMovingObject instanceof IsoZombie isoZombie) {
                if (GameServer.guiCommandline) {
                    isoZombie.updateForServerGui();
                }
            } else {
                if (isoMovingObject.getCurrentSquare() == null) {
                    isoMovingObject.setCurrentSquareFromPosition();
                }

                switch (this.getUpdateSchedulerSimulationLevelForObject(isoMovingObject, averageFps)) {
                    case FULL:
                        this.fullSimulation.add(isoMovingObject);
                        break;
                    case HALF:
                        this.halfSimulation.add(isoMovingObject);
                        break;
                    case QUARTER:
                        this.quarterSimulation.add(isoMovingObject);
                        break;
                    case EIGHTH:
                        this.eighthSimulation.add(isoMovingObject);
                        break;
                    case SIXTEENTH:
                        this.sixteenthSimulation.add(isoMovingObject);
                    case null:
                }
            }
        }
    }

    private UpdateSchedulerSimulationLevel getUpdateSchedulerSimulationLevelForObject(IsoMovingObject isoMovingObject, float averageFps) {
        // ApocBR Patch D: override simulation tier for parked vehicles on the client.
        // Must come before the minSim == FULL short-circuit below, which vehicles always trigger.
        // QUARTER = update every 4 frames; the bucket distributes via (objectId % 4) so
        // only ~25% of parked vehicles fire their update() on any given frame.
        if (!GameServer.server && isoMovingObject instanceof BaseVehicle bv) {
            if (bv.getDriver() == null && bv.engineState == BaseVehicle.engineStateTypes.Idle) {
                return UpdateSchedulerSimulationLevel.QUARTER;
            }
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
                float minAlpha = 0.25F;
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
