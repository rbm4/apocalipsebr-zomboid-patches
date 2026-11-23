package cloud.apocalipse.pz.agent.integration.probe;

import cloud.apocalipse.pz.agent.features.optimization.OptimizationVisitor;
import cloud.apocalipse.pz.agent.features.optimization.vehicle.ParkedVehicleModule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.jar.asm.MethodVisitor;
import zombie.MovingObjectUpdateScheduler;
import zombie.UpdateSchedulerSimulationLevel;
import zombie.vehicles.BaseVehicle;

import static net.bytebuddy.jar.asm.Opcodes.*;
import static net.bytebuddy.matcher.ElementMatchers.*;
import static zombie.UpdateSchedulerSimulationLevel.FULL;
import static zombie.UpdateSchedulerSimulationLevel.SIXTEENTH;

/** Opt-in paired benchmark in a single loaded world and JVM. Used only on the world owner. */
public final class VehicleCadenceProbe {
    public static final String COMPARE_PROPERTY = "pz.it.parkedVehicleComparison";
    private static final String SELECTOR = "getUpdateSchedulerSimulationLevelForObject";
    private static final String SELECTOR_DESCRIPTOR = "(Lzombie/iso/IsoMovingObject;F)Lzombie/UpdateSchedulerSimulationLevel;";
    private static final int WINDOW_FRAMES = 16;
    private static final int FIRST_FRAME = 35;
    private static final ArrayList<Window> windows = new ArrayList<>();
    private static Window current;
    private static boolean checkedWakeup;

    private VehicleCadenceProbe() {}

    static AgentBuilder configure(AgentBuilder builder) {
        return builder.type(named("zombie.MovingObjectUpdateScheduler")).transform((dynamic, type, loader, module, domain) ->
            dynamic.visit(OptimizationVisitor.method(SELECTOR, SELECTOR_DESCRIPTOR, SelectorVisitor::new))
                .visit(Advice.to(SchedulerTiming.class).on(namedOneOf("startFrame", "update", "postupdate")
                    .and(hasDescriptor("()V")))));
    }

    static void frame(int cycle, int held, boolean released) {
        int offset = held - FIRST_FRAME;
        if (released || offset < 0 || offset >= 4 * WINDOW_FRAMES) {
            current = null;
            return;
        }
        if (offset % WINDOW_FRAMES == 0) {
            int block = offset / WINDOW_FRAMES;
            // Reverse which arm starts on odd cycles, with two windows per arm.
            boolean enabled = (block == 1 || block == 2) ^ (cycle % 2 == 1);
            current = new Window(cycle, block, enabled);
            windows.add(current);
        }
        current.frames++;
    }

    static void stop() {
        current = null;
    }

    public static UpdateSchedulerSimulationLevel selectLevel(BaseVehicle vehicle, UpdateSchedulerSimulationLevel vanilla) {
        Window window = current;
        if (window == null) return ParkedVehicleModule.selectLevel(vehicle, vanilla);
        window.offered++;
        UpdateSchedulerSimulationLevel result = window.enabled ? ParkedVehicleModule.selectLevel(vehicle, vanilla) : vanilla;
        if (result == SIXTEENTH) {
            window.parked++;
            if (!checkedWakeup) verifyWakeup(vehicle, vanilla);
            int frame = (int) MovingObjectUpdateScheduler.instance.getFrameCounter();
            if (vehicle.getID() % WINDOW_FRAMES != frame % WINDOW_FRAMES) return result;
            window.expectedParked++;
        }
        window.expected++;
        return result;
    }

