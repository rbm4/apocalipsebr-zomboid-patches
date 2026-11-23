package cloud.apocalipse.pz.agent.integration.probe;

import cloud.apocalipse.pz.agent.features.optimization.OptimizationVisitor;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.List;
import net.bytebuddy.jar.asm.MethodVisitor;
import static net.bytebuddy.jar.asm.Opcodes.*;

/** Times the original operations in SafeRead without substituting their implementations. */
public final class ChunkReadProbe extends MethodVisitor {
    static final String SAFE_READ_DESCRIPTOR = "(IILjava/nio/ByteBuffer;)Ljava/nio/ByteBuffer;";
    private static final String CHUNK = "zombie/iso/IsoChunk";
    private static final String LOCK = CHUNK + "$ChunkLock";
    private static final String OPEN = "chunk.io.open";
    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();

    private record Step(String phase, String owner, String method, String descriptor, int anchors) {}

    private static final List<Step> STEPS = List.of(
        new Step("chunk.io.lockLookup", CHUNK, "acquireLock", "(II)L" + LOCK + ";", 1),
        new Step("chunk.io.readLock", LOCK, "lockForReading", "()V", 1),
        new Step("chunk.io.resolveFile", "zombie/ChunkMapFilenames", "getFilename", "(II)Ljava/io/File;", 1),
        new Step(OPEN, "java/io/FileInputStream", "<init>", "(Ljava/io/File;)V", 1),
        new Step("chunk.io.stat", "java/io/File", "length", "()J", 1),
        new Step("chunk.io.buffer", CHUNK, "ensureCapacity", "(Ljava/nio/ByteBuffer;I)Ljava/nio/ByteBuffer;", 1),
        new Step("chunk.io.read", "java/io/FileInputStream", "read", "([B)I", 1),
        new Step("chunk.io.close", "java/io/FileInputStream", "close", "()V", 2),
        new Step("chunk.io.readUnlock", LOCK, "unlockForReading", "()V", 2),
        new Step("chunk.io.lockRelease", CHUNK, "releaseLock", "(L" + LOCK + ";)V", 2)
    );

    private final int[] matches = new int[STEPS.size()];
    // Only the native LoadChunk worker sees an active timer. Other callers leave it alone.
    private static long started;
    private static long cpuStarted;

    ChunkReadProbe(MethodVisitor downstream) { super(ASM9, downstream); }

    @Override
    public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean itf) {
        for (int index = 0; index < STEPS.size(); index++) {
            Step step = STEPS.get(index);
            if (!step.owner.equals(owner) || !step.method.equals(name) || !step.descriptor.equals(descriptor)) {
                continue;
            }
            matches[index]++;
            mv.visitLdcInsn(index);
            OptimizationVisitor.callStatic(mv, ChunkReadProbe.class, "begin", "(I)V");
            super.visitMethodInsn(opcode, owner, name, descriptor, itf);
            mv.visitLdcInsn(index);
            OptimizationVisitor.callStatic(mv, ChunkReadProbe.class, "end", "(I)V");
            if (step.phase.equals("chunk.io.read")) {
                mv.visitInsn(DUP);
                OptimizationVisitor.callStatic(mv, StreamingProbe.class, "recordReadSize", "(I)V");
            }
            return;
        }
        super.visitMethodInsn(opcode, owner, name, descriptor, itf);
    }

    @Override
    public void visitMaxs(int maxStack, int maxLocals) { super.visitMaxs(maxStack + 1, maxLocals); }

    @Override
    public void visitEnd() {
        for (int index = 0; index < STEPS.size(); index++) {
            Step step = STEPS.get(index);
            OptimizationVisitor.requireMatches(step.phase, matches[index], step.anchors);
        }
        super.visitEnd();
    }

    public static void begin(int step) {
        if (!isLoader()) return;
        started = StreamingProbe.startSavedChunkIo();
        if (started != 0 && STEPS.get(step).phase.equals(OPEN)) {
            cpuStarted = tracksOpenCpu() ? THREADS.getCurrentThreadCpuTime() : -1;
        }
    }

    public static void end(int step) {
        if (!isLoader() || started == 0) return;
        String phase = STEPS.get(step).phase;
        StreamingProbe.finish(phase, started, null);
        if (phase.equals(OPEN) && cpuStarted >= 0) {
            long now = THREADS.getCurrentThreadCpuTime();
            if (now >= cpuStarted) StreamingProbe.recordSavedChunkCpu(now - cpuStarted);
        }
    }

    static boolean tracksOpenCpu() {
        return THREADS.isCurrentThreadCpuTimeSupported() && THREADS.isThreadCpuTimeEnabled();
    }

    static boolean isLoader() { return "LoadChunk".equals(Thread.currentThread().getName()); }

    static List<String> phases() { return STEPS.stream().map(Step::phase).toList(); }
}
