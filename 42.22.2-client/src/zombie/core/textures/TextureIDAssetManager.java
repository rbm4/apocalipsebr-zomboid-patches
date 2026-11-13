// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.core.textures;

import zombie.asset.Asset;
import zombie.asset.AssetManager;
import zombie.asset.AssetPath;
import zombie.asset.AssetTask;
import zombie.asset.AssetTask_RunFileTask;
import zombie.asset.FileTask_LoadImageData;
import zombie.asset.FileTask_LoadPackImage;
import zombie.core.opengl.RenderThread;
import zombie.core.utils.DirectBufferAllocator;
import zombie.fileSystem.FileSystem;
import zombie.fileSystem.FileTask;

public final class TextureIDAssetManager extends AssetManager {
    public static final TextureIDAssetManager instance = new TextureIDAssetManager();

    @Override
    protected void startLoading(Asset asset) {
        TextureID textureID = (TextureID)asset;
        FileSystem fs = this.getOwner().getFileSystem();
        if (textureID.assetParams != null && textureID.assetParams.subTexture != null) {
            FileSystem.SubTexture subTex = textureID.assetParams.subTexture;
            FileTask fileTask = new FileTask_LoadPackImage(subTex.packName, subTex.pageName, fs, result -> this.onFileTaskFinished(asset, result));
            fileTask.setPriority(7);
            AssetTask assetTask = new AssetTask_RunFileTask(fileTask, asset);
            this.setTask(asset, assetTask);
            assetTask.execute();
        } else {
            int flags = textureID.assetParams == null ? 0 : textureID.assetParams.flags;
            int maxSize = (flags & 384) == 0 ? 0 : zombie.core.Core.getInstance().getMaxTextureSizeFromFlags(flags);
            FileTask fileTask;
            if (ApocBRTexturePreprocessor.ENABLED && maxSize > 0 && !zombie.network.GameServer.server) {
                ApocBRTextureLoadTask.Plan plan = new ApocBRTextureLoadTask.Plan(flags, maxSize,
                    zombie.debug.DebugOptions.instance.isoSprite.worldMipmapColors.getValue());
                fileTask = new ApocBRTextureLoadTask(asset.getPath().getPath(), fs, plan,
                    (task, image) -> this.onPreparedFileTaskFinished(asset, task.plan(), image));
            } else {
                fileTask = new FileTask_LoadImageData(asset.getPath().getPath(), fs, result -> this.onFileTaskFinished(asset, result));
            }
            fileTask.setPriority(7);
            AssetTask assetTask = new AssetTask_RunFileTask(fileTask, asset);
            this.setTask(asset, assetTask);
            assetTask.execute();
        }
    }

    @Override
    protected void unloadData(Asset asset) {
        TextureID textureID = (TextureID)asset;
        if (!textureID.isDestroyed()) {
            RenderThread.invokeOnRenderContext(textureID::destroy);
        }
    }

    @Override
    protected Asset createAsset(AssetPath path, AssetManager.AssetParams params) {
        return new TextureID(path, this, (TextureID.TextureIDAssetParams)params);
    }

    @Override
    protected void destroyAsset(Asset asset) {
    }

    private void onFileTaskFinished(Asset asset, Object result) {
        TextureID textureID = (TextureID)asset;
        if (result instanceof ImageData imageData) {
            textureID.setImageData(imageData);
            long apocProbe328 = zombie.ApocBRClientTelemetryLite.beginPhase();
            try {
                this.onLoadingSucceeded(asset);
            } finally {
                zombie.ApocBRClientTelemetryLite.endPhase(zombie.ApocBRClientTelemetryLite.Phase.TEXTURE_ASSET_SUCCESS, apocProbe328);
            }
        } else {
            this.onLoadingFailed(asset);
        }
    }

    public void waitFileTask() {
        while (DirectBufferAllocator.getBytesAllocated() > 52428800L) {
            try {
                Thread.sleep(20L);
            } catch (InterruptedException var2) {
            }
        }
    }

    /** Runs on the existing asset-completion owner, never the decode worker. */
    private void onPreparedFileTaskFinished(Asset asset, ApocBRTextureLoadTask.Plan plan, ImageData image) {
        if (image == null) { this.onLoadingFailed(asset); return; }
        TextureID textureID = (TextureID)asset;
        int flags = textureID.assetParams == null ? 0 : textureID.assetParams.flags;
        int maxSize = (flags & 384) == 0 ? 0 : zombie.core.Core.getInstance().getMaxTextureSizeFromFlags(flags);
        boolean debugColors = zombie.debug.DebugOptions.instance.isoSprite.worldMipmapColors.getValue();
        if (flags != plan.flags() || maxSize != plan.maxSize() || debugColors != plan.debugColors()) {
            ApocBRTexturePreprocessor.disposeOwned(image);
            zombie.ApocBRClientTelemetryLite.workerTexture(zombie.ApocBRClientTelemetryLite.WorkerTexture.SETTINGS_RELOADS, 1L);
            // A smaller decoded result cannot recover pixels when the user raises the limit.
            this.setTask(asset, null);
            this.startLoading(asset);
            return;
        }
        boolean adopted = false;
        try {
            textureID.setImageData(image);
            adopted = true;
            long started = zombie.ApocBRClientTelemetryLite.beginPhase();
            try { this.onLoadingSucceeded(asset); }
            finally { zombie.ApocBRClientTelemetryLite.endPhase(zombie.ApocBRClientTelemetryLite.Phase.TEXTURE_ASSET_SUCCESS, started); }
        } finally {
            if (!adopted && textureID.getImageData() != image && image.data != null) ApocBRTexturePreprocessor.disposeOwned(image);
        }
    }
}
