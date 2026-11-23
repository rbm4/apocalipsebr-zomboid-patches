package cloud.apocalipse.pz.agent.features.optimization.streaming;

import cloud.apocalipse.pz.agent.HookCheck;
import cloud.apocalipse.pz.agent.config.AgentConfiguration;
import cloud.apocalipse.pz.agent.features.optimization.OptimizationModule;
import cloud.apocalipse.pz.agent.features.optimization.OptimizationVisitor;
import gnu.trove.map.hash.TIntObjectHashMap;
import java.util.List;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.field.FieldList;
import net.bytebuddy.description.method.MethodList;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.pool.TypePool;
import zombie.iso.areas.isoregion.data.DataCell;

import static net.bytebuddy.jar.asm.Opcodes.*;
import static net.bytebuddy.matcher.ElementMatchers.named;

/** A primitive lookup index owned by each vanilla region buffer. */
public final class RegionCellLookupModule extends OptimizationModule {
    private static final String TARGET = "zombie.iso.areas.isoregion.data.DataRoot";
    private static final String OWNER = TARGET.replace('.', '/');
    private static final String CELL = "Lzombie/iso/areas/isoregion/data/DataCell;";
    private static final String INDEX = "Lgnu/trove/map/hash/TIntObjectHashMap;";
    private static final String FIELD = "apocbrCellLookup";

    public RegionCellLookupModule() {
        super("region-cell-lookup", AgentConfiguration.REGION_CELL_LOOKUP_OPTIMIZATION);
    }

    @Override
    public List<HookCheck> hookChecks() {
        String helper = getClass().getName();
        return List.of(new HookCheck("create-index", TARGET, "<init>", helper, "newIndex", false),
            new HookCheck("lookup-cell", TARGET, "getCell", helper, "getCell", false),
            new HookCheck("index-cell", TARGET, "addCell", helper, "indexCell", false),
            new HookCheck("clear-index", TARGET, "resetAllData", helper, "clearIndex", false));
    }

    @Override
    public AgentBuilder configure(AgentBuilder builder) {
        if (disabledReason() != null) return builder;
        return builder.type(named(TARGET)).and((type, loader, module, redefining, domain) -> redefining == null)
            .transform((dynamic, type, loader, module, domain) -> dynamic.visit(new IndexVisitor()));
    }

    public static TIntObjectHashMap<DataCell> newIndex() { return new TIntObjectHashMap<>(); }

    public static DataCell getCell(TIntObjectHashMap<DataCell> index, int id) { return index.get(id); }

    public static DataCell indexCell(DataCell cell, TIntObjectHashMap<DataCell> index, int id) {
        index.put(id, cell);
        return cell;
    }

    public static void clearIndex(TIntObjectHashMap<DataCell> index) { index.clear(); }

    private static void helper(MethodVisitor downstream, String name, String descriptor) {
        OptimizationVisitor.callStatic(downstream, RegionCellLookupModule.class, name, descriptor);
    }

    private static void loadIndex(MethodVisitor downstream) {
        downstream.visitVarInsn(ALOAD, 0);
        downstream.visitFieldInsn(GETFIELD, OWNER, FIELD, INDEX);
    }

    private static boolean cellMap(int opcode, int expected, String owner, String name, String descriptor) {
        return opcode == expected && owner.equals(OWNER) && name.equals("cellMap") && descriptor.equals("Ljava/util/Map;");
    }

    private static final class IndexVisitor extends AsmVisitorWrapper.AbstractBase {
        @Override public int mergeWriter(int flags) { return flags | ClassWriter.COMPUTE_MAXS; }

