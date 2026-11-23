package cloud.apocalipse.pz.agent.features.optimization;

import cloud.apocalipse.pz.agent.AgentModules;
import cloud.apocalipse.pz.agent.features.optimization.entity.EntityDueIndex;
import cloud.apocalipse.pz.agent.features.optimization.entity.EntitySimulationClock;
import cloud.apocalipse.pz.agent.features.optimization.los.ServerLosModule;
import cloud.apocalipse.pz.agent.features.telemetry.OptimizationTelemetry;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.Stack;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import zombie.GameTime;
import zombie.characters.IsoGameCharacter.LightInfo;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.characters.Stats;
import zombie.core.raknet.RakNetPeerInterface;
import zombie.core.raknet.UdpConnection;
import zombie.core.raknet.UdpEngine;
import zombie.entity.Engine;
import zombie.entity.EngineSystem;
import zombie.entity.EntityBucket;
import zombie.entity.EntitySimulation;
import zombie.entity.GameEntity;
import zombie.entity.GameEntityManager;
import zombie.entity.GameEntityType;
import zombie.entity.MetaEntity;
import zombie.entity.UsingPlayerUpdateSystem;
import zombie.entity.util.ImmutableArray;
import zombie.iso.IsoCell;
import zombie.iso.IsoChunk;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.IsoWorld;
import zombie.iso.WorldReuserThread;
import zombie.network.PacketTypes.PacketType;
import zombie.network.ServerLOS;
import zombie.network.ServerMap;
import zombie.popman.NetworkZombiePacker;

import static cloud.apocalipse.pz.agent.features.optimization.OptimizationOriginalSmokeMain.allocate;
import static cloud.apocalipse.pz.agent.features.optimization.OptimizationOriginalSmokeMain.check;
import static cloud.apocalipse.pz.agent.features.optimization.OptimizationOriginalSmokeMain.field;
import static cloud.apocalipse.pz.agent.features.optimization.OptimizationOriginalSmokeMain.gameClass;

final class LegacyHotspotsOriginalSmoke {
    private LegacyHotspotsOriginalSmoke() {
    }

    static void run(Path evidenceDirectory, boolean enabled) throws Exception {
        checkHooks(evidenceDirectory, enabled);
        if (enabled) {
            entityRemovalAndSimulation();
            serverLos();
            asyncTransmission();
        }
    }

    private static void checkHooks(Path directory, boolean enabled) throws Exception {
        Set<String> scoped = Set.of("entity-removal-index", "entity-simulation-catchup", "server-los-workers",
            "zombie-async-transmission");
        for (var module : AgentModules.all()) {
            if (!scoped.contains(module.name())) {
                continue;
            }
            check((module.disabledReason() == null) == enabled, "Wrong hotspot configuration: " + module.name());
            for (var hook : module.hookChecks()) {
                gameClass(hook.targetClass()).getDeclaredMethods();
                Properties evidence = new Properties();
                Path evidenceFile = directory.resolve(hook.targetClass() + ".properties");
                if (Files.isRegularFile(evidenceFile)) {
                    try (var input = Files.newInputStream(evidenceFile)) {
                        evidence.load(input);
                    }
                }
                String key = hook.method() + "|" + hook.helperClass().replace('.', '/') + "|" + hook.helperMethod();
                check(evidence.containsKey(key) == enabled, "Wrong hotspot hook evidence: " + module.name() + "/" + hook.id());
            }
        }
    }

