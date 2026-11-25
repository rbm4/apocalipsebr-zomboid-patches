package cloud.apocalipse.pz.agent.features.optimization.streaming;

import cloud.apocalipse.pz.agent.AgentModule;
import cloud.apocalipse.pz.agent.HookCheck;
import cloud.apocalipse.pz.agent.config.AgentConfiguration;
import cloud.apocalipse.pz.agent.features.optimization.OptimizationVisitor;
import gnu.trove.set.hash.TLongHashSet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.field.FieldList;
import net.bytebuddy.description.method.MethodList;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.FieldVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.pool.TypePool;
import zombie.core.math.PZMath;
import zombie.iso.IsoChunk;
import zombie.iso.areas.isoregion.regions.IsoWorldRegion;

import static net.bytebuddy.jar.asm.Opcodes.*;
import static net.bytebuddy.matcher.ElementMatchers.named;

/** Uses private, reconciliation-owned sets on the original local-world update path. */
public final class BuildingReconciliationModule implements AgentModule {
    private static final String TARGET = "zombie.iso.areas.isoregion.metagrid.WorldRegionToMetaGrid";
    private static final String OWNER = TARGET.replace('.', '/');
    private static final String ARRAY_LIST = "java/util/ArrayList";
    private static final String LIST = "java/util/List";
    private static final String REGION_SET = "java/util/LinkedHashSet";
    private static final String DIRTY_SET = "gnu/trove/set/hash/TLongHashSet";
    private static final String REGIONS = "allWorldRegions";
    private static final String DIRTY = "dirtyChunks";
    private static final String WORLD_REGION = "Lzombie/iso/areas/isoregion/regions/IsoWorldRegion;";
    private static final String CHUNK = "Lzombie/iso/IsoChunk;";
    private static final String PROCESS = "clientProcessBuildings";
    private static final String CONNECTED = "getConnectedRegions";
    private static final String MARK = "markChunkDirty";
    private static final String QUERY = "chunkIsDirty";

    @Override public String name() { return "client-building-reconciliation"; }

    @Override
    public String disabledReason() {
        return AgentConfiguration.clientBuildingReconciliationOptimizationEnabled() ? null
            : "Enable with -D" + AgentConfiguration.CLIENT_BUILDING_RECONCILIATION_OPTIMIZATION + "=true";
    }

    @Override
    public List<HookCheck> hookChecks() {
        String helper = getClass().getName();
        return List.of(
            new HookCheck("building-region-set", TARGET, "<init>", helper, "newRegionSet", false),
            new HookCheck("building-dirty-set", TARGET, "<init>", helper, "newDirtySet", false),
            new HookCheck("building-dirty-reset", TARGET, PROCESS, helper, "clearDirtyChunks", false),
            new HookCheck("building-connected-regions", TARGET, CONNECTED, helper, "collectConnectedRegions", false),
            new HookCheck("building-dirty-mark", TARGET, MARK, helper, "markDirtyChunk", false),
            new HookCheck("building-dirty-query", TARGET, QUERY, helper, "isDirtyChunk", false));
    }

    @Override
    public AgentBuilder configure(AgentBuilder builder) {
        if (disabledReason() != null) return builder;
        return builder.type(named(TARGET)).and((type, loader, module, redefining, domain) -> redefining == null)
            .transform((dynamic, type, loader, module, domain) -> dynamic.visit(new ReconciliationVisitor()));
    }

    public static LinkedHashSet<IsoWorldRegion> newRegionSet() { return new LinkedHashSet<>(); }
    public static TLongHashSet newDirtySet() { return new TLongHashSet(); }
    public static void clearDirtyChunks(TLongHashSet dirty) { dirty.clear(); }

    public static void collectConnectedRegions(IsoWorldRegion root, ArrayList<IsoWorldRegion> result) {
        var seen = new HashSet<>(result);
        if (!seen.add(root)) return;
        result.add(root);

        // Resume each parent's iterator at the same position as the recursive traversal.
        var stack = new ArrayDeque<Iterator<IsoWorldRegion>>();
        stack.push(root.getNeighbors().iterator());
        while (!stack.isEmpty()) {
            var neighbors = stack.peek();
            if (!neighbors.hasNext()) {
                stack.pop();
                continue;
            }
            IsoWorldRegion neighbor = neighbors.next();
            if (neighbor.isEnclosed() && neighbor.getRoofedPercentage() >= 0.5F && seen.add(neighbor)) {
                result.add(neighbor);
                stack.push(neighbor.getNeighbors().iterator());
            }
        }
    }

