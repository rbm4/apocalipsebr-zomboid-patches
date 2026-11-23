package cloud.apocalipse.pz.agent.experimental.worldsimulation.features;

import cloud.apocalipse.pz.agent.experimental.worldsimulation.WorldSimulation;
import cloud.apocalipse.pz.agent.features.optimization.OptimizationVisitor;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.asm.ModifierAdjustment;
import net.bytebuddy.description.modifier.FieldManifestation;
import net.bytebuddy.jar.asm.MethodVisitor;
import zombie.GameTime;
import zombie.MovingObjectUpdateScheduler;
import zombie.vehicles.BaseVehicle;
import zombie.vehicles.VehiclePart;
import zombie.vehicles.VehicleParts;

import static net.bytebuddy.jar.asm.Opcodes.ASM9;
import static net.bytebuddy.matcher.ElementMatchers.hasDescriptor;
import static net.bytebuddy.matcher.ElementMatchers.named;

/** Moves the live EngineDoor part clock and resumes the named update callback on the server. */
public final class EngineDoorSimulation {
    private static final String VEHICLE = "zombie.vehicles.BaseVehicle";
    private static final String PART = "zombie.vehicles.VehiclePart";
    private static final String CALLBACK = "Vehicles.Update.EngineDoor";
    private static final LinkedHashMap<VehiclePart, Pending> pending = new LinkedHashMap<>();
    private static final HashSet<VehiclePart> delivering = new HashSet<>();
    private static ThreadPoolExecutor worker;
    private static CountDownLatch hold;
    private static VehiclePart heldPart;
    private static long heldFrame;
    private static boolean observedBusyOnLaterFrame;
    private static int heldResults;
    private static EngineDoorCallbackProbe callbackProbe;

    private EngineDoorSimulation() {}

    public static AgentBuilder configure(AgentBuilder builder) {
        return builder.type(named(PART)).transform((dynamic, type, loader, module, domain) ->
                // The original field is also accessed directly by save/load.
                dynamic.visit(new ModifierAdjustment().withFieldModifiers(named("lastUpdated"), FieldManifestation.VOLATILE)))
            .type(named("zombie.vehicles.VehicleParts")).transform((dynamic, type, loader, module, domain) ->
                dynamic.visit(Advice.to(PartUpdate.class).on(named("updatePart").and(hasDescriptor("(Lzombie/vehicles/VehiclePart;)Z")))))
            .type(named(VEHICLE)).transform((dynamic, type, loader, module, domain) ->
                dynamic.visit(OptimizationVisitor.method("update", "()V", PartPassVisitor::new)))
            .type(named("zombie.MovingObjectUpdateScheduler")).transform((dynamic, type, loader, module, domain) ->
                dynamic.visit(Advice.to(SchedulerTiming.class).on(named("update").and(hasDescriptor("()V")))));
    }

    public static boolean runPartPass(BaseVehicle vehicle) {
        // Symmetric fixture pressure. Keep the original cadence and force a parts pass only when offered a matching door.
        return vehicle.needPartsUpdate() || WorldSimulation.isActive() && eligible(vehicle.getPartById("EngineDoor"));
    }

    private static boolean eligible(VehiclePart part) {
        return part != null && "EngineDoor".equals(part.getId()) && part.getOwner() instanceof BaseVehicle
            && part.getDeviceData() == null && part.getLight() == null && CALLBACK.equals(part.getLuaFunction("update"));
    }