    private static void verifyWakeup(BaseVehicle vehicle, UpdateSchedulerSimulationLevel vanilla) {
        vehicle.setNeedPartsUpdate(true);
        try {
            requireFull(vehicle, vanilla, "pending part update");
        } finally {
            vehicle.setNeedPartsUpdate(false);
        }
        float vertical = vehicle.jniLinearVelocity.y;
        vehicle.jniLinearVelocity.y = 1.0F;
        try {
            requireFull(vehicle, vanilla, "motion reported without a local physics controller");
        } finally {
            vehicle.jniLinearVelocity.y = vertical;
        }
        if (ParkedVehicleModule.selectLevel(vehicle, vanilla) != SIXTEENTH) {
            throw new IllegalStateException("Restoring the parked state must restore sixteenth cadence");
        }
        checkedWakeup = true;
    }

    private static void requireFull(BaseVehicle vehicle, UpdateSchedulerSimulationLevel vanilla, String reason) {
        if (ParkedVehicleModule.selectLevel(vehicle, vanilla) != FULL) {
            throw new IllegalStateException("Expected full cadence with " + reason);
        }
    }

    static void recordVehicleUpdate(Object vehicle, long nanos) {
        Window window = current;
        if (window == null) return;
        window.callbacks++;
        window.vehicleNanos += nanos;
        if (((BaseVehicle) vehicle).getCurrentSimulationLevel() == SIXTEENTH) window.parkedCallbacks++;
    }

    public static long beginScheduler() {
        return current == null ? 0 : System.nanoTime();
    }

    public static void recordScheduler(String method, long started) {
        if (started == 0) return;
        long nanos = System.nanoTime() - started;
        switch (method) {
            case "startFrame" -> current.setupNanos += nanos;
            case "update" -> current.updateNanos += nanos;
            case "postupdate" -> current.postNanos += nanos;
            default -> throw new IllegalStateException(method);
        }
    }

    static void write(Path directory) throws IOException {
        var csv = new StringBuilder("cycle,block,enabled,frames,offered,parked,expected,expectedParked,callbacks,"
            + "parkedCallbacks,vehicleNanos,setupNanos,updateNanos,postNanos\n");
        for (Window window : windows) {
            csv.append(window.cycle).append(',').append(window.block).append(',').append(window.enabled)
                .append(',').append(window.frames).append(',').append(window.offered).append(',').append(window.parked)
                .append(',').append(window.expected).append(',').append(window.expectedParked).append(',')
                .append(window.callbacks).append(',').append(window.parkedCallbacks).append(',')
                .append(window.vehicleNanos).append(',').append(window.setupNanos).append(',')
                .append(window.updateNanos).append(',').append(window.postNanos).append('\n');
        }
        Files.writeString(directory.resolve("vehicle-cadence.csv"), csv);
        if (checkedWakeup) Files.createFile(directory.resolve("vehicle-cadence-wakeup"));
    }

    public static final class SchedulerTiming {
        @Advice.OnMethodEnter public static long enter() { return beginScheduler(); }

        @Advice.OnMethodExit public static void exit(@Advice.Origin("#m") String method, @Advice.Enter long started) {
            recordScheduler(method, started);
        }
    }

    private static final class SelectorVisitor extends MethodVisitor {
        private int replacements;

        SelectorVisitor(MethodVisitor downstream) { super(ASM9, downstream); }

        @Override public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean itf) {
            if (opcode == INVOKESTATIC && owner.equals(ParkedVehicleModule.class.getName().replace('.', '/'))
                    && name.equals("selectLevel")) {
                OptimizationVisitor.callStatic(mv, VehicleCadenceProbe.class, name, descriptor);
                replacements++;
            } else {
                super.visitMethodInsn(opcode, owner, name, descriptor, itf);
            }
        }

        @Override public void visitEnd() {
            OptimizationVisitor.requireMatches("parked vehicle comparison selector", replacements, 1);
            super.visitEnd();
        }
    }

    private static final class Window {
        final int cycle, block;
        final boolean enabled;
        int frames, offered, parked, expected, expectedParked, callbacks, parkedCallbacks;
        long vehicleNanos, setupNanos, updateNanos, postNanos;

        Window(int cycle, int block, boolean enabled) {
            this.cycle = cycle;
            this.block = block;
            this.enabled = enabled;
        }
    }
}
