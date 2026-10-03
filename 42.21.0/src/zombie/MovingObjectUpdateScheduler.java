// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie;

import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.characters.animals.IsoAnimal;
import zombie.core.math.PZMath;
import zombie.iso.IsoMovingObject;
import zombie.iso.IsoCell;
import zombie.iso.IsoWorld;
import zombie.network.GameServer;
import zombie.popman.NetworkZombiePacker;
import zombie.popman.ZombieCountOptimiser;
import zombie.util.list.PZArrayUtil;
import zombie.vehicles.BaseVehicle;

public final class MovingObjectUpdateScheduler {
    public static final MovingObjectUpdateScheduler instance = new MovingObjectUpdateScheduler();
    private final MovingObjectUpdateSchedulerUpdateBucket[] simulationLevels;
    private long frameCounter;
    private boolean isEnabled = true;
    private final IdentityHashMap<IsoMovingObject, ServerMembership> serverMembership = new IdentityHashMap<>();
    private IsoCell serverCell;

    private static final class ServerMembership {
        UpdateSchedulerSimulationLevel level;
        long seenFrame;

        ServerMembership(UpdateSchedulerSimulationLevel level, long frame) {
            this.level = level;
            this.seenFrame = frame;
        }
    }

    private MovingObjectUpdateScheduler() {
        this.simulationLevels = new MovingObjectUpdateSchedulerUpdateBucket[UpdateSchedulerSimulationLevel.numValues()];

        for (UpdateSchedulerSimulationLevel simulationLevel : UpdateSchedulerSimulationLevel.allValues()) {
            this.simulationLevels[simulationLevel.getUpdateOrderIndex()] = new MovingObjectUpdateSchedulerUpdateBucket(simulationLevel);
        }
    }

    public long getFrameCounter() {
        return this.frameCounter;
    }

    public void startFrame() {
        if (GameServer.server) {
            // Optional threaded animation must finish using the previous frame's buckets
            // before persistent membership or the frame counter can change. Join outside
            // the scheduler monitor: postupdate/removal callbacks acquire that monitor.
            IsoWorld.instance.FinishAnimation();
        }
        synchronized (this) {
            this.startFrameInternal();
        }
    }

    private void startFrameInternal() {
        this.frameCounter++;
        boolean server = GameServer.server;
        float averageFps = server ? 0.0F : GameWindow.averageFPS;
        IsoCell cell = IsoWorld.instance.getCell();
        if (!server || this.serverCell != cell) {
            PZArrayUtil.forEach(this.simulationLevels, MovingObjectUpdateSchedulerUpdateBucket::clear);
            this.serverMembership.clear();
            this.serverCell = server ? cell : null;
        }
        long classificationStarted = 0L;
        if (server) {
            NetworkZombiePacker.getInstance().awaitWorkers();
            ZombieCountOptimiser.prepareZombiesForDeletion();
            classificationStarted = System.nanoTime();
            ServerMovingObjectIndex.beginFrame(cell);
            for (MovingObjectUpdateSchedulerUpdateBucket bucket : this.simulationLevels) {
                bucket.compact();
            }
        }

        int scheduled = 0;
        for (IsoMovingObject isoMovingObject : cell.getObjectList()) {
            if (server) {
                ServerMovingObjectIndex.classify(isoMovingObject);
            }
            if (GameServer.server && isoMovingObject instanceof IsoZombie isoZombie) {
                if (GameServer.guiCommandline) {
                    isoZombie.updateForServerGui();
                }
            } else {
                if (isoMovingObject.getCurrentSquare() == null) {
                    isoMovingObject.setCurrentSquareFromPosition();
                }

                UpdateSchedulerSimulationLevel sim = this.getUpdateSchedulerSimulationLevelForObject(isoMovingObject, averageFps);
                if (server) {
                    scheduled++;
                    ServerMembership membership = this.serverMembership.get(isoMovingObject);
                    if (membership == null) {
                        membership = new ServerMembership(sim, this.frameCounter);
                        this.serverMembership.put(isoMovingObject, membership);
                        this.simulationLevels[sim.getUpdateOrderIndex()].add(isoMovingObject);
                        ApocBRServerTelemetryLite.count("movingObjects.schedulerAdded", 1L);
                    } else {
                        membership.seenFrame = this.frameCounter;
                        if (membership.level != sim) {
                            this.simulationLevels[membership.level.getUpdateOrderIndex()].removeObject(isoMovingObject);
                            this.simulationLevels[sim.getUpdateOrderIndex()].add(isoMovingObject);
                            membership.level = sim;
                            ApocBRServerTelemetryLite.count("movingObjects.schedulerLevelChanges", 1L);
                        }
                    }
                } else {
                    this.simulationLevels[sim.getUpdateOrderIndex()].add(isoMovingObject);
                }
            }
        }

        if (server) {
            // Mods and animal virtualization can remove directly from objectList, bypassing
            // removeObject. Sweep only when the authoritative pass found missing members.
            if (scheduled != this.serverMembership.size()) {
                Iterator<Map.Entry<IsoMovingObject, ServerMembership>> entries = this.serverMembership.entrySet().iterator();
                while (entries.hasNext()) {
                    Map.Entry<IsoMovingObject, ServerMembership> entry = entries.next();
                    if (entry.getValue().seenFrame != this.frameCounter) {
                        this.simulationLevels[entry.getValue().level.getUpdateOrderIndex()].removeObject(entry.getKey());
                        entries.remove();
                    }
                }
            }
            ServerMovingObjectIndex.finishFrameIndex();
            ApocBRServerTelemetryLite.recordPhase("simulation.movingObjects.classify", System.nanoTime() - classificationStarted);
            ApocBRServerTelemetryLite.count("movingObjects.classified", cell.getObjectList().size());
        }
    }

