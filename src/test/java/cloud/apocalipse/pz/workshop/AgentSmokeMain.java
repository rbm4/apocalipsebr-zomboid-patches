package cloud.apocalipse.pz.workshop;

import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import zombie.Lua.LuaManager;

/** Run manually with the packaged -javaagent to verify transformation and helper linkage. */
public class AgentSmokeMain {
    public static void main(String[] args) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<Thread> worker = new AtomicReference<>();
        CountDownLatch called = new CountDownLatch(1);
        ModUpdateRestartNotifier notifier = new ModUpdateRestartNotifier(
            URI.create("https://example.invalid/api/server/mod-update/restart"), "test-token", (url, token) -> {
                if (!Thread.currentThread().isVirtual()) throw new AssertionError("Expected virtual worker");
                worker.set(Thread.currentThread());
                calls.incrementAndGet();
                called.countDown();
                return RestartHttpClient.Result.ALREADY_IN_PROGRESS;
            }, System::nanoTime);
        var field = ModUpdateRestartNotifier.class.getDeclaredField("instance");
        field.setAccessible(true);
        field.set(null, notifier);
        LuaManager.GlobalObject.inform("CheckModsNeedUpdate: Mods updated");
        LuaManager.GlobalObject.inform("CheckModsNeedUpdate: Check not completed");
        if (calls.get() != 0) throw new AssertionError("Unrelated messages triggered restart");
        LuaManager.GlobalObject.inform("CheckModsNeedUpdate: Mods need update");
        if (!called.await(5, TimeUnit.SECONDS)) throw new AssertionError("Hook did not notify");
        worker.get().join(5000);
        LuaManager.GlobalObject.inform("CheckModsNeedUpdate: Mods need update");
        if (calls.get() != 1) throw new AssertionError("Duplicate request");
        if (LuaManager.informCalls != 4) throw new AssertionError("Original method did not run for every message");
        System.out.println("Packaged agent smoke check passed.");
    }
}
