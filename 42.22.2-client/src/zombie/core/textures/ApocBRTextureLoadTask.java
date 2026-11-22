package zombie.core.textures;

import java.util.function.BiConsumer;
import zombie.ApocBROwnedResult;
import zombie.ApocBRClientTelemetryLite;
import zombie.asset.FileTask_LoadImageData;
import zombie.fileSystem.FileSystem;
import zombie.fileSystem.FileTask;

/** The future can be cancelled while decode runs: late publication and done are coordinated explicitly. */
public final class ApocBRTextureLoadTask extends FileTask {
    // Decode happens after queue admission, so queued jobs retain paths/plans, not decoded buffers.
    private static final java.util.concurrent.ThreadPoolExecutor WORKERS = new java.util.concurrent.ThreadPoolExecutor(
        2, 2, 0L, java.util.concurrent.TimeUnit.MILLISECONDS, new java.util.concurrent.ArrayBlockingQueue<>(16), runnable -> {
            Thread thread = new Thread(runnable, "ApocBR-Texture-Preprocess"); thread.setDaemon(true); return thread;
        });
    public static boolean submit(java.util.concurrent.FutureTask<Object> future) {
        WORKERS.purge();
        try { WORKERS.execute(future); return true; }
        catch (java.util.concurrent.RejectedExecutionException full) { return false; }
    }
    public record Plan(int flags, int maxSize, boolean debugColors) { }
    private final FileTask_LoadImageData decode;
    private final Plan plan;
    private final BiConsumer<ApocBRTextureLoadTask, ImageData> receiver;
    private final ApocBROwnedResult<ImageData> ownership = new ApocBROwnedResult<>();
    public ApocBRTextureLoadTask(String path, FileSystem fs, Plan plan,
                               BiConsumer<ApocBRTextureLoadTask, ImageData> receiver) {
        super(fs);
        this.decode = new FileTask_LoadImageData(path, fs, null);
        this.plan = plan; this.receiver = receiver;
    }
    public Plan plan() { return plan; }
    /** Future.cancel(false) can fail after completion; cancellation must still revoke adoption. */
    public void cancelResult() { done(); }
    @Override public String getErrorMessage() { return decode.getErrorMessage(); }
    @Override public Object call() throws Exception {
        ImageData image = null;
        try {
            if (ownership.isClosed()) return null;
            image = (ImageData)decode.call();
            if (!ownership.isClosed()) image = ApocBRTexturePreprocessor.prepare(image, plan.maxSize, plan.debugColors, ownership::isClosed);
            if (!ownership.publish(image)) {
                if (image != null) ApocBRTexturePreprocessor.disposeOwned(image);
                ApocBRClientTelemetryLite.workerTexture(ApocBRClientTelemetryLite.WorkerTexture.CANCELLED_RESULTS, 1L);
                return null;
            }
            return image;
        } catch (Exception | Error error) {
            if (image != null) ApocBRTexturePreprocessor.disposeOwned(image);
            throw error;
        }
    }
    @Override public void handleResult(Object result) {
        ImageData image = (ImageData)result;
        if (ownership.claim(image) || image == null && ownership.claimFailure()) receiver.accept(this, image);
    }
    @Override public void done() {
        ImageData abandoned = ownership.cancel();
        if (abandoned != null) {
            ApocBRTexturePreprocessor.disposeOwned(abandoned);
            ApocBRClientTelemetryLite.workerTexture(ApocBRClientTelemetryLite.WorkerTexture.CANCELLED_RESULTS, 1L);
        }
    }
}
