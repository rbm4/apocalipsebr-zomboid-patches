// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import zombie.core.Core;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;

public final class ChunkMapFilenames {
    public static ChunkMapFilenames instance = new ChunkMapFilenames();
    private File dirFile;
    private String cacheDir;
    private final HashSet<Integer> wxFolders = new HashSet<>();

    public ChunkMapFilenames() {
        this.cacheDir = ZomboidFileSystem.instance.getGameModeCacheDir();
        File[] directories = ZomboidFileSystem.listAllDirectories(
            this.cacheDir + File.separator + Core.gameSaveWorld + File.separator + "map", file -> true, false
        );

        for (File dir : directories) {
            try {
                this.wxFolders.add(Integer.valueOf(dir.getName()));
            } catch (Exception var7) {
            }
        }
    }

    public void clear() {
        this.dirFile = null;
        this.cacheDir = null;
        this.wxFolders.clear();
    }

    public File getFilename(int wx, int wy) {
        if (this.cacheDir == null) {
            this.cacheDir = ZomboidFileSystem.instance.getGameModeCacheDir();
        }

        if (this.wxFolders.add(wx)) {
            try {
                Files.createDirectories(Path.of(this.cacheDir + File.separator + Core.gameSaveWorld + File.separator + "map" + File.separator + wx));
            } catch (IOException var4) {
                DebugType.General.printException(var4, "", LogSeverity.Error);
            }
        }

        String filename = this.cacheDir + File.separator + Core.gameSaveWorld + File.separator + "map" + File.separator + wx + File.separator + wy + ".bin";
        return new File(filename);
    }

    public File getDir(String gameSaveWorld) {
        if (this.cacheDir == null) {
            this.cacheDir = ZomboidFileSystem.instance.getGameModeCacheDir();
        }

        if (this.dirFile == null) {
            this.dirFile = new File(this.cacheDir, "map" + File.separator + gameSaveWorld);
        }

        return this.dirFile;
    }

    public String getHeader(int wX, int wY) {
        return wX + "_" + wY + ".lotheader";
    }
}
