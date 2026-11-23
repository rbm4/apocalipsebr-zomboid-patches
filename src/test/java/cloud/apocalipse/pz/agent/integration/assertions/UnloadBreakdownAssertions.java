package cloud.apocalipse.pz.agent.integration.assertions;

import cloud.apocalipse.pz.agent.config.AgentConfiguration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** Verifies that the unload report reconciles with the independent streaming lifecycle probe. */
final class UnloadBreakdownAssertions {
    private UnloadBreakdownAssertions() {}

    private record Frame(int cycle, int postupdate) {}
    private record Phase(long calls, long wall, long self, long max, long work) {}
    private record Cell(int cycle, int x, int y) {}

    static void verify(Path directory) throws Exception {
        Instant.parse(Files.readString(directory.resolve("unload-clock-utc.txt")).strip());
        var phases = new HashMap<Frame, Map<String, Phase>>();
        for (String row : data(directory, "unload-phases.csv")) {
            String[] fields = row.split(",");
            var sample = new Phase(number(fields, 3), number(fields, 4), number(fields, 5),
                number(fields, 6), number(fields, 7));
            assertTrue(sample.calls() > 0 && sample.self() >= 0 && sample.self() <= sample.wall()
                && sample.max() <= sample.wall() && sample.work() >= 0, row);
            Frame frame = key(fields);
            assertNull(phases.computeIfAbsent(frame, ignored -> new HashMap<>()).put(fields[2], sample), row);
        }

        var cells = new HashMap<Frame, Long>();
        var coordinates = new HashSet<Cell>();
        for (String row : data(directory, "unload-cell.csv")) {
            String[] fields = row.split(",");
            Frame frame = key(fields);
            assertTrue(coordinates.add(new Cell(frame.cycle(), (int) number(fields, 2), (int) number(fields, 3))), row);
            assertTrue(number(fields, 4) >= 0 && number(fields, 5) > 0, row);
            cells.merge(frame, 1L, Long::sum);
        }

        var cycleCells = new HashMap<Integer, Long>();
        var cycleChunks = new HashMap<Integer, Long>();
        long objects = 0;
        long squares = 0;
        long exactEngineVisits = 0;
        for (String row : data(directory, "unload-postupdate.csv")) {
            String[] fields = row.split(",");
            Frame frame = key(fields);
            Map<String, Phase> timing = phases.remove(frame);
            assertNotNull(timing, row);
            long wall = number(fields, 3);
            long cellCount = number(fields, 5);
            long chunkCount = number(fields, 6);
            assertTrue(wall > 0 && cellCount > 0 && chunkCount > 0, row);
            assertEquals(cellCount, cells.remove(frame), row);
            assertEquals(wall, timing.get("POSTUPDATE").wall(), row);
            assertEquals(wall, timing.values().stream().mapToLong(Phase::self).sum(), row);
            assertEquals(cellCount, timing.get("CELL_UNLOAD").calls(), row);
            assertEquals(chunkCount, timing.get("CHUNK_DETACH").calls(), row);
            assertEquals(chunkCount, timing.get("SAVE_ENQUEUE").calls(), row);
            assertEquals(timing.get("RAIN").calls(), timing.get("CLEAR_SQUARE").calls(), row);
            assertEquals(timing.get("RAIN").calls(), timing.get("ADJACENCY").calls(), row);
            squares += timing.get("RAIN").calls();
            objects += timing.get("OBJECT_DISPATCH").calls();
            if (timing.containsKey("ENGINE_ARRAY_SEARCH")) exactEngineVisits += timing.get("ENGINE_ARRAY_SEARCH").work();
            cycleCells.merge(frame.cycle(), cellCount, Long::sum);
            cycleChunks.merge(frame.cycle(), chunkCount, Long::sum);
        }
        assertTrue(phases.isEmpty() && cells.isEmpty(), "Unexpected rows outside unload-bearing postupdates");
        assertTrue(squares > 0 && objects > 0, "Expected real square and object teardown");
        if (!AgentConfiguration.optimizationEnabled(AgentConfiguration.ENTITY_REMOVAL_OPTIMIZATION)) {
            assertTrue(exactEngineVisits > 0, "Expected native global entity searches with the removal index disabled");
        }
        for (String row : data(directory, "streaming.csv")) {
            String[] fields = row.split(",");
            int cycle = Integer.parseInt(fields[0]);
            if (fields[4].equals("cell.unload")) assertEquals(number(fields, 5), cycleCells.remove(cycle), row);
            if (fields[4].equals("chunk.detach")) assertEquals(number(fields, 5), cycleChunks.remove(cycle), row);
        }
        assertTrue(cycleCells.isEmpty() && cycleChunks.isEmpty(), "Expected real streaming coverage for every profiled cycle");
    }

    private static List<String> data(Path directory, String name) throws Exception {
        var rows = Files.readAllLines(directory.resolve(name));
        assertTrue(rows.size() > 1, "Missing observations in " + name);
        return rows.subList(1, rows.size());
    }

    private static Frame key(String[] fields) {
        return new Frame(Integer.parseInt(fields[0]), Integer.parseInt(fields[1]));
    }

    private static long number(String[] fields, int index) {
        return Long.parseLong(fields[index]);
    }
}
