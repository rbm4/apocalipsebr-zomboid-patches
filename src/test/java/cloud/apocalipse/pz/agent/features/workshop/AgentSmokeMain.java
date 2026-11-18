package cloud.apocalipse.pz.agent.features.workshop;

import cloud.apocalipse.pz.agent.features.telemetry.TelemetryRegistry;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import zombie.Lua.Event;
import zombie.Lua.LuaManager;
import zombie.network.GameServer;

/** Run manually with the packaged -javaagent to verify transformation and helper linkage. */
public class AgentSmokeMain {
    public static void main(String[] args) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<Thread> worker = new AtomicReference<>();
        CountDownLatch called = new CountDownLatch(1);
        ModUpdateRestartNotifier notifier = new ModUpdateRestartNotifier(
            "test-token", token -> {
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

        Event event = new Event();
        event.callbacks.add(new Object());
        event.callbacks.add(new Object());
        event.trigger(null, null, new Object[0]);
        TelemetryRegistry.EventSnapshot metric = TelemetryRegistry.snapshot().get("OnTick");
        if (metric == null || metric.calls() != 1L || metric.callbacks() != 2L || metric.totalNanos() < 0L) {
            throw new AssertionError("Lua event timing advice did not record one invocation");
        }
        GameServer.launchCommandHandler();
        System.out.println("Packaged agent smoke check passed.");
    }
}
