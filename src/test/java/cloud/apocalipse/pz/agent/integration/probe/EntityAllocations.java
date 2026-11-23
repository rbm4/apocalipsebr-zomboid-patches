package cloud.apocalipse.pz.agent.integration.probe;

import com.sun.management.ThreadMXBean;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.List;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import zombie.characters.IsoPlayer;
import zombie.characters.animals.IsoAnimal;

import static net.bytebuddy.matcher.ElementMatchers.hasDescriptor;
import static net.bytebuddy.matcher.ElementMatchers.named;

/** Inclusive allocation on the native scheduler owner, excluding other invocations of the same methods. */
public final class EntityAllocations {
    public static final String PROPERTY = MovingObjectLoad.PROPERTY + ".entityAllocations";
    private static final boolean ENABLED = Boolean.getBoolean(PROPERTY);
    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean() instanceof ThreadMXBean bean
        && bean.isThreadAllocatedMemorySupported() && bean.isThreadAllocatedMemoryEnabled() ? bean : null;

    private enum Scope {
        PLAYER_UPDATE, PLAYER_REMOTE, PLAYER_BASE, ANIMAL_BASE, PLAYER_STATS, ANIMAL_STATS,
        ANIMAL_UPDATE, ANIMAL_BEHAVIOR, ANIMAL_DATA, ANIMAL_GROWTH, ANIMAL_LOS
    }

    private static final Scope[] SCOPES = Scope.values();
    private static final long[] BYTES = new long[SCOPES.length];
    private static final long[] CALLS = new long[SCOPES.length];
    private static Thread owner;

    private EntityAllocations() {}

    private record Target(Scope scope, String method, String descriptor) {
        Target(Scope scope, String method) { this(scope, method, "()V"); }
    }

    static AgentBuilder configure(AgentBuilder builder) {
        if (!ENABLED) return builder;
        builder = instrument(builder, "zombie.characters.IsoPlayer",
            new Target(Scope.PLAYER_UPDATE, "update"), new Target(Scope.PLAYER_REMOTE, "updateRemotePlayer", "()Z"));
        builder = instrument(builder, "zombie.characters.IsoGameCharacter",
            new Target(Scope.PLAYER_BASE, "update"), new Target(Scope.PLAYER_STATS, "calculateStats"));
        builder = instrument(builder, "zombie.characters.animals.IsoAnimal",
            new Target(Scope.ANIMAL_UPDATE, "update"), new Target(Scope.ANIMAL_LOS, "updateLOS"));
        builder = instrument(builder, "zombie.characters.animals.behavior.BaseAnimalBehavior",
            new Target(Scope.ANIMAL_BEHAVIOR, "update"));
        return instrument(builder, "zombie.characters.animals.datas.AnimalData",
            new Target(Scope.ANIMAL_DATA, "update"), new Target(Scope.ANIMAL_GROWTH, "checkStages"));
    }

    private static AgentBuilder instrument(AgentBuilder builder, String typeName, Target... targets) {
        return builder.type(named(typeName)).transform((dynamic, type, loader, module, domain) -> {
            for (Target target : targets) {
                dynamic = dynamic.visit(Advice.withCustomMapping().bind(ScopeId.class, target.scope().ordinal())
                    .to(MethodSample.class).on(named(target.method()).and(hasDescriptor(target.descriptor()))));
            }
            return dynamic;
        });
    }

    static void beginFrame() {
        if (!ENABLED) return;
        Arrays.fill(BYTES, 0);
        Arrays.fill(CALLS, 0);
        owner = Thread.currentThread();
    }

    static void endFrame() {
        if (ENABLED) owner = null;
    }

    static void appendColumns(StringBuilder csv) {
        if (!ENABLED) return;
        for (Scope scope : SCOPES) csv.append(',').append(scope).append("_bytes,").append(scope).append("_calls");
    }

    static void appendSample(StringBuilder csv) {
        if (!ENABLED) return;
        for (Scope scope : SCOPES) {
            int index = scope.ordinal();
            csv.append(',').append(THREADS == null ? -1 : BYTES[index]).append(',').append(CALLS[index]);
        }
    }

    public static int scopeFor(int index, Object receiver) {
        if (owner != Thread.currentThread()) return -1;
        Scope scope = SCOPES[index];
        if (scope == Scope.PLAYER_BASE || scope == Scope.PLAYER_STATS) {
            if (receiver instanceof IsoAnimal) {
                return (scope == Scope.PLAYER_BASE ? Scope.ANIMAL_BASE : Scope.ANIMAL_STATS).ordinal();
            }
            if (!(receiver instanceof IsoPlayer)) return -1;
        }
        if ((scope == Scope.PLAYER_UPDATE || scope == Scope.PLAYER_REMOTE) && receiver instanceof IsoAnimal) return -1;
        return index;
    }

    public static long allocated() { return THREADS == null ? -1 : THREADS.getCurrentThreadAllocatedBytes(); }

    public static void finish(int scope, long started) {
        if (scope < 0) return;
        if (started >= 0) BYTES[scope] += allocated() - started;
        CALLS[scope]++;
    }

    @Retention(RetentionPolicy.RUNTIME)
    public @interface ScopeId {}

    public static class MethodSample {
        @Advice.OnMethodEnter
        public static long enter(@ScopeId int index, @Advice.This Object receiver, @Advice.Local("scope") int scope) {
            scope = scopeFor(index, receiver);
            return scope < 0 ? -1 : allocated();
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(@Advice.Local("scope") int scope, @Advice.Enter long started) {
            finish(scope, started);
        }
    }
}

