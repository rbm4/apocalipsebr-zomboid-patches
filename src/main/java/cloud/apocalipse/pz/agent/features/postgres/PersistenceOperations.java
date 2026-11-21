package cloud.apocalipse.pz.agent.features.postgres;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** Detached account DTOs retain the legacy serialized field layout and receipt identity. */
public final class PersistenceOperations {
    private PersistenceOperations() { }

    public record Sql(String sql, List<Object> parameters) implements Serializable {
        public Sql {
            for (Object value : parameters) {
                if (value != null && !(value instanceof String || value instanceof Boolean || value instanceof Byte
                        || value instanceof Short || value instanceof Integer || value instanceof Long
                        || value instanceof Float || value instanceof Double || value instanceof UUID)) {
                    throw new IllegalArgumentException("Unsupported journal parameter type");
                }
            }
            parameters = Collections.unmodifiableList(new ArrayList<>(parameters));
        }
    }

    public record Account(List<Sql> statements) implements PersistenceJournal.Operation {
        public Account { statements = List.copyOf(statements); }
        public String kind() { return "account-write-v1"; }
        public long estimatedBytes() {
            long size = 256;
            for (Sql sql : statements) {
                size += sql.sql().length() * 2L + 128;
                for (Object value : sql.parameters()) size += value instanceof String text ? text.length() * 2L + 32 : 64;
            }
            return size;
        }
    }
}
