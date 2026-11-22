package cloud.apocalipse.pz.agent.features.workshop;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import zombie.Lua.LuaManager.GlobalObject;
import zombie.core.znet.SteamUtils;
import zombie.core.znet.SteamWorkshop;
import zombie.network.GameServer;

public final class WorkshopAsyncSmokeMain {
    public static void main(String[] args) throws Exception {
        GameServer.server = true;
        GameServer.mainThread = Thread.currentThread();
        SteamWorkshop steam = SteamWorkshop.instance;

        SteamUtils.enabled = false;
        GlobalObject.checkModsNeedUpdate(null);
        require(GlobalObject.vanillaChecks == 1, "Steam-disabled path must call vanilla");
        SteamUtils.enabled = true;

        GlobalObject.checkModsNeedUpdate(null);
        GlobalObject.checkModsNeedUpdate(null);
        require(steam.created == 0 && GlobalObject.vanillaChecks == 1, "entry point must defer work");
        Thread.ofPlatform().start(SteamUtils::runLoop).join();
        require(steam.created == 0, "other thread cannot start the query");
        SteamUtils.runLoop();
        require(steam.created == 1 && steam.reads == 0, "coalesced query starts during the pump");
        SteamUtils.runLoop();
        require(steam.reads > 0 && steam.reads <= 8 && steam.released == 0, "result scan must yield");
        for (int i = 0; i < 18; i++) SteamUtils.runLoop();
        require(steam.reads == 18 && steam.released == 1 && steam.removed == 1, "completed query cleanup");

        var notification = new CountDownLatch(1);
        var entry = ModUpdateRestartNotifier.class.getDeclaredField("instance");
        entry.setAccessible(true);
        entry.set(null, new ModUpdateRestartNotifier("fixture", token -> {
            if (!Thread.currentThread().isVirtual()) throw new AssertionError("restart must use worker");
            notification.countDown();
            return RestartHttpClient.Result.ACCEPTED;
        }, System::nanoTime));
        steam.updateAt = 0;
        GlobalObject.checkModsNeedUpdate(null);
        SteamUtils.runLoop();
        SteamUtils.runLoop();
        require(notification.await(5, TimeUnit.SECONDS), "mismatched installed timestamp must notify");
        require(steam.released == 2 && steam.reads == 19, "update consumes only necessary results");

        steam.failCreate = true;
        GlobalObject.checkModsNeedUpdate(null);
        SteamUtils.runLoop();
        require(steam.released == 2 && steam.removed == 3, "zero handle removes callback only");
        steam.failCreate = false;
        steam.failQuery = true;
        GlobalObject.checkModsNeedUpdate(null);
        SteamUtils.runLoop();
        SteamUtils.runLoop();
        require(steam.released == 3 && steam.removed == 4, "failed callback releases handle");
        steam.failQuery = false;
        steam.failRead = true;
        GlobalObject.checkModsNeedUpdate(null);
        SteamUtils.runLoop();
        SteamUtils.runLoop();
        require(steam.released == 4 && steam.removed == 5, "result read exception releases handle");

        steam.complete = false;
        GlobalObject.checkModsNeedUpdate(null);
        SteamUtils.runLoop();
        GameServer.runShutdownHook();
        require(GameServer.done && steam.released == 5 && steam.removed == 6, "shutdown releases pending query");
        GlobalObject.checkModsNeedUpdate(null);
        SteamUtils.runLoop();
        require(steam.created == 6, "shutdown must stop new queries");
        System.out.println("Async Workshop hooks passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
