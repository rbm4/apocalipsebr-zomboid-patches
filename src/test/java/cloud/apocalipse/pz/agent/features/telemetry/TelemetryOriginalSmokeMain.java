package cloud.apocalipse.pz.agent.features.telemetry;

import java.util.concurrent.locks.LockSupport;
import org.luaj.kahluafork.compiler.FuncState;
import se.krka.kahlua.integration.LuaCaller;
import se.krka.kahlua.luaj.compiler.LuaCompiler;
import se.krka.kahlua.vm.JavaFunction;
import se.krka.kahlua.vm.KahluaTable;
import se.krka.kahlua.vm.KahluaThread;
import se.krka.kahlua.vm.Platform;
import zombie.Lua.Event;
import zombie.ZomboidFileSystem;
import zombie.core.Core;

/** Run with the original archive ahead of test fixtures on the classpath. */
public final class TelemetryOriginalSmokeMain {
    public static void main(String[] args) throws Exception {
        String gameVersion = Core.getInstance().getVersionNumber();
        if (gameVersion.isBlank() || !gameVersion.equals(TelemetryWorldSnapshot.gameVersion())) {
            throw new AssertionError("Could not read the original Core game version");
        }
        Class<?> lua = Class.forName("zombie.Lua.LuaManager");
        Platform platform = (Platform) lua.getField("platform").get(null);
        KahluaTable environment = platform.newTable();
        KahluaThread thread = new KahluaThread(platform, environment);
        thread.debugOwnerThread = Thread.currentThread();
        lua.getField("thread").set(null, thread);
        LuaCaller caller = (LuaCaller) lua.getField("caller").get(null);
        Event event = (Event) Event.class.getConstructor(String.class, int.class).newInstance("FixtureHook", 0);

        FuncState.currentFile = "hello.lua";
        FuncState.currentfullFile = "media/lua/server/hello.lua";
        ZomboidFileSystem files = ZomboidFileSystem.instance;
        files.setCacheDir(java.nio.file.Files.createTempDirectory("apocbr-telemetry-fixture-").toString());
        files.init();
        String modDirectory = "/fixture/workshop/content/108600/1234/mods/Fixture";
        files.activeFileMap.put(FuncState.currentfullFile, modDirectory + "/42/" + FuncState.currentfullFile);
        files.getModIDs().add("FixtureMod");
        var modDirectories = ZomboidFileSystem.class.getDeclaredField("modIdToDir");
        modDirectories.setAccessible(true);
        @SuppressWarnings("unchecked")
        var directoryByMod = (java.util.Map<String, String>) modDirectories.get(files);
        directoryByMod.put("FixtureMod", modDirectory);
        environment.rawset("stall", (JavaFunction) (frame, count) -> {
            LockSupport.parkNanos(15_000_000);
            environment.rawset("executed", true);
            return 0;
        });
        var closure = LuaCompiler.loadstring("stall()", "hello.lua", environment);
        event.callbacks.add(closure);
        var trigger = Event.class.getMethod("trigger", KahluaTable.class, LuaCaller.class, Object[].class);

        TickDiagnostics.begin(System.nanoTime());
        try {
            if (!Boolean.TRUE.equals(trigger.invoke(event, environment, caller, new Object[0]))
                    || !Boolean.TRUE.equals(environment.rawget("executed"))) {
                throw new AssertionError("original event callback did not run");
            }
            try {
                thread.call(LuaCompiler.loadstring("broken()", "failure.lua", environment), new Object[0]);
                throw new AssertionError("expected original Lua exception");
            } catch (RuntimeException expected) {
                if (!TickDiagnostics.canTimeCallback()) throw new AssertionError("exception left callback depth set");
            }
            caller.protectedCallVoid(thread, closure, new Object[0]);
            zombie.core.znet.SteamUtils.runLoop();
            TickDiagnostics.recordTick(1_200_000_000L);
        } finally {
            TickDiagnostics.end();
        }

        TickDiagnostics.begin(System.nanoTime());
        OptimizationTelemetry.Phase.BACKUP.recordSince(System.nanoTime() - 10_000_000);
        TickDiagnostics.recordTick(100_000_001L);
        TickDiagnostics.end();
        var report = TickDiagnostics.snapshotAndReset();
        var peak = report.peak();
        if (peak == null || peak.nanos() != 1_200_000_000L || !peak.phases().containsKey("workshop.steamPump")
                || peak.phases().containsKey("backup.onPeriod") || peak.luaMs() < 15
                || peak.callbacks().stream().noneMatch(row -> "FixtureHook".equals(row.get("hook"))
                    && "media/lua/server/hello.lua".equals(row.get("file"))
                    && "FixtureMod".equals(row.get("modId")) && "1234".equals(row.get("workshopId")))
                || report.callbacks().stream().noneMatch(row -> "(direct)".equals(row.get("hook")))) {
            throw new AssertionError("callback and slow tick attribution missing: " + report);
        }
        if (TickDiagnostics.snapshotAndReset().peak() != null) throw new AssertionError("peak was not reset");
        System.out.println("Original Lua callback hooks passed");
    }
}
