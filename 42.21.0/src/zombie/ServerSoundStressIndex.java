package zombie;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import zombie.WorldSoundManager.WorldSound;
import zombie.iso.IsoUtils;
import zombie.iso.IsoWorld;
import zombie.util.list.MutationTrackedArrayList;

/** Preserve stress-sound order and the original two-dimensional Manhattan test. */
public final class ServerSoundStressIndex {
    private record Entry(WorldSound sound, int order) {}
    private final HashMap<Long, ArrayList<Entry>> buckets = new HashMap<>();
    private final ArrayList<Entry> stressSounds = new ArrayList<>(), candidates = new ArrayList<>();
    private long frame = Long.MIN_VALUE, revision = Long.MIN_VALUE;
    private int maxRadius;
    private boolean negativeRadius;
    private static int coordinate(int value) { return Math.floorDiv(value, 16); }
    private static long key(int x, int y) { return ((long)x << 32) | (y & 0xffffffffL); }
    public void invalidate() { frame = Long.MIN_VALUE; }
    public float query(List<WorldSound> sounds, int x, int y) {
        long currentFrame = IsoWorld.instance.getFrameNo();
        long version = ((MutationTrackedArrayList<WorldSound>)sounds).mutationVersion();
        if (frame != currentFrame || revision != version) {
            buckets.clear();
            stressSounds.clear();
            maxRadius = 0;
            negativeRadius = false;
            for (int i = 0; i < sounds.size(); i++) {
                WorldSound sound = sounds.get(i);
                if (!sound.stresshumans || sound.radius == 0) continue;
                Entry entry = new Entry(sound, i);
                stressSounds.add(entry);
                negativeRadius |= sound.radius < 0;
                maxRadius = Math.max(maxRadius, sound.radius);
                buckets.computeIfAbsent(key(coordinate(sound.x), coordinate(sound.y)), k -> new ArrayList<>()).add(entry);
            }
            frame = currentFrame;
            revision = version;
            ApocBRServerTelemetryLite.count("players.soundStress.discoveryEntries", sounds.size());
        }
        long minX = Math.floorDiv((long)x - maxRadius, 16), maxX = Math.floorDiv((long)x + maxRadius, 16);
        long minY = Math.floorDiv((long)y - maxRadius, 16), maxY = Math.floorDiv((long)y + maxRadius, 16);
        long area = (maxX - minX + 1) * (maxY - minY + 1);
        List<Entry> selected = stressSounds;
        candidates.clear();
        // Bound grid work for very large sound radii/small lists; negative radii
        // retain vanilla's unrestricted/clamped contribution.
        if (!negativeRadius && area <= (long)stressSounds.size() * 4) {
            for (long bx = minX; bx <= maxX; bx++) {
                for (long by = minY; by <= maxY; by++) {
                    ArrayList<Entry> bucket = buckets.get(key((int)bx, (int)by));
                    if (bucket != null) candidates.addAll(bucket);
                }
            }
            candidates.sort(Comparator.comparingInt(Entry::order));
            selected = candidates;
        }
        float result = 0.0F;
        for (Entry entry : selected) {
            WorldSound sound = entry.sound();
            float distance = IsoUtils.DistanceManhatten(x, y, sound.x, sound.y);
            float delta = 1.0F - distance / sound.radius;
            if (!(delta <= 0.0F)) {
                if (delta > 1.0F) delta = 1.0F;
                result += delta * sound.stressMod;
            }
        }
        ApocBRServerTelemetryLite.count("players.soundStress.candidatesVisited", selected.size());
        candidates.clear();
        return result;
    }
}
