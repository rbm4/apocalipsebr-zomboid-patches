package cloud.apocalipse.pz.agent.features.telemetry;

import cloud.apocalipse.pz.agent.AgentModule;
import cloud.apocalipse.pz.agent.HookCheck;
import cloud.apocalipse.pz.agent.config.AgentConfiguration;
import cloud.apocalipse.pz.agent.features.optimization.OptimizationVisitor;
import cloud.apocalipse.pz.agent.features.telemetry.OptimizationTelemetry.Phase;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.List;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.jar.asm.MethodVisitor;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.characters.animals.IsoAnimal;
import zombie.iso.IsoMovingObject;
import zombie.vehicles.BaseVehicle;
import static net.bytebuddy.jar.asm.Opcodes.*;
import static net.bytebuddy.matcher.ElementMatchers.hasDescriptor;
import static net.bytebuddy.matcher.ElementMatchers.named;

/** Times the native scheduler's virtual callbacks on the tick-diagnostics owner. */
public final class MovingObjectTelemetryModule implements AgentModule {
    private static final String BUCKET = "zombie.MovingObjectUpdateSchedulerUpdateBucket";
    private static final String SCHEDULER = "zombie.MovingObjectUpdateScheduler";
    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();
    private static final List<String> UPDATE = List.of("preupdate", "frameStep", "update");
    private static final List<String> POSTUPDATE = List.of("postupdate");

    private static final Phase[] PREUPDATE_PHASES = {Phase.MOVING_PREUPDATE_PLAYER,
        Phase.MOVING_PREUPDATE_VEHICLE,
        Phase.MOVING_PREUPDATE_ANIMAL,
        Phase.MOVING_PREUPDATE_ZOMBIE,
        Phase.MOVING_PREUPDATE_OTHER};
    private static final Phase[] FRAME_STEP_PHASES = {Phase.MOVING_FRAME_STEP_PLAYER,
        Phase.MOVING_FRAME_STEP_VEHICLE,
        Phase.MOVING_FRAME_STEP_ANIMAL,
        Phase.MOVING_FRAME_STEP_ZOMBIE,
        Phase.MOVING_FRAME_STEP_OTHER};
    private static final Phase[] UPDATE_PHASES = {Phase.MOVING_UPDATE_PLAYER,
        Phase.MOVING_UPDATE_VEHICLE,
        Phase.MOVING_UPDATE_ANIMAL,
        Phase.MOVING_UPDATE_ZOMBIE,
        Phase.MOVING_UPDATE_OTHER};
    private static final Phase[] POSTUPDATE_PHASES = {Phase.MOVING_POSTUPDATE_PLAYER,
        Phase.MOVING_POSTUPDATE_VEHICLE,
        Phase.MOVING_POSTUPDATE_ANIMAL,
        Phase.MOVING_POSTUPDATE_ZOMBIE,
        Phase.MOVING_POSTUPDATE_OTHER};

    @Override public String name() { return "telemetry-moving-object-callbacks"; }

    @Override public String disabledReason() {
        return AgentConfiguration.movingObjectTelemetryEnabled()
            ? null : "Disabled by -D" + AgentConfiguration.MOVING_OBJECT_TELEMETRY_PROPERTY;
    }

    @Override public List<HookCheck> hookChecks() {
        String helper = getClass().getName();
        return List.of(
            new HookCheck("preupdate", BUCKET, "update", helper, "preupdate", false),
            new HookCheck("frameStep", BUCKET, "update", helper, "frameStep", false),
            new HookCheck("update", BUCKET, "update", helper, "update", false),
            new HookCheck("postupdate", BUCKET, "postupdate", helper, "postupdate", false),
            new HookCheck("update-cpu", SCHEDULER, "update", helper, "finishCpu", false),
            new HookCheck("postupdate-cpu", SCHEDULER, "postupdate", helper, "finishCpu", false));
    }

