package zombie.savefile;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import zombie.network.GameServer;

/** PostgreSQL implementation of the native multiplayer-player storage contract. */
public final class ApocBRPlayerDatabase {
    @FunctionalInterface public interface ConnectionFactory { Connection open() throws SQLException; }
    private final ConnectionFactory connections;
    public ApocBRPlayerDatabase() { this(ApocBRPostgresConnector::borrowPlayerConnection); }
    ApocBRPlayerDatabase(ConnectionFactory connections) { this.connections = connections; }

    public static boolean enabled() {
        return GameServer.server && Boolean.parseBoolean(System.getProperty("apocbr.postgres.players.enabled", "false"));
    }

    public record Profile(int id, float x, float y, float z, byte[] data, int worldVersion,
                          boolean dead, String splitUsername, ApocBRPayloadProvenance origin) {
        public Profile(int id,float x,float y,float z,byte[] data,int worldVersion,boolean dead,String splitUsername) {
            this(id,x,y,z,data,worldVersion,dead,splitUsername,null);
        }
        public Profile { if (data != null) data = data.clone(); }
        @Override public byte[] data() { return data == null ? null : data.clone(); }
    }

    private static PreparedStatement prepare(Connection connection, String sql) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        try { statement.setQueryTimeout(2); return statement; }
        catch (SQLException failure) { statement.close(); throw failure; }
    }
    private static SQLException safe(String operation, SQLException error) {
        return new SQLException("PostgreSQL players " + operation + " failed; SQLState=" + error.getSQLState(),
                error.getSQLState(), error.getErrorCode());
    }

    public void validate() throws SQLException {
        try (Connection connection = connections.open(); PreparedStatement statement = prepare(connection,
                "SELECT id,world,username,playerindex,name,steamid,x,y,z,worldversion,data,isdead,splitusername,source_server_id,source_dictionary_namespace_id,source_dictionary_view_id FROM public.networkplayers WHERE false");
             ResultSet ignored = statement.executeQuery()) { connection.commit(); }
        catch (SQLException failure) { throw safe("schema validation", failure); }
    }

    public Profile load(String world, String identity, int slot, boolean steamCoop) throws SQLException {
        if (slot < 0 || slot > 3) return null;
        String query = "SELECT id,x,y,z,data,worldversion,isdead,splitusername,source_server_id,source_dictionary_namespace_id,source_dictionary_view_id FROM public.networkplayers WHERE "
                + (steamCoop ? "steamid" : "username") + "=? AND world=? AND playerindex=?";
        try (Connection connection = connections.open(); PreparedStatement statement = prepare(connection,query)) {
            statement.setString(1,identity); statement.setString(2,world); statement.setInt(3,slot);
            Profile profile = null;
            try (ResultSet rows = statement.executeQuery()) {
                if (rows.next()) {
                    profile = new Profile(rows.getInt(1),rows.getFloat(2),rows.getFloat(3),rows.getFloat(4),rows.getBytes(5),
                            rows.getInt(6),rows.getBoolean(7),rows.getString(8),ApocBRPayloadProvenance.read(rows,9));
                    if (rows.next()) throw new SQLException("Ambiguous player identity", "21000");
                }
            }
            connection.commit(); return profile;
        } catch (SQLException failure) { throw safe("load", failure); }
    }

    public int convert(String world,String username,String steamid) throws SQLException {
        return update("UPDATE public.networkplayers SET steamid=? WHERE username=? AND world=? AND (steamid IS NULL OR steamid='')",
                steamid,username,world);
    }
    public void delete(String world,String identity,int slot,boolean steamCoop) throws SQLException {
        update("DELETE FROM public.networkplayers WHERE " + (steamCoop ? "steamid" : "username")
                + "=? AND world=? AND playerindex=?",identity,world,slot);
    }
    public void removeUser(String world,String username) throws SQLException {
        update("DELETE FROM public.networkplayers WHERE username=? AND world=?",username,world);
    }
    private int update(String query,Object... arguments) throws SQLException {
        try (Connection connection = connections.open()) {
            try (PreparedStatement statement = prepare(connection,query)) {
                for (int i=0;i<arguments.length;i++) {
                    if (arguments[i] instanceof Integer number) statement.setInt(i+1,number);
                    else statement.setString(i+1,(String)arguments[i]);
                }
                int changed = statement.executeUpdate(); connection.commit(); return changed;
            } catch (SQLException failure) {
                try { connection.rollback(); } catch (SQLException ignored) { }
                throw failure;
            }
        } catch (SQLException failure) { throw safe("update", failure); }
    }
    public boolean containsUser(String world,String username) throws SQLException {
        try (Connection connection = connections.open(); PreparedStatement statement = prepare(connection,
                "SELECT id FROM public.networkplayers WHERE username=? AND world=?")) {
            statement.setString(1,username); statement.setString(2,world);
            boolean found; try (ResultSet rows=statement.executeQuery()) { found=rows.next(); }
            connection.commit(); return found;
        } catch (SQLException failure) { throw safe("existence lookup", failure); }
    }
    public String steamId(String world,String username) throws SQLException {
        try (Connection connection = connections.open(); PreparedStatement statement = prepare(connection,
                "SELECT steamid FROM public.networkplayers WHERE world=? AND username=?")) {
            statement.setString(1,world); statement.setString(2,username);
            String result; try (ResultSet rows=statement.executeQuery()) { result=rows.next()?rows.getString(1):null; }
            connection.commit(); return result;
        } catch (SQLException failure) { throw safe("Steam lookup", failure); }
    }
    public int countOtherAccounts(String steamid,String username) throws SQLException {
        try (Connection connection = connections.open(); PreparedStatement statement = prepare(connection,
                "SELECT COUNT(id) FROM public.networkplayers WHERE steamid=? AND username<>?")) {
            statement.setString(1,steamid); statement.setString(2,username);
            int count; try (ResultSet rows=statement.executeQuery()) { count=rows.next()?rows.getInt(1):0; }
            connection.commit(); return count;
        } catch (SQLException failure) { throw safe("account count", failure); }
    }
}
