package cloud.apocalipse.pz.agent.features.optimization.streaming;

import cloud.apocalipse.pz.agent.AgentModule;
import cloud.apocalipse.pz.agent.HookCheck;
import cloud.apocalipse.pz.agent.config.AgentConfiguration;
import cloud.apocalipse.pz.agent.features.optimization.OptimizationVisitor;
import cloud.apocalipse.pz.agent.features.telemetry.OptimizationTelemetry;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.jar.asm.MethodVisitor;
import zombie.network.GameServer;
import zombie.network.ServerMap;
import static net.bytebuddy.jar.asm.Opcodes.*;
import static net.bytebuddy.matcher.ElementMatchers.named;

/** Avoids repeated relevance searches while keeping the public vanilla list authoritative. */
public final class CellRelevanceModule implements AgentModule {
    private static final String MAP = "zombie.network.ServerMap";
    private static final String LIST = "java/util/ArrayList";
    private static final IdentityHashMap<Object, Integer> POSITIONS = new IdentityHashMap<>();
    private static int hits;

    @Override public String name() { return "cell-relevance-index"; }

    @Override public String disabledReason() {
        return AgentConfiguration.cellRelevanceOptimizationEnabled()
            ? null : "Disabled by -D" + AgentConfiguration.CELL_RELEVANCE_OPTIMIZATION;
    }

    @Override public List<HookCheck> hookChecks() {
        String helper = getClass().getName();
        return List.of(
            new HookCheck("renewal", MAP, "loadOrKeepRelevent", helper, "contains", false),
            new HookCheck("append", MAP, "loadOrKeepRelevent", helper, "append", false),
            new HookCheck("eviction-check", MAP, "postupdate", helper, "contains", false),
            new HookCheck("frame-reset", MAP, "preupdate", helper, "clear", false));
    }

    @Override public AgentBuilder configure(AgentBuilder builder) {
        if (disabledReason() != null) return builder;
        return builder.type(named(MAP)).transform((dynamic, type, loader, module, domain) ->
            dynamic.visit(OptimizationVisitor.method("loadOrKeepRelevent", "(II)V",
                    downstream -> new RelevanceVisitor(downstream, 1, 2, 0)))
                .visit(OptimizationVisitor.method("postupdate", "()V",
                    downstream -> new RelevanceVisitor(downstream, 1, 0, 0)))
                .visit(OptimizationVisitor.method("preupdate", "()V",
                    downstream -> new RelevanceVisitor(downstream, 0, 0, 1))));
    }

    private static boolean owner(ArrayList<?> list) {
        return GameServer.server && Thread.currentThread() == GameServer.mainThread
            && list == ServerMap.instance.releventNow && list.getClass() == ArrayList.class;
    }

    public static boolean contains(ArrayList<?> list, Object cell) {
        if (!owner(list)) return list.contains(cell);
        Integer position = POSITIONS.get(cell);
        if (position != null && position < list.size() && list.get(position) == cell) {
            hits++;
            return true;
        }
        // A public list edit can invalidate a cached position or introduce a new member.
        int actual = list.indexOf(cell);
        if (actual < 0) return false;
        POSITIONS.put(cell, actual);
        return true;
    }

    public static <T> boolean append(ArrayList<T> list, T cell) {
        if (!owner(list)) return list.add(cell);
        int position = list.size();
        boolean added = list.add(cell);
        POSITIONS.put(cell, position);
        return added;
    }

    public static void clear(ArrayList<?> list) {
        list.clear();
        // Also drop old hints when a caller has replaced the public relevance list.
        if (GameServer.server && Thread.currentThread() == GameServer.mainThread) {
            POSITIONS.clear();
            OptimizationTelemetry.count(OptimizationTelemetry.Counter.CELL_RELEVANCE_SCAN_SKIPPED, hits);
            hits = 0;
        }
    }

    private static final class RelevanceVisitor extends MethodVisitor {
        private final int expectedContains;
        private final int expectedAdds;
        private final int expectedClears;
        private int contains;
        private int adds;
        private int clears;
        private boolean readsRelevance;

        RelevanceVisitor(MethodVisitor downstream, int expectedContains, int expectedAdds, int expectedClears) {
            super(ASM9, downstream);
            this.expectedContains = expectedContains;
            this.expectedAdds = expectedAdds;
            this.expectedClears = expectedClears;
        }

        @Override public void visitFieldInsn(int opcode, String owner, String field, String descriptor) {
            readsRelevance = opcode == GETFIELD && owner.equals("zombie/network/ServerMap")
                && field.equals("releventNow") && descriptor.equals("Ljava/util/ArrayList;");
            super.visitFieldInsn(opcode, owner, field, descriptor);
        }

        @Override public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean itf) {
            boolean relevanceCall = readsRelevance;
            readsRelevance = false;
            if (relevanceCall && opcode == INVOKEVIRTUAL && owner.equals(LIST)) {
                if (name.equals("contains") && descriptor.equals("(Ljava/lang/Object;)Z")) {
                    contains++;
                    call("contains", "(Ljava/util/ArrayList;Ljava/lang/Object;)Z");
                    return;
                }
                if (name.equals("add") && descriptor.equals("(Ljava/lang/Object;)Z")) {
                    adds++;
                    call("append", "(Ljava/util/ArrayList;Ljava/lang/Object;)Z");
                    return;
                }
                if (name.equals("clear") && descriptor.equals("()V")) {
                    clears++;
                    call("clear", "(Ljava/util/ArrayList;)V");
                    return;
                }
            }
            super.visitMethodInsn(opcode, owner, name, descriptor, itf);
        }

        private void call(String name, String descriptor) {
            OptimizationVisitor.callStatic(mv, CellRelevanceModule.class, name, descriptor);
        }

        @Override public void visitEnd() {
            OptimizationVisitor.requireMatches("ServerMap frame relevance contains", contains, expectedContains);
            OptimizationVisitor.requireMatches("ServerMap cell request adds", adds, expectedAdds);
            OptimizationVisitor.requireMatches("ServerMap preupdate clears", clears, expectedClears);
            super.visitEnd();
        }
    }
}
