package cloud.apocalipse.pz.agent.features.optimization.streaming;

import cloud.apocalipse.pz.agent.AgentModule;
import cloud.apocalipse.pz.agent.HookCheck;
import cloud.apocalipse.pz.agent.config.AgentConfiguration;
import cloud.apocalipse.pz.agent.features.lua.HookPreflight;
import cloud.apocalipse.pz.agent.features.optimization.OptimizationVisitor;
import cloud.apocalipse.pz.agent.features.telemetry.OptimizationTelemetry.Phase;
import cloud.apocalipse.pz.agent.features.telemetry.ServerPhaseTelemetryModule;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.zip.Deflater;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import net.bytebuddy.jar.asm.MethodVisitor;
import zombie.core.raknet.UdpConnection;
import zombie.network.PacketTypes.PacketType;

import static net.bytebuddy.jar.asm.Opcodes.*;
import static net.bytebuddy.matcher.ElementMatchers.*;

public final class ChunkAsyncSendModule implements AgentModule {
    private static final String SERVER = "zombie.network.PlayerDownloadServer";
    private static final String WORKER = SERVER + "$WorkerThread";
    private static final String ENGINE = "zombie.core.raknet.UdpEngine";
    private static final String CONNECTION = "Lzombie/core/raknet/UdpConnection;";
    private static final String SERVER_TYPE = "Lzombie/network/PlayerDownloadServer;";
    private static final String CHUNK_REQUEST = "Lzombie/network/ClientChunkRequest;";
    private static final String PACKET_TYPE = "Lzombie/network/PacketTypes$PacketType;";
    private static final String NOT_READY = "(Lzombie/network/IConnection;" + PACKET_TYPE + "[Ljava/lang/Object;)V";
    private static final String ADD_PENDING = "(Lzombie/network/PlayerDownloadServer$QueuedRequest;)V";
    private static final String COMMAND = "(Lzombie/network/PlayerDownloadServer$EThreadCommand;" + CHUNK_REQUEST + ")V";
    private static final String QUEUE = "java/util/concurrent/LinkedBlockingQueue";
    private static final List<String> TARGETS = List.of(SERVER, WORKER, ENGINE, "zombie.iso.IsoChunk");

    @Override public String name() { return "chunk-async-transmission"; }

    @Override public String disabledReason() {
        return AgentConfiguration.optimizationEnabled(AgentConfiguration.CHUNK_ASYNC_SEND_OPTIMIZATION)
            ? null : "Disabled by -D" + AgentConfiguration.CHUNK_ASYNC_SEND_OPTIMIZATION;
    }

    @Override public void initialize() {
        if (disabledReason() == null) HookPreflight.validate(TARGETS, hookChecks(), ChunkAsyncSendModule::transform);
    }

    @Override public AgentBuilder configure(AgentBuilder builder) {
        return disabledReason() != null ? builder : builder.type(namedOneOf(TARGETS.toArray(String[]::new)))
            .transform((dynamic, type, loader, module, domain) -> transform(type.getName(), dynamic));
    }

    @Override public List<HookCheck> hookChecks() {
        String helper = ChunkDownloadReplies.class.getName();
        String timing = ServerPhaseTelemetryModule.class.getName();
        return List.of(
            new HookCheck("download-open", SERVER, "<init>", helper, "attach", true),
            new HookCheck("download-update", SERVER, "update", helper, "begin", true),
            new HookCheck("download-update-publish", SERVER, "update", helper, "finish", true),
            new HookCheck("download-pending", SERVER, "updatePendingChunks", helper, "pollPending", true),
            new HookCheck("download-pending-iterator", SERVER, "updatePendingChunks", helper, "hasPending", true),
            new HookCheck("download-out-of-range", SERVER, "updateOutOfRangeRequests", helper, "outOfRangeSize", true),
            new HookCheck("download-duplicate", SERVER, "cancelDuplicateChunk", helper, "checkDuplicateBudget", true),
            new HookCheck("download-pending-time", SERVER, "updatePendingChunks", timing, "finishPhase", true),
            new HookCheck("download-duplicate-time", SERVER, "removeOlderDuplicateRequests", timing, "finishPhase", true),
            new HookCheck("download-status-capacity", SERVER, "addPendingChunk", helper, "notReady", true),
            new HookCheck("download-status-pending", SERVER, "updatePendingChunks", helper, "notReady", true),
            new HookCheck("download-payload-send", SERVER, "sendPacket", helper, "skipPayloadSend", true),
            new HookCheck("download-retire", SERVER, "destroy", helper, "retire", true),
            new HookCheck("download-worker-open", WORKER, "<init>", helper, "register", true),
            new HookCheck("download-worker-exit", WORKER, "run", helper, "stopped", true),
            new HookCheck("download-worker-read", WORKER, "runInner", helper, "takeCommand", true),
            new HookCheck("download-worker-submit", WORKER, "putCommand", helper, "beforePayload", true),
            new HookCheck("download-status-not-required", WORKER, "sendNotRequired", helper, "notRequired", true),
            new HookCheck("download-disconnect", ENGINE, "removeConnection", helper, "removeConnection", false),
            new HookCheck("download-peer-shutdown", ENGINE, "Shutdown", helper, "shutdownPeer", false),
            new HookCheck("download-serialize", "zombie.iso.IsoChunk", "SaveLoadedChunk", timing, "finishPhase", false));
    }

