package zombie;

import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Main-thread capture only; writer receives detached numeric snapshots, never game objects. */
public final class ApocBRClientTelemetryLite {
    public enum Phase {
        LOGIC, NETWORK, STATE_UPDATE, UI_UPDATE, FILE_TRANSACTIONS, FINISH_ANIMATION,
        OBJECT_ANIMATIONS, RENDER_PREPARE, ON_RENDER, FRAME_READY, LIGHTING, QUEUED_LUA,
        RENDER_LIGHTING_PREUPDATE, RENDER_PICKER, RENDER_STATES, RENDER_SCENE,
        RENDER_COMPOSITE, RENDER_TEXT, RENDER_UI, RENDER_HANDOFF, RENDER_HANDOFF_MONITOR,
        RENDER_READY_SLOT_WAIT, UPDATE_CHUNK_POSITION, UPDATE_WORLD, UPDATE_ENTITIES,
        UPDATE_ANIMALS, UPDATE_RADIO, UPDATE_STUFF, UPDATE_LUA_TICK, UPDATE_MODELS,
        UPDATE_MANAGERS, WORLD_PHYSICS, WORLD_CLIMATE, WORLD_CELL, WORLD_CONTACTS,
        WORLD_REMAINDER, MOVING_UPDATE, MOVING_POSTUPDATE,
        FILE_HANDLE_RESULT, FILE_DONE, FILE_ADMISSION_LOCK, FILE_ADMISSION, FILE_SUBMIT,
        RENDER_INLINE_LIGHTING, WORLD_WORKER_WAIT, UPDATE_LUA_EVEN_PAUSED
    }

    public static final boolean ENABLED = Boolean.getBoolean("apocbr.clientTelemetry.enabled");
    private static final long INTERVAL_NS = TimeUnit.MILLISECONDS.toNanos(
        Math.max(1000L, Math.min(60000L, Long.getLong("apocbr.clientTelemetry.intervalMs", 5000L))));
    private static final long SLOW_NS = TimeUnit.MILLISECONDS.toNanos(
        Math.max(1L, Math.min(60000L, Long.getLong("apocbr.clientTelemetry.slowFrameMs", 50L))));
    private static final int PHASES = Phase.values().length;
    private static final int SLOW_LIMIT = 16;
    private static final long[] LIMITS = {
        1000000L, 2000000L, 4000000L, 8000000L, 16666667L, 25000000L,
        33333334L, 50000000L, 100000000L, 250000000L, 500000000L, 1000000000L
    };
    private static final ArrayBlockingQueue<Snapshot> OUTPUT = new ArrayBlockingQueue<>(
        Math.max(1, Math.min(256, Integer.getInteger("apocbr.clientTelemetry.queueCapacity", 8))));
    private static final AtomicLong dropped = new AtomicLong(), writeErrors = new AtomicLong();
    private static final Stats frames = new Stats(), cadence = new Stats();
    private static final Stats frameCpu = new Stats();
    private static final java.lang.management.ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();
    private static final Stats[] phases = new Stats[PHASES];
    private static final long[] framePhases = new long[PHASES];
    private static final SlowFrame[] slow = new SlowFrame[SLOW_LIMIT];
    private static Thread owner, writer;
    private static volatile boolean running;
    private static boolean capturing;
    private static long frameStarted, previousStart, windowStarted, sequence, slowCount, frameNumber;
    private static long frameCadence;
    private static int slowSize, zombies, movingObjects;
    private static final Map<String, TaskTiming> fileTasks = new LinkedHashMap<>();
    private static long fileCompletions, fileSubmissions, fileTaskOverflow;
    private static int filesInProgress, filesPending, filesAdded, filesBacklogMax;
    private static long frameFileMaxNanos;
    private static String frameFileMaxType;
    private static long frameCpuStarted;
    private static int screenWidth, screenHeight;
    private static float zoom;

    static {
        for (int i = 0; i < PHASES; i++) phases[i] = new Stats();
    }

