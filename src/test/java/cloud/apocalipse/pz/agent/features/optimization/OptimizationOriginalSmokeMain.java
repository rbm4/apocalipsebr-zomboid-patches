package cloud.apocalipse.pz.agent.features.optimization;

import cloud.apocalipse.pz.agent.AgentModules;
import cloud.apocalipse.pz.agent.features.optimization.zombie.packet.ZombiePacketWork;
import cloud.apocalipse.pz.agent.features.telemetry.OptimizationTelemetry;
import cloud.apocalipse.pz.agent.features.telemetry.TelemetryRegistry;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import se.krka.kahlua.converter.KahluaConverterManager;
import se.krka.kahlua.integration.LuaCaller;
import se.krka.kahlua.vm.KahluaThread;
import se.krka.kahlua.vm.LuaClosure;
import se.krka.kahlua.vm.Prototype;
import sun.misc.Unsafe;
import zombie.Lua.Event;
import zombie.SandboxOptions;
import zombie.ZomboidFileSystem;
import zombie.characterTextures.BloodClothingType;
import zombie.characters.BodyDamage.BodyDamage;
import zombie.characters.BodyDamage.Thermoregulator;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.characters.NetworkCharacter;
import zombie.characters.NetworkPlayerAI;
import zombie.characters.NetworkZombieAI;
import zombie.characters.NetworkZombieVariables;
import zombie.characters.Safety;
import zombie.characters.Stats;
import zombie.characters.animals.AnimalDefinitions;
import zombie.characters.animals.IsoAnimal;
import zombie.characters.animals.behavior.BaseAnimalBehavior;
import zombie.core.raknet.UdpConnection;
import zombie.core.raknet.UdpEngine;
import zombie.core.network.ByteBufferWriter;
import zombie.core.random.RandAbstract;
import zombie.core.random.RandLua;
import zombie.core.random.RandStandard;
import zombie.core.skinnedmodel.animation.debug.AnimationPlayerRecorder;
import zombie.core.skinnedmodel.BaseGrappleable;
import zombie.core.skinnedmodel.advancedanimation.AnimationVariableSource;
import zombie.core.skinnedmodel.visual.HumanVisual;
import zombie.core.skinnedmodel.visual.ItemVisual;
import zombie.core.skinnedmodel.visual.ItemVisuals;
import zombie.debug.DebugOptions;
import zombie.inventory.types.Clothing;
import zombie.iso.Vector2;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.areas.isoregion.data.DataRoot;
import zombie.network.NetworkVariables.WalkType;
import zombie.network.NetworkVariables.ZombieState;
import zombie.network.ServerMap;
import zombie.network.packets.character.ZombiePacket;
import zombie.pathfind.PathFindBehavior2;
import zombie.popman.NetworkZombiePacker;
import zombie.popman.ZombieCountOptimiser;

/** Packaged-agent behavior fixture. Launch in a disposable JVM with the original JAR first. */
public final class OptimizationOriginalSmokeMain {
    private static final Class<?> SERVER = gameClass("zombie.network.GameServer");
    private static final Unsafe UNSAFE = unsafe();

