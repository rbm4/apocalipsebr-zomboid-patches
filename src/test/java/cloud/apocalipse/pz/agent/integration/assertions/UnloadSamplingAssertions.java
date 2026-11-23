package cloud.apocalipse.pz.agent.integration.assertions;

import cloud.apocalipse.pz.agent.integration.probe.UnloadSamplingProbe;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import static org.junit.jupiter.api.Assertions.*;

final class UnloadSamplingAssertions {
    private static final String GC_PAUSE = "jdk.GCPhasePause";
    private static final String EXECUTION = "jdk.ExecutionSample";
    private static final String NATIVE = "jdk.NativeMethodSample";

    private record Window(int cycle, int frame, Instant start, Instant end, long threadId,
                          int cells, int chunks, long cpu, long searches, long candidates, long searchTime) {}

    private UnloadSamplingAssertions() {}

    static void verify(Path directory) throws IOException {
        Path recording = directory.resolve(UnloadSamplingProbe.RECORDING);
        List<Window> windows = windows(recording);
        assertFalse(windows.isEmpty(), "Expected unload postupdate markers in the JFR recording");
        verifyLifecycle(directory, windows);
        writeWindows(directory, windows);
        assertTrue(writeSamples(directory, recording, windows) > 0,
            "Expected at least one execution sample in an owner-thread unload stack");
    }

    private static List<Window> windows(Path recording) throws IOException {
        boolean expectSearches = Boolean.getBoolean(UnloadSamplingProbe.SEARCH_PROPERTY);
        var windows = new ArrayList<Window>();
        try (var input = new RecordingFile(recording)) {
            while (input.hasMoreEvents()) {
                var event = input.readEvent();
                if (!event.getEventType().getName().equals(UnloadSamplingProbe.EVENT)) continue;
                assertEquals(expectSearches, event.getBoolean("searchEnabled"));
                assertEquals(0, event.getLong("searchIncomplete"), "Every native identity scan must be counted");
                Window window = new Window(event.getInt("cycle"), event.getInt("postupdate"),
                    event.getStartTime(), event.getEndTime(), event.getThread().getJavaThreadId(),
                    event.getInt("cells"), event.getInt("chunks"), event.getLong("ownerCpuNanos"),
                    event.getLong("searchCalls"), event.getLong("searchCandidates"), event.getLong("searchNanos"));
                assertTrue(window.cells > 0 && window.chunks > 0 && window.end.isAfter(window.start));
                assertTrue(window.searches >= 0 && window.candidates >= 0 && window.searchTime >= 0);
                windows.add(window);
            }
        }
        windows.sort(Comparator.comparing(Window::start));
        if (expectSearches) assertTrue(windows.stream().anyMatch(window -> window.candidates > 0),
            "Expected real native array comparisons in the instrumented fixture");
        return windows;
    }

    private static void verifyLifecycle(Path directory, List<Window> windows) throws IOException {
        var cells = new HashMap<Integer, Long>();
        var chunks = new HashMap<Integer, Long>();
        for (Window window : windows) {
            cells.merge(window.cycle, (long) window.cells, Long::sum);
            chunks.merge(window.cycle, (long) window.chunks, Long::sum);
        }
        for (String row : Files.readAllLines(directory.resolve("streaming.csv")).stream().skip(1).toList()) {
            String[] fields = row.split(",");
            int cycle = Integer.parseInt(fields[0]);
            if (fields[4].equals("cell.unload")) assertEquals(Long.parseLong(fields[5]), cells.remove(cycle), row);
            if (fields[4].equals("chunk.detach")) assertEquals(Long.parseLong(fields[5]), chunks.remove(cycle), row);
        }
        assertTrue(cells.isEmpty() && chunks.isEmpty(), "Expected a native lifecycle count for each recorded cycle");
    }

