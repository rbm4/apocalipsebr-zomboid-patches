package cloud.apocalipse.pz.agent.experimental.luapreparation;

import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import zombie.ZomboidFileSystem;
import zombie.gameStates.ChooseGameInfo;
import zombie.network.GameClient;
import zombie.network.GameServer;

/** Client-thread adapter between vanilla's resolved paths and the file preparation worker. */
public final class AsyncLuaPreparation {
    private static final ThreadLocal<Join> JOIN = new ThreadLocal<>();
    private static final ThreadLocal<Join> RELOAD = new ThreadLocal<>();

    private AsyncLuaPreparation() { }

    public static void startJoin(Object owner) {
        if (GameServer.server || !GameClient.client) return;
        Join previous = JOIN.get();
        if (previous != null) endJoin(previous.owner);

        Join join = new Join(owner);
        JOIN.set(join);
        join.files.prepare(List.of(ZomboidFileSystem.instance.getMediaFile("lua").toPath()));
    }

    public static void prepareRequiredMods(List<String> requested, List<String> resolved, String missing) {
        Join join = JOIN.get();
        if (join != null && missing == null && requested == GameClient.instance.serverMods) {
            prepareMods(join, resolved);
        }
    }

    public static void prepareLoadedMods() {
        Join join = RELOAD.get();
        if (join != null) prepareMods(join, ZomboidFileSystem.instance.getModIDs());
    }

    private static void prepareMods(Join join, List<String> ids) {
        List<Path> roots = new ArrayList<>();
        for (String id : ids) {
            ChooseGameInfo.Mod mod = ChooseGameInfo.getAvailableModDetails(id);
            if (mod == null) continue;
            addModRoot(roots, mod.getCommonDir());
            addModRoot(roots, mod.getVersionDir());
        }
        join.files.prepare(roots);
    }

    private static void addModRoot(List<Path> roots, String directory) {
        if (directory != null) roots.add(Path.of(directory, "media", "lua"));
    }

    public static boolean beginReload(String reason) {
        Join join = JOIN.get();
        if (join == null || RELOAD.get() != null || !"ConnectedToServer".equals(reason)) return false;
        join.reloadStarted = System.nanoTime();
        RELOAD.set(join);
        return true;
    }

    public static InputStreamReader openPrepared(String path) {
        Join join = RELOAD.get();
        if (join == null || !path.toLowerCase(Locale.ROOT).endsWith(".lua")) return null;

        Path resolved = Path.of(ZomboidFileSystem.instance.getString(path));
        InputStreamReader reader = join.files.open(resolved);
        if (reader == null) join.misses++;
        else join.hits++;
        return reader;
    }

    public static void endReload(boolean opened) {
        if (!opened) return;
        Join join = RELOAD.get();
        RELOAD.remove();
        if (join == null) return;

        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - join.reloadStarted);
        System.out.printf("[ApocBR-Agent] Client Lua reload %d ms, prepared reads %d, vanilla reads %d%n",
            millis, join.hits, join.misses);
    }

    public static void endJoin(Object owner) {
        Join join = JOIN.get();
        if (join == null || join.owner != owner) return;
        JOIN.remove();
        if (RELOAD.get() == join) RELOAD.remove();
        join.files.close();
    }

    private static final class Join {
        private final Object owner;
        private final PreparedLuaFiles files = new PreparedLuaFiles();
        private long reloadStarted;
        private int hits;
        private int misses;

        private Join(Object owner) { this.owner = owner; }
    }
}
