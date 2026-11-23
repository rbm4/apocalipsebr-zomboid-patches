package cloud.apocalipse.pz.agent.integration.probe;

import cloud.apocalipse.pz.agent.features.optimization.los.perception.PerceptionBuckets;
import cloud.apocalipse.pz.agent.features.telemetry.OptimizationTelemetry.Phase;
import cloud.apocalipse.pz.agent.features.telemetry.TickDiagnostics;
import com.sun.management.ThreadMXBean;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import zombie.VirtualZombieManager;
import zombie.WorldSoundManager;
import zombie.characters.CharacterStat;
import zombie.characters.IsoPlayer;
import zombie.characters.Stats;
import zombie.characters.IsoZombie;
import zombie.characters.SurvivorFactory;
import zombie.characters.animals.IsoAnimal;
import zombie.characters.animals.datas.AnimalGrowStage;
import zombie.iso.IsoCell;
import zombie.iso.IsoDirections;
import zombie.iso.IsoGridSquare;
import zombie.iso.worldgen.blending.BlendDirection;
import zombie.iso.areas.isoregion.data.DataChunk;
import zombie.iso.areas.isoregion.data.DataRoot;
import zombie.network.GameServer;
import zombie.network.ServerLOS;
import zombie.network.ServerMap;
import zombie.vehicles.BaseVehicle;

/** Drives remote player state on the server thread after the login boundary. */
public final class MovingObjectLoad {
    public static final String PROPERTY = "pz.it.movingObjectLoad";
    public static final String JFR_PROPERTY = PROPERTY + ".jfr";
    public static final String YOUNG_HEAP_PROPERTY = PROPERTY + ".youngMiB";
    private static final String PLAYERS = PROPERTY + ".players";
    private static final String ANIMALS = PROPERTY + ".animals";
    private static final String ZOMBIES = PROPERTY + ".zombies";
    private static final String FRAMES = PROPERTY + ".frames";
    private static final String VEHICLE = PROPERTY + ".vehicle";
    private static final int WARMUP = 60;
    private static final List<Phase> PHASES = Arrays.stream(Phase.values())
        .filter(phase -> phase == Phase.MOVING || phase.name().startsWith("MOVING_") || phase == Phase.THERMAL_CLOTHING
            || phase == Phase.ZOMBIE_POPULATION || phase == Phase.VIRTUAL_ZOMBIES || phase == Phase.ANIMALS_SYNC
            || phase == Phase.SIMULATION || phase == Phase.WORLD || phase == Phase.CELL || phase == Phase.ISO_OBJECTS
            || phase == Phase.INCOMING || phase == Phase.MAP_POST || phase == Phase.VEHICLES || phase == Phase.WORLD_PHYSICS)
        .toList();

    public record Settings(int players, int animals, int zombies, int frames, boolean vehicle) {
        public static Settings from(Properties properties) {
            return new Settings(read(properties, PLAYERS, 30, 1, 30), read(properties, ANIMALS, 120, 0, 120),
                read(properties, ZOMBIES, 120, 0, 300), read(properties, FRAMES, 360, 30, 600),
                Boolean.parseBoolean(properties.getProperty(VEHICLE, "true")));
        }

        public void forward(Map<String, String> properties) {
            properties.put(PROPERTY, "true");
            properties.put(PLAYERS, Integer.toString(players));
            properties.put(ANIMALS, Integer.toString(animals));
            properties.put(ZOMBIES, Integer.toString(zombies));
            properties.put(FRAMES, Integer.toString(frames));
            properties.put(VEHICLE, Boolean.toString(vehicle));
        }

        private static int read(Properties properties, String key, int fallback, int min, int max) {
            int value = Integer.parseInt(properties.getProperty(key, Integer.toString(fallback)));
            if (value < min || value > max) {
                throw new IllegalArgumentException(key + " must be between " + min + " and " + max);
            }
            return value;
        }
    }

    private record Route(IsoPlayer player, IsoGridSquare from, IsoGridSquare to) {}

