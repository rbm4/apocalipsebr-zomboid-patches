package cloud.apocalipse.pz.agent.features.optimization.building;

import cloud.apocalipse.pz.agent.AgentModule;
import cloud.apocalipse.pz.agent.HookCheck;
import cloud.apocalipse.pz.agent.config.AgentConfiguration;
import cloud.apocalipse.pz.agent.features.optimization.OptimizationVisitor;
import java.util.List;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.MethodVisitor;
import zombie.buildingRooms.BuildingRoomsEditor;

import static net.bytebuddy.jar.asm.Opcodes.*;
import static net.bytebuddy.matcher.ElementMatchers.hasDescriptor;
import static net.bytebuddy.matcher.ElementMatchers.named;

/** Scopes the editor's global passes only for explicitly requested mod commits. */
public final class ScopedRoomCommitModule implements AgentModule {
    private static final String TARGET = "zombie.buildingRooms.BuildingRoomsEditor";
    private static final String EDITOR = TARGET.replace('.', '/');
    private static final String EDITOR_ARG = "L" + EDITOR + ";";
    private static final String APPLY = "applyChanges";
    private static final String CHECK = "checkBuildingAndRoomIDs";
    private static final String REBUILD = "recalculateBuildingAndRoomIDs";

    @Override public String name() { return "scoped-room-commit"; }

    @Override public String disabledReason() {
        return AgentConfiguration.scopedRoomCommitEnabled() ? null
            : "Enable with -D" + AgentConfiguration.SCOPED_ROOM_COMMIT + "=true";
    }

    @Override public List<HookCheck> hookChecks() {
        String helper = ScopedRoomCommit.class.getName();
        return List.of(
            new HookCheck("scope", TARGET, APPLY, helper, "enter", false),
            new HookCheck("owner", TARGET, APPLY, helper, "trackOwner", false),
            new HookCheck("checks", TARGET, APPLY, helper, "check", false),
            new HookCheck("rebuild", TARGET, APPLY, helper, "rebuild", false));
    }

    @Override public AgentBuilder configure(AgentBuilder builder) {
        if (disabledReason() != null) return builder;
        return builder.type(named(TARGET)).transform((dynamic, type, loader, module, domain) ->
            dynamic.visit(OptimizationVisitor.method(APPLY, "(Z)V", CommitCalls::new)
                    .writerFlags(ClassWriter.COMPUTE_MAXS))
                .visit(Advice.to(CommitBoundary.class).on(named(APPLY).and(hasDescriptor("(Z)V")))));
    }

    public static final class CommitBoundary {
        @Advice.OnMethodEnter
        public static ScopedRoomCommit.Scope enter(@Advice.This Object editor,
                @Advice.Argument(0) boolean loading, @Advice.FieldValue("preEditBuildings") List<?> oldBuildings) {
            return ScopedRoomCommit.enter(editor, loading, oldBuildings);
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(@Advice.Enter ScopedRoomCommit.Scope previous) {
            ScopedRoomCommit.exit(previous);
        }
    }

    private static final class CommitCalls extends MethodVisitor {
        private int checks, rebuilds, owners;

        private CommitCalls(MethodVisitor next) { super(ASM9, next); }

        @Override public void visitMethodInsn(int opcode, String owner, String name, String descriptor,
                boolean isInterface) {
            if (owner.equals(EDITOR) && descriptor.equals("()V")) {
                if (name.equals(CHECK)) {
                    checks++;
                    OptimizationVisitor.callStatic(mv, ScopedRoomCommit.class, "check", "(" + EDITOR_ARG + ")V");
                    return;
                }
                if (name.equals(REBUILD)) {
                    rebuilds++;
                    OptimizationVisitor.callStatic(mv, ScopedRoomCommit.class, "rebuild", "(" + EDITOR_ARG + ")V");
                    return;
                }
            }
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
            if (owner.equals("zombie/buildingRooms/BREBuilding") && name.equals("getLotHeader")
                    && descriptor.equals("(Lzombie/iso/BuildingDef;)Lzombie/iso/IsoMetaCell;")) {
                owners++;
                super.visitInsn(DUP);
                OptimizationVisitor.callStatic(mv, ScopedRoomCommit.class, "trackOwner", "(Lzombie/iso/IsoMetaCell;)V");
            }
        }

        @Override public void visitEnd() {
            OptimizationVisitor.requireMatches("Room commit validation", checks, 3);
            OptimizationVisitor.requireMatches("Room commit rebuild", rebuilds, 1);
            OptimizationVisitor.requireMatches("Room commit owner lookup", owners, 2);
            super.visitEnd();
        }
    }
}
