package cloud.apocalipse.pz.agent.features.postgres.player;

import cloud.apocalipse.pz.agent.features.postgres.*;
import cloud.apocalipse.pz.agent.features.postgres.dictionary.*;
import java.sql.*;

/** Original native snapshot mirror, detached before leaving the server thread. */
record PlayerNativeSave(String world, String username, String steamid, int playerIndex, String name,
        float x, float y, float z, int worldVersion, boolean dead, String splitUsername, boolean steamCoop,
        byte[] data, DictionaryOrigin origin) implements PlayerJournal.ReplayOperation {
    PlayerNativeSave {
        if (world == null || username == null || playerIndex < 0 || playerIndex > 3 || data == null || data.length == 0 || data.length > PlayerLimits.SOURCE_BYTES)
            throw new IllegalArgumentException("Invalid native player snapshot");
        data = data.clone();
    }
    @Override public byte[] data() { return data.clone(); }
    public String kind() { return "agent-player-native-v1"; }
    public long estimatedBytes() { return data.length + 1024L; }
    public String playerKey() { return PlayerOperations.key(world, steamCoop ? steamid : username, playerIndex, steamCoop); }
    public void apply() throws SQLException {
        try (var connection = PostgresRuntime.borrowBackground()) {
            try {
                if (!PlayerJournal.claim(connection)) { connection.rollback(); return; }
                try (var lock = connection.prepareStatement("LOCK TABLE public.networkplayers IN SHARE ROW EXCLUSIVE MODE")) { lock.setQueryTimeout(2); lock.execute(); }
                Integer id = null;
                try (var query = connection.prepareStatement("SELECT id FROM public.networkplayers WHERE " + (steamCoop ? "steamid" : "username") + "=? AND world=? AND playerindex=?")) {
                    query.setQueryTimeout(2); query.setString(1, steamCoop ? steamid : username); query.setString(2, world); query.setInt(3, playerIndex);
                    try (var rows = query.executeQuery()) { if (rows.next()) { id = rows.getInt(1); if (rows.next()) throw new SQLException("Ambiguous player identity", "21000"); } }
                }
                String fields = "world,username,steamid,playerindex,name,x,y,z,worldversion,isdead,splitusername,data,source_server_id,source_dictionary_namespace_id,source_dictionary_view_id";
                String sql = id == null ? "INSERT INTO public.networkplayers(" + fields + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
                    : "UPDATE public.networkplayers SET " + String.join(",", java.util.Arrays.stream(fields.split(",")).map(field -> field + "=?").toList()) + " WHERE id=?";
                try (var statement = connection.prepareStatement(sql)) {
                    statement.setQueryTimeout(2); statement.setString(1, world); statement.setString(2, username); statement.setString(3, steamid); statement.setInt(4, playerIndex);
                    statement.setString(5, name); statement.setFloat(6, x); statement.setFloat(7, y); statement.setFloat(8, z); statement.setInt(9, worldVersion);
                    statement.setBoolean(10, dead); statement.setString(11, splitUsername); statement.setBytes(12, data);
                    statement.setObject(13, origin == null ? null : origin.serverId(), Types.INTEGER);
                    statement.setObject(14, origin == null ? null : origin.namespaceId(), Types.OTHER);
                    statement.setObject(15, origin == null ? null : origin.viewId(), Types.OTHER);
                    if (id != null) statement.setInt(16, id);
                    if (statement.executeUpdate() != 1) throw new SQLException("Unexpected native snapshot row count", "P0001");
                }
                connection.commit();
            } catch (SQLException | RuntimeException failure) { try { connection.rollback(); } catch (SQLException ignored) { } throw failure; }
        }
    }
}
