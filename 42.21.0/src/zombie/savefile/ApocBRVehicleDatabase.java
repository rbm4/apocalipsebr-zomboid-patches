package zombie.savefile;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** PostgreSQL SQL operations only; vehicle capture and world integration remain native. */
public final class ApocBRVehicleDatabase {
    @FunctionalInterface
    public interface ConnectionFactory { Connection open() throws SQLException; }
    private final ConnectionFactory connections;

    public ApocBRVehicleDatabase() { this(ApocBRPostgresConnector::borrowVehicleConnection); }
    ApocBRVehicleDatabase(ConnectionFactory connections) { this.connections = connections; }

    public static boolean enabled() {
        return Boolean.parseBoolean(System.getProperty("apocbr.postgres.vehicles.enabled",
                System.getProperty("apocbr.postgres.enabled", "false")));
    }

    public record Location(int id, int wx, int wy) {}
    public record Vehicle(int id, int wx, int wy, float x, float y, int worldVersion,
                          boolean inMeta, byte[] data) {
        public Vehicle { if (data != null) data = data.clone(); }
        @Override public byte[] data() { return data == null ? null : data.clone(); }
    }

    private static PreparedStatement prepare(Connection connection, String sql) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        try { statement.setQueryTimeout(2); return statement; }
        catch (SQLException failure) { statement.close(); throw failure; }
    }

    private static SQLException safe(String operation, SQLException failure) {
        return new SQLException("PostgreSQL vehicles " + operation + " failed; SQLState="
                + failure.getSQLState(), failure.getSQLState(), failure.getErrorCode());
    }

    public List<Location> initialize() throws SQLException {
        try (Connection connection = connections.open()) {
            try (PreparedStatement statement = prepare(connection,
                    "SELECT id,wx,wy,x,y,inmeta,worldversion,data FROM public.vehicles WHERE false");
                 ResultSet ignored = statement.executeQuery()) { }
            List<Location> locations = new ArrayList<>();
            try (PreparedStatement statement = prepare(connection,"SELECT wx,wy,id FROM public.vehicles");
                 ResultSet rows = statement.executeQuery()) {
                while (rows.next()) locations.add(new Location(rows.getInt(3), rows.getInt(1), rows.getInt(2)));
            }
            connection.commit();
            return locations;
        } catch (SQLException failure) { throw safe("initialization", failure); }
    }

    public List<Vehicle> loadChunk(int wx, int wy) throws SQLException { return load(wx, wy, false); }
    public List<Vehicle> loadMeta() throws SQLException { return load(0, 0, true); }

    private List<Vehicle> load(int wx, int wy, boolean meta) throws SQLException {
        String sql = "SELECT id,wx,wy,x,y,data,worldversion,inmeta FROM public.vehicles WHERE "
                + (meta ? "inmeta=TRUE" : "wx=? AND wy=?");
        try (Connection connection = connections.open(); PreparedStatement statement = prepare(connection, sql)) {
            if (!meta) { statement.setInt(1, wx); statement.setInt(2, wy); }
            List<Vehicle> vehicles = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) vehicles.add(new Vehicle(rows.getInt(1), rows.getInt(2), rows.getInt(3),
                        rows.getFloat(4), rows.getFloat(5), rows.getInt(7), rows.getBoolean(8), rows.getBytes(6)));
            }
            connection.commit();
            // Connections are returned before native load callbacks run (which may delete a bad vehicle).
            return vehicles;
        } catch (SQLException failure) { throw safe("load", failure); }
    }

    public void save(Vehicle vehicle) throws SQLException {
        if (vehicle.id < 1 || vehicle.data == null || vehicle.data.length == 0 || vehicle.data.length > 2_097_152) {
            throw new SQLException("Invalid vehicle snapshot", "22023");
        }
        try (Connection connection = connections.open()) {
            try {
                boolean exists;
                try (PreparedStatement lookup = prepare(connection,"SELECT 1 FROM public.vehicles WHERE id=?")) {
                    lookup.setInt(1,vehicle.id);
                    try (ResultSet rows = lookup.executeQuery()) { exists = rows.next(); }
                }
                String sql = exists
                        ? "UPDATE public.vehicles SET wx=?,wy=?,x=?,y=?,worldversion=?,inmeta=?,data=? WHERE id=?"
                        : "INSERT INTO public.vehicles(wx,wy,x,y,worldversion,inmeta,data,id) VALUES(?,?,?,?,?,?,?,?)";
                try (PreparedStatement statement = prepare(connection,sql)) {
                    statement.setInt(1,vehicle.wx); statement.setInt(2,vehicle.wy);
                    statement.setFloat(3,vehicle.x); statement.setFloat(4,vehicle.y);
                    statement.setInt(5,vehicle.worldVersion); statement.setBoolean(6,vehicle.inMeta);
                    statement.setBytes(7,vehicle.data); statement.setInt(8,vehicle.id);
                    if (statement.executeUpdate()!=1) throw new SQLException("Unexpected affected vehicle rows", "P0001");
                }
                connection.commit();
            } catch (SQLException failure) {
                try { connection.rollback(); } catch (SQLException ignored) { }
                throw failure;
            }
        } catch (SQLException failure) { throw safe("save", failure); }
    }

    public void remove(int id) throws SQLException {
        if (id < 1) return;
        try (Connection connection = connections.open()) {
            try (PreparedStatement statement = prepare(connection,"DELETE FROM public.vehicles WHERE id=?")) {
                statement.setInt(1,id); statement.executeUpdate(); connection.commit();
            } catch (SQLException failure) {
                try { connection.rollback(); } catch (SQLException ignored) { }
                throw failure;
            }
        } catch (SQLException failure) { throw safe("delete", failure); }
    }
}
