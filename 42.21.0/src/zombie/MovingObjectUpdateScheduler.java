// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie;

import java.util.IdentityHashMap;
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
        final ServerMovingObjectIndex.Member source;
        UpdateSchedulerSimulationLevel level;

        ServerMembership(ServerMovingObjectIndex.Member source, UpdateSchedulerSimulationLevel level) {
            this.source = source;
            this.level = level;
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
        if (server) {
            NetworkZombiePacker.getInstance().awaitWorkers();
            try (ApocBRServerTelemetryLite.Scope telemetry = ApocBRServerTelemetryLite.phase("simulation.zombies.cullPrepare")) {
                ZombieCountOptimiser.prepareZombiesForDeletion();
            }
            long started = System.nanoTime();
            ServerMovingObjectIndex index = ServerMovingObjectIndex.forCell(cell);
            this.drainLifecycleRemovals(index);
            index.compact();
            for (MovingObjectUpdateSchedulerUpdateBucket bucket : this.simulationLevels) bucket.compact();
            ApocBRServerTelemetryLite.recordPhase("simulation.movingObjects.lifecycle", System.nanoTime() - started);

            started = System.nanoTime();
            int checked = 0;
            for (ServerMovingObjectIndex.Member entry : index.getScheduledMembers()) {
                if (entry == null || !entry.active) continue;
                IsoMovingObject object = entry.object;
                if (object.getCurrentSquare() == null) object.setCurrentSquareFromPosition();
                UpdateSchedulerSimulationLevel level = switch (entry.kind) {
                    case ServerMovingObjectIndex.VEHICLE -> getServerSimulationLevelForVehicle((BaseVehicle)object);
                    case ServerMovingObjectIndex.ANIMAL -> getServerSimulationLevelForAnimal((IsoAnimal)object);
                    default -> object.getMinimumSimulationLevel();
                };
                checked++;
                ServerMembership membership = this.serverMembership.get(object);
                if (membership != null && membership.source != entry) {
                    this.removeObject(object); // A pooled/re-added instance begins a new active lifetime.
                    membership = null;
                }
                if (membership == null) {
                    this.serverMembership.put(object, new ServerMembership(entry, level));
                    this.simulationLevels[level.getUpdateOrderIndex()].add(object);
                    ApocBRServerTelemetryLite.count("movingObjects.schedulerAdded", 1L);
                } else if (membership.level != level) {
                    this.simulationLevels[membership.level.getUpdateOrderIndex()].removeObject(object);
                    this.simulationLevels[level.getUpdateOrderIndex()].add(object);
                    membership.level = level;
                    ApocBRServerTelemetryLite.count("movingObjects.schedulerLevelChanges", 1L);
                }
            }
            // Server GUI work is optional; dedicated servers never traverse zombie targets here.
            if (GameServer.guiCommandline) {
                for (IsoMovingObject target : ServerMovingObjectIndex.getPerceptionTargets(cell)) {
                    if (target instanceof IsoZombie zombie) zombie.updateForServerGui();
                }
            }
            ApocBRServerTelemetryLite.count("movingObjects.activityChecked", checked);
            ApocBRServerTelemetryLite.recordPhase("simulation.movingObjects.activity", System.nanoTime() - started);
            return;
        }

        ServerMovingObjectIndex clientIndex = ServerMovingObjectIndex.forCell(cell);
        clientIndex.drainRemoved(entry -> {});
        clientIndex.compact();
        // Client distance/render scheduling retains its original world pass.
        for (IsoMovingObject object : cell.getObjectList()) {
            if (object.getCurrentSquare() == null) object.setCurrentSquareFromPosition();
            UpdateSchedulerSimulationLevel level = this.getUpdateSchedulerSimulationLevelForObject(object, averageFps);
            this.simulationLevels[level.getUpdateOrderIndex()].add(object);
        }
    }

    private void drainLifecycleRemovals(ServerMovingObjectIndex index) {
        index.drainRemoved(entry -> {
            ServerMembership membership = this.serverMembership.get(entry.object);
            if (membership != null && membership.source == entry) this.removeObject(entry.object);
        });
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
            if (GameServer.server) this.drainLifecycleRemovals(ServerMovingObjectIndex.forCell(IsoWorld.instance.getCell()));
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
                this.drainLifecycleRemovals(ServerMovingObjectIndex.forCell(IsoWorld.instance.getCell()));
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
