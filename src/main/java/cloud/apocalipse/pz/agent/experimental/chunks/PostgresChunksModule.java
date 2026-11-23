package cloud.apocalipse.pz.agent.experimental.chunks;

import cloud.apocalipse.pz.agent.AgentModule;
import cloud.apocalipse.pz.agent.HookCheck;
import cloud.apocalipse.pz.agent.config.AgentConfiguration;
import cloud.apocalipse.pz.agent.features.lua.HookPreflight;
import cloud.apocalipse.pz.agent.features.optimization.OptimizationVisitor;
import cloud.apocalipse.pz.agent.features.postgres.PostgresMigrations;
import java.util.List;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import static net.bytebuddy.matcher.ElementMatchers.*;

/** Opt-in persistence for native dedicated-server map chunk worker paths. */
public final class PostgresChunksModule implements AgentModule {
    private static final String SERVER_LOADER = "zombie.network.ServerChunkLoader";
    private static final String LOAD_WORKER = SERVER_LOADER + "$LoaderThread";
    private static final String DOWNLOAD_WORKER = "zombie.network.PlayerDownloadServer$WorkerThread";
    private static final String ISO_CHUNK = "zombie/iso/IsoChunk";
    private static final String REQUEST = "zombie/network/ClientChunkRequest";
    private static final String CHUNK_REQUEST = "L" + REQUEST + "$Chunk;";
    private static final String BUFFER = "Ljava/nio/ByteBuffer;";
    private static final String LOAD = "(II" + BUFFER + ")Z";
    private static final List<String> TARGETS = List.of(SERVER_LOADER, LOAD_WORKER, DOWNLOAD_WORKER);
    private boolean enabled;

    @Override public String name() { return "experimental-postgres-chunks"; }

    @Override public void initialize() {
        enabled = AgentConfiguration.postgresChunksEnabled();
        if (!enabled) return;
        if (!AgentConfiguration.postgresSettings().enabled()) {
            throw new IllegalStateException("Experimental chunk persistence requires PostgreSQL");
        }
        String dataset = AgentConfiguration.postgresChunkDataset();
        if (dataset.isBlank() || dataset.length() > 128) {
            throw new IllegalStateException("Experimental chunk persistence requires a dataset of 1 to 128 characters");
        }
        HookPreflight.validate(TARGETS, hookChecks(), PostgresChunksModule::transform);
        PostgresMigrations.prepareChunkSchema(AgentConfiguration.postgresMigrationUrl());
        PostgresChunks.initialize(dataset);
    }

    @Override public String disabledReason() {
        return enabled ? null : "Experimental PostgreSQL chunk persistence disabled by configuration";
    }

    @Override public List<HookCheck> hookChecks() {
        String helper = PostgresChunks.class.getName();
        return List.of(
            new HookCheck("load-worker", LOAD_WORKER, "run", helper, "loadChunk", false),
            new HookCheck("save-worker", SERVER_LOADER, "writeChunk", helper, "writeChunk", false),
            new HookCheck("download-large-area", DOWNLOAD_WORKER, "sendLargeArea", helper, "downloadBuffer", true),
            new HookCheck("download-array", DOWNLOAD_WORKER, "sendArray", helper, "downloadBuffer", false));
    }

    @Override public AgentBuilder configure(AgentBuilder builder) {
        return enabled ? builder.type(namedOneOf(TARGETS.toArray(String[]::new)))
            .transform((dynamic, type, loader, module, domain) -> transform(type.getName(), dynamic)) : builder;
    }

    private static DynamicType.Builder<?> transform(String target, DynamicType.Builder<?> builder) {
        return switch (target) {
            case SERVER_LOADER -> builder.visit(Advice.to(Write.class).on(
                named("writeChunk").and(hasDescriptor("(" + CHUNK_REQUEST + ")V"))));
            case LOAD_WORKER -> builder.visit(OptimizationVisitor.method("run", "()V", LoadCall::new));
            case DOWNLOAD_WORKER -> builder
                .visit(OptimizationVisitor.method("sendLargeArea", "(L" + REQUEST + ";)V",
                    downstream -> new DownloadBuffer(downstream, 5)))
                .visit(OptimizationVisitor.method("sendArray", "(L" + REQUEST + ";)V",
                    downstream -> new DownloadBuffer(downstream, 10)));
            default -> throw new IllegalArgumentException(target);
        };
    }

    public static class Write {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        public static boolean enter(@Advice.Argument(0) Object request) throws java.io.IOException {
            PostgresChunks.writeChunk(request);
            return true;
        }
    }

    private static final class LoadCall extends MethodVisitor {
        private int sites;

        LoadCall(MethodVisitor downstream) {
            super(Opcodes.ASM9, downstream);
        }

        @Override public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean itf) {
            if (opcode == Opcodes.INVOKEVIRTUAL && owner.equals(ISO_CHUNK)
                    && name.equals("LoadChunk") && descriptor.equals(LOAD)) {
                sites++;
                OptimizationVisitor.callStatic(mv, PostgresChunks.class, "loadChunk", "(L" + ISO_CHUNK + ";II" + BUFFER + ")Z");
            } else {
                super.visitMethodInsn(opcode, owner, name, descriptor, itf);
            }
        }

        @Override public void visitEnd() {
            OptimizationVisitor.requireMatches("native chunk load call", sites, 1);
            super.visitEnd();
        }
    }

    private static final class DownloadBuffer extends MethodVisitor {
        private final int expected;
        private int sites;

        DownloadBuffer(MethodVisitor downstream, int expected) {
            super(Opcodes.ASM9, downstream);
            this.expected = expected;
        }

        @Override public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            // The first buffer read per request tests the existing in-memory snapshot before the disk branch.
            if (opcode == Opcodes.GETFIELD && owner.equals(REQUEST + "$Chunk")
                    && name.equals("bb") && descriptor.equals(BUFFER) && sites++ == 0) {
                OptimizationVisitor.callStatic(mv, PostgresChunks.class, "downloadBuffer", "(" + CHUNK_REQUEST + ")" + BUFFER);
            } else {
                super.visitFieldInsn(opcode, owner, name, descriptor);
            }
        }

        @Override public void visitEnd() {
            OptimizationVisitor.requireMatches("native chunk download buffer reads", sites, expected);
            super.visitEnd();
        }
    }
}