    public static Boolean update(VehiclePart part) {
        if (WorldSimulation.isOwner() && delivering.contains(part)) return false;
        if (!WorldSimulation.isActive() || !eligible(part) || !isAttached(part)) return null;
        float now = (float) GameTime.getInstance().getWorldAgeHours();
        if (!WorldSimulation.asynchronous()) {
            WorldSimulation.record(true, 1, false);
            return deliver(part, advance(part, part.getLastUpdated(), now));
        }

        Pending previous = pending.get(part);
        boolean consumed = false;
        boolean updated = false;
        if (previous != null) {
            if (!previous.future().ready) {
                if (part == heldPart && MovingObjectUpdateScheduler.instance.getFrameCounter() > heldFrame) {
                    observedBusyOnLaterFrame = true;
                    hold.countDown();
                }
                WorldSimulation.record(false, 0, true);
                return false;
            }
            pending.remove(part);
            updated = deliver(part, result(previous));
            consumed = true;
            if (part == heldPart) heldResults++;
        }

        if (WorldSimulation.correctnessScenario() && heldPart == part) {
            return updated;
        }

        CountDownLatch gate = null;
        if (WorldSimulation.correctnessScenario() && heldPart == null) {
            heldPart = part;
            heldFrame = MovingObjectUpdateScheduler.instance.getFrameCounter();
            part.setLastUpdated(now - 1.0F);
            callbackProbe = new EngineDoorCallbackProbe(CALLBACK, part, now);
            gate = new CountDownLatch(1);
            hold = gate;
        }
        // Lua delivery can change the part or remove the vehicle.
        if (!eligible(part) || !isAttached(part)) {
            WorldSimulation.record(false, consumed ? 1 : 0, false);
            return updated;
        }
        CountDownLatch delay = gate;
        float previousTimestamp = part.getLastUpdated();
        try {
            Completion job = new Completion(() -> {
                if (delay != null && !delay.await(4, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Main thread never observed the held vehicle part");
                }
                return advance(part, previousTimestamp, now);
            });
            executor().execute(job);
            pending.put(part, new Pending(part, job));
        } catch (RejectedExecutionException full) {
            WorldSimulation.record(true, consumed ? 2 : 1, false);
            return deliver(part, advance(part, previousTimestamp, now)) || updated;
        }
        WorldSimulation.record(true, consumed ? 1 : 0, false);
        return updated;
    }

    private static boolean isAttached(VehiclePart part) {
        BaseVehicle vehicle = (BaseVehicle) part.getOwner();
        return !vehicle.isRemovedFromWorld() && vehicle.getPartById(part.getId()) == part;
    }

    private static boolean deliver(VehiclePart part, Result update) {
        if (!update.due() || !eligible(part) || !isAttached(part)) return false;
        BaseVehicle vehicle = (BaseVehicle) part.getOwner();
        delivering.add(part);
        try {
            Fields.CALL_LUA.invokeExact(vehicle.getParts(), CALLBACK, (Object) vehicle, (Object) part,
                (Object) update.elapsedMinutes());
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException("Original vehicle Lua call failed", failure);
        } finally {
            delivering.remove(part);
        }
        return true;
    }

    private static Result advance(VehiclePart part, float previous, float now) {
        float adjusted = GameTime.checkHours(previous, now);
        boolean due = (int) ((now - adjusted) * 60.0F) > 0;
        float next = due ? now : adjusted;
        // The worker owns only this scalar. A concurrent native setter/load wins over an obsolete calculation.
        boolean changed = Fields.LAST_UPDATED.compareAndSet(part, previous, next);
        return new Result(changed && due, next, (double) ((now - adjusted) * 60.0F), Thread.currentThread());
    }

    private static ThreadPoolExecutor executor() {
        if (worker == null) {
            worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(512),
                task -> Thread.ofPlatform().daemon().name("Experimental-WorldSimulation").unstarted(task));
        }
        return worker;
    }

    private static Result result(Pending job) {
        try {
            Result result = job.future().get(5, TimeUnit.SECONDS);
            if (job.part() == heldPart) {
                if (result.thread() == Thread.currentThread() || !result.due()
                        || Float.compare(job.part().getLastUpdated(), result.timestamp()) != 0) {
                    throw new IllegalStateException("Held update did not publish the actual native part clock");
                }
            }
            return result;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while checking the experimental part result", failure);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failure) {
            throw new IllegalStateException("Experimental part worker failed", failure);
        }
    }

    public static int drain() {
        // Between benchmark windows and before unload only. Account for a due result even if the part
        // was never scheduled again to return it to BaseVehicle.updateParts().
        if (hold != null) hold.countDown();
        var jobs = java.util.List.copyOf(pending.values());
        pending.clear();
        for (Pending job : jobs) {
            if (deliver(job.part(), result(job))) Fields.BRAKE_SPEED.set((BaseVehicle) job.part().getOwner(), 0.0F);
        }
        if (callbackProbe != null) callbackProbe.close();
        return jobs.size();
    }