    private ApocBRClientTelemetryLite() { }

    /** Called once per actual GameWindow.frameStep, never from a worker or render thread. */
    public static void beginFrame(boolean connectedIngame) {
        if (!ENABLED) return;
        Thread current = Thread.currentThread();
        if (owner == null) {
            if (!connectedIngame) return;
            owner = current;
            running = true;
            writer = new Thread(ApocBRClientTelemetryLite::writeLoop, "ApocBR-ClientTelemetry-Writer");
            writer.setDaemon(true);
            writer.start();
        }
        if (current != owner) return;
        long now = System.nanoTime();
        if (!connectedIngame) {
            capturing = false;
            if (frames.count > 0) publish(now);
            previousStart = 0L;
            windowStarted = 0L;
            return;
        }
        if (windowStarted == 0L) windowStarted = now;
        frameCadence = previousStart == 0L ? 0L : now - previousStart;
        previousStart = now;
        frameStarted = now;
        frameCpuStarted = currentCpuTime();
        Arrays.fill(framePhases, 0L);
        frameFileMaxNanos = 0L;
        frameFileMaxType = null;
        capturing = true;
    }

    public static long beginPhase() {
        return ENABLED && capturing && Thread.currentThread() == owner ? System.nanoTime() : 0L;
    }

    public static void endPhase(Phase phase, long started) {
        if (started == 0L || !capturing || Thread.currentThread() != owner) return;
        long elapsed = Math.max(0L, System.nanoTime() - started);
        phases[phase.ordinal()].add(elapsed);
        framePhases[phase.ordinal()] += elapsed;
    }

    public static boolean isCapturing() {
        return ENABLED && capturing && Thread.currentThread() == owner;
    }

    /** Called in finally around main-thread handleResult/done; never retains a task object. */
    public static void fileTask(Object task, long started, boolean cleanup) {
        if (started == 0L || !isCapturing()) return;
        long nanos = Math.max(0L, System.nanoTime() - started);
        String name = task.getClass().getName();
        TaskTiming timing = fileTasks.get(name);
        if (timing == null) {
            if (fileTasks.size() >= 64) {
                name = "<other>";
                fileTaskOverflow++;
            }
            timing = fileTasks.get(name);
            if (timing == null) { timing = new TaskTiming(); fileTasks.put(name, timing); }
        }
        (cleanup ? timing.done : timing.result).add(nanos);
        if (!cleanup) fileCompletions++;
        Phase phase = cleanup ? Phase.FILE_DONE : Phase.FILE_HANDLE_RESULT;
        phases[phase.ordinal()].add(nanos);
        framePhases[phase.ordinal()] += nanos;
        if (nanos > frameFileMaxNanos) { frameFileMaxNanos = nanos; frameFileMaxType = name; }
    }

    /** added size must be read by caller while holding FileSystemImpl's admission lock. */
    public static void fileQueues(int inProgress, int pending, int added) {
        if (!isCapturing()) return;
        filesInProgress = inProgress; filesPending = pending; filesAdded = added;
        filesBacklogMax = Math.max(filesBacklogMax, inProgress + pending + added);
    }

    public static void fileSubmitted() { if (isCapturing()) fileSubmissions++; }

    public static void renderContext(int width, int height, float playerZoom) {
        if (!isCapturing()) return;
        screenWidth = width; screenHeight = height; zoom = playerZoom;
    }

    private static long currentCpuTime() {
        if (!THREADS.isCurrentThreadCpuTimeSupported() || !THREADS.isThreadCpuTimeEnabled()) return -1L;
        return THREADS.getCurrentThreadCpuTime();
    }