    private static void entityRemovalAndSimulation() throws Exception {
        Engine engine = new Engine();
        var add = Engine.class.getDeclaredMethod("addEntity", GameEntity.class);
        var remove = Engine.class.getDeclaredMethod("removeEntity", GameEntity.class);
        var entities = Engine.class.getDeclaredMethod("getEntities");
        var addSystem = Engine.class.getDeclaredMethod("addSystem", EngineSystem.class);
        for (var method : List.of(add, remove, entities, addSystem)) {
            method.setAccessible(true);
        }

        IsoObject first = new IsoObject();
        IsoObject middle = new IsoObject();
        IsoObject tail = new IsoObject();
        var ensureComponents = GameEntity.class.getDeclaredMethod("ensureComponents");
        ensureComponents.setAccessible(true);
        for (GameEntity entity : List.of(first, middle, tail)) {
            ensureComponents.invoke(entity);
        }
        add.invoke(engine, first);
        add.invoke(engine, middle);
        add.invoke(engine, tail);
        @SuppressWarnings("unchecked")
        ImmutableArray<GameEntity> view = (ImmutableArray<GameEntity>) entities.invoke(engine);
        remove.invoke(engine, first);
        check(view.size() == 2 && view.get(0) == tail && view.get(1) == middle, "Vanilla tail swap changed");
        remove.invoke(engine, tail);
        check(view.size() == 1 && view.get(0) == middle, "Swapped identity position was not updated");
        add.invoke(engine, first);

        EntityBucket bucket = engine.registerCustomBucket("hotspot-original-fixture", entity -> true);
        var constructor = MetaEntity.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        MetaEntity due = constructor.newInstance();
        MetaEntity deferred = constructor.newInstance();
        ensureComponents.invoke(due);
        ensureComponents.invoke(deferred);
        field(MetaEntity.class, "entityNetId").setLong(due, 9);
        field(MetaEntity.class, "entityNetId").setLong(deferred, 5);
        add.invoke(engine, due);
        add.invoke(engine, deferred);

        List<Integer> elapsed = new ArrayList<>();
        List<List<GameEntity>> chosen = new ArrayList<>();
        addSystem.invoke(engine, new EngineSystem(false, true, 0) {
            @Override
            public void updateSimulation() {
                elapsed.add(EntitySimulationClock.effectiveTicks());
                List<GameEntity> frame = new ArrayList<>();
                for (GameEntity entity : EntityDueIndex.select(bucket)) {
                    frame.add(entity);
                }
                chosen.add(frame);
            }
        });
        field(GameEntityManager.class, "engine").set(null, engine);
        float oldMultiplier = field(GameTime.class, "multiplier").getFloat(GameTime.getInstance());
        try {
            GameTime.getInstance().setMultiplier(19.0F);
            GameEntityManager.Update();
            check(EntitySimulation.getSimulationTicksThisFrame() == 3, "Fixture did not produce three native ticks");
            check(elapsed.equals(List.of(3)), "Catch-up did not collapse three native ticks into one pass");
            check(chosen.equals(List.of(List.of(middle, first, due))), "Wrong phase selection or bucket order");
            remove.invoke(engine, middle);
            check(!bucket.getEntities().contains(middle, true), "Native bucket retained the removed entity");
            field(MetaEntity.class, "entityNetId").setLong(deferred, 6);
            EntityDueIndex.refresh(deferred);
            GameTime.getInstance().setMultiplier(6.0F);
            GameEntityManager.Update();
            check(elapsed.equals(List.of(3, 1)), "Next frame inherited the previous delta");
            List<GameEntity> currentOrder = new ArrayList<>();
            for (GameEntity entity : bucket.getEntities()) {
                if (entity == first || entity == deferred) {
                    currentOrder.add(entity);
                }
            }
            check(currentOrder.size() == 2 && chosen.get(1).equals(currentOrder),
                "Due index missed removal or a phase change: " + chosen.get(1).stream().map(GameEntity::getEntityNetID).toList()
                    + " instead of " + currentOrder.stream().map(GameEntity::getEntityNetID).toList());
            check(EntitySimulationClock.effectiveTicks() == 1, "Simulation delta leaked out of the frame");
            usingPlayer(engine, bucket);
        } finally {
            GameTime.getInstance().setMultiplier(oldMultiplier);
            GameEntityManager.Reset();
        }
    }

    private static void usingPlayer(Engine engine, EntityBucket bucket) throws Exception {
        IsoObject entity = new IsoObject() {
            @Override public GameEntityType getGameEntityType() { return GameEntityType.VehiclePart; }
            @Override public float getX() { return 0; }
            @Override public float getY() { return 0; }
            @Override public float getZ() { return 0; }
        };
        var ensureComponents = GameEntity.class.getDeclaredMethod("ensureComponents");
        ensureComponents.setAccessible(true);
        ensureComponents.invoke(entity);
        var add = Engine.class.getDeclaredMethod("addEntity", GameEntity.class);
        add.setAccessible(true);
        add.invoke(engine, entity);

        var system = new UsingPlayerUpdateSystem(0);
        field(UsingPlayerUpdateSystem.class, "isoEntities").set(system, bucket);
        system.update(); // Build the index before the native setter activates this member.
        IsoPlayer player = allocate(IsoPlayer.class);
        field(zombie.characters.IsoGameCharacter.class, "health").setFloat(player, 1);
        entity.setUsingPlayer(player);
        system.update();
        check(entity.getUsingPlayer() == player, "The using-player index cleared an in-range player");
        player.setX(11);
        system.update();
        check(entity.getUsingPlayer() == null, "The using-player index missed a member activated by the setter");
    }