    private final Settings settings = Settings.from(System.getProperties());
    private final List<Route> players = new ArrayList<>();
    private final List<IsoAnimal> animals = new ArrayList<>();
    private final Map<IsoZombie, Short> zombies = new IdentityHashMap<>();
    private final StringBuilder samples = new StringBuilder(
        "scenario,frame,epochMs,players,movedPlayers,forcedAnimalsActive,forcedZombiesActive,"
            + "activeZombies,activeAnimals,activeVehicles,movedVehicle,vehiclePacket_ns,drivenVehicleUpdate_ns,bulletSteps,"
            + "schedulerStartEpochMs,schedulerEndEpochMs,schedulerAllocatedBytes,mapPostupdateAllocatedBytes");
    private final long[] timings;
    private final float[] previousX = new float[settings.players()];
    private final float[] previousY = new float[settings.players()];
    private IsoCell cell;
    private MovingVehicleLoad vehicle;
    private long vehiclePacketNanos;
    private long vehicleUpdateNanos;
    private int bulletSteps;
    private final ThreadMXBean threadAllocations = ManagementFactory.getThreadMXBean() instanceof ThreadMXBean bean
        && bean.isThreadAllocatedMemorySupported() && bean.isThreadAllocatedMemoryEnabled() ? bean : null;
    private long schedulerStartEpochMs;
    private long schedulerEndEpochMs;
    private long schedulerStartBytes;
    private long schedulerAllocatedBytes;
    private long mapPostupdateStartBytes;
    private long mapPostupdateAllocatedBytes;
    private int frame;
    private boolean finished;

    MovingObjectLoad() throws ReflectiveOperationException {
        // Read production measurements on their owning thread. Copy only primitive values into the CSV.
        Field phases = TickDiagnostics.class.getDeclaredField("FRAME_PHASES");
        phases.setAccessible(true);
        timings = (long[]) phases.get(null);
        EntityAllocations.appendColumns(samples);
        for (Phase phase : PHASES) samples.append(',').append(phase.name()).append("_ns");
        samples.append('\n');
    }

    boolean finished() { return finished; }

    boolean ownsPlayers() {
        return GameServer.Players.size() == players.size()
            && players.stream().allMatch(route -> GameServer.Players.contains(route.player()));
    }

    void start(ServerMap map, List<StreamingWorkload.Position> positions) throws IOException {
        verifyCharacterStats();
        verifyBlendDirections();
        verifyPerceptionBuckets();
        verifyRegionLookup();
        for (int index = 0; index < settings.players(); index++) {
            var position = positions.get(index * positions.size() / settings.players());
            var site = squares(map, position.chunkX() * 8, position.chunkY() * 8, 8);
            IsoGridSquare from = site.getFirst();
            IsoGridSquare to = site.stream().filter(square ->
                Math.abs(square.getX() - from.getX()) + Math.abs(square.getY() - from.getY()) == 1
                    && !from.testCollideAdjacent(null, square.getX() - from.getX(), square.getY() - from.getY(), 0))
                .findFirst().orElseThrow(() -> new IllegalStateException("No adjacent walkable square at " + position));
            cell = from.getCell();
            IsoPlayer player = new IsoPlayer(cell, SurvivorFactory.CreateSurvivor(), from.getX(), from.getY(), 0);
            players.add(new Route(player, from, to));
            player.setOnlineID((short) index);
            player.setUsername("integration-mover-" + index);
            player.remote = true;
            player.onlineChunkGridWidth = StreamingWorkload.CHUNK_GRID_WIDTH;
            player.setCurrent(from);
            player.realx = player.getX();
            player.realy = player.getY();
            player.realz = player.getZ();
            player.setMovingSquareNow();
            GameServer.Players.add(player);
            GameServer.IDToPlayerMap.put(player.getOnlineID(), player);
            cell.addMovingObject(player);
            ServerLOS.instance.addPlayer(player);
        }

        var usedSquares = new HashSet<IsoGridSquare>();
        for (Route route : players) {
            usedSquares.add(route.from());
            usedSquares.add(route.to());
        }
        var manager = VirtualZombieManager.instance;
        var originalChoices = List.copyOf(manager.choices);
        try {
            for (int index = 0; index < positions.size(); index++) {
                var position = positions.get(index);
                int animalCount = portion(settings.animals(), index, positions.size());
                int zombieCount = portion(settings.zombies(), index, positions.size());
                if (animalCount + zombieCount == 0) continue;
                var sites = squares(map, position.chunkX() * 8 - 4, position.chunkY() * 8 - 4, 16);
                sites.removeIf(usedSquares::contains);
                if (sites.size() < animalCount + zombieCount) {
                    throw new IllegalStateException("Insufficient outdoor spawn squares near " + position);
                }
                IsoPlayer target = players.get(index * players.size() / positions.size()).player();
                for (int n = 0; n < animalCount; n++) {
                    var square = sites.get(n);
                    usedSquares.add(square);
                    var animal = new IsoAnimal(cell, square.getX(), square.getY(), 0, "cow", "angus");
                    animals.add(animal);
                    animal.addToWorld();
                    cell.addMovingObject(animal);
                }
                for (int n = 0; n < zombieCount; n++) {
                    var square = sites.get(animalCount + n);
                    usedSquares.add(square);
                    manager.choices.clear();
                    manager.choices.add(square);
                    IsoZombie zombie = manager.createRealZombieAlways(IsoDirections.S, false);
                    if (zombie == null) throw new IllegalStateException("Zombie spawn rejected at " + square);
                    zombies.put(zombie, zombie.getOnlineID());
                    zombie.setTarget(target);
                }
            }
        } finally {
            manager.choices.clear();
            manager.choices.addAll(originalChoices);
        }
        if (!animals.isEmpty()) verifyGrowthGene(animals.getFirst());
        if (settings.vehicle()) {
            Route driver = players.getFirst();
            var parking = squares(map, driver.from().getX() - 4, driver.from().getY() - 4, 16).stream()
                .filter(square -> !usedSquares.contains(square)).findFirst().orElseThrow();
            vehicle = new MovingVehicleLoad(parking, driver.player());
            vehicle.start();
        }
    }

