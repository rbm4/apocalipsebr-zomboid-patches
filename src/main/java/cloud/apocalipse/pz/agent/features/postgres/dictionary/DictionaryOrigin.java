package cloud.apocalipse.pz.agent.features.postgres.dictionary;

import java.util.Objects;
import java.util.UUID;

/** Exact immutable dictionary identity; never substitute the latest view for existing bytes. */
public record DictionaryOrigin(int serverId, UUID namespaceId, UUID viewId) {
    public DictionaryOrigin {
        if (serverId < 1) throw new IllegalArgumentException("Dictionary server ID must be positive");
        Objects.requireNonNull(namespaceId); Objects.requireNonNull(viewId);
    }
}