    @Override public AgentBuilder configure(AgentBuilder builder) {
        if (disabledReason() != null) return builder;
        return builder.type(named(BUCKET)).transform((dynamic, type, loader, module, domain) -> dynamic
            .visit(OptimizationVisitor.method("update", "(I)V", downstream -> new CallbackVisitor(downstream, UPDATE)))
            .visit(OptimizationVisitor.method("postupdate", "(I)V",
                downstream -> new CallbackVisitor(downstream, POSTUPDATE))))
            .type(named(SCHEDULER)).transform((dynamic, type, loader, module, domain) -> dynamic
                .visit(Advice.to(UpdateCpu.class).on(named("update").and(hasDescriptor("()V"))))
                .visit(Advice.to(PostupdateCpu.class).on(named("postupdate").and(hasDescriptor("()V")))));
    }

    public static class UpdateCpu {
        @Advice.OnMethodEnter public static long enter() { return startCpu(); }
        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(@Advice.Enter long started) { finishCpu(started, false); }
    }

    public static class PostupdateCpu {
        @Advice.OnMethodEnter public static long enter() { return startCpu(); }
        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(@Advice.Enter long started) { finishCpu(started, true); }
    }

    public static long startCpu() {
        return TickDiagnostics.activeOnCurrentThread() && THREADS.isCurrentThreadCpuTimeSupported()
            ? THREADS.getCurrentThreadCpuTime() : -1;
    }

    public static void finishCpu(long started, boolean postupdate) {
        if (started < 0) return;
        long ended = THREADS.getCurrentThreadCpuTime();
        if (ended >= started) {
            Phase phase = postupdate ? Phase.MOVING_POSTUPDATE_CPU : Phase.MOVING_UPDATE_CPU;
            phase.recordNanos(ended - started);
        }
    }

    public static void preupdate(IsoMovingObject object) {
        long started = start();
        try {
            object.preupdate();
        } finally {
            finish(object, PREUPDATE_PHASES, started);
        }
    }

    public static void frameStep(IsoMovingObject object) {
        long started = start();
        try {
            object.frameStep();
        } finally {
            finish(object, FRAME_STEP_PHASES, started);
        }
    }

    public static void update(IsoMovingObject object) {
        long started = start();
        try {
            object.update();
        } finally {
            finish(object, UPDATE_PHASES, started);
        }
    }

    public static void postupdate(IsoMovingObject object) {
        long started = start();
        try {
            object.postupdate();
        } finally {
            finish(object, POSTUPDATE_PHASES, started);
        }
    }

    private static long start() {
        return TickDiagnostics.activeOnCurrentThread() ? System.nanoTime() : 0;
    }

    private static void finish(IsoMovingObject object, Phase[] phases, long started) {
        if (started == 0 || object == null) return;
        int kind;
        if (object instanceof IsoAnimal) kind = 2; // IsoAnimal is an IsoPlayer subtype.
        else if (object instanceof IsoPlayer) kind = 0;
        else if (object instanceof BaseVehicle) kind = 1;
        else if (object instanceof IsoZombie) kind = 3;
        else kind = 4;
        phases[kind].recordSince(started);
    }

    private static final class CallbackVisitor extends MethodVisitor {
        private final List<String> expected;
        private int matches;

        CallbackVisitor(MethodVisitor downstream, List<String> expected) {
            super(ASM9, downstream);
            this.expected = expected;
        }

        @Override public void visitMethodInsn(int opcode, String owner, String name, String descriptor,
                                              boolean itf) {
            if (opcode == INVOKEVIRTUAL && owner.equals("zombie/iso/IsoMovingObject")
                    && descriptor.equals("()V") && expected.contains(name)) {
                if (matches >= expected.size() || !expected.get(matches).equals(name)) {
                    throw new IllegalStateException("Unexpected moving-object callback order");
                }
                matches++;
                OptimizationVisitor.callStatic(mv, MovingObjectTelemetryModule.class, name,
                    "(Lzombie/iso/IsoMovingObject;)V");
                return;
            }
            super.visitMethodInsn(opcode, owner, name, descriptor, itf);
        }

        @Override public void visitEnd() {
            OptimizationVisitor.requireMatches("moving-object callback", matches, expected.size());
            super.visitEnd();
        }
    }
}
