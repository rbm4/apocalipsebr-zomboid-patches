package zombie.Lua;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CompletableFuture;
import se.krka.kahlua.integration.annotations.LuaMethod;
import zombie.core.PZForkJoinPool;
import zombie.core.logger.ExceptionLogger;

public class AsyncLuaManager {
    private static final Object APPEND_QUEUE_LOCK = new Object();
    private static CompletableFuture<Void> appendQueue = CompletableFuture.completedFuture(null);

    @LuaMethod(name = "appendToFileAsync", global = true)
    public static boolean appendToFileAsync(String fileName, String contents) {
        Path target = resolveCacheFile(fileName);
        if (target == null || contents == null) {
            return false;
        }

        synchronized (APPEND_QUEUE_LOCK) {
            appendQueue = appendQueue.handle((ignored, error) -> null).thenRunAsync(() -> append(target, contents), PZForkJoinPool.commonPool());
        }

        return true;
    }

    private static Path resolveCacheFile(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return null;
        }

        try {
            Path cacheRoot = Path.of(LuaManager.getLuaCacheDir()).toAbsolutePath().normalize();
            Path requested = Path.of(fileName.replace('\\', '/'));
            if (requested.isAbsolute()) {
                return null;
            }

            Path target = cacheRoot.resolve(requested).normalize();
            return target.startsWith(cacheRoot) && !target.equals(cacheRoot) ? target : null;
        } catch (InvalidPathException ignored) {
            return null;
        }
    }

    private static void append(Path target, String contents) {
        try {
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            Files.writeString(target, contents, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException exception) {
            ExceptionLogger.logException(exception, "Async Lua file append failed: " + target);
        }
    }
}