    public static void main(String[] arguments) throws Exception {
        boolean enabled = Boolean.parseBoolean(arguments[1]);
        Path original = Path.of(arguments[0]).toRealPath();
        for (Class<?> target : List.of(SERVER, ZombieCountOptimiser.class, Event.class, IsoPlayer.class,
                NetworkZombieAI.class, ZombiePacket.class, Thermoregulator.class, Stats.class, DataRoot.class,
                gameClass("zombie.characters.animals.behavior.BaseAnimalBehavior"),
                gameClass("zombie.popman.animal.AnimalInstanceManager"),
                gameClass("zombie.MovingObjectUpdateScheduler"), gameClass("zombie.iso.IsoChunk"),
                gameClass("zombie.iso.IsoObject"),
                gameClass("zombie.characters.animals.datas.AnimalGrowStage"),
                gameClass("zombie.iso.worldgen.blending.Blending"),
                gameClass("zombie.iso.worldgen.blending.BlendDirection"))) {
            Path source = Path.of(target.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
            check(original.equals(source), "Game class came from " + source);
            target.getDeclaredMethods();
        }

        field(SERVER, "mainThread").set(null, Thread.currentThread());
        server(true);
        RandStandard.INSTANCE.init();
        RandLua.INSTANCE.init();
        ZomboidFileSystem.instance.setCacheDir(arguments[2]);
        ZomboidFileSystem.instance.init();
        gameClass("zombie.Lua.LuaManager").getMethod("init").invoke(null);
        culling(enabled);
        luaDispatch();
        nearVehicle(enabled);
        packetPrediction();
        packetBytes();
        thermalClothing(enabled);
        actorUpdateAllocations(enabled);
        verifyTelemetry(enabled);
        AnimalPerceptionOriginalSmoke.run(original, Path.of(arguments[3]), enabled);
        verifyHookEvidence(Path.of(arguments[3]), enabled);
        if (arguments.length == 5 && Boolean.parseBoolean(arguments[4])) {
            LegacyHotspotsOriginalSmoke.run(Path.of(arguments[3]), enabled);
        }
        System.out.println("ORIGINAL_OPTIMIZATION_SMOKE_PASS enabled=" + enabled);
    }

    private static void actorUpdateAllocations(boolean enabled) throws Exception {
        var stat = zombie.characters.CharacterStat.STRESS;
        var stats = new Stats();
        var values = (Map<?, ?>) field(Stats.class, "stats").get(stats);
        check(!stats.set(stat, 0.0F) && values.containsKey(stat), "First write of the default must store it");
        check(stats.set(stat, 2.0F) && stats.get(stat) == 1.0F, "Stat write did not clamp");
        Object clamped = values.get(stat);
        check(!stats.set(stat, 3.0F) && (values.get(stat) == clamped) == enabled, "Clamped repeat did not reuse the float");
        check(stats.set(stat, 0.0F) && !stats.set(stat, -0.0F)
            && Float.floatToRawIntBits(stats.get(stat)) == Integer.MIN_VALUE, "Stat write lost signed zero");
        float nan = Float.intBitsToFloat(0x7fc00001);
        check(stats.set(stat, nan) && stats.set(stat, nan)
            && Float.floatToRawIntBits(stats.get(stat)) == 0x7fc00001, "Stat write changed NaN semantics");

        var animal = allocate(PerceptionAnimal.class);
        animal.setOnlineID((short) 313);
        var manager = zombie.popman.animal.AnimalInstanceManager.getInstance();
        var dirty = (Set<?>) field(zombie.popman.animal.AnimalSynchronizationManager.class, "sendToClients").get(null);
        try {
            manager.update(animal);
            Object first = dirty.iterator().next();
            check(first.equals((short) 313), "Animal update lost the dirty ID");
            dirty.clear();
            manager.update(animal);
            check(dirty.size() == 1 && (dirty.iterator().next() == first) == enabled, "Wrong dirty-ID reuse");
        } finally {
            dirty.clear();
        }

        var cell = new IsoCell(1, 1);
        var square = new IsoGridSquare(cell, null, 0, 0, 0);
        animal.setCurrentSquare(square);
        animal.adef = new AnimalDefinitions();
        animal.playerAcceptanceList = new HashMap<>();
        var player = allocate(IsoPlayer.class);
        player.cheats = new zombie.characters.PlayerCheats();
        player.setOnlineID((short) 14);
        player.setCurrentSquare(square);
        field(zombie.characters.IsoGameCharacter.class, "health").setFloat(player, 1.0F);
        var behavior = new BaseAnimalBehavior(animal);
        animal.playerAcceptanceList.put((short) 14, 100.0F);
        Float capped = animal.playerAcceptanceList.get((short) 14);
        behavior.spotted(player, false, 2.0F);
        Float after = animal.playerAcceptanceList.get((short) 14);
        check(after == 100.0F && (after == capped) == enabled, "Wrong capped acceptance write");
        animal.playerAcceptanceList.put((short) 14, 2.0F);
        behavior.spotted(player, false, 2.0F);
        check(animal.playerAcceptanceList.get((short) 14) > 2.0F, "Perception lost the acceptance increment");
    }

    private static final class PerceptionAnimal extends IsoAnimal {
        PerceptionAnimal() {
            super(null);
        }

        @Override public boolean isAnimalMoving() {
            return true;
        }
    }

    private static void culling(boolean enabled) throws Exception {
        // Seed the native RNG before initializing ServerMap and count the candidates actually read.
        field(RandAbstract.class, "rand").set(RandStandard.INSTANCE, new Random(0));
        IsoZombie zombie = allocate(IsoZombie.class);
        ServerMap.instance.zombieMap.put((short) 1, zombie);
        UdpEngine engine = allocate(UdpEngine.class);
        UdpConnection zero = new UdpConnection(engine, 1L, 0);
        UdpConnection positive = new UdpConnection(engine, 2L, 1);
        field(UdpEngine.class, "connections").set(engine, new ArrayList<>(List.of(zero, positive)));
        field(SERVER, "udpEngine").set(null, engine);
        try {
            var packer = NetworkZombiePacker.getInstance();
            SandboxOptions.instance.zombieConfig.zombiesCountBeforeDeletion.setValue(2);
            var zeroCandidates = new CandidateIds(List.of((short) 1, (short) 2));
            var positiveCandidates = new CandidateIds(List.of((short) 1, (short) 2, (short) 3));
            packer.zombiesToSend.put(zero, zeroCandidates);
            packer.zombiesToSend.put(positive, positiveCandidates);

            // This seed yields zero on the first nextInt(3), filling the second connection's quota.
            field(RandAbstract.class, "rand").set(RandStandard.INSTANCE, new Random(0));
            ZombieCountOptimiser.prepareZombiesForDeletion();
            var queued = (List<?>) field(ZombieCountOptimiser.class, "zombiesForDelete").get(null);
            check(queued.equals(List.of(zombie)), "Changed culling selection");
            check(zeroCandidates.reads == (enabled ? 0 : 2), "Wrong zero-quota candidate reads: " + zeroCandidates.reads);
            check(positiveCandidates.reads == (enabled ? 1 : 3), "Wrong fulfilled-quota candidate reads: " + positiveCandidates.reads);
            queued.clear();

            field(RandAbstract.class, "rand").set(RandStandard.INSTANCE, new Random(0));
            var missing = new CandidateIds(List.of((short) 8, (short) 9, (short) 10));
            packer.zombiesToSend.put(positive, missing);
            ZombieCountOptimiser.prepareZombiesForDeletion();
            check(missing.reads == 3 && queued.isEmpty(), "Stopped with unfilled quota");
            packer.zombiesToSend.clear();
        } finally {
            field(SERVER, "udpEngine").set(null, null);
            ServerMap.instance.zombieMap.clear();
        }
    }

    private static void luaDispatch() throws Exception {
        var trigger = Event.class.getDeclaredMethod("trigger", se.krka.kahlua.vm.KahluaTable.class,
            LuaCaller.class, Object[].class);
        trigger.setAccessible(true);
        for (boolean slowChecks : new boolean[]{false, true}) {
            DebugOptions.instance.checks.slowLuaEvents.setValue(slowChecks);
            Event event = Event.class.getConstructor(String.class, int.class)
                .newInstance("OptimizationFixture" + slowChecks, 0);
            LuaClosure first = new LuaClosure(new Prototype(), null);
            LuaClosure second = new LuaClosure(new Prototype(), null);
            LuaClosure last = new LuaClosure(new Prototype(), null);
            LuaClosure replacement = new LuaClosure(new Prototype(), null);
            AtomicBoolean replace = new AtomicBoolean();
            List<Object> order = new ArrayList<>();
            LuaCaller caller = new LuaCaller(new KahluaConverterManager()) {
                @Override
                public void protectedCallVoid(KahluaThread thread, Object closure, Object[] values) {
                    order.add(closure);
                    if (closure == first) {
                        if (replace.get()) {
                            event.callbacks.set(0, replacement);
                        } else {
                            event.callbacks.remove(first);
                        }
                    }
                }
            };
            check(!((boolean) trigger.invoke(event, null, caller, new Object[0])), "Empty event changed its result");
            event.callbacks.addAll(List.of(first, second, last));
            check((boolean) trigger.invoke(event, null, caller, new Object[0]), "Populated event changed its result");
            check(order.equals(List.of(first, second, last)), "Mutation skipped or replayed a callback");
            order.clear();
            check((boolean) trigger.invoke(event, null, caller, new Object[0]), "Stable event changed its result");
            check(order.equals(List.of(second, last)), "Stable event changed callback order");
            replace.set(true);
            event.callbacks.addFirst(first);
            order.clear();
            check((boolean) trigger.invoke(event, null, caller, new Object[0]), "Replacement changed the result");
            check(order.equals(List.of(first, replacement, second, last)), "An in-place replacement skipped a callback");
            check(TelemetryRegistry.snapshot().get(event.name).calls() == 4, "Existing Lua timing was lost");
        }
        DebugOptions.instance.checks.slowLuaEvents.setValue(false);
    }

    private static void nearVehicle(boolean enabled) throws Exception {
        var update = IsoPlayer.class.getDeclaredMethod("updateInternal2");
        update.setAccessible(true);
        for (int scenario = 0; scenario < 3; scenario++) {
            boolean dedicated = scenario != 2;
            boolean sneaking = scenario == 1;
            boolean skip = enabled && dedicated && !sneaking;
            server(dedicated);
            field(gameClass("zombie.network.GameClient"), "client").setBoolean(null, !dedicated);
            ProximityFixture player = allocate(ProximityFixture.class);
            field(zombie.iso.IsoObject.class, "ecsComponentMap").set(player, new java.util.HashMap<>());
            field(zombie.characters.IsoGameCharacter.class, "stats").set(player, new Stats());
            field(zombie.characters.IsoGameCharacter.class, "safety").set(player, new Safety(player));
            field(zombie.characters.IsoGameCharacter.class, "sneaking").setBoolean(player, sneaking);
            player.ai = new NetworkPlayerAI(player);
            player.setAttackStarted(true);
            try {
                update.invoke(player);
                throw new AssertionError("Remote update did not reach the proximity checkpoint");
            } catch (InvocationTargetException failure) {
                if (failure.getCause() != (skip ? ProximityFixture.CONTINUED : ProximityFixture.QUERIED)) {
                    throw failure;
                }
            }
            check(player.queries == (skip ? 0 : 1), "Wrong remote near-vehicle call count");
        }
        field(gameClass("zombie.network.GameClient"), "client").setBoolean(null, false);
        server(true);
    }

    private static void packetPrediction() throws Exception {
        IsoZombie zombie = allocate(IsoZombie.class);
        PathFindBehavior2 behavior = new PathFindBehavior2(zombie);
        field(zombie.characters.IsoGameCharacter.class, "pfb2").set(zombie, behavior);
        NetworkZombieAI ai = new NetworkZombieAI(zombie);
        var prediction = NetworkZombieAI.class.getDeclaredMethod("setUsingWalkTowardState", ZombiePacket.class);
        prediction.setAccessible(true);
        var path = (zombie.pathfind.Path) field(PathFindBehavior2.class, "path").get(behavior);
        field(PathFindBehavior2.class, "targetX").setFloat(behavior, 8.0F);
        path.addNode(0, 0, 0);
        path.addNode(6, 0, 0);
        path.addNode(7, 0, 0);
        var packet = new ZombiePacket();
        prediction.invoke(ai, packet);
        check(packet.predictionType == 8 && packet.x == 4.0F, "Long path changed prediction");
        path.getNode(2).x = Float.NaN;
        prediction.invoke(ai, packet);
        check(packet.predictionType == 7 && packet.x == 8.0F, "NaN tail changed vanilla prediction");
        path.getNode(1).x = 5.0F;
        path.getNode(2).x = 5.0F;
        prediction.invoke(ai, packet);
        check(packet.predictionType == 7, "Exactly five changed vanilla prediction");

        zombie.lunger = true;
        field(zombie.iso.IsoMovingObject.class, "animationRecorder").set(zombie, allocate(AnimationPlayerRecorder.class));
        check(ZombiePacketWork.flags(zombie) == NetworkZombieVariables.getBooleanVariables(zombie).asShort(),
            "Primitive flag bits differ");
        Locale originalLocale = Locale.getDefault();
        try {
            for (Locale locale : List.of(Locale.ROOT, Locale.forLanguageTag("tr-TR"))) {
                Locale.setDefault(locale);
                for (String input : new String[]{null, "", "ſlow1", "SLOW2", "sprint3", "1", "5", "IDLE", "WALKTOWARD"}) {
                    check(ZombiePacketWork.walkType(input) == WalkType.fromString(input), "Walk type differs for " + input);
                    check(ZombiePacketWork.state(input) == ZombieState.fromString(input), "State differs for " + input);
                }
            }
        } finally {
            Locale.setDefault(originalLocale);
        }
    }

    private static void thermalClothing(boolean enabled) throws Exception {
        PlayerFixture first = allocate(PlayerFixture.class);
        PlayerFixture second = allocate(PlayerFixture.class);
        ClothingFixture firstShirt = allocate(ClothingFixture.class);
        ClothingFixture secondShirt = allocate(ClothingFixture.class);
        first.worn = visual(firstShirt);
        second.worn = visual(secondShirt);
        Thermoregulator left = new Thermoregulator(new BodyDamage(first));
        Thermoregulator right = new Thermoregulator(new BodyDamage(second));
        var update = Thermoregulator.class.getDeclaredMethod("updateClothing");
        update.setAccessible(true);
        update.invoke(left);
        update.invoke(right);
        update.invoke(left);
        check(firstShirt.insulationReads == (enabled ? 1 : 2), "Unexpected clothing rebuild count");
        check(secondShirt.insulationReads == 1, "Second player clothing was not initialized");
        check(hasClothing(left, firstShirt) && hasClothing(right, secondShirt), "Clothing was assigned to the wrong regulator");
        first.worn = null;
        update.invoke(left);
        check(!hasClothing(left, firstShirt) && hasClothing(right, secondShirt), "Clothing removal crossed regulator state");
    }

    private static void packetBytes() throws Exception {
        IsoZombie zombie = allocate(IsoZombie.class);
        Class<?> character = zombie.characters.IsoGameCharacter.class;
        field(zombie.iso.IsoObject.class, "ecsComponentMap").set(zombie, new java.util.HashMap<>());
        zombie.registerECSComponents();
        field(character, "pfb2").set(zombie, new PathFindBehavior2(zombie));
        field(character, "forwardDirection").set(zombie, new Vector2(0.6F, 0.8F));
        field(character, "networkCharacter").set(zombie, new NetworkCharacter());
        field(character, "grappleable").set(zombie, new BaseGrappleable());
        field(character, "gameVariables").set(zombie, new AnimationVariableSource());
        field(IsoZombie.class, "humanVisual").set(zombie, new HumanVisual(zombie));
        field(zombie.iso.IsoMovingObject.class, "animationRecorder").set(zombie, allocate(AnimationPlayerRecorder.class));
        zombie.lunger = true;
        zombie.getGameVariablesInternal().setVariable("zombieWalkType", "slow1");
        zombie.setWalkType("sprint3");

        ZombiePacket standalone = new ZombiePacket();
        server(false);
        zombie.getNetworkCharacterAI().set(standalone);
        server(true);
        check(standalone.walkType == WalkType.WTSlow1, "Standalone animation walk type changed");
        for (boolean moving : new boolean[]{false, true}) {
            zombie.setMoving(moving);
            ZombiePacket combined = new ZombiePacket();
            combined.set(zombie);
            check(combined.walkType == WalkType.WTSprint3 && zombie.thumpSent, "Combined packet bookkeeping changed");
            ByteBuffer bytes = ByteBuffer.allocate(128);
            combined.write(new ByteBufferWriter(bytes));
            System.out.println("OPTIMIZATION_PACKET_" + moving + "=" + HexFormat.of().formatHex(bytes.array(), 0, bytes.position()));
        }
    }

    private static ItemVisual visual(Clothing item) {
        ItemVisual visual = new ItemVisual();
        visual.setInventoryItem(item);
        return visual;
    }

    private static void verifyTelemetry(boolean enabled) throws ReflectiveOperationException {
        var takeSnapshot = OptimizationTelemetry.class.getDeclaredMethod("snapshotAndReset");
        takeSnapshot.setAccessible(true);
        Object snapshot = takeSnapshot.invoke(null);
        var counters = snapshot.getClass().getDeclaredMethod("counters");
        counters.setAccessible(true);
        check(counters.invoke(snapshot).equals(enabled ? Map.of(
            "zombies.culling.zeroQuotaConnections", 2L,
            "zombies.culling.fulfilledQuotaConnections", 1L,
            "lua.dispatch.membershipScansSkipped", 14L,
            "players.nearVehicle.queriesSkipped", 1L,
            "zombies.packet.pathSquareRootsSkipped", 1L,
            "players.thermal.clothingRebuilt", 3L
        ) : Map.of()), "Wrong optimization counter totals: " + counters.invoke(snapshot));
        var phases = snapshot.getClass().getDeclaredMethod("phases");
        phases.setAccessible(true);
        check(((List<?>) phases.invoke(snapshot)).size() == (enabled ? 3 : 0), "Wrong optimization phase count");
        check(counters.invoke(takeSnapshot.invoke(null)).equals(Map.of()), "Counter window did not reset");
    }

    private static void verifyHookEvidence(Path directory, boolean enabled) throws Exception {
        check(!Files.exists(directory.resolve("probe-error.txt")), "Hook observer failed");
        for (var module : AgentModules.all()) {
            if (!(module instanceof OptimizationModule)) {
                continue;
            }
            check((module.disabledReason() == null) == enabled, "Wrong configuration for " + module.name());
            for (var check : module.hookChecks()) {
                Path evidenceFile = directory.resolve(check.targetClass() + ".properties");
                Properties evidence = new Properties();
                if (Files.isRegularFile(evidenceFile)) {
                    try (var input = Files.newInputStream(evidenceFile)) {
                        evidence.load(input);
                    }
                }
                String key = check.method() + "|" + check.helperClass().replace('.', '/') + "|" + check.helperMethod();
                check(evidence.containsKey(key) == enabled, "Wrong hook evidence for " + module.name() + "/" + check.id());
            }
        }
    }

    private static boolean hasClothing(Thermoregulator regulator, Clothing item) throws Exception {
        for (int index = 0; index < regulator.getNodeSize(); index++) {
            Object node = regulator.getNode(index);
            if (((List<?>) field(node.getClass(), "clothing").get(node)).contains(item)) {
                return true;
            }
        }
        return false;
    }

    private static void server(boolean dedicated) throws ReflectiveOperationException {
        field(SERVER, "server").setBoolean(null, dedicated);
    }

    static Field field(Class<?> owner, String name) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    static Class<?> gameClass(String name) {
        try {
            return Class.forName(name, false, OptimizationOriginalSmokeMain.class.getClassLoader());
        } catch (ClassNotFoundException invalidFixture) {
            throw new AssertionError(invalidFixture);
        }
    }

    private static Unsafe unsafe() {
        try {
            return (Unsafe) field(Unsafe.class, "theUnsafe").get(null);
        } catch (ReflectiveOperationException invalidFixture) {
            throw new AssertionError(invalidFixture);
        }
    }

    /** Game fixtures omit constructors needing native world state. */
    static <T> T allocate(Class<T> type) throws InstantiationException {
        return type.cast(UNSAFE.allocateInstance(type));
    }

    static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static final class CandidateIds extends AbstractList<Short> {
        private final List<Short> ids;
        int reads;

        CandidateIds(List<Short> ids) {
            this.ids = ids;
        }

        @Override
        public Short get(int index) {
            reads++;
            return ids.get(index);
        }

        @Override
        public int size() {
            return ids.size();
        }
    }

    private static final class PlayerFixture extends IsoPlayer {
        ItemVisual worn;

        PlayerFixture() {
            super(null);
        }

        @Override
        public void getItemVisuals(ItemVisuals output) {
            output.clear();
            if (worn != null) {
                output.add(worn);
            }
        }
    }

    private static final class ProximityFixture extends IsoPlayer {
        private static final RuntimeException QUERIED = new RuntimeException("Vanilla proximity query");
        private static final RuntimeException CONTINUED = new RuntimeException("Vanilla update continued past query");
        private NetworkPlayerAI ai;
        private boolean passedWall;
        private int queries;

        ProximityFixture() {
            super(null);
        }

        @Override public boolean isAnimal() { return true; }
        @Override public boolean isLocalPlayer() { return false; }
        @Override public boolean isWeaponReady() { return false; }
        @Override protected void doDeferredMovement() { }
        @Override public void updateEquippedItemSounds() { }
        @Override public void updateEmitter() { }
        @Override public void checkActionGroup() { }
        @Override public NetworkPlayerAI getNetworkCharacterAI() { return ai; }
        @Override protected boolean updateRemotePlayer() { return true; }

        @Override
        public float checkIsNearWall() {
            passedWall = true;
            return 0.0F;
        }

        @Override
        public boolean checkIsNearVehicle() {
            queries++;
            throw QUERIED;
        }

        @Override
        public BaseGrappleable getWrappedGrappleable() {
            if (passedWall) {
                throw CONTINUED;
            }
            return super.getWrappedGrappleable();
        }
    }

    private static final class ClothingFixture extends Clothing {
        int insulationReads;

        ClothingFixture() {
            super(null, null, null, "", null, null);
        }

        @Override
        public float getInsulation() {
            insulationReads++;
            return 1.0F;
        }

        @Override
        public ArrayList<BloodClothingType> getBloodClothingType() {
            return new ArrayList<>(List.of(BloodClothingType.Shirt));
        }
    }

}