    private static void serverLos() throws Exception {
        ServerMap previous = ServerMap.instance;
        IsoCell cell = new IsoCell(1, 1);
        IsoGridSquare square = new IsoGridSquare(cell, null, 0, 0, 0);
        field(IsoGridSquare.class, "chunk").set(square, allocate(IsoChunk.class));
        ServerMap.instance = new ServerMap() {
            @Override
            public IsoGridSquare getGridSquare(int x, int y, int z) {
                return x == 0 && y == 0 && z == 0 ? square : null;
            }
        };
        LosPlayer player = allocate(LosPlayer.class);
        player.light = new LightInfo();
        player.light.square = square;
        try {
            ServerLOS.init();
            ServerLOS los = ServerLOS.instance;
            los.addPlayer(player);
            los.doServerZombieLOS(player);
            await(() -> {
                los.updateLOS(player);
                return player.completions == 1;
            }, "LOS result was not applied");
            check(los.isCouldSee(player, square), "Native visibility was not published");
            los.suspend();
            check(!los.isCouldSee(player, square), "Vanilla suspend hook retained visibility across world mutation");
            los.doServerZombieLOS(player);
            los.updateLOS(player);
            check(player.completions == 1, "Suspended LOS accepted work");
            los.resume();
            los.doServerZombieLOS(player);
            await(() -> {
                los.updateLOS(player);
                return player.completions == 2;
            }, "LOS resume did not schedule a fresh result");
            los.removePlayer(player);
            check(!los.isCouldSee(player, square), "Player removal retained LOS");
            spatialPerception(cell, square);
        } finally {
            ServerLosModule.shutdown();
            WorldReuserThread.instance.stop();
            ServerMap.instance = previous;
        }
    }

    private static void spatialPerception(IsoCell cell, IsoGridSquare square) throws Exception {
        IsoPlayer player = allocate(IsoPlayer.class);
        field(zombie.characters.IsoGameCharacter.class, "stats").set(player, new Stats());
        field(IsoPlayer.class, "spottedList").set(player, new Stack<>());
        field(IsoPlayer.class, "lastSpotted").set(player, new Stack<>());
        player.setCurrent(square);

        IsoZombie nearby = allocate(IsoZombie.class);
        IsoZombie distant = allocate(IsoZombie.class);
        distant.setX(1000);
        cell.getZombieList().add(nearby);
        cell.getZombieList().add(distant);
        player.updateLOS();
        check(losCandidateCounter() == 1, "Dedicated LOS did not restrict candidates by tile");
        distant.setX(0);
        player.updateLOS();
        check(losCandidateCounter() == 2, "Cross-tile movement did not refresh the shared zombie view");
        nearby.setX(1000);
        cell.getZombieList().set(1, nearby);
        player.updateLOS();
        check(losCandidateCounter() == 0, "Same-size list replacement retained stale zombie candidates");
    }

    private static long losCandidateCounter() throws ReflectiveOperationException {
        var takeSnapshot = OptimizationTelemetry.class.getDeclaredMethod("snapshotAndReset");
        takeSnapshot.setAccessible(true);
        Object snapshot = takeSnapshot.invoke(null);
        var counters = snapshot.getClass().getDeclaredMethod("counters");
        counters.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Long> values = (Map<String, Long>) counters.invoke(snapshot);
        return values.getOrDefault("players.los.zombieCandidates", 0L);
    }