    public static void endFrame(int loadedZombies, int loadedMovingObjects) {
        if (!isCapturing()) return;
        long now = System.nanoTime();
        long elapsed = Math.max(0L, now - frameStarted);
        long cpuEnd = currentCpuTime();
        long cpuNanos = frameCpuStarted < 0L || cpuEnd < 0L ? -1L : Math.max(0L, cpuEnd - frameCpuStarted);
        if (cpuNanos >= 0L) frameCpu.add(cpuNanos);
        capturing = false;
        zombies = loadedZombies;
        movingObjects = loadedMovingObjects;
        frames.add(elapsed);
        if (frameCadence > 0L) cadence.add(frameCadence);
        frameNumber++;
        if (elapsed >= SLOW_NS) {
            slowCount++;
            int slot = slowSize;
            if (slot == SLOW_LIMIT) {
                slot = 0;
                for (int i = 1; i < slowSize; i++) if (slow[i].nanos < slow[slot].nanos) slot = i;
            }
            if (slowSize < SLOW_LIMIT || elapsed > slow[slot].nanos) {
                slow[slot] = new SlowFrame(frameNumber, System.currentTimeMillis(), elapsed,
                    frameCadence, zombies, movingObjects, framePhases.clone(), frameFileMaxType, frameFileMaxNanos, cpuNanos);
                if (slowSize < SLOW_LIMIT) slowSize++;
            }
        }
        if (now - windowStarted >= INTERVAL_NS) publish(now);
    }

    private static void publish(long now) {
        long[][] phaseData = new long[PHASES][];
        for (int i = 0; i < PHASES; i++) phaseData[i] = phases[i].take();
        TaskSnapshot[] tasks = new TaskSnapshot[fileTasks.size()];
        int taskIndex = 0;
        for (Map.Entry<String, TaskTiming> entry : fileTasks.entrySet())
            tasks[taskIndex++] = new TaskSnapshot(entry.getKey(), entry.getValue().result.take(), entry.getValue().done.take());
        Snapshot snapshot = new Snapshot(++sequence, System.currentTimeMillis(), now - windowStarted,
            frames.take(), cadence.take(), phaseData, slowCount, Arrays.copyOf(slow, slowSize),
            zombies, movingObjects, owner.getName(), tasks, fileCompletions, fileSubmissions,
            filesInProgress, filesPending, filesAdded, filesBacklogMax, fileTaskOverflow, frameCpu.take(), screenWidth, screenHeight, zoom);
        fileCompletions = fileSubmissions = fileTaskOverflow = 0L;
        filesInProgress = filesPending = filesAdded = filesBacklogMax = 0;
        Arrays.fill(slow, null);
        slowSize = 0;
        slowCount = 0L;
        windowStarted = now;
        if (!OUTPUT.offer(snapshot)) dropped.incrementAndGet();
    }

