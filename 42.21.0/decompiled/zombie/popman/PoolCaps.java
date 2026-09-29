// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.popman;

import se.krka.kahlua.stdlib.StringLib;
import zombie.iso.IsoChunkLevel;
import zombie.iso.IsoChunkMap;
import zombie.iso.IsoGridSquare;
import zombie.iso.sprite.IsoSpriteInstance;
import zombie.network.GameServer;

public final class PoolCaps {
    private static final int SQUARE_CACHE_BASE = 24576;
    private static final int SQUARE_CACHE_PER_PLAYER = 4096;
    private static final int SQUARE_CACHE_LIMIT = 49152;
    private static final int CHUNK_STORE_BASE = 1024;
    private static final int CHUNK_STORE_PER_PLAYER = 256;
    private static final int CHUNK_STORE_LIMIT = 4096;
    private static final int CHUNK_LEVEL_BASE = 4096;
    private static final int CHUNK_LEVEL_PER_PLAYER = 1024;
    private static final int CHUNK_LEVEL_LIMIT = 12288;
    private static final int SPRITE_INSTANCE_BASE = 8192;
    private static final int SPRITE_INSTANCE_PER_PLAYER = 2048;
    private static final int SPRITE_INSTANCE_LIMIT = 32768;
    private static int appliedPlayers = -1;

    private PoolCaps() {
    }

    public static void onLoadingFinished() {
        StringLib.onLoadingFinished();
    }

    public static void updateServerCaps() {
        int players = GameServer.Players.size();
        if (players != appliedPlayers) {
            appliedPlayers = players;
            IsoGridSquare.isoGridSquareCache.setMaxSize(scale(24576, 4096, players, 49152));
            IsoChunkMap.chunkStore.setMaxSize(scale(1024, 256, players, 4096));
            IsoChunkLevel.pool.setMaxSize(scale(4096, 1024, players, 12288));
            IsoSpriteInstance.pool.setMaxSize(scale(8192, 2048, players, 32768));
        }
    }

    private static int scale(int base, int perPlayer, int players, int limit) {
        return Math.min(base + perPlayer * players, limit);
    }
}
