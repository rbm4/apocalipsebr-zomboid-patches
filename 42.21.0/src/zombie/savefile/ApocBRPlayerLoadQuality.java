package zombie.savefile;

import java.util.ArrayList;
import java.util.List;

/** Scoped to a native player decode; ordinary item/world loads allocate nothing. */
public final class ApocBRPlayerLoadQuality implements AutoCloseable {
    private static final ThreadLocal<ApocBRPlayerLoadQuality> ACTIVE = new ThreadLocal<>();
    private final ApocBRPlayerLoadQuality previous;
    private final ArrayList<String> issues = new ArrayList<>();
    private ApocBRPlayerLoadQuality() { previous=ACTIVE.get(); ACTIVE.set(this); }
    public static ApocBRPlayerLoadQuality begin() { return new ApocBRPlayerLoadQuality(); }
    public static void issue(String code) {
        ApocBRPlayerLoadQuality scope=ACTIVE.get();
        if (scope!=null && scope.issues.size()<32 && !scope.issues.contains(code)) scope.issues.add(code);
    }
    public List<String> issues() { return List.copyOf(issues); }
    @Override public void close() { if (previous==null) ACTIVE.remove(); else ACTIVE.set(previous); }
}
