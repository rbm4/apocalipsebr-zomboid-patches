package cloud.apocalipse.pz.agent.features.optimization.player.proximity;

import cloud.apocalipse.pz.agent.HookCheck;
import cloud.apocalipse.pz.agent.config.AgentConfiguration;
import cloud.apocalipse.pz.agent.features.optimization.OptimizationModule;
import cloud.apocalipse.pz.agent.features.optimization.OptimizationVisitor;
import cloud.apocalipse.pz.agent.features.telemetry.OptimizationTelemetry;
import java.util.List;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.jar.asm.MethodVisitor;
import zombie.characters.IsoPlayer;
import zombie.network.GameServer;

import static net.bytebuddy.jar.asm.Opcodes.*;
import static net.bytebuddy.matcher.ElementMatchers.named;

public final class PlayerNearVehicleModule extends OptimizationModule {
    private static final String TARGET = "zombie.characters.IsoPlayer";
    private static final String METHOD = "updateInternal2";

    public PlayerNearVehicleModule() {
        super("player-near-vehicle", AgentConfiguration.PLAYER_NEAR_VEHICLE_OPTIMIZATION);
    }

    @Override
    public List<HookCheck> hookChecks() {
        return List.of(new HookCheck("remote-player-query", TARGET, METHOD, PlayerNearVehicleModule.class.getName(),
            "checkRemotePlayer", false));
    }

    @Override
    public AgentBuilder configure(AgentBuilder builder) {
        if (disabledReason() != null) {
            return builder;
        }
        return builder.type(named(TARGET)).transform((dynamic, type, loader, module, domain) ->
            dynamic.visit(OptimizationVisitor.method(METHOD, "()Z", RemoteQueryVisitor::new)));
    }

    public static boolean checkRemotePlayer(IsoPlayer player) {
        if (!GameServer.server) {
            return player.checkIsNearVehicle();
        }
        if (!player.isSneaking()) {
            OptimizationTelemetry.count(OptimizationTelemetry.Counter.NEAR_VEHICLE_QUERY_SKIPPED);
            return false;
        }
        long started = System.nanoTime();
        try {
            return player.checkIsNearVehicle();
        } finally {
            OptimizationTelemetry.Phase.NEAR_VEHICLE.recordSince(started);
        }
    }

    private static final class RemoteQueryVisitor extends MethodVisitor {
        private int matches;

        RemoteQueryVisitor(MethodVisitor downstream) {
            super(ASM9, downstream);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean itf) {
            if (opcode == INVOKEVIRTUAL && owner.equals("zombie/characters/IsoPlayer")
                    && name.equals("checkIsNearVehicle") && descriptor.equals("()Z") && ++matches == 1) {
                OptimizationVisitor.callStatic(mv, PlayerNearVehicleModule.class, "checkRemotePlayer",
                    "(Lzombie/characters/IsoPlayer;)Z");
                return;
            }
            super.visitMethodInsn(opcode, owner, name, descriptor, itf);
        }

        @Override
        public void visitEnd() {
            OptimizationVisitor.requireMatches("player near-vehicle call sites", matches, 2);
            super.visitEnd();
        }
    }
}
