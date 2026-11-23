package cloud.apocalipse.pz.agent.integration.probe;

import cloud.apocalipse.pz.agent.features.optimization.OptimizationVisitor;
import cloud.apocalipse.pz.agent.features.optimization.entity.EntityDueIndex;
import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.jar.asm.MethodVisitor;
import zombie.entity.EntityBucket;
import zombie.entity.GameEntity;
import zombie.entity.util.Array;
import static net.bytebuddy.jar.asm.Opcodes.*;
import static net.bytebuddy.matcher.ElementMatchers.*;

/** Test-only attribution of native bucket membership visits and mutations during cell eviction. */
public final class BucketUnloadProbe {
    public static final String PROPERTY = "pz.it.bucketUnload";
    private static final boolean ENABLED = Boolean.getBoolean(PROPERTY);
    private static final String BUCKET = "zombie.entity.EntityBucket";
    private static final String ENTITY = "Lzombie/entity/GameEntity;";
    private static final String ENTITY_ARG = "(" + ENTITY + ")V";
    private static final String INDEX = "cloud.apocalipse.pz.agent.features.optimization.entity.BucketRemovalIndex";
    private static final String DUE = EntityDueIndex.class.getName().replace('.', '/');
    private static final String ARRAY = "Lzombie/entity/util/Array;";
    private static final String BUCKET_REF = "Lzombie/entity/EntityBucket;";
    private static final String APPEND = "(" + ARRAY + ENTITY + BUCKET_REF + ")V";
    private static final String REMOVE = "(" + ARRAY + ENTITY + "Z" + BUCKET_REF + ")Z";

    private static final Map<Integer, IdentityHashMap<Object, Bucket>> BUCKETS = new LinkedHashMap<>();
    private static final ArrayList<Bucket> ORDER = new ArrayList<>();
    private static final Map<Integer, Map<String, Metric>> PHASES = new LinkedHashMap<>();
    private static Bucket[] stack = new Bucket[8];
    private static int depth;
    private static int cycle = -1;
    private static IdentityHashMap<Object, Bucket> active;
    private static int unregisterDepth;

    private BucketUnloadProbe() {}

    static AgentBuilder configure(AgentBuilder builder) {
        if (!ENABLED) return builder;
        return builder.type(named(BUCKET)).transform((dynamic, type, loader, module, domain) -> dynamic
                .visit(OptimizationVisitor.method("updateMembership", ENTITY_ARG, MutationVisitor::new))
                .visit(Advice.to(BucketScope.class).on(named("updateMembership").and(hasDescriptor(ENTITY_ARG)))))
            .type(named(INDEX)).transform((dynamic, type, loader, module, domain) -> dynamic
                .visit(Advice.to(Fallback.class).on(named("scan")))
                .visit(Advice.withCustomMapping().bind(PhaseName.class, "positionBuild").to(Phase.class)
                    .on(named("build"))))
            .type(named("zombie.entity.EntityBucketManager")).transform((dynamic, type, loader, module, domain) ->
                dynamic.visit(Advice.withCustomMapping().bind(PhaseName.class, "fanout").to(Phase.class)
                    .on(named("updateBucketMembership").and(hasDescriptor(ENTITY_ARG)))))
            .type(named("zombie.entity.GameEntityManager")).transform((dynamic, type, loader, module, domain) ->
                dynamic.visit(Advice.to(Unregister.class)
                    .on(named("UnregisterEntity").and(hasDescriptor("(" + ENTITY + "Z)V")))))
            .type(named("zombie.iso.IsoObject")).transform((dynamic, type, loader, module, domain) ->
                dynamic.visit(Advice.to(EntityId.class).on(named("getEntityNetID").and(hasDescriptor("()J")))))
            .type(named("zombie.entity.MetaEntity")).transform((dynamic, type, loader, module, domain) ->
                dynamic.visit(Advice.withCustomMapping().bind(PhaseName.class, "metaAllocate").to(Phase.class)
                    .on(named("alloc").and(hasDescriptor("(" + ENTITY + ")Lzombie/entity/MetaEntity;")))));
    }

    private static final class MutationVisitor extends MethodVisitor {
        private int sites;
        MutationVisitor(MethodVisitor visitor) { super(ASM9, visitor); }

        @Override public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean itf) {
            if (opcode == INVOKESTATIC && owner.equals(DUE)
                    && (name.equals("append") && descriptor.equals(APPEND)
                        || name.equals("remove") && descriptor.equals(REMOVE))) {
                sites++;
                OptimizationVisitor.callStatic(mv, BucketUnloadProbe.class, name, descriptor);
            } else {
                super.visitMethodInsn(opcode, owner, name, descriptor, itf);
            }
        }

