// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie;

import java.util.ArrayList;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.core.math.PZMath;
import zombie.iso.IsoMovingObject;
import zombie.iso.IsoWorld;
import zombie.network.GameServer;
import zombie.vehicles.BaseVehicle;
import zombie.characters.animals.IsoAnimal;
import zombie.core.raknet.UdpConnection;

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
        long apocBrStartFrameStart = System.nanoTime();
        int apocBrServerZombieCount = 0;
        int apocBrZombieGuiUpdates = 0;

        for (IsoMovingObject isoMovingObject : IsoWorld.instance.getCell().getObjectList()) {
            if (GameServer.server && isoMovingObject instanceof IsoZombie isoZombie) {
                if (GameServer.guiCommandline) {
                    isoZombie.updateForServerGui();
                    apocBrZombieGuiUpdates++;
                }

                apocBrServerZombieCount++;
            } else {
                if (isoMovingObject.getCurrentSquare() == null) {
                    long apocBrSquareFixStart = System.nanoTime();
                    isoMovingObject.setCurrentSquareFromPosition();
                    ApocBRServerTelemetry.recordMovingStartFrameSquareFix(System.nanoTime() - apocBrSquareFixStart);
                }

                UpdateSchedulerSimulationLevel simulationLevel = this.getUpdateSchedulerSimulationLevelForObject(isoMovingObject, averageFps);
                if (GameServer.server) {
                    ApocBRServerTelemetry.recordMovingStartFrameBucket(isoMovingObject.getClass().getSimpleName(), simulationLevel.name().toLowerCase());
                }

                switch (simulationLevel) {
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

        ApocBRServerTelemetry.recordMovingStartFrame(IsoWorld.instance.getCell().getObjectList().size(), System.nanoTime() - apocBrStartFrameStart);
        ApocBRServerTelemetry.recordMovingStartFrameServerZombies(apocBrServerZombieCount, apocBrZombieGuiUpdates, 0L);
    }

    private UpdateSchedulerSimulationLevel getUpdateSchedulerSimulationLevelForObject(IsoMovingObject isoMovingObject, float averageFps) {
        if (GameServer.server) {
            if (isoMovingObject instanceof BaseVehicle baseVehicle) {
                return baseVehicle.apocBrGetServerSimulationLevel();
            }

            if (isoMovingObject instanceof IsoAnimal isoAnimal) {
                if (isoAnimal.heldBy != null
                    || isoAnimal.luredBy != null
                    || isoAnimal.atkTarget != null
                    || isoAnimal.fightingOpponent != null
                    || isoAnimal.thumpTarget != null
                    || isoAnimal.alerted
                    || isoAnimal.alertedChr != null
                    || isoAnimal.walkToCharLuring
                    || isoAnimal.getVehicle() != null
                    || isoAnimal.isOnHook()) {
                    return UpdateSchedulerSimulationLevel.HALF;
                }

                float x = isoAnimal.getX();
                float y = isoAnimal.getY();
                if (GameServer.udpEngine != null) {
                    for (UdpConnection connection : GameServer.udpEngine.connections) {
                        if (connection != null && connection.isRelevantTo(x, y)) {
                            return UpdateSchedulerSimulationLevel.HALF;
                        }
                    }
                }

                return UpdateSchedulerSimulationLevel.SIXTEENTH;
            }

            return isoMovingObject.getMinimumSimulationLevel();
        } else if (this.isEnabled) {
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
