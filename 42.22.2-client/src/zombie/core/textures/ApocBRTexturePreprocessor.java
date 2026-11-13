package zombie.core.textures;

import java.util.concurrent.Semaphore;
import zombie.ApocBRClientTelemetryLite;
import zombie.core.utils.DirectBufferAllocator;
import zombie.core.utils.WrappedBuffer;
import zombie.debug.DebugOptions;

/** CPU-only preprocessing of an exclusively owned image. No TextureID, asset state, Lua or GL on workers. */
public final class ApocBRTexturePreprocessor {
    public static final boolean ENABLED = !"false".equalsIgnoreCase(System.getProperty("apocbr.clientTextures.workerPreprocess", "true"));
    private static final Semaphore SLOTS = new Semaphore(2, true);
    private static final int BUDGET_MIB = Math.max(1, Math.min(16384, Integer.getInteger("apocbr.clientTextures.preprocessMiB", 256)));
    private static final Semaphore MEMORY = new Semaphore(BUDGET_MIB, true);
    private static final ThreadLocal<Boolean> DEBUG_COLORS = new ThreadLocal<>();
    private ApocBRTexturePreprocessor() { }

    public static boolean mipmapDebugColors() {
        Boolean snapshot = DEBUG_COLORS.get();
        return snapshot != null ? snapshot : DebugOptions.instance.isoSprite.worldMipmapColors.getValue();
    }

    /** Called on workers only. Eligible jobs wait for capacity; oversized jobs take the entire budget. */
    public static ImageData prepare(ImageData image, int maxSize, boolean debugColors) {
        return prepare(image, maxSize, debugColors, () -> false);
    }
    public static ImageData prepare(ImageData image, int maxSize, boolean debugColors, java.util.function.BooleanSupplier cancelled) {
        if (image == null || maxSize <= 0 || image.getWidth() <= maxSize && image.getHeight() <= maxSize) return image;
        if (image.data == null || !image.frames.isEmpty()) {
            ApocBRClientTelemetryLite.workerTexture(ApocBRClientTelemetryLite.WorkerTexture.FALLBACK, 1L);
            ApocBRClientTelemetryLite.workerTexture(ApocBRClientTelemetryLite.WorkerTexture.UNSUPPORTED, 1L); return image;
        }
        // Mip-chain extra bytes plus one copied result; source decode memory already belongs to the vanilla pool.
        long extra = 4096L;
        int width = image.getWidthHW(), height = image.getHeightHW();
        int selectedWidth = width, selectedHeight = height;
        while (width > 1 || height > 1) {
            width = Math.max(1, width >> 1); height = Math.max(1, height >> 1);
            extra += (long)width * height * 4L;
        }
        while (selectedWidth > maxSize || selectedHeight > maxSize) {
            selectedWidth = Math.max(1, selectedWidth >> 1); selectedHeight = Math.max(1, selectedHeight >> 1);
        }
        extra += (long)selectedWidth * selectedHeight * 4L;
        long permitsLong = (extra + 1048575L) / 1048576L;
        int permits = (int)Math.max(1L, Math.min(BUDGET_MIB, permitsLong));
        if (permitsLong > BUDGET_MIB) ApocBRClientTelemetryLite.workerTexture(ApocBRClientTelemetryLite.WorkerTexture.OVERSIZED, 1L);
        long waitStarted = System.nanoTime();
        acquire(SLOTS, 1, cancelled);
        try { acquire(MEMORY, permits, cancelled); }
        catch (RuntimeException | Error failure) { SLOTS.release(); throw failure; }
        ApocBRClientTelemetryLite.workerTexture(ApocBRClientTelemetryLite.WorkerTexture.CAPACITY_WAIT_NANOS, System.nanoTime() - waitStarted);
        long started = System.nanoTime();
        long inputPixels = (long)image.getWidth() * image.getHeight();
        WrappedBuffer buffer = null;
        ImageData result = null;
        try {
            DEBUG_COLORS.set(debugColors);
            // Same steps/order as TextureID.limitMaxSize; no alternative sampling/filter.
            image.preserveTransparentColor = true;
            MipMapLevel mip = image.getData();
            int level = 0;
            while (mip.width > maxSize || mip.height > maxSize) mip = image.getMipMapData(++level);
            buffer = DirectBufferAllocator.allocate(mip.getBuffer().capacity());
            mip.getBuffer().rewind();
            buffer.getBuffer().put(mip.getBuffer());
            buffer.getBuffer().rewind();
            result = new ImageData(image.getWidth() >> level, image.getHeight() >> level, buffer);
            buffer = null; // result owns it now
            result.alphaPaddingDone = true;
            disposeOwned(image);
            ApocBRClientTelemetryLite.workerTexture(ApocBRClientTelemetryLite.WorkerTexture.PREPROCESSED, 1L);
            ApocBRClientTelemetryLite.workerTexture(ApocBRClientTelemetryLite.WorkerTexture.INPUT_PIXELS, inputPixels);
            ApocBRClientTelemetryLite.workerTexture(ApocBRClientTelemetryLite.WorkerTexture.OUTPUT_PIXELS, (long)result.getWidth() * result.getHeight());
            return result;
        } catch (Throwable error) {
            if (buffer != null) buffer.dispose();
            if (result != null) disposeOwned(result);
            if (buffer != null) DirectBufferAllocator.destroyDisposed();
            // Input remains the caller's responsibility, including partial mip allocation.
            ApocBRClientTelemetryLite.workerTexture(ApocBRClientTelemetryLite.WorkerTexture.FAILED, 1L);
            throw error;
        } finally {
            DEBUG_COLORS.remove();
            MEMORY.release(permits); SLOTS.release();
            ApocBRClientTelemetryLite.workerTexture(ApocBRClientTelemetryLite.WorkerTexture.WORK_NANOS, System.nanoTime() - started);
        }
    }

    private static void acquire(Semaphore semaphore, int permits, java.util.function.BooleanSupplier cancelled) {
        try {
            while (!cancelled.getAsBoolean()) {
                if (semaphore.tryAcquire(permits, 25L, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    if (!cancelled.getAsBoolean()) return;
                    semaphore.release(permits); break;
                }
            }
            throw new java.util.concurrent.CancellationException("Texture request cancelled while waiting");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new java.util.concurrent.CancellationException("Texture preprocessing interrupted");
        }
    }

    public static void disposeOwned(ImageData image) {
        // APNG is not preprocessed, but cancellation still owns all unpublished frame buffers.
        for (ImageDataFrame frame : image.frames) {
            if (frame != null && frame.data != null && frame.data != image.data && !frame.data.isDisposed()) frame.data.dispose();
        }
        image.frames.clear();
        image.dispose();
        // Publish disposal flags/prune registry entries under the allocator's existing monitor.
        DirectBufferAllocator.destroyDisposed();
    }
}