    private static void writeWindows(Path directory, List<Window> windows) throws IOException {
        var csv = new StringBuilder("cycle,postupdate,startUtc,wallNanos,cpuNanos,cells,chunks,searchCalls,searchCandidates,searchNanos\n");
        for (Window window : windows) {
            csv.append(window.cycle).append(',').append(window.frame).append(',').append(window.start).append(',')
                .append(Duration.between(window.start, window.end).toNanos()).append(',').append(window.cpu).append(',')
                .append(window.cells).append(',').append(window.chunks).append(',').append(window.searches).append(',')
                .append(window.candidates).append(',').append(window.searchTime).append('\n');
        }
        Files.writeString(directory.resolve("unload-sampling-windows.csv"), csv);
    }

    private static long writeSamples(Path directory, Path recording, List<Window> windows) throws IOException {
        long ownerUnload = 0;
        try (var output = Files.newBufferedWriter(directory.resolve("unload-sampling-events.csv"));
             var input = new RecordingFile(recording)) {
            output.write("cycle,postupdate,lane,event,bucket,overlapNanos,stack\n");
            while (input.hasMoreEvents()) {
                var event = input.readEvent();
                String type = event.getEventType().getName();
                boolean sample = type.equals(EXECUTION) || type.equals(NATIVE);
                if (!sample && !type.equals(GC_PAUSE) && !type.equals("jdk.JavaMonitorEnter")
                        && !type.equals("jdk.ThreadPark") && !type.equals("jdk.ThreadSleep")) continue;
                var thread = sample ? event.getThread("sampledThread") : event.getThread();
                for (Window window : windows) {
                    if (!event.getStartTime().isBefore(window.end) || event.getEndTime().isBefore(window.start)) continue;
                    String lane = lane(type, thread, window);
                    if (lane == null) continue;
                    Instant start = event.getStartTime().isBefore(window.start) ? window.start : event.getStartTime();
                    Instant end = event.getEndTime().isAfter(window.end) ? window.end : event.getEndTime();
                    String stack = stack(event);
                    String bucket = sample ? bucket(stack) : "duration";
                    if (sample && lane.equals("owner") && stack.contains("ServerMap$ServerCell.Unload@")) ownerUnload++;
                    output.write(window.cycle + "," + window.frame + "," + lane + "," + type + ","
                        + bucket + "," + Duration.between(start, end).toNanos() + "," + stack + "\n");
                }
            }
        }
        return ownerUnload;
    }

    private static String lane(String type, jdk.jfr.consumer.RecordedThread thread, Window window) {
        if (type.equals(GC_PAUSE)) return "jvm";
        if (thread == null) return null;
        if (thread.getJavaThreadId() == window.threadId) return "owner";
        return switch (thread.getJavaName()) {
            case "SaveChunk", "LoadChunk", "WorldReuser" -> thread.getJavaName();
            default -> null;
        };
    }

    private static String stack(RecordedEvent event) {
        if (event.getStackTrace() == null) return "";
        return String.join(";", event.getStackTrace().getFrames().stream().map(frame -> {
            var method = frame.getMethod();
            return method.getType().getName() + "." + method.getName() + "@"
                + frame.getBytecodeIndex() + ":" + frame.getLineNumber();
        }).toList());
    }

    private static String bucket(String stack) {
        if (!stack.contains("ServerMap$ServerCell.Unload@")) return "outsideCellUnload";
        if (stack.contains("ZombiePopulationManager.removeChunkFromWorld@")) return "zombiePopulation";
        if (stack.contains("AnimalPopulationManager.removeChunkFromWorld@")) return "animalPopulation";
        if (stack.contains("Array.removeValue@")) return "nativeArraySearch";
        if (stack.contains("IsoObject.removeFromWorldToMeta@")) return "objectTeardown";
        if (stack.contains("VehiclesDB2.updateVehicle@")) return "vehicleCapture";
        if (stack.contains("ServerChunkLoader.addSaveUnloadedJob@")) return "saveEnqueue";
        if (stack.contains("IsoChunk.removeFromWorld@")) return "remainingChunkDetach";
        return "remainingCellUnload";
    }
}
