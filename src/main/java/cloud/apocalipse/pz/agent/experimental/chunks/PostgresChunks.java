package cloud.apocalipse.pz.agent.experimental.chunks;

import cloud.apocalipse.pz.agent.features.postgres.PostgresRuntime;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.sql.SQLException;
import zombie.iso.IsoChunk;
import zombie.network.ClientChunkRequest;

/** Stores the native save payload. The vanilla load, save and download workers own the calls. */
public final class PostgresChunks {
    private static String dataset;

    private PostgresChunks() {
    }

    static void initialize(String configuredDataset) {
        dataset = configuredDataset;
    }

    public static boolean loadChunk(IsoChunk chunk, int wx, int wy, ByteBuffer pending) throws IOException {
        ByteBuffer payload = pending == null ? read(wx, wy) : pending;
        return chunk.LoadChunk(wx, wy, payload);
    }

    /** The native download buffer branch expects its position at the end, like a freshly saved chunk. */
    public static ByteBuffer downloadBuffer(ClientChunkRequest.Chunk chunk) throws IOException {
        if (chunk.bb == null) {
            ByteBuffer payload = read(chunk.wx, chunk.wy);
            if (payload != null) {
                chunk.bb = payload.position(payload.limit());
            }
        }
        return chunk.bb;
    }

    private static ByteBuffer read(int wx, int wy) throws IOException {
        try (var connection = PostgresRuntime.borrowBackground();
             var query = connection.prepareStatement("""
                 SELECT payload FROM apocbr_world.map_chunks WHERE dataset = ? AND wx = ? AND wy = ?
                 """)) {
            query.setString(1, dataset);
            query.setInt(2, wx);
            query.setInt(3, wy);
            try (var rows = query.executeQuery()) {
                if (!rows.next()) return null;
                ByteBuffer payload = ByteBuffer.wrap(rows.getBytes(1));
                if (!IsoChunk.validateByteBufferHeader(payload)) {
                    throw new IOException("Invalid PostgreSQL chunk payload at " + wx + "," + wy);
                }
                return payload;
            }
        } catch (SQLException failure) {
            throw unavailable("read", wx, wy, failure);
        }
    }

    public static void writeChunk(Object request) throws IOException {
        var chunk = (ClientChunkRequest.Chunk) request;
        ByteBuffer source = chunk.bb.duplicate().flip();
        byte[] payload = new byte[source.remaining()];
        source.get(payload);
        try (var connection = PostgresRuntime.borrowBackground();
             var write = connection.prepareStatement("""
                 INSERT INTO apocbr_world.map_chunks AS previous (dataset, wx, wy, payload)
                 VALUES (?, ?, ?, ?)
                 ON CONFLICT (dataset, wx, wy) DO UPDATE SET payload = EXCLUDED.payload
                 WHERE previous.payload IS DISTINCT FROM EXCLUDED.payload
                 """)) {
            write.setString(1, dataset);
            write.setInt(2, chunk.wx);
            write.setInt(3, chunk.wy);
            write.setBytes(4, payload);
            write.executeUpdate();
            connection.commit();
        } catch (SQLException failure) {
            throw unavailable("write", chunk.wx, chunk.wy, failure);
        }
    }

    private static IOException unavailable(String operation, int wx, int wy, SQLException failure) {
        String state = failure.getSQLState();
        if (state == null || !state.matches("[A-Z0-9]{5}")) state = "HY000";
        return new IOException("PostgreSQL chunk " + operation + " failed at " + wx + "," + wy + "; SQLState=" + state);
    }
}
