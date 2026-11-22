// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.popman;

import gnu.trove.map.hash.TIntObjectHashMap;
import zombie.characters.IsoZombie;

public final class RealZombieMap {
    private final TIntObjectHashMap<IsoZombie> zombiesById = new TIntObjectHashMap<>();

    public void register(IsoZombie zombie, int persistentId) {
        if (persistentId != 0) {
            this.zombiesById.put(persistentId, zombie);
        }
    }

    public boolean unregister(IsoZombie zombie) {
        if (this.zombiesById.get(zombie.persistentId) != zombie) {
            return false;
        } else {
            this.zombiesById.remove(zombie.persistentId);
            return true;
        }
    }

    public boolean isLive(int persistentId) {
        return persistentId != 0 && this.zombiesById.containsKey(persistentId);
    }

    public void clear() {
        this.zombiesById.clear();
    }
}