    public static String correctnessEvidence() {
        if (!observedBusyOnLaterFrame || heldResults != 1 || callbackProbe == null || !callbackProbe.deliveredOnce()) {
            throw new IllegalStateException("Expected one server-thread Lua delivery for the held part and one result");
        }
        return "busy across server frame; native timestamp published; Lua override called once on server thread\n";
    }

    public static void stop() {
        if (callbackProbe != null) callbackProbe.close();
        if (hold != null) hold.countDown();
        pending.values().forEach(job -> job.future().cancel(true));
        pending.clear();
        if (worker != null) {
            worker.shutdownNow();
            try {
                if (!worker.awaitTermination(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Experimental worker did not terminate");
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted stopping experimental worker", failure);
            }
        }
    }

    private record Pending(VehiclePart part, Completion future) {}
    private record Result(boolean due, float timestamp, Double elapsedMinutes, Thread thread) {}

    private static final class Completion extends FutureTask<Result> {
        volatile boolean ready;

        Completion(Callable<Result> update) {
            super(update);
        }

        @Override protected void done() {
            // Publish only after FutureTask has entered a terminal state, so a scheduled
            // server pass never waits in get() while the worker is completing the task.
            ready = true;
        }
    }

    private static final class Fields {
        static final VarHandle LAST_UPDATED = field(VehiclePart.class, "lastUpdated", true);
        static final VarHandle BRAKE_SPEED = field(BaseVehicle.class, "brakeBetweenUpdatesSpeed", false);
        static final MethodHandle CALL_LUA = luaCall();

        private static MethodHandle luaCall() {
            try {
                return MethodHandles.privateLookupIn(VehicleParts.class, MethodHandles.lookup()).findVirtual(
                    VehicleParts.class, "callLuaVoid", MethodType.methodType(void.class,
                        String.class, Object.class, Object.class, Object.class));
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Missing original vehicle Lua call", failure);
            }
        }

        private static VarHandle field(Class<?> owner, String name, boolean requireVolatile) {
            try {
                if (requireVolatile && !java.lang.reflect.Modifier.isVolatile(owner.getDeclaredField(name).getModifiers())) {
                    throw new IllegalStateException("Expected a volatile native part clock");
                }
                return MethodHandles.privateLookupIn(owner, MethodHandles.lookup()).findVarHandle(owner, name, float.class);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Missing original field: " + owner.getName() + "." + name, failure);
            }
        }
    }

    private static final class PartPassVisitor extends MethodVisitor {
        private int matches;

        PartPassVisitor(MethodVisitor delegate) {
            super(ASM9, delegate);
        }

        @Override public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean itf) {
            if (owner.equals("zombie/vehicles/BaseVehicle") && name.equals("needPartsUpdate") && descriptor.equals("()Z")) {
                matches++;
                OptimizationVisitor.callStatic(mv, EngineDoorSimulation.class, "runPartPass", "(Lzombie/vehicles/BaseVehicle;)Z");
            } else {
                super.visitMethodInsn(opcode, owner, name, descriptor, itf);
            }
        }

        @Override public void visitEnd() {
            OptimizationVisitor.requireMatches("BaseVehicle.update parts branch", matches, 1);
            super.visitEnd();
        }
    }

    public static final class PartUpdate {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        public static Boolean enter(@Advice.Argument(0) VehiclePart part) {
            return update(part);
        }

        @Advice.OnMethodExit
        public static void exit(@Advice.Enter Boolean result, @Advice.Return(readOnly = false) boolean returned) {
            if (result != null) returned = result;
        }
    }

    public static final class SchedulerTiming {
        @Advice.OnMethodEnter public static long enter() { return WorldSimulation.beginScheduler(); }
        @Advice.OnMethodExit public static void exit(@Advice.Enter long started) { WorldSimulation.endScheduler(started); }
    }
}
