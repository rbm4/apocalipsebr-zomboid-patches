package cloud.apocalipse.pz.agent.features.postgres.dictionary;

import java.util.Objects;
import java.util.UUID;

/** Exact immutable dictionary identity; never substitute the latest view for existing bytes. */
public record DictionaryOrigin(int serverId, UUID namespaceId, UUID viewId) implements java.io.Serializable {
    public DictionaryOrigin {
        if (serverId < 1) throw new IllegalArgumentException("Dictionary server ID must be positive");
        Objects.requireNonNull(namespaceId); Objects.requireNonNull(viewId);
    }
    public static DictionaryOrigin of(Object server, Object namespace, Object view) {
        if (server == null && namespace == null && view == null) return null;
        if (server == null || namespace == null || view == null) throw new IllegalArgumentException("Incomplete payload provenance");
        return new DictionaryOrigin(((Number) server).intValue(), UUID.fromString(namespace.toString()), UUID.fromString(view.toString()));
    }
}
