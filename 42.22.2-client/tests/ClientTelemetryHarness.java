import zombie.ApocBRClientTelemetryLite;
import zombie.ApocBRClientTelemetryLite.Phase;

/** Exercises production capture, phase ownership, window reset and bounded slow-frame retention. */
public final class ClientTelemetryHarness {
    public static void main(String[] args) throws Exception {
        ApocBRClientTelemetryLite.beginFrame(false);
        if (ApocBRClientTelemetryLite.isCapturing()) throw new AssertionError("menu captured");
        for (int i = 0; i < 25; i++) {
            ApocBRClientTelemetryLite.beginFrame(true);
            long start = ApocBRClientTelemetryLite.beginPhase();
            ApocBRClientTelemetryLite.renderContext(1920, 1080, 1.5f);
            ApocBRClientTelemetryLite.textureLimit(ApocBRClientTelemetryLite.beginPhase(),
                4096, 2048, 1024, 512, 1024, 128, true);
            ApocBRClientTelemetryLite.fileQueues(3, 4, 5);
            ApocBRClientTelemetryLite.networkQueues(3,4,5);
            ApocBRClientTelemetryLite.buildingCommit(2,3,4,1,2,1,5);
            ApocBRClientTelemetryLite.networkPolled(2);
            ApocBRClientTelemetryLite.networkStage("test",ApocBRClientTelemetryLite.NetworkStage.PARSE,ApocBRClientTelemetryLite.beginPhase());
            ApocBRClientTelemetryLite.networkPacket("test",ApocBRClientTelemetryLite.beginPhase(),12,25,false,false);
            ApocBRClientTelemetryLite.fileSubmitted();
            ApocBRClientTelemetryLite.fileTask("result", ApocBRClientTelemetryLite.beginPhase(), false);
            ApocBRClientTelemetryLite.fileTask("cleanup", ApocBRClientTelemetryLite.beginPhase(), true);
            if (i == 0) {
                for(int type=0;type<300;type++) {
                    ApocBRClientTelemetryLite.networkStage("type"+type,ApocBRClientTelemetryLite.NetworkStage.APPLY,ApocBRClientTelemetryLite.beginPhase());
                    ApocBRClientTelemetryLite.networkPacket("type"+type,ApocBRClientTelemetryLite.beginPhase(),1,100,false,type==299);
                }
                for (int dimensions = 1; dimensions <= 70; dimensions++) {
                    Object task = java.lang.reflect.Array.newInstance(int.class, new int[dimensions]);
                    ApocBRClientTelemetryLite.fileTask(task, ApocBRClientTelemetryLite.beginPhase(), false);
                }
            }
            if (ApocBRClientTelemetryLite.ENABLED && start == 0L) throw new AssertionError("capture missing");
            Thread other = new Thread(() -> {
                ApocBRClientTelemetryLite.beginFrame(false);
                ApocBRClientTelemetryLite.endPhase(Phase.NETWORK, System.nanoTime() - 1000000000L);
                ApocBRClientTelemetryLite.endFrame(999, 999);
                ApocBRClientTelemetryLite.fileTask(new Object(), 1L, false);
                ApocBRClientTelemetryLite.textureLimit(1L, 999, 999, 999, 999, 999, 0, false);
                ApocBRClientTelemetryLite.networkStage("wrong-thread",ApocBRClientTelemetryLite.NetworkStage.PARSE,1L);
                ApocBRClientTelemetryLite.networkPacket("wrong-thread",1L,999,999,false,true);
                ApocBRClientTelemetryLite.networkPolled(999);
                ApocBRClientTelemetryLite.networkQueues(999,999,999);
                ApocBRClientTelemetryLite.buildingCommit(999,999,999,999,999,999,999);
                ApocBRClientTelemetryLite.buildingWorker(1000000L,400000L,600000L);
            });
            other.start(); other.join();
            Thread.sleep(2L);
            ApocBRClientTelemetryLite.luaCallback(start, "mods/test.lua", "tick", 42, 123);
            if (i == 0) for (int callback = 0; callback < 40; callback++) {
                ApocBRClientTelemetryLite.luaCallback(System.nanoTime() - 10000000L,
                    "mods/slow.lua", "slow\"callback", callback, callback);
            }
            ApocBRClientTelemetryLite.endPhase(Phase.LOGIC, start);
            ApocBRClientTelemetryLite.endFrame(200 + i, 300 + i);
        }
        ApocBRClientTelemetryLite.beginFrame(false);
        ApocBRClientTelemetryLite.endPhase(Phase.NETWORK, System.nanoTime() - 1000000000L);
        ApocBRClientTelemetryLite.beginFrame(true);
        ApocBRClientTelemetryLite.renderContext(1280, 720, 2.0f);
        ApocBRClientTelemetryLite.endFrame(7, 8);
        ApocBRClientTelemetryLite.shutdown();
    }
}