    private static DynamicType.Builder<?> transform(String target, DynamicType.Builder<?> builder) {
        return switch (target) {
            case SERVER -> builder
                .visit(Advice.to(Attach.class).on(isConstructor().and(hasDescriptor("(" + CONNECTION + ")V"))))
                .visit(Advice.to(Update.class).on(named("update").and(hasDescriptor("()V"))))
                .visit(Advice.to(Destroy.class).on(named("destroy").and(hasDescriptor("()V"))))
                .visit(Advice.to(Duplicate.class).on(named("cancelDuplicateChunk").and(hasDescriptor("(" + CHUNK_REQUEST + "II)Z"))))
                .visit(Advice.to(PayloadSend.class).on(named("sendPacket").and(hasDescriptor("(" + PACKET_TYPE + ")V"))))
                .visit(calls("addPendingChunk", ADD_PENDING, 1, new Call(
                    "zombie/network/packets/INetworkPacket", "send", NOT_READY, "notReady", NOT_READY)))
                .visit(calls("updatePendingChunks", "()V", 5,
                    new Call("zombie/network/packets/INetworkPacket", "send", NOT_READY, "notReady", NOT_READY),
                    new Call("java/util/concurrent/ConcurrentLinkedQueue", "poll", "()Ljava/lang/Object;", "pollPending",
                        "(Ljava/util/concurrent/ConcurrentLinkedQueue;)Ljava/lang/Object;"),
                    new Call("java/util/Iterator", "hasNext", "()Z", "hasPending", "(Ljava/util/Iterator;)Z")))
                .visit(calls("updateOutOfRangeRequests", "()V", 1, new Call(
                    "java/util/List", "size", "()I", "outOfRangeSize", "(Ljava/util/List;)I")))
                .visit(timing("updatePendingChunks", "()V", Phase.CHUNK_DOWNLOAD_PENDING))
                .visit(timing("removeOlderDuplicateRequests", "()V", Phase.CHUNK_DOWNLOAD_DEDUPLICATE));
            case WORKER -> builder
                .visit(Advice.to(Register.class).on(isConstructor().and(hasDescriptor("(" + SERVER_TYPE + ")V"))))
                .visit(Advice.to(WorkerExit.class).on(named("run").and(hasDescriptor("()V"))))
                .visit(Advice.to(BeforePayload.class).on(named("putCommand").and(hasDescriptor(COMMAND))))
                .visit(Advice.to(NotRequired.class).on(named("sendNotRequired").and(hasDescriptor("(IZ)V"))))
                .visit(calls("runInner", "()V", 1, new Call(QUEUE, "take", "()Ljava/lang/Object;", "takeCommand",
                    "(L" + QUEUE + ";)Ljava/lang/Object;")));
            case ENGINE -> builder
                .visit(calls("removeConnection", "(J)V", 1, new Call("java/util/Map", "remove",
                    "(Ljava/lang/Object;)Ljava/lang/Object;", "removeConnection",
                    "(Ljava/util/Map;Ljava/lang/Object;)Ljava/lang/Object;")))
                .visit(calls("Shutdown", "()V", 1, new Call("zombie/core/raknet/RakNetPeerInterface", "Shutdown", "()V",
                    "shutdownPeer", "(Lzombie/core/raknet/RakNetPeerInterface;)V")));
            case "zombie.iso.IsoChunk" -> builder.visit(timing("SaveLoadedChunk",
                "(Lzombie/network/ClientChunkRequest$Chunk;Ljava/util/zip/CRC32;)V", Phase.CHUNK_DOWNLOAD_SERIALIZE));
            default -> throw new IllegalArgumentException(target);
        };
    }

