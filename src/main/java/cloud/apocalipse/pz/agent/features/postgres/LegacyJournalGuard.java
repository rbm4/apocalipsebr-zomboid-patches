package cloud.apocalipse.pz.agent.features.postgres;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;
import java.util.zip.CRC32C;

/** Read-only cutover guard until the complete replay coordinator and all legacy operation types are ported. */
final class LegacyJournalGuard {
    private LegacyJournalGuard() { }

    static void requireDrained(Path directory) throws IOException {
        if (!Files.exists(directory)) return;
        Path checkpoint=directory.resolve("checkpoint");
        if (!Files.isRegularFile(checkpoint)) {
            try (var entries=Files.list(directory)) {
                if (entries.anyMatch(p -> p.toString().endsWith(".log"))) {
                    throw new IOException("PostgreSQL journal has no valid checkpoint; recovery is required before account-hook cutover");
                }
            }
            return;
        }
        if (Files.size(checkpoint) != 32) throw new IOException("Invalid PostgreSQL journal checkpoint");
        byte[] bytes=Files.readAllBytes(checkpoint);
        if (bytes.length != 32) throw new IOException("Invalid PostgreSQL journal checkpoint");
        ByteBuffer state=ByteBuffer.wrap(bytes);
        UUID generation=new UUID(state.getLong(),state.getLong());
        long acknowledged=state.getLong(),checksum=state.getLong();
        CRC32C crc=new CRC32C(); crc.update(Arrays.copyOf(bytes,24));
        Path log=directory.resolve(generation+".log");
        if (checksum != crc.getValue() || acknowledged<0 || !Files.isRegularFile(log)
                || Files.size(log)<acknowledged) throw new IOException("Invalid PostgreSQL journal checkpoint");
        if (Files.size(log) != acknowledged) {
            throw new IOException("Pending PostgreSQL journal operations require the complete replay migration before account-hook cutover");
        }
    }
}
