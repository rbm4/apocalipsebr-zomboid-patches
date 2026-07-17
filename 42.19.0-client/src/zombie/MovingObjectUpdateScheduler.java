// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie;

import java.util.ArrayList;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.characters.animals.IsoAnimal;
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
    private static final float[] apocbrPlayerX = new float[4];
    private static final float[] apocbrPlayerY = new float[4];
    private static final int[] apocbrPlayerZi = new int[4];

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
        for (int playerIndex = 0; playerIndex < IsoPlayer.numPlayers; playerIndex++) {
            IsoPlayer player = IsoPlayer.players[playerIndex];
            if (player != null) {
                apocbrPlayerX[playerIndex] = player.getX();
                apocbrPlayerY[playerIndex] = player.getY();
                apocbrPlayerZi[playerIndex] = player.getZi();
            } else {
                apocbrPlayerX[playerIndex] = Float.NaN;
            }
        }

        if (GameServer.server) {
            ZombieCountOptimiser.startCount();
        }

        for (IsoMovingObject isoMovingObject : IsoWorld.instance.getCell().getObjectList()) {
            if (GameServer.server && isoMovingObject instanceof IsoZombie isoZombie) {
                if (GameServer.guiCommandline) {
                    isoZombie.updateForServerGui();
                }

                ZombieCountOptimiser.incrementZombie(isoZombie);
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
        if (this.isEnabled && !GameServer.server) {
            if (isoMovingObject instanceof BaseVehicle baseVehicle) {
                return baseVehicle.apocBrGetClientSimulationLevel();
            }

            if (isoMovingObject instanceof IsoAnimal isoAnimal) {
                return isoAnimal.apocBrGetClientSimulationLevel();
            }

            UpdateSchedulerSimulationLevel minSim = isoMovingObject.getMinimumSimulationLevel();
            if (minSim == UpdateSchedulerSimulationLevel.FULL) {
                return minSim;
            } else if (isoMovingObject.getDoRender() && !isoMovingObject.isSceneCulled()) {
                float distanceSq = Float.MAX_VALUE;
                int levelSeparation = Integer.MAX_VALUE;
                float alpha = 0.0F;
                float targetAlpha = 0.0F;
                int objectZi = isoMovingObject.getZi();
                float objectX = isoMovingObject.getX();
                float objectY = isoMovingObject.getY();
                boolean isPlayer = false;

                for (int playerIndex = 0; playerIndex < IsoPlayer.numPlayers; playerIndex++) {
                    float playerX = apocbrPlayerX[playerIndex];
                    if (Float.isNaN(playerX)) {
                        continue;
                    }

                    if (IsoPlayer.players[playerIndex] == isoMovingObject) {
                        isPlayer = true;
                        break;
                    }

                    float dx = objectX - playerX;
                    float dy = objectY - apocbrPlayerY[playerIndex];
                    float distSq = dx * dx + dy * dy;
                    if (distSq < distanceSq) {
                        distanceSq = distSq;
                    }

                    int sep = PZMath.abs(objectZi - apocbrPlayerZi[playerIndex]);
                    if (sep < levelSeparation) {
                        levelSeparation = sep;
                    }

                    alpha = PZMath.max(isoMovingObject.getAlpha(playerIndex), alpha);
                    targetAlpha = PZMath.max(isoMovingObject.getTargetAlpha(playerIndex), targetAlpha);
                }

                if (isPlayer) {
                    return UpdateSchedulerSimulationLevel.FULL;
                }

                if (distanceSq > 40000.0F && alpha < 0.1F && targetAlpha < 0.1F) {
                    return UpdateSchedulerSimulationLevel.SIXTEENTH.max(minSim);
                }

                UpdateSchedulerSimulationLevel sim = UpdateSchedulerSimulationLevel.FULL;
                if (alpha < 0.25F && targetAlpha < 0.25F) {
                    sim = sim.less();
                    if (distanceSq > 100.0F) {
                        sim = sim.less();
                    }

                    if (levelSeparation > 1) {
                        sim = minSim;
                    }
                }

                if (distanceSq > 900.0F) {
                    sim = sim.less();
                }

                if (distanceSq > 3600.0F) {
                    sim = sim.less();
                    if (averageFps < 20.0F) {
                        sim = sim.less();
                    }

                    if (averageFps < 10.0F) {
                        sim = sim.less();
                    }
                }

                if (distanceSq > 6400.0F) {
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
        if (GameServer.server) {
            ZombieCountOptimiser.deleteZombies();
        }

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
