package cloud.apocalipse.pz.agent.features.optimization.streaming;

import cloud.apocalipse.pz.agent.HookCheck;
import cloud.apocalipse.pz.agent.config.AgentConfiguration;
import cloud.apocalipse.pz.agent.features.optimization.OptimizationModule;
import cloud.apocalipse.pz.agent.features.telemetry.OptimizationTelemetry;
import java.util.List;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import zombie.iso.IsoGridSquare;
import zombie.network.GameServer;
import static net.bytebuddy.matcher.ElementMatchers.*;

/** Reads each adjacency slot once while preserving the original two reverse-link writes. */
public final class ChunkAdjacencyModule extends OptimizationModule {
    private static final int EXACT_FLOAT_COORDINATE = 1 << 24;
    private static int interior;

    public ChunkAdjacencyModule() {
        super("chunk-adjacency-detach", AgentConfiguration.CHUNK_ADJACENCY_OPTIMIZATION);
    }

    @Override public List<HookCheck> hookChecks() {
        return List.of(
            new HookCheck("adjacency", "zombie.iso.IsoChunk", "disconnectFromAdjacentChunks",
                getClass().getName(), "unlink", false),
            new HookCheck("adjacency-metrics", "zombie.iso.IsoChunk", "removeFromWorld",
                getClass().getName(), "report", false));
    }

    @Override public AgentBuilder configure(AgentBuilder builder) {
        if (disabledReason() != null) return builder;
        return builder.type(named("zombie.iso.IsoChunk")).transform((dynamic, type, loader, module, domain) ->
            dynamic.visit(Advice.to(Unlink.class).on(named("disconnectFromAdjacentChunks")
                    .and(hasDescriptor("(Lzombie/iso/IsoGridSquare;)V"))))
                .visit(Advice.to(Report.class).on(named("removeFromWorld").and(hasDescriptor("()V")))));
    }

    public static boolean unlink(Object receiver) {
        if (!GameServer.server || Thread.currentThread() != GameServer.mainThread) return false;
        IsoGridSquare square = (IsoGridSquare) receiver;
        if (square.x < -EXACT_FLOAT_COORDINATE || square.x > EXACT_FLOAT_COORDINATE
                || square.y < -EXACT_FLOAT_COORDINATE || square.y > EXACT_FLOAT_COORDINATE) return false;
        int x = square.x & 7;
        int y = square.y & 7;
        if (x != 0 && x != 7 && y != 0 && y != 7) {
            interior++;
            return true;
        }
        // IsoDirections declares N, NW, W, SW, S, SE, E, NE in this order.
        var neighbors = square.getSurroundingSquares();
        var chunk = square.chunk;
        for (int direction = 0; direction < 8; direction++) {
            var neighbor = neighbors[direction];
            if (neighbor == null || neighbor.chunk == chunk) continue;
            neighbor.getSurroundingSquares()[(direction + 4) & 7] = null;
            switch (direction) {
                case 0 -> neighbor.s = null;
                case 1 -> neighbor.se = null;
                case 2 -> neighbor.e = null;
                case 3 -> neighbor.ne = null;
                case 4 -> neighbor.n = null;
                case 5 -> neighbor.nw = null;
                case 6 -> neighbor.w = null;
                case 7 -> neighbor.sw = null;
            }
        }
        return true;
    }

    public static void report() {
        if (Thread.currentThread() == GameServer.mainThread && interior > 0) {
            OptimizationTelemetry.count(OptimizationTelemetry.Counter.CHUNK_INTERIOR_UNLINK_SKIPPED, interior);
            interior = 0;
        }
    }

    public static class Unlink {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        public static boolean enter(@Advice.Argument(0) Object square) { return unlink(square); }
    }

    public static class Report {
        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit() { report(); }
    }
}