    /** Normal game-thread exit only. Writer draining is bounded; abrupt process termination can lose data. */
    public static void shutdown() {
        if (!ENABLED || owner != Thread.currentThread()) return;
        capturing = false;
        if (frames.count > 0L) publish(System.nanoTime());
        running = false;
        try {
            writer.join(1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void writeLoop() {
        long previousGcCount = -1L, previousGcMs = -1L;
        Map<String, long[]> previousCollectors = new LinkedHashMap<>();
        while (running || !OUTPUT.isEmpty()) {
            try {
                Snapshot snapshot = OUTPUT.poll(200L, TimeUnit.MILLISECONDS);
                if (snapshot == null) continue;
                List<GarbageCollectorMXBean> beans = ManagementFactory.getGarbageCollectorMXBeans();
                long gcCount = 0L, gcMs = 0L;
                StringBuilder collectors = new StringBuilder("[");
                for (GarbageCollectorMXBean bean : beans) {
                    long count = bean.getCollectionCount(), millis = bean.getCollectionTime();
                    gcCount += Math.max(0L, count);
                    gcMs += Math.max(0L, millis);
                    long[] previous = previousCollectors.put(bean.getName(), new long[] {count, millis});
                    if (collectors.length() > 1) collectors.append(',');
                    collectors.append("{\"name\":\"").append(escape(bean.getName())).append("\",\"countDelta\":")
                        .append(previous == null || count < 0 || previous[0] < 0 ? -1L : Math.max(0L, count - previous[0]))
                        .append(",\"collectionTimeMsDelta\":")
                        .append(previous == null || millis < 0 || previous[1] < 0 ? -1L : Math.max(0L, millis - previous[1]))
                        .append('}');
                }
                collectors.append(']');
                String json = toJson(snapshot, previousGcCount < 0L ? -1L : Math.max(0L, gcCount - previousGcCount),
                    previousGcMs < 0L ? -1L : Math.max(0L, gcMs - previousGcMs), collectors.toString());
                previousGcCount = gcCount;
                previousGcMs = gcMs;
                Path path = Path.of(System.getProperty("apocbr.clientTelemetry.path", "apocbr-client-telemetry.ndjson")).toAbsolutePath();
                if (path.getParent() != null) Files.createDirectories(path.getParent());
                Files.writeString(path, json + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (IOException | RuntimeException e) {
                long errors = writeErrors.incrementAndGet();
                if (errors == 1L || errors % 60L == 0L)
                    System.err.println("[ApocBR][ClientTelemetry] write failed: " + e.getMessage());
            }
        }
    }

    private static String toJson(Snapshot s, long gcCount, long gcMs, String collectors) {
        StringBuilder json = new StringBuilder(8192);
        json.append("{\"schemaVersion\":2,\"kind\":\"client-main-thread\",\"targetBuild\":\"42.22.2\",\"seq\":")
            .append(s.seq).append(",\"ts\":").append(s.ts).append(",\"intervalMs\":").append(ms(s.interval))
            .append(",\"thread\":\"").append(escape(s.thread)).append("\",\"frameWork\":");
        appendStats(json, s.frames);
        json.append(",\"frameCadence\":");
        appendStats(json, s.cadence);
        json.append(",\"frameCpu\":"); appendStats(json, s.cpu);
        json.append(",\"phases\":{");
        for (int i = 0; i < PHASES; i++) {
            if (i > 0) json.append(',');
            json.append('"').append(Phase.values()[i].name()).append("\":");
            appendStats(json, s.phases[i]);
        }
        json.append("},\"context\":{\"loadedZombies\":").append(s.zombies)
            .append(",\"movingObjects\":").append(s.movingObjects)
            .append(",\"screenWidth\":").append(s.width).append(",\"screenHeight\":").append(s.height)
            .append(",\"playerZoom\":").append(Float.isFinite(s.zoom) ? Float.toString(s.zoom) : "null")
            .append("},\"slowFrameThresholdMs\":").append(ms(SLOW_NS))
            .append(",\"slowFrameCount\":").append(s.slowCount).append(",\"worstFrames\":[");
        for (int i = 0; i < s.slow.length; i++) {
            if (i > 0) json.append(',');
            SlowFrame f = s.slow[i];
            json.append("{\"frame\":").append(f.frame).append(",\"ts\":").append(f.ts)
                .append(",\"workMs\":").append(ms(f.nanos)).append(",\"cadenceMs\":").append(ms(f.cadence))
                .append(",\"cpuMs\":").append(f.cpuNanos < 0L ? "null" : ms(f.cpuNanos))
                .append(",\"loadedZombies\":").append(f.zombies).append(",\"movingObjects\":").append(f.movingObjects)
                .append(",\"phaseMs\":{");
            for (int p = 0; p < PHASES; p++) {
                if (p > 0) json.append(',');
                json.append('"').append(Phase.values()[p].name()).append("\":").append(ms(f.phases[p]));
            }
            json.append("},\"largestFileCallback\":");
            if (f.fileType == null) json.append("null");
            else json.append("{\"type\":\"").append(escape(f.fileType)).append("\",\"ms\":").append(ms(f.fileNanos)).append('}');
            json.append('}');
        }
        json.append("],\"fileSystem\":{\"resultCallbacks\":").append(s.fileCompletions)
            .append(",\"submitted\":").append(s.fileSubmissions).append(",\"inProgressLast\":").append(s.inProgress)
            .append(",\"pendingLast\":").append(s.pending).append(",\"addedLast\":").append(s.added)
            .append(",\"backlogMax\":").append(s.backlogMax).append(",\"taskTypeOverflow\":").append(s.taskOverflow)
            .append(",\"tasks\":{");
        for (int i = 0; i < s.tasks.length; i++) {
            TaskSnapshot task = s.tasks[i];
            if (i > 0) json.append(',');
            json.append('"').append(escape(task.type)).append("\":{\"handleResult\":");
            appendStats(json, task.result);
            json.append(",\"done\":"); appendStats(json, task.done); json.append('}');
        }
        Runtime rt = Runtime.getRuntime();
        json.append("}},\"writerJvm\":{\"sampleTs\":").append(System.currentTimeMillis())
            .append(",\"heapUsedBytes\":").append(rt.totalMemory() - rt.freeMemory())
            .append(",\"heapMaxBytes\":").append(rt.maxMemory())
            .append(",\"gcCountDelta\":").append(gcCount).append(",\"gcMsDelta\":").append(gcMs)
            .append(",\"collectors\":").append(collectors)
            .append("},\"droppedWindows\":").append(dropped.get()).append(",\"writeErrors\":").append(writeErrors.get()).append('}');
        return json.toString();
    }

    private static void appendStats(StringBuilder json, long[] data) {
        json.append("{\"count\":").append(data[0]).append(",\"totalMs\":").append(ms(data[1]))
            .append(",\"avgMs\":").append(ms(data[0] == 0L ? 0L : data[1] / data[0]))
            .append(",\"maxMs\":").append(ms(data[2]))
            .append(",\"p95UpperMs\":").append(percentile(data, 95))
            .append(",\"p99UpperMs\":").append(percentile(data, 99)).append(",\"histogram\":[");
        for (int i = 3; i < data.length; i++) {
            if (i > 3) json.append(',');
            json.append(data[i]);
        }
        json.append("]}");
    }

    private static String percentile(long[] data, int percent) {
        if (data[0] == 0L) return "null";
        long target = (data[0] * percent + 99L) / 100L, sum = 0L;
        for (int i = 0; i < LIMITS.length; i++) {
            sum += data[i + 3];
            if (sum >= target) return ms(LIMITS[i]);
        }
        return "null"; // Overflow bucket: no finite upper bound.
    }

    private static String ms(long nanos) { return String.format(Locale.ROOT, "%.3f", nanos / 1000000.0); }
    private static String escape(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' || c == '\\') out.append('\\').append(c);
            else if (c < 32) out.append(String.format(Locale.ROOT, "\\u%04x", (int)c));
            else out.append(c);
        }
        return out.toString();
    }

    private static final class Stats {
        long count, nanos, max;
        final long[] histogram = new long[LIMITS.length + 1];
        void add(long value) {
            count++; nanos += value; max = Math.max(max, value);
            int i = 0;
            while (i < LIMITS.length && value > LIMITS[i]) i++;
            histogram[i]++;
        }
        long[] take() {
            long[] data = new long[3 + histogram.length];
            data[0] = count; data[1] = nanos; data[2] = max;
            System.arraycopy(histogram, 0, data, 3, histogram.length);
            count = nanos = max = 0L;
            Arrays.fill(histogram, 0L);
            return data;
        }
    }

    private static final class TaskTiming { final Stats result = new Stats(), done = new Stats(); }
    private record TaskSnapshot(String type, long[] result, long[] done) { }
    private record SlowFrame(long frame, long ts, long nanos, long cadence, int zombies, int movingObjects, long[] phases,
        String fileType, long fileNanos, long cpuNanos) { }
    private record Snapshot(long seq, long ts, long interval, long[] frames, long[] cadence,
        long[][] phases, long slowCount, SlowFrame[] slow, int zombies, int movingObjects, String thread,
        TaskSnapshot[] tasks, long fileCompletions, long fileSubmissions, int inProgress, int pending, int added,
        int backlogMax, long taskOverflow, long[] cpu, int width, int height, float zoom) { }
}
