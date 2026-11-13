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
        RENDER_INLINE_LIGHTING, WORLD_WORKER_WAIT, UPDATE_LUA_EVEN_PAUSED,
        SCENE_WORLD_RENDER, SCENE_OUTLINE_START, SCENE_OUTLINE_END, SCENE_LEGACY_POST,
        SCENE_LUA_POST_RENDER, SCENE_END_FRAME, WORLD_RENDER_CULL, WORLD_RENDER_MASK,
        WORLD_RENDER_BODY_ATLAS, WORLD_RENDER_ITEM_ATLAS, WORLD_RENDER_CELL,
        WORLD_RENDER_WEATHER, WORLD_RENDER_SKYBOX, WORLD_HUTCH, WORLD_FOG,
        WORLD_HELICOPTER, WORLD_EMITTERS, WORLD_SOUND_INIT, WORLD_GROUP_PREUPDATE,
        WORLD_ONCE_EVERY, WORLD_COLLISION_INIT, WORLD_REGIONS, WORLD_HALO,
        WORLD_RECORDER, WORLD_DESIGNATION, RENDER_CONTEXT_INVOKE, RENDER_CONTEXT_WAIT,
        TEXTURE_SET_IMAGE, TEXTURE_LIMIT_SIZE, TEXTURE_MIP_SELECT, TEXTURE_BUFFER_ALLOC,
        TEXTURE_BUFFER_COPY, TEXTURE_SOURCE_DISPOSE, TEXTURE_QUEUE_UPLOAD,
        TEXTURE_ASSET_SUCCESS, IMAGE_GENERATE_MIPMAPS, IMAGE_PREMULTIPLY,
        REGION_SWAP, REGION_RESET_REFERENCES, REGION_PROCESS_BUILDINGS, REGION_WORKER_UPDATE, REGION_LOGGER,
        CELL_FBO_RENDER, CELL_GRID_STACKS, CELL_CHECKS, CELL_PREPARE_CHUNKS,
        CELL_OCCLUSION, CELL_LIGHTING, CELL_DRAW_TILES, CELL_RENDER_CHUNK,
        CELL_CORPSES, CELL_ITEMS, CELL_MOVING_OBJECTS, CELL_TRANSLUCENT_FLOORS,
        CELL_TRANSLUCENT_OBJECTS, CELL_FOG, CELL_PREPARE_CHUNK, CELL_REBAKE_LEVEL, LUA_ONTICK_CALLBACK,
        REGION_BUILD_REMOVE, REGION_BUILD_CREATE, REGION_BUILD_FILTER, REGION_BUILD_COMBINE, REGION_BUILD_INSERT, REGION_BUILD_SQUARES,
        CELL_TRANSLUCENT_ORDER,
        NET_SAFEHOUSE, NET_QUEUE_DRAIN, NET_DISCONNECT_EVENTS, NET_LOADING_DISPATCH, NET_REPLAY_QUEUES,
        NET_DISPATCH_BATCH, NET_OUTGOING_REQUESTS, NET_PLAYER_TIMEOUTS, NET_OUTGOING_INVENTORY,
        NET_VOICE, NET_VEHICLE_CLIP, NET_STREAMING, NET_CONNECTION_MAINTENANCE, NET_CHAT, NET_STATISTICS, NET_DISCONNECT_CLEANUP,
        NET_PACKET_STATISTICS, NET_PACKET_FACTORY, NET_PACKET_PARSE, NET_PACKET_VALIDATE, NET_PACKET_LOG,
        NET_PACKET_APPLY, NET_PACKET_POSTPONE, NET_PACKET_LEGACY
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
    private static long textureLimitCalls, texturesScaled, textureInputPixels, textureOutputPixels;
    private static TextureSample frameLargestTexture, largestTexture;
    public enum NetworkStage { STATISTICS, FACTORY, PARSE, VALIDATE, LOG, APPLY, POSTPONE, LEGACY }
    private static final Phase[] NETWORK_STAGE_PHASES = { Phase.NET_PACKET_STATISTICS, Phase.NET_PACKET_FACTORY,
        Phase.NET_PACKET_PARSE, Phase.NET_PACKET_VALIDATE, Phase.NET_PACKET_LOG, Phase.NET_PACKET_APPLY,
        Phase.NET_PACKET_POSTPONE, Phase.NET_PACKET_LEGACY };
    private static final Map<String, NetworkTiming> networkTypes = new LinkedHashMap<>();
    private static long networkPolled, networkOverflow, frameNetworkPackets, frameNetworkBytes;
    private static int networkPending, networkLoading, networkCoop, networkBacklogMax, frameNetworkBacklogMax;
    private static PacketSample frameLargestPacket;
    public enum WorkerTexture { PREPROCESSED, FALLBACK, WORK_NANOS, INPUT_PIXELS, OUTPUT_PIXELS, FAILED, CANCELLED_RESULTS, SETTINGS_RELOADS,
        UNSUPPORTED, OVERSIZED, CAPACITY_WAIT_NANOS, QUEUED }
    public enum TranslucentWorker { SUBMITTED, COMPLETED, REUSED, PENDING, QUEUE_FULL, FAILED, WORK_NANOS,
        CONTINUITY_REUSED, OWNER_CATCHUP, OWNER_CATCHUP_NANOS }
    private static final java.util.concurrent.atomic.LongAdder[] translucentWorkers = new java.util.concurrent.atomic.LongAdder[TranslucentWorker.values().length];
    public static void translucentWorker(TranslucentWorker key, long amount) { if (ENABLED) translucentWorkers[key.ordinal()].add(amount); }
    private static final java.util.concurrent.atomic.LongAdder[] workerTextures = new java.util.concurrent.atomic.LongAdder[WorkerTexture.values().length];
    private static final LuaSample[] slowLua = new LuaSample[32];
    private static int slowLuaSize;
    private static long slowLuaCount;
    private static final long SLOW_LUA_NS = TimeUnit.MILLISECONDS.toNanos(Math.max(1L,
        Long.getLong("apocbr.clientTelemetry.slowLuaMs", 5L)));

    static {
        for (int i = 0; i < PHASES; i++) phases[i] = new Stats();
        for (int i = 0; i < workerTextures.length; i++) workerTextures[i] = new java.util.concurrent.atomic.LongAdder();
        for (int i = 0; i < translucentWorkers.length; i++) translucentWorkers[i] = new java.util.concurrent.atomic.LongAdder();
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
        frameLargestTexture = null;
        frameNetworkPackets = frameNetworkBytes = 0L;
        frameNetworkBacklogMax = 0; frameLargestPacket = null;
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

    public static void networkPolled(long count) { if (isCapturing()) networkPolled += count; }
    /** Owner-list sizes only. Never scans ConcurrentLinkedQueue.size() on the receive thread. */
    public static void networkQueues(int pending, int loading, int coop) {
        if (!isCapturing()) return;
        networkPending=pending; networkLoading=loading; networkCoop=coop;
        int backlog=pending+loading+coop;
        networkBacklogMax=Math.max(networkBacklogMax,backlog);
        frameNetworkBacklogMax=Math.max(frameNetworkBacklogMax,backlog);
    }
    private static NetworkTiming networkTiming(String type) {
        NetworkTiming timing=networkTypes.get(type);
        if(timing!=null) return timing;
        if(networkTypes.size()>=256) type="<other>";
        timing=networkTypes.get(type);
        if(timing==null) { timing=new NetworkTiming(); networkTypes.put(type,timing); }
        return timing;
    }
    public static void networkStage(String type, NetworkStage stage, long started) {
        if(started==0L || !isCapturing()) return;
        long elapsed=Math.max(0L,System.nanoTime()-started);
        networkTiming(type).stages[stage.ordinal()].add(elapsed);
        Phase phase=NETWORK_STAGE_PHASES[stage.ordinal()];
        phases[phase.ordinal()].add(elapsed); framePhases[phase.ordinal()]+=elapsed;
    }
    /** Successful explicit receive-side construction; excludes connection-time cache preconstruction. */
    public static void networkInstance(String type) { if(isCapturing()) networkTiming(type).instances++; }
    /** Called before pooled input is reset; retains only type text and detached numeric data. */
    public static void networkPacket(String type, long started, int bytes, long queueAgeMs, boolean delayed, boolean failed) {
        if(started==0L || !isCapturing()) return;
        if(!networkTypes.containsKey(type) && networkTypes.size()>=256) networkOverflow++;
        long elapsed=Math.max(0L,System.nanoTime()-started);
        NetworkTiming timing=networkTiming(type);
        timing.total.add(elapsed); timing.age.add(TimeUnit.MILLISECONDS.toNanos(Math.max(0L,queueAgeMs)));
        timing.bytes+=Math.max(0,bytes); if(delayed) timing.delayed++; if(failed) timing.errors++;
        frameNetworkPackets++; frameNetworkBytes+=Math.max(0,bytes);
        if(frameLargestPacket==null || elapsed>frameLargestPacket.nanos)
            frameLargestPacket=new PacketSample(type,elapsed,Math.max(0,bytes),Math.max(0L,queueAgeMs),delayed,failed);
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

    /** Worker counters only; no game objects cross this boundary. Sampled by the writer, not attributed per frame. */
    public static void workerTexture(WorkerTexture key, long amount) {
        if (ENABLED) workerTextures[key.ordinal()].add(amount);
    }

    public static long beginLuaCallback(String event) { return "OnTick".equals(event) ? beginPhase() : 0L; }

    public static void luaCallback(long started, String file, String name, int line) {
        luaCallback(started, file, name, line, 0);
    }

    public static void luaCallback(long started, String file, String name, int line, int closureId) {
        if (started == 0L || !isCapturing()) return;
        long elapsed = Math.max(0L, System.nanoTime() - started);
        phases[Phase.LUA_ONTICK_CALLBACK.ordinal()].add(elapsed);
        framePhases[Phase.LUA_ONTICK_CALLBACK.ordinal()] += elapsed;
        if (elapsed < SLOW_LUA_NS) return;
        slowLuaCount++;
        int slot = slowLuaSize;
        if (slot == slowLua.length) {
            slot = 0;
            for (int i = 1; i < slowLuaSize; i++) if (slowLua[i].nanos < slowLua[slot].nanos) slot = i;
        }
        if (slowLuaSize < slowLua.length || elapsed > slowLua[slot].nanos) {
            slowLua[slot] = new LuaSample(frameNumber + 1, elapsed, bounded(file), bounded(name), line, closureId);
            if (slowLuaSize < slowLua.length) slowLuaSize++;
        }
    }

    private static String bounded(String text) { return text == null ? "<unknown>" : text.substring(0, Math.min(text.length(), 512)); }

    /** Detached dimensions only. scaled is true only after vanilla successfully disposes/replaces the source. */
    public static void textureLimit(long started, int inWidth, int inHeight, int outWidth, int outHeight,
                                    int maxSize, int flags, boolean scaled) {
        if (started == 0L || !isCapturing()) return;
        long elapsed = Math.max(0L, System.nanoTime() - started);
        textureLimitCalls++;
        if (scaled) texturesScaled++;
        textureInputPixels += Math.max(0L, (long)inWidth * inHeight);
        textureOutputPixels += Math.max(0L, (long)outWidth * outHeight);
        if (frameLargestTexture == null || elapsed > frameLargestTexture.nanos) {
            frameLargestTexture = new TextureSample(elapsed, inWidth, inHeight, outWidth, outHeight, maxSize, flags, scaled);
            if (largestTexture == null || elapsed > largestTexture.nanos) largestTexture = frameLargestTexture;
        }
    }

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
                    frameCadence, zombies, movingObjects, framePhases.clone(), frameFileMaxType, frameFileMaxNanos, cpuNanos, frameLargestTexture,
                    frameNetworkPackets, frameNetworkBytes, frameNetworkBacklogMax, frameLargestPacket);
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
            filesInProgress, filesPending, filesAdded, filesBacklogMax, fileTaskOverflow, frameCpu.take(), screenWidth, screenHeight, zoom,
            textureLimitCalls, texturesScaled, textureInputPixels, textureOutputPixels, largestTexture,
            slowLuaCount, Arrays.copyOf(slowLua, slowLuaSize), takeNetwork());
        slowLuaCount = 0L; slowLuaSize = 0; Arrays.fill(slowLua, null);
        textureLimitCalls = texturesScaled = textureInputPixels = textureOutputPixels = 0L;
        largestTexture = null;
        fileCompletions = fileSubmissions = fileTaskOverflow = 0L;
        filesInProgress = filesPending = filesAdded = filesBacklogMax = 0;
        Arrays.fill(slow, null);
        slowSize = 0;
        slowCount = 0L;
        windowStarted = now;
        if (!OUTPUT.offer(snapshot)) dropped.incrementAndGet();
    }

    private static NetworkSnapshot takeNetwork() {
        NetworkTypeSnapshot[] types=new NetworkTypeSnapshot[networkTypes.size()]; int at=0;
        for(Map.Entry<String,NetworkTiming> entry:networkTypes.entrySet()) {
            NetworkTiming t=entry.getValue(); long[][] stages=new long[NetworkStage.values().length][];
            for(int i=0;i<stages.length;i++) stages[i]=t.stages[i].take();
            types[at++]=new NetworkTypeSnapshot(entry.getKey(),t.total.take(),t.age.take(),t.bytes,t.delayed,t.errors,t.instances,stages);
            t.bytes=t.delayed=t.errors=t.instances=0L;
        }
        NetworkSnapshot result=new NetworkSnapshot(networkPolled,networkPending,networkLoading,networkCoop,networkBacklogMax,networkOverflow,types);
        networkPolled=networkOverflow=0L; networkPending=networkLoading=networkCoop=networkBacklogMax=0;
        return result;
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
        json.append("{\"schemaVersion\":7,\"kind\":\"client-main-thread\",\"targetBuild\":\"42.22.2\",\"seq\":")
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
            json.append(",\"largestTextureLimit\":"); appendTexture(json, f.texture);
            json.append(",\"network\":{\"packets\":").append(f.networkPackets).append(",\"payloadBytes\":").append(f.networkBytes)
                .append(",\"ownerBacklogMax\":").append(f.networkBacklogMax).append(",\"largestPacket\":");
            appendPacket(json,f.packet); json.append('}');
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
        json.append("}},\"network\":"); appendNetwork(json,s.network);
        Runtime rt = Runtime.getRuntime();
        json.append(",\"textures\":{\"limitCalls\":").append(s.textureCalls)
            .append(",\"scaled\":").append(s.scaled).append(",\"inputPixels\":").append(s.inputPixels)
            .append(",\"outputPixels\":").append(s.outputPixels).append(",\"largestLimit\":");
        appendTexture(json, s.texture);
        json.append("},\"slowLua\":{\"event\":\"OnTick\",\"thresholdMs\":").append(ms(SLOW_LUA_NS))
            .append(",\"count\":").append(s.luaSlowCount).append(",\"callbacks\":[");
        for (int i = 0; i < s.lua.length; i++) {
            if (i > 0) json.append(',');
            LuaSample l = s.lua[i];
            json.append("{\"frame\":").append(l.frame).append(",\"ms\":").append(ms(l.nanos))
                .append(",\"file\":\"").append(escape(l.file)).append("\",\"function\":\"").append(escape(l.name))
                .append("\",\"line\":").append(l.line).append(",\"closureId\":").append(l.closureId).append('}');
        }
        json.append("]},\"workerTextures\":{\"enabled\":")
            .append(!"false".equalsIgnoreCase(System.getProperty("apocbr.clientTextures.workerPreprocess", "true")));
        for (int i = 0; i < workerTextures.length; i++) {
            json.append(',');
            json.append('"').append(WorkerTexture.values()[i].name()).append("\":").append(workerTextures[i].sumThenReset());
        }
        json.append("},\"translucentWorker\":{\"enabled\":")
            .append(!"false".equalsIgnoreCase(System.getProperty("apocbr.clientTranslucent.async", "true")));
        for (int i = 0; i < translucentWorkers.length; i++) {
            json.append(',').append('"').append(TranslucentWorker.values()[i].name()).append("\":").append(translucentWorkers[i].sumThenReset());
        }
        json.append("},\"writerJvm\":{\"sampleTs\":").append(System.currentTimeMillis())
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

    private static void appendTexture(StringBuilder json, TextureSample t) {
        if (t == null) { json.append("null"); return; }
        json.append("{\"ms\":").append(ms(t.nanos)).append(",\"inputWidth\":").append(t.inWidth)
            .append(",\"inputHeight\":").append(t.inHeight).append(",\"outputWidth\":").append(t.outWidth)
            .append(",\"outputHeight\":").append(t.outHeight).append(",\"maxSize\":").append(t.maxSize)
            .append(",\"flags\":").append(t.flags).append(",\"scaled\":").append(t.scaled).append('}');
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
    private static void appendPacket(StringBuilder out,PacketSample p) {
        if(p==null) { out.append("null"); return; }
        out.append("{\"type\":\"").append(escape(p.type)).append("\",\"ms\":").append(ms(p.nanos))
            .append(",\"payloadBytes\":").append(p.bytes).append(",\"queueAgeMs\":").append(p.ageMs)
            .append(",\"delayed\":").append(p.delayed).append(",\"error\":").append(p.error).append('}');
    }
    private static void appendNetwork(StringBuilder out,NetworkSnapshot n) {
        out.append("{\"polled\":").append(n.polled).append(",\"pendingLast\":").append(n.pending)
            .append(",\"loadingLast\":").append(n.loading).append(",\"coopLast\":").append(n.coop)
            .append(",\"ownerBacklogMax\":").append(n.backlog).append(",\"typeOverflow\":").append(n.overflow).append(",\"types\":{");
        for(int i=0;i<n.types.length;i++) {
            if(i>0) out.append(','); NetworkTypeSnapshot t=n.types[i];
            out.append('"').append(escape(t.type)).append("\":{\"dispatch\":"); appendStats(out,t.total);
            out.append(",\"queueAge\":"); appendStats(out,t.age);
            out.append(",\"payloadBytes\":").append(t.bytes).append(",\"delayed\":").append(t.delayed)
                .append(",\"errors\":").append(t.errors).append(",\"freshInstances\":").append(t.instances).append(",\"stages\":{");
            for(int j=0;j<t.stages.length;j++) {
                if(j>0) out.append(','); out.append('"').append(NetworkStage.values()[j].name()).append("\":"); appendStats(out,t.stages[j]);
            }
            out.append("}}");
        }
        out.append("}}");
    }
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
    private static final class NetworkTiming {
        final Stats total=new Stats(),age=new Stats(); final Stats[] stages=new Stats[NetworkStage.values().length];
        long bytes,delayed,errors,instances;
        NetworkTiming() { for(int i=0;i<stages.length;i++) stages[i]=new Stats(); }
    }
    private record PacketSample(String type,long nanos,int bytes,long ageMs,boolean delayed,boolean error) { }
    private record NetworkTypeSnapshot(String type,long[] total,long[] age,long bytes,long delayed,long errors,long instances,long[][] stages) { }
    private record NetworkSnapshot(long polled,int pending,int loading,int coop,int backlog,long overflow,NetworkTypeSnapshot[] types) { }
    private record TaskSnapshot(String type, long[] result, long[] done) { }
    private record TextureSample(long nanos, int inWidth, int inHeight, int outWidth, int outHeight,
                                 int maxSize, int flags, boolean scaled) { }
    private record LuaSample(long frame, long nanos, String file, String name, int line, int closureId) { }
    private record SlowFrame(long frame, long ts, long nanos, long cadence, int zombies, int movingObjects, long[] phases,
        String fileType, long fileNanos, long cpuNanos, TextureSample texture,
        long networkPackets,long networkBytes,int networkBacklogMax,PacketSample packet) { }
    private record Snapshot(long seq, long ts, long interval, long[] frames, long[] cadence,
        long[][] phases, long slowCount, SlowFrame[] slow, int zombies, int movingObjects, String thread,
        TaskSnapshot[] tasks, long fileCompletions, long fileSubmissions, int inProgress, int pending, int added,
        int backlogMax, long taskOverflow, long[] cpu, int width, int height, float zoom,
        long textureCalls, long scaled, long inputPixels, long outputPixels, TextureSample texture,
        long luaSlowCount, LuaSample[] lua,NetworkSnapshot network) { }
}
