package cloud.apocalipse.pz.agent.features.postgres.player;

import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlayerReaderTest {
    static Object zero(Class<?> type) { if (type == boolean.class) return false; if (type == int.class) return 0; if (type == long.class) return 0L; return null; }
    static final class Jdbc {
        final List<String> events = new ArrayList<>();
        final Map<Integer, Object> bindings = new HashMap<>();
        final List<Map.Entry<String, Map<String, Object>>> rows = new ArrayList<>();
        boolean fail;
        Jdbc() {
            rows.add(Map.entry("snapshot", new HashMap<>(Map.of("status", "complete", "schema_version", 1, "codec_version", 4,
                "source_world_version", 251, "expected_object_count", 0, "expected_item_count", 0, "expected_container_count", 0, "expected_edge_count", 0))));
        }
        Connection open() {
            events.add("open");
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class}, (p, m, a) -> switch (m.getName()) {
                case "prepareStatement" -> {
                    assertEquals(PlayerReader.SQL, a[0]);
                    yield Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PreparedStatement.class}, (statement, method, values) -> {
                        if (method.getName().startsWith("set")) { if (method.getName().equals("setQueryTimeout")) assertEquals(2, values[0]); else bindings.put((Integer) values[0], values[1]); return null; }
                        if (method.getName().equals("executeQuery")) {
                            if (fail) throw new SQLException("PRIVATE_SQL_VALUES", "08006");
                            AtomicInteger index = new AtomicInteger(-1);
                            return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ResultSet.class}, (result, read, args) -> switch (read.getName()) {
                                case "next" -> index.incrementAndGet() < rows.size();
                                case "getString" -> (int) args[0] == 1 ? rows.get(index.get()).getKey() : new JSONObject(rows.get(index.get()).getValue()).toString();
                                default -> zero(read.getReturnType());
                            });
                        }
                        return zero(method.getReturnType());
                    });
                }
                case "commit", "rollback", "close" -> { events.add(m.getName()); yield null; }
                default -> zero(m.getReturnType());
            });
        }
    }
    @Test void oneStatementReadPreservesIdentityModeAndClosesLease() throws Exception {
        var jdbc = new Jdbc();
        var profile = new PlayerReader(jdbc::open).load("world", "identity", 3, true);
        assertNotNull(profile); assertEquals(Map.of(1, "world", 2, "steam_coop", 3, "identity", 4, 3), jdbc.bindings);
        assertEquals(List.of("open", "commit", "close"), jdbc.events);
        assertThrows(UnsupportedOperationException.class, () -> profile.tables().clear());
        assertThrows(UnsupportedOperationException.class, () -> profile.one("snapshot").put("status", "bad"));
        assertTrue(PlayerReader.SQL.contains("selected AS MATERIALIZED"));
        assertTrue(PlayerReader.SQL.contains("LEFT JOIN player_rel.character_head"));
    }
    @Test void absenceIsDifferentFromCorruptOrUnavailableProfile() throws Exception {
        var absent = new Jdbc(); absent.rows.clear(); assertNull(new PlayerReader(absent::open).load("world", "id", 0, false));
        var invalid = new Jdbc(); invalid.rows.getFirst().getValue().put("codec_version", 99);
        var error = assertThrows(SQLException.class, () -> new PlayerReader(invalid::open).load("world", "id", 0, false));
        assertEquals("22000", error.getSQLState()); assertEquals(List.of("open", "rollback", "close"), invalid.events);
        var unavailable = new Jdbc(); unavailable.fail = true;
        error = assertThrows(SQLException.class, () -> new PlayerReader(unavailable::open).load("world", "id", 0, false));
        assertEquals("08006", error.getSQLState()); assertFalse(error.toString().contains("PRIVATE_SQL_VALUES"));
        var nativeOnly=new Jdbc();nativeOnly.rows.clear();nativeOnly.rows.add(Map.entry("native_only",Map.of("present",true)));
        assertThrows(SQLException.class,()->new PlayerReader(nativeOnly::open).load("world","id",0,false));
    }
    @Test void graphCountsAndPartialProvenanceFailClosedAfterReturningGraphLease() {
        var mismatch = new Jdbc(); mismatch.rows.getFirst().getValue().put("expected_item_count", 1);
        assertThrows(SQLException.class, () -> new PlayerReader(mismatch::open).load("world", "id", 0, false));
        var provenance = new Jdbc(); provenance.rows.add(Map.entry("value_node", Map.of("source_server_id", 1)));
        assertThrows(IllegalArgumentException.class, () -> new PlayerReader(provenance::open).load("world", "id", 0, false));
        assertEquals(List.of("open", "commit", "close"), provenance.events);
    }
}
