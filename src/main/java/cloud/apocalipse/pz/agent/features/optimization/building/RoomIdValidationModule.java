package cloud.apocalipse.pz.agent.features.optimization.building;

import cloud.apocalipse.pz.agent.AgentModule;
import cloud.apocalipse.pz.agent.HookCheck;
import cloud.apocalipse.pz.agent.config.AgentConfiguration;
import cloud.apocalipse.pz.agent.features.optimization.OptimizationVisitor;
import java.util.ArrayList;
import java.util.List;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.jar.asm.MethodVisitor;
import zombie.iso.RoomDef;
import zombie.iso.RoomID;

import static net.bytebuddy.jar.asm.Opcodes.ASM9;
import static net.bytebuddy.jar.asm.Opcodes.INVOKEVIRTUAL;
import static net.bytebuddy.matcher.ElementMatchers.named;

/** Uses the encoded room-list position in the editor's read-only ID checks. */
public final class RoomIdValidationModule implements AgentModule {
    private static final String TARGET = "zombie.buildingRooms.BuildingRoomsEditor";
    private static final String CHECK = "checkBuildingAndRoomIDs";

    @Override public String name() { return "room-id-validation"; }

    @Override public String disabledReason() {
        return AgentConfiguration.roomIdValidationOptimizationEnabled() ? null
            : "Enable with -D" + AgentConfiguration.ROOM_ID_VALIDATION_OPTIMIZATION + "=true";
    }

    @Override public List<HookCheck> hookChecks() {
        return List.of(new HookCheck("room-list-position", TARGET, CHECK, getClass().getName(),
            "roomIndex", false));
    }

    @Override public AgentBuilder configure(AgentBuilder builder) {
        if (disabledReason() != null) return builder;
        return builder.type(named(TARGET)).transform((dynamic, type, loader, module, domain) ->
            dynamic.visit(OptimizationVisitor.method(CHECK, "(Lzombie/iso/IsoMetaCell;)V", IndexLookup::new)));
    }

    public static int roomIndex(ArrayList<?> rooms, Object value) {
        if (value instanceof RoomDef room) {
            int index = RoomID.getIndex(room.id);
            if (index >= 0 && index < rooms.size() && rooms.get(index) == room) return index;
        }
        return rooms.indexOf(value);
    }

    private static final class IndexLookup extends MethodVisitor {
        private int lookups;

        private IndexLookup(MethodVisitor downstream) {
            super(ASM9, downstream);
        }

        @Override public void visitMethodInsn(int opcode, String owner, String name, String descriptor,
                boolean isInterface) {
            if (opcode == INVOKEVIRTUAL && owner.equals("java/util/ArrayList") && name.equals("indexOf")
                    && descriptor.equals("(Ljava/lang/Object;)I")) {
                lookups++;
                OptimizationVisitor.callStatic(mv, RoomIdValidationModule.class, "roomIndex",
                    "(Ljava/util/ArrayList;Ljava/lang/Object;)I");
            } else {
                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
            }
        }

        @Override public void visitEnd() {
            OptimizationVisitor.requireMatches("BuildingRoomsEditor room ID lookup", lookups, 2);
            super.visitEnd();
        }
    }
}
