package cloud.apocalipse.pz.agent.integration.probe;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import static net.bytebuddy.matcher.ElementMatchers.*;

/** Runs one known delay within a native vehicle callback on the real scheduler. */
public final class MovingObjectSpike {
    private static final long DELAY_MILLIS = 750;
    private static Path evidence;
    private static Thread owner;
    private static boolean armed;
    private static boolean fired;
    private static int depth;

    private MovingObjectSpike() {}

    static AgentBuilder configure(AgentBuilder builder, Path directory) {
        evidence = directory.resolve("moving-object-spike.txt");
        return builder
            .type(named("zombie.MovingObjectUpdateSchedulerUpdateBucket"))
            .transform((dynamic, type, loader, module, domain) -> dynamic.visit(Advice.to(Bucket.class)
                .on(named("update").and(hasDescriptor("(I)V")))))
            .type(named("zombie.vehicles.BaseVehicle"))
            .transform((dynamic, type, loader, module, domain) -> dynamic.visit(Advice.to(Vehicle.class)
                .on(named("update").and(hasDescriptor("()V")))));
    }

    static void arm() {
        if (!fired) {
            owner = Thread.currentThread();
            armed = true;
        }
    }

    public static void enterBucket() {
        if (Thread.currentThread() == owner) depth++;
    }

    public static void leaveBucket() {
        if (Thread.currentThread() == owner) depth--;
    }

    public static boolean delayVehicle() throws InterruptedException {
        if (Thread.currentThread() != owner || depth == 0 || !armed) return false;
        armed = false;
        fired = true;
        Thread.sleep(DELAY_MILLIS);
        return true;
    }

    public static void recordVehicle(Throwable failure) throws IOException {
        Files.writeString(evidence, failure == null ? "native vehicle update returned" : failure.toString());
    }

    public static class Bucket {
        @Advice.OnMethodEnter public static void enter() { enterBucket(); }
        @Advice.OnMethodExit(onThrowable = Throwable.class) public static void exit() { leaveBucket(); }
    }

    public static class Vehicle {
        @Advice.OnMethodEnter public static boolean enter() throws InterruptedException { return delayVehicle(); }
        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(@Advice.Enter boolean delayed, @Advice.Thrown Throwable failure) throws IOException {
            if (delayed) recordVehicle(failure);
        }
    }
}
