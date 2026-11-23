package cloud.apocalipse.pz.agent.features.postgres.dictionary;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Out-of-band identity of existing bytes; content equality must never imply common origin. */
public final class PayloadProvenance {
    public record Metadata(DictionaryOrigin origin, Integer worldVersion) implements java.io.Serializable {
        public Metadata { if (worldVersion != null && worldVersion < 1) throw new IllegalArgumentException("Invalid payload world version"); }
    }
    private static final ReferenceQueue<Object> COLLECTED = new ReferenceQueue<>();
    private static final Map<Identity, Metadata> PAYLOADS = new HashMap<>();
    private static final class Identity extends WeakReference<Object> {
        private final int hash;
        Identity(Object value) { super(value, COLLECTED); hash = System.identityHashCode(value); }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object other) {
            return this == other || other instanceof Identity key && get() != null && get() == key.get();
        }
    }
    private static void clean() { Identity key; while ((key = (Identity) COLLECTED.poll()) != null) PAYLOADS.remove(key); }
    public static synchronized Metadata metadata(Object bytes) {
        clean(); return bytes == null ? null : PAYLOADS.get(new Identity(bytes));
    }
    /** A null metadata removes a previous association when a pooled payload is reused. */
    public static synchronized void remember(Object bytes, Metadata metadata) {
        clean(); Objects.requireNonNull(bytes);
        if (metadata == null) PAYLOADS.remove(new Identity(bytes)); else PAYLOADS.put(new Identity(bytes), metadata);
    }
    public static void copy(Object source, Object target) { remember(target, metadata(source)); }
    static synchronized void clear() { PAYLOADS.clear(); while (COLLECTED.poll() != null) { } }
    private PayloadProvenance() { }
}
