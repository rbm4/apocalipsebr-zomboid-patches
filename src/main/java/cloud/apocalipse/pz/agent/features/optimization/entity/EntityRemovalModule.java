package cloud.apocalipse.pz.agent.features.optimization.entity;

import cloud.apocalipse.pz.agent.AgentModule;
import cloud.apocalipse.pz.agent.HookCheck;
import cloud.apocalipse.pz.agent.config.AgentConfiguration;
import cloud.apocalipse.pz.agent.features.optimization.OptimizationVisitor;
import cloud.apocalipse.pz.agent.features.telemetry.OptimizationTelemetry;
import java.util.IdentityHashMap;
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
import zombie.entity.GameEntity;
import zombie.entity.util.Array;
import zombie.network.GameServer;

import static net.bytebuddy.jar.asm.Opcodes.*;
import static net.bytebuddy.matcher.ElementMatchers.named;

/** Indexes the engine's private unordered array while preserving its vanilla swap removal. */
public final class EntityRemovalModule implements AgentModule {
    private static final String TARGET = "zombie.entity.EngineEntityManager";
    private static final String OWNER = TARGET.replace('.', '/');
    private static final String ARRAY = "zombie/entity/util/Array";
    private static final String INDEX = PositionIndex.class.getName().replace('.', '/');
    private static final String INDEX_TYPE = "L" + INDEX + ";";
    private static final String INDEX_FIELD = "apocbrEntityPositions";

    @Override
    public String name() {
        return "entity-removal-index";
    }

    @Override
    public String disabledReason() {
        return AgentConfiguration.optimizationEnabled(AgentConfiguration.ENTITY_REMOVAL_OPTIMIZATION)
            ? null : "Disabled by -D" + AgentConfiguration.ENTITY_REMOVAL_OPTIMIZATION;
    }

    @Override
    public List<HookCheck> hookChecks() {
        return List.of(
            new HookCheck("append", TARGET, "addEntityInternal", getClass().getName(), "append", false),
            new HookCheck("remove", TARGET, "removeEntityInternal", getClass().getName(), "remove", false));
    }

    @Override
    public AgentBuilder configure(AgentBuilder builder) {
        if (disabledReason() != null) {
            return builder;
        }
        return builder.type(named(TARGET)).and((type, loader, module, redefined, domain) -> redefined == null)
            .transform((dynamic, type, loader, module, domain) -> dynamic.visit(new IndexVisitor()));
    }

    public static void append(Array<GameEntity> entities, GameEntity entity, PositionIndex index) {
        int position = entities.size;
        entities.add(entity);
        index.positions.put(entity, position);
    }

    public static boolean remove(Array<GameEntity> entities, GameEntity entity, boolean identity, PositionIndex index) {
        Integer position = index.positions.get(entity);
        if (!identity || position == null || position >= entities.size || entities.get(position) != entity) {
            boolean removed = entities.removeValue(entity, identity);
            index.positions.clear();
            for (int cursor = 0; cursor < entities.size; cursor++) {
                index.positions.put(entities.get(cursor), cursor);
            }
            return removed;
        }

        GameEntity tail = entities.peek();
        entities.removeIndex(position);
        index.positions.remove(entity);
        if (tail != entity) {
            index.positions.put(tail, position);
        }
        if (GameServer.server) {
            OptimizationTelemetry.count(OptimizationTelemetry.Counter.ENTITY_REMOVAL_INDEXED);
        }
        return true;
    }

    public static final class PositionIndex {
        private final IdentityHashMap<GameEntity, Integer> positions = new IdentityHashMap<>();
    }

    private static final class IndexVisitor extends AsmVisitorWrapper.AbstractBase {
        @Override
        public int mergeWriter(int flags) {
            return flags | ClassWriter.COMPUTE_MAXS;
        }

        @Override
        public ClassVisitor wrap(TypeDescription type, ClassVisitor downstream, Implementation.Context context,
                TypePool pool, FieldList<FieldDescription.InDefinedShape> fields, MethodList<?> methods,
                int writerFlags, int readerFlags) {
            return new ClassVisitor(ASM9, downstream) {
                private int initialized;
                private int additions;
                private int removals;

                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                        String[] exceptions) {
                    MethodVisitor method = super.visitMethod(access, name, descriptor, signature, exceptions);
                    boolean append = name.equals("addEntityInternal");
                    if (name.equals("<init>")) {
                        return new MethodVisitor(ASM9, method) {
                            @Override
                            public void visitInsn(int opcode) {
                                if (opcode == RETURN) {
                                    initialized++;
                                    mv.visitVarInsn(ALOAD, 0);
                                    mv.visitTypeInsn(NEW, INDEX);
                                    mv.visitInsn(DUP);
                                    mv.visitMethodInsn(INVOKESPECIAL, INDEX, "<init>", "()V", false);
                                    mv.visitFieldInsn(PUTFIELD, OWNER, INDEX_FIELD, INDEX_TYPE);
                                }
                                super.visitInsn(opcode);
                            }
                        };
                    }
                    if (!(append || name.equals("removeEntityInternal"))
                            || !descriptor.equals("(Lzombie/entity/GameEntity;)V")) {
                        return method;
                    }
                    return new MethodVisitor(ASM9, method) {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String called, String desc, boolean itf) {
                            if (opcode == INVOKEVIRTUAL && owner.equals(ARRAY)) {
                                if (append && called.equals("add") && desc.equals("(Ljava/lang/Object;)V")) {
                                    additions++;
                                    loadIndex();
                                    OptimizationVisitor.callStatic(mv, EntityRemovalModule.class, "append",
                                        "(L" + ARRAY + ";Lzombie/entity/GameEntity;" + INDEX_TYPE + ")V");
                                    return;
                                }
                                if (!append && called.equals("removeValue") && desc.equals("(Ljava/lang/Object;Z)Z")) {
                                    removals++;
                                    loadIndex();
                                    OptimizationVisitor.callStatic(mv, EntityRemovalModule.class, "remove",
                                        "(L" + ARRAY + ";Lzombie/entity/GameEntity;Z" + INDEX_TYPE + ")Z");
                                    return;
                                }
                            }
                            super.visitMethodInsn(opcode, owner, called, desc, itf);
                        }

                        private void loadIndex() {
                            mv.visitVarInsn(ALOAD, 0);
                            mv.visitFieldInsn(GETFIELD, OWNER, INDEX_FIELD, INDEX_TYPE);
                        }
                    };
                }

                @Override
                public void visitEnd() {
                    OptimizationVisitor.requireMatches("engine position-index construction", initialized, 1);
                    OptimizationVisitor.requireMatches("engine entity append", additions, 1);
                    OptimizationVisitor.requireMatches("engine entity swap removal", removals, 1);
                    super.visitField(ACC_PRIVATE | ACC_FINAL | ACC_SYNTHETIC, INDEX_FIELD, INDEX_TYPE, null, null).visitEnd();
                    super.visitEnd();
                }
            };
        }
    }
}
