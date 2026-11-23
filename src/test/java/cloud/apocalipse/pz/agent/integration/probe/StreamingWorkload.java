package cloud.apocalipse.pz.agent.integration.probe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Random;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;

/** Reproducible test inputs shared by the runner and the test-only child agent. */
public record StreamingWorkload(String id, List<Cell> cells, List<Position> positions,
                                int cycleTimeoutSeconds, Long vehicleSeed, boolean chunkComparison) {
    public static final String PROFILE = "pz.it.streamingProfile";
    public static final String VEHICLE_SEED = "pz.it.vehicleSeed";
    private static final long DEFAULT_VEHICLE_SEED = 42_220_002L;
    public static final int CHUNK_GRID_WIDTH = 13;

    private StreamingWorkload(String id, List<Cell> cells, List<Position> positions, int cycleTimeoutSeconds) {
        this(id, cells, positions, cycleTimeoutSeconds, null, false);
    }

    public record Cell(int x, int y) {}
    public record Position(int chunkX, int chunkY) {}

    public static StreamingWorkload from(Properties properties) {
        return switch (properties.getProperty(PROFILE, "smoke")) {
            case "smoke" -> new StreamingWorkload("muldraugh-165-155-2x2-v2",
                List.of(new Cell(165, 155), new Cell(165, 156),
                    new Cell(166, 155), new Cell(166, 156)), List.of(), 30);
            case "players30" -> clusteredPlayers();
            case "vehicles72" -> vehiclePressure(Long.parseLong(
                properties.getProperty(VEHICLE_SEED, Long.toString(DEFAULT_VEHICLE_SEED))),
                Boolean.parseBoolean(properties.getProperty(ChunkLifecycleProbe.COMPARE_PROPERTY)));
            default -> throw new IllegalArgumentException("Expected smoke, players30 or vehicles72 for " + PROFILE);
        };
    }

    private static StreamingWorkload clusteredPlayers() {
        var centers = List.of(new Cell(164, 154), new Cell(167, 154), new Cell(170, 154),
            new Cell(164, 157), new Cell(167, 157));
        var positions = new ArrayList<Position>();
        var cells = new LinkedHashSet<Cell>();
        int radius = CHUNK_GRID_WIDTH / 2 * 8;
        for (Cell center : centers) {
            for (int dy = 3; dy <= 4; dy++) {
                for (int dx = 3; dx <= 5; dx++) {
                    int chunkX = center.x() * 8 + dx;
                    int chunkY = center.y() * 8 + dy;
                    positions.add(new Position(chunkX, chunkY));
                    // These positions are strictly inside the overlap, away from native boundary ties.
                    for (int y = Math.floorDiv(chunkY * 8 - radius, 64);
                            y <= Math.floorDiv(chunkY * 8 + radius, 64); y++) {
                        for (int x = Math.floorDiv(chunkX * 8 - radius, 64);
                                x <= Math.floorDiv(chunkX * 8 + radius, 64); x++) {
                            cells.add(new Cell(x, y));
                        }
                    }
                }
            }
        }
        return new StreamingWorkload("muldraugh-30-positions-5-clusters-width13-v2",
            List.copyOf(cells), List.copyOf(positions), 120);
    }

    private static StreamingWorkload vehiclePressure(long seed, boolean chunkComparison) {
        var cells = new ArrayList<Cell>();
        for (int x = 162; x < 174; x++) {
            for (int y = 153; y < 159; y++) cells.add(new Cell(x, y));
        }
        String id = (chunkComparison ? "muldraugh-chunk-comparison-v1-" : "muldraugh-vehicles72-v1-") + seed;
        return new StreamingWorkload(id, List.copyOf(cells), List.of(), 145, seed, chunkComparison);
    }

    public boolean vehicleStress() { return vehicleSeed != null; }
    public int cycles() { return chunkComparison ? 5 : vehicleStress() ? 4 : 6; }
    public int holdFrames() { return vehicleStress() ? 100 : 3; }

    public List<Cell> cellsFor(int cycle) {
        if (!vehicleStress()) return cells;
        var shuffled = new ArrayList<>(cells);
        Collections.shuffle(shuffled, new Random(vehicleSeed + (chunkComparison && cycle > 0 ? 1 : cycle)));
        return List.copyOf(shuffled.subList(0, cycle == 0 ? 72 : 54));
    }

    public int runTimeoutSeconds() { return cycles() * cycleTimeoutSeconds + 20; }
}