    public static void markDirtyChunk(TLongHashSet dirty, int x, int y) {
        dirty.add(chunkKey(x, y));
    }

    public static boolean isDirtyChunk(TLongHashSet dirty, IsoChunk chunk) {
        // Match the original conversion, including its integer multiplication and float rounding.
        int x = PZMath.fastfloor(chunk.wx * 8 / 8.0F);
        int y = PZMath.fastfloor(chunk.wy * 8 / 8.0F);
        return dirty.contains(chunkKey(x, y));
    }

    private static long chunkKey(int x, int y) {
        // Trove folds the two halves with XOR. Mix the packed key first so grid
        // diagonals don't all start in the same bucket. The mix is reversible.
        long key = ((long) x << 32) | (y & 0xffff_ffffL);
        key = (key ^ (key >>> 33)) * 0xff51afd7ed558ccdL;
        key = (key ^ (key >>> 33)) * 0xc4ceb9fe1a85ec53L;
        return key ^ (key >>> 33);
    }

    private static String descriptor(String owner) { return "L" + owner + ";"; }

    private static void helper(MethodVisitor method, String name, String descriptor) {
        OptimizationVisitor.callStatic(method, BuildingReconciliationModule.class, name, descriptor);
    }

    private static final class ReconciliationVisitor extends AsmVisitorWrapper.AbstractBase {
        @Override
        public ClassVisitor wrap(TypeDescription type, ClassVisitor downstream, Implementation.Context context,
                TypePool pool, FieldList<FieldDescription.InDefinedShape> fields, MethodList<?> methods,
                int writerFlags, int readerFlags) {
            return new ClassVisitor(ASM9, downstream) {
                private int regionFields;
                private int dirtyFields;
                private int replacements;
                private int regionReads;
                private int dirtyReads;
                private int regionWrites;
                private int dirtyWrites;

                @Override
                public FieldVisitor visitField(int access, String name, String desc, String signature, Object value) {
                    if ((access & (ACC_PRIVATE | ACC_FINAL | ACC_STATIC)) == (ACC_PRIVATE | ACC_FINAL)) {
                        if (name.equals(REGIONS) && desc.equals(descriptor(ARRAY_LIST))) {
                            regionFields++;
                            return super.visitField(access, name, descriptor(REGION_SET),
                                signature == null ? null : signature.replace(ARRAY_LIST, REGION_SET), value);
                        }
                        if (name.equals(DIRTY) && desc.equals(descriptor(LIST))) {
                            dirtyFields++;
                            return super.visitField(access, name, descriptor(DIRTY_SET), null, value);
                        }
                    }
                    return super.visitField(access, name, desc, signature, value);
                }

                @Override
                public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                    MethodVisitor method = super.visitMethod(access, name, desc, signature, exceptions);
                    if ((access & (ACC_PRIVATE | ACC_STATIC)) == ACC_PRIVATE && replaceBody(method, name + desc)) {
                        replacements++;
                        return null;
                    }
                    return new MethodVisitor(ASM9, method) {
                        private String pendingCollection;

                        @Override
                        public void visitFieldInsn(int opcode, String owner, String field, String fieldDesc) {
                            if (owner.equals(OWNER) && field.equals(REGIONS) && fieldDesc.equals(descriptor(ARRAY_LIST))) {
                                if (opcode == GETFIELD) {
                                    regionReads++;
                                    pendingCollection = ARRAY_LIST;
                                } else if (opcode == PUTFIELD && name.equals("<init>")) {
                                    regionWrites++;
                                    mv.visitInsn(POP);
                                    helper(mv, "newRegionSet", "()" + descriptor(REGION_SET));
                                } else {
                                    throw new IllegalStateException("Unexpected allWorldRegions access in " + name);
                                }
                                super.visitFieldInsn(opcode, owner, field, descriptor(REGION_SET));
                                return;
                            }
                            if (owner.equals(OWNER) && field.equals(DIRTY) && fieldDesc.equals(descriptor(LIST))) {
                                if (opcode == GETFIELD && name.equals(PROCESS)) {
                                    dirtyReads++;
                                    pendingCollection = LIST;
                                } else if (opcode == PUTFIELD && name.equals("<init>")) {
                                    dirtyWrites++;
                                    mv.visitInsn(POP);
                                    helper(mv, "newDirtySet", "()" + descriptor(DIRTY_SET));
                                } else {
                                    throw new IllegalStateException("Unexpected dirtyChunks access in " + name);
                                }
                                super.visitFieldInsn(opcode, owner, field, descriptor(DIRTY_SET));
                                return;
                            }
                            if (pendingCollection != null) {
                                throw new IllegalStateException("Unexpected collection receiver in " + name);
                            }
                            super.visitFieldInsn(opcode, owner, field, fieldDesc);
                        }

                        @Override
                        public void visitMethodInsn(int opcode, String owner, String operation, String methodDesc, boolean itf) {
                            if (pendingCollection == null) {
                                super.visitMethodInsn(opcode, owner, operation, methodDesc, itf);
                            } else if (pendingCollection.equals(ARRAY_LIST) && opcode == INVOKEVIRTUAL
                                    && owner.equals(ARRAY_LIST) && isRegionOperation(operation + methodDesc)) {
                                super.visitMethodInsn(INVOKEVIRTUAL, REGION_SET, operation, methodDesc, false);
                            } else if (pendingCollection.equals(LIST) && opcode == INVOKEINTERFACE
                                    && owner.equals(LIST) && operation.equals("clear") && methodDesc.equals("()V")) {
                                helper(mv, "clearDirtyChunks", "(" + descriptor(DIRTY_SET) + ")V");
                            } else {
                                throw new IllegalStateException("Unexpected reconciliation collection operation: " + operation);
                            }
                            pendingCollection = null;
                        }

                        @Override public void visitEnd() {
                            if (pendingCollection != null) throw new IllegalStateException("Unconsumed collection read in " + name);
                            super.visitEnd();
                        }
                    };
                }

                @Override
                public void visitEnd() {
                    OptimizationVisitor.requireMatches("building region field", regionFields, 1);
                    OptimizationVisitor.requireMatches("building dirty field", dirtyFields, 1);
                    OptimizationVisitor.requireMatches("building helper methods", replacements, 3);
                    OptimizationVisitor.requireMatches("building region reads", regionReads, 7);
                    OptimizationVisitor.requireMatches("building dirty reads", dirtyReads, 1);
                    OptimizationVisitor.requireMatches("building region initialization", regionWrites, 1);
                    OptimizationVisitor.requireMatches("building dirty initialization", dirtyWrites, 1);
                    super.visitEnd();
                }
            };
        }
    }

    private static boolean isRegionOperation(String signature) {
        return switch (signature) {
            case "clear()V", "iterator()Ljava/util/Iterator;", "add(Ljava/lang/Object;)Z",
                 "remove(Ljava/lang/Object;)Z", "contains(Ljava/lang/Object;)Z" -> true;
            default -> false;
        };
    }

    private static boolean replaceBody(MethodVisitor method, String signature) {
        String regionArguments = "(" + WORLD_REGION + "Ljava/util/ArrayList;)V";
        switch (signature) {
            case CONNECTED + "(" + WORLD_REGION + "Ljava/util/ArrayList;)V" -> {
                method.visitCode();
                method.visitVarInsn(ALOAD, 1);
                method.visitVarInsn(ALOAD, 2);
                helper(method, "collectConnectedRegions", regionArguments);
                method.visitInsn(RETURN);
                method.visitMaxs(2, 3);
            }
            case MARK + "(II)V" -> {
                method.visitCode();
                loadDirtySet(method);
                method.visitVarInsn(ILOAD, 1);
                method.visitVarInsn(ILOAD, 2);
                helper(method, "markDirtyChunk", "(" + descriptor(DIRTY_SET) + "II)V");
                method.visitInsn(RETURN);
                method.visitMaxs(3, 3);
            }
            case QUERY + "(" + CHUNK + ")Z" -> {
                method.visitCode();
                loadDirtySet(method);
                method.visitVarInsn(ALOAD, 1);
                helper(method, "isDirtyChunk", "(" + descriptor(DIRTY_SET) + CHUNK + ")Z");
                method.visitInsn(IRETURN);
                method.visitMaxs(2, 2);
            }
            default -> { return false; }
        }
        method.visitEnd();
        return true;
    }

    private static void loadDirtySet(MethodVisitor method) {
        method.visitVarInsn(ALOAD, 0);
        method.visitFieldInsn(GETFIELD, OWNER, DIRTY, descriptor(DIRTY_SET));
    }
}
