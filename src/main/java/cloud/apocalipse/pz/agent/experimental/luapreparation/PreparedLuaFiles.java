package cloud.apocalipse.pz.agent.experimental.luapreparation;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** One join's prepared bytes. The worker receives only paths and never enters game code. */
final class PreparedLuaFiles {
    private static final int MAX_FILE_BYTES = 1024 * 1024;
    private static final int MAX_TOTAL_BYTES = 32 * 1024 * 1024;
    private static final int MAX_FILES = 16_384;
    private static final List<String> LUA_DIRECTORIES = List.of("shared", "client");
    private static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(0, 1, 10, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(3), task -> Thread.ofPlatform().daemon().name("ApocBR-Client-LuaPrepare").unstarted(task));

    private final Map<Path, PreparedFile> files = new ConcurrentHashMap<>();
    private final Set<Path> scheduledRoots = new HashSet<>();
    private final List<Future<?>> jobs = new ArrayList<>();
    private volatile boolean closed;
    private int preparedBytes;

    void prepare(List<Path> roots) {
        List<Path> pending = roots.stream().map(PreparedLuaFiles::absolute).filter(scheduledRoots::add).toList();
        if (pending.isEmpty()) return;
        try {
            jobs.add(WORKER.submit(() -> readRoots(pending)));
        } catch (RejectedExecutionException ignored) {
            scheduledRoots.removeAll(pending);
        }
    }

    InputStreamReader open(Path requested) {
        Path path = absolute(requested);
        PreparedFile file = files.get(path);
        if (file == null) return null;
        try {
            if (file.stamp.equals(Stamp.read(path))) {
                return new InputStreamReader(new ByteArrayInputStream(file.bytes), StandardCharsets.UTF_8);
            }
        } catch (IOException ignored) {
            // Let the original loader handle unavailable files and its own fallback paths.
        }
        return null;
    }

    void close() {
        synchronized (this) {
            closed = true;
            files.clear();
        }
        jobs.forEach(job -> job.cancel(true));
        WORKER.purge();
    }

    private void readRoots(List<Path> roots) {
        for (Path root : roots) {
            for (String directory : LUA_DIRECTORIES) {
                if (!canPrepareMore()) return;
                try (var walk = Files.walk(root.resolve(directory))) {
                    var paths = walk.iterator();
                    while (canPrepareMore() && paths.hasNext()) {
                        Path path = paths.next();
                        if (path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".lua")) readFile(path);
                    }
                } catch (IOException | UncheckedIOException | SecurityException ignored) {
                    // A missing or unreadable subtree can still be loaded through vanilla.
                }
            }
        }
    }

    private boolean canPrepareMore() {
        return !closed && !Thread.currentThread().isInterrupted()
            && preparedBytes < MAX_TOTAL_BYTES && files.size() < MAX_FILES;
    }

    private void readFile(Path path) {
        if (files.containsKey(path)) return;
        try {
            Stamp before = Stamp.read(path);
            if (before == null || before.size > MAX_FILE_BYTES || before.size > MAX_TOTAL_BYTES - preparedBytes) return;
            byte[] bytes;
            try (var input = Files.newInputStream(path)) {
                bytes = input.readNBytes((int) before.size + 1);
            }
            if (bytes.length != before.size || !before.equals(Stamp.read(path))) return;
            synchronized (this) {
                if (closed) return;
                files.put(path, new PreparedFile(before, bytes));
                preparedBytes += bytes.length;
            }
        } catch (IOException | SecurityException ignored) {
            // Preparation is best effort. A single file failure leaves the other files eligible.
        }
    }

    private static Path absolute(Path path) { return path.toAbsolutePath().normalize(); }

    private record PreparedFile(Stamp stamp, byte[] bytes) { }

    private record Stamp(long size, FileTime modified, Object fileKey) {
        private static Stamp read(Path path) throws IOException {
            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
            return attrs.isRegularFile() ? new Stamp(attrs.size(), attrs.lastModifiedTime(), attrs.fileKey()) : null;
        }
    }
}