    private static void verifyRegionLookup() {
        var first = new DataRoot();
        var second = new DataRoot();
        int x = 10600;
        int y = 9970;
        first.select.reset(x, y, 0, false);
        if (first.select.cell != null) throw new IllegalStateException("Empty region root returned a cell");
        first.select.reset(x, y, 0, true);
        var originalCell = first.select.cell;
        var originalChunk = first.select.chunk;
        first.select.reset(x, y, 0, false);
        var chunks = new ArrayList<DataChunk>();
        first.getAllChunks(chunks);
        second.select.reset(x, y, 0, true);
        var secondCell = second.select.cell;
        if (originalCell == null || originalChunk == null || first.select.cell != originalCell
                || !chunks.equals(List.of(originalChunk)) || secondCell == null || secondCell == originalCell) {
            throw new IllegalStateException("Region cell lookup or vanilla traversal changed");
        }
        first.resetAllData();
        first.select.reset(x, y, 0, false);
        second.select.reset(x, y, 0, false);
        if (first.select.cell != null || second.select.cell != secondCell) {
            throw new IllegalStateException("Reset must clear only its own region buffer");
        }
        first.select.reset(x, y, 0, true);
        if (first.select.cell == null || first.select.cell == originalCell) {
            throw new IllegalStateException("Region lookup reused a cell from before reset");
        }
    }

    private static void verifyGrowthGene(IsoAnimal animal) {
        var stage = new AnimalGrowStage();
        stage.ageToGrow = 100;
        var allele = animal.getUsedGene("ageToGrow");
        if (allele == null) throw new IllegalStateException("Expected growth gene on the fixture cow");
        int expected = (int) (stage.ageToGrow * (0.25f - allele.currentValue / 4.0f + 1.0f));
        if (stage.getAgeToGrow(animal) != expected) throw new IllegalStateException("Growth age changed");
    }

    private static void verifyBlendDirections() {
        var directions = BlendDirection.values();
        directions[0] = BlendDirection.WEST;
        if (BlendDirection.values()[0] != BlendDirection.NORTH
                || BlendDirection.NORTH.opposite() != BlendDirection.SOUTH
                || BlendDirection.SOUTH.opposite() != BlendDirection.NORTH
                || BlendDirection.EAST.opposite() != BlendDirection.WEST
                || BlendDirection.WEST.opposite() != BlendDirection.EAST) {
            throw new IllegalStateException("Vanilla blend direction semantics changed");
        }
    }