        @Override public void visitEnd() {
            OptimizationVisitor.requireMatches("EntityBucket installed mutation hooks", sites, 2);
            super.visitEnd();
        }
    }

    public static class BucketScope {
        @Advice.OnMethodEnter public static boolean enter(@Advice.This Object bucket) { return beginBucket(bucket); }
        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(@Advice.Enter boolean entered) { if (entered) endBucket(); }
    }

    public static boolean beginBucket(Object value) {
        if (!UnloadSamplingProbe.inCell()) return false;
        int next = StreamingProbe.activeCycle();
        if (cycle != next) {
            cycle = next;
            active = BUCKETS.computeIfAbsent(cycle, ignored -> new IdentityHashMap<>());
        }
        var bucket = active.get(value);
        if (bucket == null) {
            var nativeBucket = (EntityBucket) value;
            bucket = new Bucket(cycle, nativeBucket.getIndex(), value.getClass().getSimpleName());
            active.put(value, bucket);
            ORDER.add(bucket);
        }
        if (depth == stack.length) stack = Arrays.copyOf(stack, depth * 2);
        stack[depth++] = bucket;
        bucket.visits++;
        return true;
    }

    public static void endBucket() { stack[--depth] = null; }

    private static Bucket current() {
        return depth == 0 || !StreamingProbe.onStreamingOwner() ? null : stack[depth - 1];
    }

    public static void append(Array<GameEntity> entries, GameEntity entity, EntityBucket bucket) {
        var row = current();
        if (row == null) {
            EntityDueIndex.append(entries, entity, bucket);
            return;
        }
        long start = System.nanoTime();
        EntityDueIndex.append(entries, entity, bucket);
        row.append.record(System.nanoTime() - start);
    }

    public static boolean remove(Array<GameEntity> entries, GameEntity entity, boolean identity, EntityBucket bucket) {
        var row = current();
        if (row == null) return EntityDueIndex.remove(entries, entity, identity, bucket);
        row.maxSize = Math.max(row.maxSize, entries.size);
        long start = System.nanoTime();
        boolean removed = EntityDueIndex.remove(entries, entity, identity, bucket);
        row.remove.record(System.nanoTime() - start);
        if (removed) row.removed++;
        return removed;
    }

    public static class Fallback {
        @Advice.OnMethodEnter public static void enter(@Advice.Argument(3) Enum<?> reason) { fallback(reason); }
    }

    public static void fallback(Enum<?> reason) {
        var row = current();
        if (row != null) row.fallbacks.merge(reason.name(), 1L, Long::sum);
    }

    public static void nativeSearch(long candidates, long elapsed) {
        var row = current();
        if (row == null) return;
        row.comparisons += candidates;
        row.search.record(elapsed);
    }

    @Retention(RetentionPolicy.RUNTIME)
    public @interface PhaseName {}

    public static class Phase {
        @Advice.OnMethodEnter public static long enter() { return start(); }
        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(@PhaseName String phase, @Advice.Enter long started) { finish(phase, started); }
    }

    public static class Unregister {
        @Advice.OnMethodEnter public static long enter() { return beginUnregister(); }
        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(@Advice.Enter long started) { endUnregister(started); }
    }

    public static class EntityId {
        @Advice.OnMethodEnter public static long enter() { return startEntityId(); }
        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(@Advice.Enter long started) { finish("objectEntityId", started); }
    }

    public static long start() { return UnloadSamplingProbe.inCell() ? System.nanoTime() : 0; }

    public static long beginUnregister() {
        long started = start();
        if (started != 0) unregisterDepth++;
        return started;
    }

    public static void endUnregister(long started) {
        if (started == 0) return;
        unregisterDepth--;
        finish("unregister", started);
    }

    public static long startEntityId() { return unregisterDepth == 0 ? 0 : start(); }

    public static void finish(String phase, long started) {
        if (started == 0) return;
        long elapsed = System.nanoTime() - started;
        PHASES.computeIfAbsent(StreamingProbe.activeCycle(), ignored -> new LinkedHashMap<>())
            .computeIfAbsent(phase, ignored -> new Metric()).record(elapsed);
    }

    static void write(Path directory) throws IOException {
        if (!ENABLED) return;
        var csv = new StringBuilder("cycle,bucket,kind,visits,appends,appendNanos,removalCalls,removed,removeNanos,maxSize,nativeCalls,comparisons,nativeNanos\n");
        var reasons = new StringBuilder("cycle,bucket,kind,reason,calls\n");
        var phases = new StringBuilder("cycle,phase,calls,wallNanos,maxNanos\n");
        for (Bucket row : ORDER) {
            String prefix = row.cycle + "," + row.id + "," + row.kind + ",";
            csv.append(prefix).append(row.visits).append(',').append(row.append.calls).append(',')
                .append(row.append.wall).append(',').append(row.remove.calls).append(',').append(row.removed).append(',')
                .append(row.remove.wall).append(',').append(row.maxSize).append(',').append(row.search.calls).append(',')
                .append(row.comparisons).append(',').append(row.search.wall).append('\n');
            row.fallbacks.forEach((reason, calls) -> reasons.append(prefix).append(reason).append(',').append(calls).append('\n'));
        }
        PHASES.forEach((cycle, summary) -> summary.forEach((phase, metric) -> phases.append(cycle).append(',')
            .append(phase).append(',').append(metric.calls).append(',').append(metric.wall).append(',')
            .append(metric.max).append('\n')));
        Files.writeString(directory.resolve("unload-buckets.csv"), csv);
        Files.writeString(directory.resolve("unload-bucket-fallbacks.csv"), reasons);
        Files.writeString(directory.resolve("unload-entity-phases.csv"), phases);
    }

    private static final class Metric {
        long calls, wall, max;

        void record(long elapsed) {
            calls++;
            wall += elapsed;
            max = Math.max(max, elapsed);
        }
    }

    private static final class Bucket {
        final int cycle;
        final int id;
        final String kind;
        final Metric append = new Metric();
        final Metric remove = new Metric();
        final Metric search = new Metric();
        final Map<String, Long> fallbacks = new LinkedHashMap<>();
        long visits, removed, comparisons;
        int maxSize;

        Bucket(int cycle, int id, String kind) {
            this.cycle = cycle;
            this.id = id;
            this.kind = kind;
        }
    }
}