    private static void asyncTransmission() throws Exception {
        Class<?> server = gameClass("zombie.network.GameServer");
        IsoCell cell = allocate(IsoCell.class);
        field(IsoCell.class, "zombieList").set(cell, new ArrayList<>());
        field(gameClass("zombie.iso.IsoWorld"), "currentCell").set(IsoWorld.instance, cell);
        UdpEngine engine = allocate(UdpEngine.class);
        RecordingPeer peer = new RecordingPeer();
        field(UdpEngine.class, "peer").set(engine, peer);
        field(UdpEngine.class, "thread").set(engine, new Thread());
        field(UdpEngine.class, "server").setBoolean(engine, true);
        field(UdpEngine.class, "connections").set(engine, new ArrayList<>());
        field(UdpEngine.class, "connectionMap").set(engine, new HashMap<>());
        field(UdpEngine.class, "connectionArray").set(engine, new UdpConnection[256]);
        field(server, "udpEngine").set(null, engine);

        var add = UdpEngine.class.getDeclaredMethod("addConnection", int.class, long.class);
        add.setAccessible(true);
        UdpConnection connection = (UdpConnection) add.invoke(engine, 0, 71L);
        field(UdpConnection.class, "fullyConnected").setBoolean(connection, true);
        engine.connections.add(connection);
        NetworkZombiePacker packer = NetworkZombiePacker.getInstance();
        try {
            packer.postupdate();
            check(peer.firstAttempt.await(4, TimeUnit.SECONDS), "Vanilla postupdate never reached async transport");
            check(peer.ids.peek() == PacketType.ZombieList.getId(), "Wrong first wire packet");
            packer.postupdate();
            check(peer.ids.size() == 1, "A second batch bypassed the session's in-flight limit");
            peer.allowFirst.countDown();
            await(() -> {
                packer.postupdate();
                return peer.ids.size() >= 3;
            }, "Transport failure did not trigger an authorization retry and sync");
            check(peer.ids.stream().filter(id -> id == PacketType.ZombieList.getId()).count() == 2,
                "Authorization retry count changed");
            check(peer.workerThread.get(), "Wire serialization did not reach worker transport");
            engine.Shutdown();
            check(peer.stopped && !peer.sending.get(), "Native peer shut down with active transmission");
        } finally {
            peer.allowFirst.countDown();
            if (!peer.stopped) {
                engine.Shutdown();
            }
            field(server, "udpEngine").set(null, null);
        }
    }

    private static void await(BooleanSupplier condition, String failure) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (!condition.getAsBoolean()) {
            check(System.nanoTime() - until < 0, failure);
            Thread.sleep(5L);
        }
    }

    private static final class LosPlayer extends IsoPlayer {
        LightInfo light;
        int completions;

        LosPlayer() {
            super(null);
        }

        @Override public LightInfo initLightInfo2() { return light; }
        @Override public LightInfo getLightInfo2() { return light; }
        @Override public void updateLOS() { completions++; }
    }

    private static final class RecordingPeer extends RakNetPeerInterface {
        final CountDownLatch firstAttempt = new CountDownLatch(1);
        final CountDownLatch allowFirst = new CountDownLatch(1);
        final ConcurrentLinkedQueue<Short> ids = new ConcurrentLinkedQueue<>();
        final AtomicBoolean sending = new AtomicBoolean();
        final AtomicBoolean workerThread = new AtomicBoolean();
        volatile boolean stopped;

        @Override
        public String getIPFromGUID(long guid) {
            return "127.0.0.1|16261";
        }

        @Override
        public int SendRaw(ByteBuffer data, int priority, int reliability, byte orderingChannel, long guid, boolean broadcast) {
            check(!stopped && guid == 71L && !broadcast && data.isDirect(), "Wrong captured transport endpoint");
            check(data.get() == (byte) 134, "Wrong vanilla packet marker");
            workerThread.set(Thread.currentThread().getName().startsWith("ApocBR-ZombieSend-"));
            sending.set(true);
            try {
                ids.add(data.getShort());
                if (ids.size() == 1) {
                    firstAttempt.countDown();
                    check(allowFirst.await(4, TimeUnit.SECONDS), "Fixture did not release the first send");
                    return 0;
                }
                return 1;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            } finally {
                sending.set(false);
            }
        }

        @Override
        public void Shutdown() {
            check(!sending.get(), "Peer shutdown ran before worker cleanup");
            stopped = true;
        }
    }
}