        @Override
        public ClassVisitor wrap(TypeDescription type, ClassVisitor downstream, Implementation.Context context,
                TypePool pool, FieldList<FieldDescription.InDefinedShape> fields, MethodList<?> methods,
                int writerFlags, int readerFlags) {
            return new ClassVisitor(ASM9, downstream) {
                private int targets;

                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                        String[] exceptions) {
                    MethodVisitor method = super.visitMethod(access, name, descriptor, signature, exceptions);
                    return switch (name + descriptor) {
                        case "<init>()V" -> { targets++; yield new InitializeIndex(method); }
                        case "getCell(I)" + CELL -> { targets++; yield new ReadIndex(method); }
                        case "addCell(I)" + CELL -> { targets++; yield new UpdateIndex(method); }
                        case "resetAllData()V" -> { targets++; yield new ResetIndex(method); }
                        default -> method;
                    };
                }

                @Override
                public void visitEnd() {
                    OptimizationVisitor.requireMatches("region cell index methods", targets, 4);
                    super.visitField(ACC_PRIVATE | ACC_FINAL | ACC_SYNTHETIC, FIELD, INDEX, null, null).visitEnd();
                    super.visitEnd();
                }
            };
        }
    }

    private static final class InitializeIndex extends MethodVisitor {
        private int writes;

        InitializeIndex(MethodVisitor downstream) { super(ASM9, downstream); }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            super.visitFieldInsn(opcode, owner, name, descriptor);
            if (cellMap(opcode, PUTFIELD, owner, name, descriptor)) {
                writes++;
                mv.visitVarInsn(ALOAD, 0);
                helper(mv, "newIndex", "()" + INDEX);
                mv.visitFieldInsn(PUTFIELD, OWNER, FIELD, INDEX);
            }
        }

        @Override public void visitEnd() {
            OptimizationVisitor.requireMatches("region cell map initialization", writes, 1);
            super.visitEnd();
        }
    }

    private static final class ReadIndex extends MethodVisitor {
        private int mapReads;
        private int keyBoxes;
        private int lookups;

        ReadIndex(MethodVisitor downstream) { super(ASM9, downstream); }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            if (cellMap(opcode, GETFIELD, owner, name, descriptor)) {
                mapReads++;
                super.visitFieldInsn(GETFIELD, OWNER, FIELD, INDEX);
            } else {
                super.visitFieldInsn(opcode, owner, name, descriptor);
            }
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean itf) {
            if (opcode == INVOKESTATIC && owner.equals("java/lang/Integer")
                    && name.equals("valueOf") && descriptor.equals("(I)Ljava/lang/Integer;")) {
                keyBoxes++;
            } else if (opcode == INVOKEINTERFACE && owner.equals("java/util/Map")
                    && name.equals("get") && descriptor.equals("(Ljava/lang/Object;)Ljava/lang/Object;")) {
                lookups++;
                helper(mv, "getCell", "(" + INDEX + "I)" + CELL);
            } else {
                super.visitMethodInsn(opcode, owner, name, descriptor, itf);
            }
        }

        @Override public void visitEnd() {
            OptimizationVisitor.requireMatches("region cell map read", mapReads, 1);
            OptimizationVisitor.requireMatches("region cell key boxing", keyBoxes, 1);
            OptimizationVisitor.requireMatches("region cell map lookup", lookups, 1);
            super.visitEnd();
        }
    }

    private static final class UpdateIndex extends MethodVisitor {
        private int returns;

        UpdateIndex(MethodVisitor downstream) { super(ASM9, downstream); }

        @Override
        public void visitInsn(int opcode) {
            if (opcode == ARETURN) {
                returns++;
                loadIndex(mv);
                mv.visitVarInsn(ILOAD, 1);
                helper(mv, "indexCell", "(" + CELL + INDEX + "I)" + CELL);
            }
            super.visitInsn(opcode);
        }

        @Override public void visitEnd() {
            OptimizationVisitor.requireMatches("region cell insertion return", returns, 1);
            super.visitEnd();
        }
    }

    private static final class ResetIndex extends MethodVisitor {
        private boolean cellMapRead;
        private int clears;

        ResetIndex(MethodVisitor downstream) { super(ASM9, downstream); }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            cellMapRead = cellMap(opcode, GETFIELD, owner, name, descriptor);
            super.visitFieldInsn(opcode, owner, name, descriptor);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean itf) {
            super.visitMethodInsn(opcode, owner, name, descriptor, itf);
            if (cellMapRead && opcode == INVOKEINTERFACE && owner.equals("java/util/Map")
                    && name.equals("clear") && descriptor.equals("()V")) {
                clears++;
                loadIndex(mv);
                helper(mv, "clearIndex", "(" + INDEX + ")V");
            }
            cellMapRead = false;
        }

        @Override public void visitEnd() {
            OptimizationVisitor.requireMatches("region cell map clear", clears, 1);
            super.visitEnd();
        }
    }
}

