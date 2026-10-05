package zombie.vehicles;

import java.util.Arrays;
import zombie.ApocBRServerTelemetryLite;

/** Fixed labels and one flush per scheduler batch, with no per-vehicle arrays. */
public final class ServerVehicleUpdateTelemetry {
    private static final String[] LABELS = { "checks", "inherited", "animals", "routing", "physicsState", "bookkeeping", "parts", "tail" };
    private static final class Batch { final long[] nanos = new long[LABELS.length]; int depth; }
    private static final ThreadLocal<Batch> batch = ThreadLocal.withInitial(Batch::new);
    public static void beginBatch() { batch.get().depth++; }
    public static void record(int stage, long nanos) {
        Batch current = batch.get();
        if (current.depth > 0) current.nanos[stage] += nanos;
        else ApocBRServerTelemetryLite.recordPhase("simulation.vehicles.update." + LABELS[stage], nanos);
    }
    public static void endBatch() {
        Batch current = batch.get();
        if (--current.depth != 0) return;
        for (int i = 0; i < LABELS.length; i++) {
            if (current.nanos[i] > 0L) ApocBRServerTelemetryLite.recordPhase("simulation.vehicles.update." + LABELS[i], current.nanos[i]);
        }
        Arrays.fill(current.nanos, 0L);
    }
}
