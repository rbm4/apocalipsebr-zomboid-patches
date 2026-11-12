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
            if (ApocBRClientTelemetryLite.ENABLED && start == 0L) throw new AssertionError("capture missing");
            Thread other = new Thread(() -> {
                ApocBRClientTelemetryLite.beginFrame(false);
                ApocBRClientTelemetryLite.endPhase(Phase.NETWORK, System.nanoTime() - 1000000000L);
                ApocBRClientTelemetryLite.endFrame(999, 999);
            });
            other.start(); other.join();
            Thread.sleep(2L);
            ApocBRClientTelemetryLite.endPhase(Phase.LOGIC, start);
            ApocBRClientTelemetryLite.endFrame(200 + i, 300 + i);
        }
        ApocBRClientTelemetryLite.beginFrame(false);
        ApocBRClientTelemetryLite.endPhase(Phase.NETWORK, System.nanoTime() - 1000000000L);
        ApocBRClientTelemetryLite.beginFrame(true);
        ApocBRClientTelemetryLite.endFrame(7, 8);
        ApocBRClientTelemetryLite.shutdown();
    }
}