    private static void verifyPerceptionBuckets() {
        var buckets = new PerceptionBuckets<Integer>();
        var collected = new ArrayList<Integer>();
        buckets.add(0, 1);
        buckets.add(Long.MIN_VALUE, 2);
        buckets.add(0, 3);
        buckets.append(0, collected);
        buckets.append(Long.MAX_VALUE, collected);
        buckets.append(Long.MIN_VALUE, collected);
        if (!collected.equals(List.of(1, 3, 2))) throw new IllegalStateException("Perception bucket order changed");
        collected.clear();
        buckets.clear();
        buckets.append(0, collected);
        if (!collected.isEmpty()) throw new IllegalStateException("Perception retained an invalidated bucket");
    }

    private static void verifyCharacterStats() throws IOException {
        var stats = new Stats();
        for (CharacterStat stat : CharacterStat.ORDERED_STATS) {
            if (stats.get(stat) != stat.getDefaultValue()) {
                throw new IllegalStateException("Incorrect default for " + stat.getId());
            }
        }
        stats.set(CharacterStat.ENDURANCE, 0.0f);
        stats.add(CharacterStat.ENDURANCE, 0.25f);
        stats.set(CharacterStat.TEMPERATURE, 35.0f);
        var saved = ByteBuffer.allocate(Float.BYTES * CharacterStat.ORDERED_STATS.length);
        stats.save(saved);
        var restored = new Stats();
        restored.load(saved.flip(), 251);
        if (restored.get(CharacterStat.ENDURANCE) != 0.25f || restored.get(CharacterStat.TEMPERATURE) != 35.0f
                || restored.get(CharacterStat.HUNGER) != CharacterStat.HUNGER.getDefaultValue()) {
            throw new IllegalStateException("Character stats did not survive the vanilla round trip");
        }
        try {
            restored.get(null);
            throw new IllegalStateException("A null character stat must throw");
        } catch (NullPointerException expected) {
            // Vanilla rejects a null stat when it reads the default.
        }
    }

    private static List<IsoGridSquare> squares(ServerMap map, int x, int y, int size) {
        var result = new ArrayList<IsoGridSquare>();
        for (int dy = 0; dy < size; dy++) {
            for (int dx = 0; dx < size; dx++) {
                var square = map.getGridSquare(x + dx, y + dy, 0);
                if (square != null && square.isOutside() && !square.isWaterSquare() && square.isFree(false)) {
                    result.add(square);
                }
            }
        }
        return result;
    }

    private static int portion(int count, int index, int groups) {
        return count / groups + (index < count % groups ? 1 : 0);
    }

    void drive() throws Exception {
        boolean moving = frame >= WARMUP + settings.frames();
        vehicleUpdateNanos = 0;
        bulletSteps = 0;
        vehiclePacketNanos = moving && vehicle != null ? vehicle.drive(frame) : 0;
        double fraction = 0.2 + 0.6 * Math.abs((frame % 20) - 10) / 10.0;
        for (int i = 0; i < players.size(); i++) {
            Route route = players.get(i);
            IsoPlayer player = route.player();
            previousX[i] = player.getX();
            previousY[i] = player.getY();
            IsoGridSquare from = route.from();
            IsoGridSquare to = route.to();
            float x = from.getX() + 0.5f + (moving ? (float) (fraction * (to.getX() - from.getX())) : 0);
            float y = from.getY() + 0.5f + (moving ? (float) (fraction * (to.getY() - from.getY())) : 0);
            var network = player.getNetworkCharacterAI();
            network.moving = moving;
            player.setJustMoved(moving);
            network.targetX = player.realx = x;
            network.targetY = player.realy = y;
            network.targetZ = 0;
            player.setTimeSinceLastNetData(0);
            player.setLastRemoteUpdate(System.currentTimeMillis());
            if (moving && frame % 60 == 0) {
                WorldSoundManager.instance.addSound(player, player.getXi(), player.getYi(), 0, 12, 10);
            }
        }
    }

    void recordVehicleUpdate(Object candidate, long elapsed) {
        if (vehicle != null && vehicle.matches(candidate)) vehicleUpdateNanos += elapsed;
    }

    void recordBulletStep() { bulletSteps++; }

    void beginScheduler() {
        schedulerStartEpochMs = System.currentTimeMillis();
        EntityAllocations.beginFrame();
        schedulerStartBytes = currentThreadAllocatedBytes();
    }

