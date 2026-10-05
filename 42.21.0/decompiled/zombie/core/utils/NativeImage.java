// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.core.utils;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import org.lwjgl.stb.STBIWriteCallback;
import org.lwjgl.stb.STBImage;
import org.lwjgl.stb.STBImageResize;
import org.lwjgl.stb.STBImageWrite;
import org.lwjgl.system.MemoryUtil;
import zombie.ZomboidFileSystem;
import zombie.core.logger.ExceptionLogger;
import zombie.debug.DebugType;

public final class NativeImage implements AutoCloseable {
    private static final int BYTES_PER_PIXEL = 4;
    private final int width;
    private final int height;
    private final boolean loadedByStb;
    private ByteBuffer pixels;

    private NativeImage(int width, int height, ByteBuffer pixels, boolean loadedByStb) {
        this.width = width;
        this.height = height;
        this.pixels = pixels;
        this.loadedByStb = loadedByStb;
    }

    public static NativeImage allocate(int width, int height) {
        return new NativeImage(width, height, MemoryUtil.memAlloc(width * height * 4), false);
    }

    public static NativeImage read(String path, boolean flipVertically) {
        if (ZomboidFileSystem.instance.isInitialized()) {
            ZomboidFileSystem.instance.validatePrefix(path);
        }

        STBImage.stbi_set_flip_vertically_on_load(flipVertically);
        int[] width = new int[1];
        int[] height = new int[1];
        int[] channels = new int[1];
        ByteBuffer pixels = STBImage.stbi_load(path, width, height, channels, 4);
        if (pixels == null) {
            DebugType.General.error("failed to read image %s: %s", path, STBImage.stbi_failure_reason());
            return null;
        } else {
            return new NativeImage(width[0], height[0], pixels, true);
        }
    }

    public int width() {
        return this.width;
    }

    public int height() {
        return this.height;
    }

    public ByteBuffer pixels() {
        return this.pixels;
    }

    public NativeImage resize(int targetWidth, int targetHeight) {
        NativeImage target = allocate(targetWidth, targetHeight);
        ByteBuffer result = STBImageResize.stbir_resize_uint8_srgb(
            this.pixels, this.width, this.height, this.width * 4, target.pixels, targetWidth, targetHeight, target.width * 4, 8
        );
        if (result == null) {
            target.close();
            throw new IllegalStateException("Failed to resize image to %dx%d".formatted(targetWidth, targetHeight));
        } else {
            return target;
        }
    }

    public boolean writePng(String path, boolean flipVertically) {
        ZomboidFileSystem.instance.validatePrefix(path);
        String absPath = new File(path).getAbsolutePath();

        try {
            STBImageWrite.stbi_flip_vertically_on_write(flipVertically);
            if (!STBImageWrite.stbi_write_png(absPath, this.width, this.height, 4, this.pixels, this.width * 4)) {
                throw new IllegalStateException("Failed to save image %s: %s".formatted(absPath, STBImage.stbi_failure_reason()));
            } else {
                return true;
            }
        } catch (Exception var5) {
            ExceptionLogger.logException(var5);
            return false;
        }
    }

    public boolean writePng(OutputStream stream, boolean flipVertically) {
        try {
            boolean var4;
            try (STBIWriteCallback callback = STBIWriteCallback.create((context, data, size) -> {
                    byte[] bytes = new byte[size];
                    STBIWriteCallback.getData(data, size).get(bytes);

                    try {
                        stream.write(bytes);
                    } catch (IOException var8x) {
                        throw new UncheckedIOException("Failed writing image bytes to stream", var8x);
                    }
                })) {
                STBImageWrite.stbi_flip_vertically_on_write(flipVertically);
                if (STBImageWrite.nstbi_write_png_to_func(
                        callback.address(), 0L, this.width, this.height, 4, MemoryUtil.memAddress(this.pixels), this.width * 4
                    )
                    == 0) {
                    throw new IllegalStateException("Failed to encode image: %s".formatted(STBImage.stbi_failure_reason()));
                }

                var4 = true;
            }

            return var4;
        } catch (Exception var8) {
            ExceptionLogger.logException(var8);
            return false;
        }
    }

    @Override
    public void close() {
        if (this.pixels != null) {
            if (this.loadedByStb) {
                STBImage.stbi_image_free(this.pixels);
            } else {
                MemoryUtil.memFree(this.pixels);
            }

            this.pixels = null;
        }
    }
}