    private static net.bytebuddy.asm.AsmVisitorWrapper timing(String method, String descriptor, Phase phase) {
        return Advice.withCustomMapping().bind(ServerPhaseTelemetryModule.PhaseIndex.class, phase.ordinal())
            .to(ServerPhaseTelemetryModule.Timing.class).on(named(method).and(hasDescriptor(descriptor)));
    }

    private static net.bytebuddy.asm.AsmVisitorWrapper calls(String method, String descriptor, int expected, Call... replacements) {
        return OptimizationVisitor.method(method, descriptor, downstream -> new MethodVisitor(ASM9, downstream) {
            private int matched;

            @Override public void visitMethodInsn(int opcode, String owner, String name, String signature, boolean itf) {
                for (Call call : replacements) {
                    if (call.owner.equals(owner) && call.name.equals(name) && call.descriptor.equals(signature)) {
                        matched++;
                        OptimizationVisitor.callStatic(mv, ChunkDownloadReplies.class, call.hook, call.hookDescriptor);
                        return;
                    }
                }
                super.visitMethodInsn(opcode, owner, name, signature, itf);
            }

            @Override public void visitEnd() {
                OptimizationVisitor.requireMatches(method, matched, expected);
                super.visitEnd();
            }
        });
    }

    private record Call(String owner, String name, String descriptor, String hook, String hookDescriptor) {}

    public static class Attach {
        @Advice.OnMethodExit
        public static void exit(@Advice.FieldValue("workerThread") Thread worker, @Advice.Argument(0) UdpConnection connection) {
            ChunkDownloadReplies.attach(worker, connection);
        }
    }

    public static class Register {
        @Advice.OnMethodExit
        public static void exit(@Advice.This Thread worker, @Advice.FieldValue("commandQ") LinkedBlockingQueue<Object> commands) {
            ChunkDownloadReplies.register(worker, commands);
        }
    }

    public static class Update {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        public static boolean enter(@Advice.FieldValue("workerThread") Thread worker) {
            return ChunkDownloadReplies.begin(worker);
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(@Advice.Enter boolean skipped, @Advice.Thrown(readOnly = false) Throwable fault) {
            if (!skipped) fault = ChunkDownloadReplies.finish(fault);
        }
    }

    public static class Destroy {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        public static boolean enter(@Advice.FieldValue(value = "workerThread", readOnly = false,
                typing = Assigner.Typing.DYNAMIC) Thread worker) {
            ChunkDownloadReplies.retire(worker);
            worker = null;
            return true;
        }
    }

    public static class Duplicate {
        @Advice.OnMethodEnter
        public static void enter() { ChunkDownloadReplies.checkDuplicateBudget(); }
    }

    public static class BeforePayload {
        @Advice.OnMethodEnter
        public static void enter() { ChunkDownloadReplies.beforePayload(); }
    }

    public static class NotRequired {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        public static boolean enter(@Advice.Argument(0) int request, @Advice.Argument(1) boolean same) {
            return ChunkDownloadReplies.notRequired(request, same);
        }
    }

    public static class PayloadSend {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        public static boolean enter(@Advice.Local("started") long started) {
            if (ChunkDownloadReplies.skipPayloadSend()) return true;
            started = System.nanoTime();
            return false;
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(@Advice.Local("started") long started, @Advice.Argument(0) PacketType type) {
            if (started != 0) {
                (type == PacketType.SentChunk ? Phase.CHUNK_PAYLOAD_TRANSPORT : Phase.CHUNK_REPLY_TRANSPORT)
                    .recordSince(started);
            }
        }
    }

    public static class WorkerExit {
        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(@Advice.FieldValue("compressor") Deflater compressor,
                @Advice.Thrown(readOnly = false) Throwable fault) {
            compressor.end();
            fault = ChunkDownloadReplies.stopped(fault);
        }
    }

}