    private UpdateSchedulerSimulationLevel getUpdateSchedulerSimulationLevelForObject(IsoMovingObject isoMovingObject, float averageFps) {
        if (GameServer.server) {
            if (isoMovingObject instanceof BaseVehicle baseVehicle) {
                return getServerSimulationLevelForVehicle(baseVehicle);
            } else if (isoMovingObject instanceof IsoAnimal isoAnimal) {
                return getServerSimulationLevelForAnimal(isoAnimal);
            } else {
                return isoMovingObject.getMinimumSimulationLevel();
            }
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

    private static UpdateSchedulerSimulationLevel getServerSimulationLevelForVehicle(BaseVehicle vehicle) {
        if (vehicle.getDriver() != null
            || vehicle.isMechanicUIOpen()
            || vehicle.needPartsUpdate()
            || vehicle.getEngineState() != BaseVehicle.engineStateTypes.Idle
            || vehicle.isAlarmActive()
            || vehicle.isSirenActive()
            || vehicle.lightbarLightsMode.isEnable()
            || vehicle.lightbarSirenMode.isEnable()
            || vehicle.getVehicleTowedBy() != null
            || vehicle.getVehicleTowing() != null
            || !vehicle.isAtRest()
            || !vehicle.getAnimals().isEmpty()) {
            return UpdateSchedulerSimulationLevel.FULL;
        }

        for (int seat = 0; seat < vehicle.getMaxPassengers(); seat++) {
            IsoGameCharacter character = vehicle.getCharacter(seat);
            if (character != null) {
                return UpdateSchedulerSimulationLevel.FULL;
            }
        }

        return UpdateSchedulerSimulationLevel.SIXTEENTH;
    }

    private static UpdateSchedulerSimulationLevel getServerSimulationLevelForAnimal(IsoAnimal animal) {
        if (animal.heldBy != null
            || animal.luredBy != null
            || animal.atkTarget != null
            || animal.fightingOpponent != null
            || animal.thumpTarget != null
            || animal.alerted
            || animal.alertedChr != null
            || animal.walkToCharLuring
            || animal.getVehicle() != null
            || animal.isOnHook()) {
            return UpdateSchedulerSimulationLevel.HALF;
        }

        return UpdateSchedulerSimulationLevel.SIXTEENTH;
    }

    public synchronized void update() {
        long started = GameServer.server ? System.nanoTime() : 0L;
        try {
            for (MovingObjectUpdateSchedulerUpdateBucket simulation : this.simulationLevels) {
                simulation.update((int)this.frameCounter);
            }
        } finally {
            if (GameServer.server) {
                ApocBRServerTelemetryLite.recordPhase("simulation.movingObjects.update", System.nanoTime() - started);
            }
        }
    }

    public synchronized void postupdate() {
        long started = GameServer.server ? System.nanoTime() : 0L;
        try {
            if (GameServer.server) {
                ZombieCountOptimiser.deleteZombies();
            }

            for (MovingObjectUpdateSchedulerUpdateBucket simulation : this.simulationLevels) {
                simulation.postupdate((int)this.frameCounter);
            }
        } finally {
            if (GameServer.server) {
                ApocBRServerTelemetryLite.recordPhase("simulation.movingObjects.postupdate", System.nanoTime() - started);
            }
        }
    }

    public boolean isEnabled() {
        return this.isEnabled;
    }

    public void setEnabled(boolean enabled) {
        this.isEnabled = enabled;
    }

    public synchronized void removeObject(IsoMovingObject object) {
        if (GameServer.server) {
            ServerMembership membership = this.serverMembership.remove(object);
            if (membership != null) {
                this.simulationLevels[membership.level.getUpdateOrderIndex()].removeObject(object);
                ApocBRServerTelemetryLite.count("movingObjects.schedulerRemoved", 1L);
            }
        } else {
            PZArrayUtil.forEach(this.simulationLevels, object, MovingObjectUpdateSchedulerUpdateBucket::removeObject);
        }
    }
}