    void endScheduler() {
        long allocated = currentThreadAllocatedBytes();
        EntityAllocations.endFrame();
        schedulerEndEpochMs = System.currentTimeMillis();
        schedulerAllocatedBytes = allocated < 0 || schedulerStartBytes < 0 ? -1 : allocated - schedulerStartBytes;
    }

    void beginMapPostupdate() { mapPostupdateStartBytes = currentThreadAllocatedBytes(); }

    void endMapPostupdate() {
        long allocated = currentThreadAllocatedBytes();
        mapPostupdateAllocatedBytes = allocated < 0 || mapPostupdateStartBytes < 0 ? -1 : allocated - mapPostupdateStartBytes;
    }

    private long currentThreadAllocatedBytes() {
        return threadAllocations == null ? -1 : threadAllocations.getCurrentThreadAllocatedBytes();
    }

    void sample(Path directory) throws IOException {
        boolean moving = frame >= WARMUP + settings.frames();
        int armFrame = moving ? frame - WARMUP - settings.frames() : frame;
        if (armFrame >= WARMUP) {
            if (!TickDiagnostics.activeOnCurrentThread()) {
                throw new IllegalStateException("Production tick telemetry is inactive while sampling");
            }
            int moved = 0;
            for (int i = 0; i < players.size(); i++) {
                IsoPlayer player = players.get(i).player();
                if (player.getX() != previousX[i] || player.getY() != previousY[i]) moved++;
            }
            long activeAnimals = animals.stream().filter(cell.getObjectList()::contains).count();
            int animalCount = 0;
            int vehicleCount = 0;
            for (var object : cell.getObjectList()) {
                if (object instanceof IsoAnimal) animalCount++;
                if (object instanceof BaseVehicle) vehicleCount++;
            }
            long activeZombies = zombies.entrySet().stream()
                .filter(entry -> isActive(entry.getKey(), entry.getValue())).count();
            samples.append(moving ? "moving" : "idle").append(',').append(armFrame - WARMUP).append(',')
                .append(System.currentTimeMillis()).append(',').append(players.size()).append(',').append(moved)
                .append(',').append(activeAnimals).append(',').append(activeZombies).append(',')
                .append(cell.getZombieList().size()).append(',').append(animalCount).append(',').append(vehicleCount)
                .append(',').append(moving && vehicle != null && vehicle.moved() ? 1 : 0)
                .append(',').append(vehiclePacketNanos).append(',').append(vehicleUpdateNanos).append(',').append(bulletSteps)
                .append(',').append(schedulerStartEpochMs).append(',').append(schedulerEndEpochMs)
                .append(',').append(schedulerAllocatedBytes).append(',').append(mapPostupdateAllocatedBytes);
            EntityAllocations.appendSample(samples);
            for (Phase phase : PHASES) samples.append(',').append(timings[phase.ordinal()]);
            samples.append('\n');
        }
        if (++frame == 2 * (WARMUP + settings.frames())) {
            cleanup();
            writeSamples(directory);
            finished = true;
        }
    }

    void writeSamples(Path directory) throws IOException {
        Files.writeString(directory.resolve("moving-object-load.csv"), samples);
    }

    void cleanup() {
        if (vehicle != null) {
            vehicle.cleanup();
            vehicle = null;
        }
        for (var entry : zombies.entrySet()) {
            if (isActive(entry.getKey(), entry.getValue())) VirtualZombieManager.instance.removeZombieFromWorld(entry.getKey());
        }
        zombies.clear();
        for (IsoAnimal animal : animals) {
            if (cell.getObjectList().contains(animal) || cell.getAddList().contains(animal)) {
                animal.removeFromWorld();
                animal.removeFromSquare();
            }
        }
        animals.clear();
        for (Route route : players) {
            IsoPlayer player = route.player();
            GameServer.IDToPlayerMap.remove(player.getOnlineID(), player);
            GameServer.Players.remove(player);
            ServerLOS.instance.removePlayer(player);
            player.removeFromWorld();
            player.removeFromSquare();
        }
        players.clear();
    }

    private boolean isActive(IsoZombie zombie, short id) {
        return zombie.getOnlineID() == id && cell.getObjectList().contains(zombie);
    }
}

