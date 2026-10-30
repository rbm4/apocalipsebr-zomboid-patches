// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.network.characters;

import java.util.ArrayList;
import java.util.List;

public class AttackRateChecker {
    private static final int SMOOTH_INTERVAL_COUNT = 3;
    private final List<Long> entries = new ArrayList<>();

    public void reset() {
        this.entries.clear();
    }

    public boolean check(int maxProjectiles, long maxTimeMs, int hitCount) {
        long currentTimeNs = System.nanoTime();
        long periodNs = maxTimeMs * 1000000L * 3L;
        this.entries.removeIf(timeStampNs -> timeStampNs < currentTimeNs - periodNs);

        for (int i = 0; i < hitCount; i++) {
            this.entries.add(currentTimeNs);
        }

        return this.entries.size() > maxProjectiles * 3;
    }
}
