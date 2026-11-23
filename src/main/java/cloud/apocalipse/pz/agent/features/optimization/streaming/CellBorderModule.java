package cloud.apocalipse.pz.agent.features.optimization.streaming;

import cloud.apocalipse.pz.agent.AgentModule;
import cloud.apocalipse.pz.agent.HookCheck;
import cloud.apocalipse.pz.agent.config.AgentConfiguration;
import cloud.apocalipse.pz.agent.features.optimization.OptimizationVisitor;
import java.util.List;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.jar.asm.MethodVisitor;
import zombie.iso.IsoChunk;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoWorld;
import zombie.network.ServerMap;
import static net.bytebuddy.jar.asm.Opcodes.*;
import static net.bytebuddy.matcher.ElementMatchers.named;

/** Same-cell lookup for the four perimeter recalculation sites in original 42.22.2. */
public final class CellBorderModule implements AgentModule {
    private static final String TARGET = "zombie.network.ServerMap$ServerCell";
    private static final String SQUARE = "(III)Lzombie/iso/IsoGridSquare;";
    private static final String LOCAL_SQUARE =
        "(Lzombie/network/ServerMap;IIILzombie/network/ServerMap$ServerCell;)Lzombie/iso/IsoGridSquare;";
    private static final int EXACT_FLOAT_COORDINATE = 1 << 24;

    @Override public String name() { return "cell-border-lookup"; }

    @Override
    public String disabledReason() {
        return AgentConfiguration.cellBorderOptimizationEnabled()
            ? null : "Disabled by -D" + AgentConfiguration.CELL_BORDER_OPTIMIZATION;
    }

    @Override
    public List<HookCheck> hookChecks() {
        return List.of(new HookCheck("same-cell-perimeter", TARGET, "RecalcAll2",
            CellBorderModule.class.getName(), "borderSquare", false));
    }

    @Override
    public AgentBuilder configure(AgentBuilder builder) {
        if (disabledReason() != null) return builder;
        return builder.type(named(TARGET)).transform((dynamic, type, loader, module, domain) ->
            dynamic.visit(OptimizationVisitor.method("RecalcAll2", "()V", BorderVisitor::new)));
    }

    public static IsoGridSquare borderSquare(ServerMap map, int x, int y, int z, ServerMap.ServerCell cell) {
        int cellX = x >> 6;
        int cellY = y >> 6;
        if (map == null || map.getClass() != ServerMap.class || !cell.isLoaded
                || cellX != cell.wx || cellY != cell.wy
                || x < -EXACT_FLOAT_COORDINATE || x > EXACT_FLOAT_COORDINATE
                || y < -EXACT_FLOAT_COORDINATE || y > EXACT_FLOAT_COORDINATE) {
            return map.getGridSquare(x, y, z);
        }
        // Preserve map validation and the canonical cell lookup when a caller invokes RecalcAll2
        // on an unpublished cell. Only the normal published cell takes the local path.
        if (!IsoWorld.instance.isValidSquare(x, y, z)) return null;
        if (map.getCell(cellX - map.getMinX(), cellY - map.getMinY()) != cell) {
            return map.getGridSquare(x, y, z);
        }
        IsoChunk chunk = cell.getChunk((x & 63) >> 3, (y & 63) >> 3);
        return chunk == null ? null : chunk.getGridSquare(x & 7, y & 7, z);
    }

    private static final class BorderVisitor extends MethodVisitor {
        private int lookups;
        private int recalculations;

        BorderVisitor(MethodVisitor downstream) {
            super(ASM9, downstream);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
            if (opcode == INVOKEVIRTUAL && owner.equals("zombie/network/ServerMap")
                    && name.equals("getGridSquare") && descriptor.equals(SQUARE) && ++lookups > 8) {
                mv.visitVarInsn(ALOAD, 0);
                OptimizationVisitor.callStatic(mv, CellBorderModule.class, "borderSquare", LOCAL_SQUARE);
                return;
            }
            if (opcode == INVOKEVIRTUAL && owner.equals("zombie/iso/IsoGridSquare")
                    && name.equals("RecalcAllWithNeighbours") && descriptor.equals("(Z)V")) {
                if (lookups != 9 + recalculations++) {
                    throw new IllegalStateException("Unexpected 42.22.2 perimeter lookup and recalculation order");
                }
            }
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
        }

        @Override
        public void visitMaxs(int maxStack, int maxLocals) {
            super.visitMaxs(maxStack + 1, maxLocals);
        }

        @Override
        public void visitEnd() {
            OptimizationVisitor.requireMatches("ServerCell.RecalcAll2 square lookups", lookups, 12);
            OptimizationVisitor.requireMatches("ServerCell.RecalcAll2 perimeter recalculations", recalculations, 4);
            super.visitEnd();
        }
    }
}
