package cloud.apocalipse.pz.agent.features.postgres;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class LegacyJournalGuardTest {
    @TempDir Path temporary;

    @Test void rejectsPendingOperationsWithoutChangingJournalAndAcceptsDrainedCheckpoint() throws Exception {
        Path directory=temporary.resolve("journal");
        LegacyJournalGuard.requireDrained(directory);
        try (var journal=new PersistenceJournal(directory,4096,message -> {})) {
            var id=journal.submitDurable(new PersistenceOperations.Account(List.of(
                new PersistenceOperations.Sql("UPDATE whitelist SET role=?",List.of(1)))));
            byte[] checkpoint=Files.readAllBytes(directory.resolve("checkpoint"));
            assertThrows(IOException.class,() -> LegacyJournalGuard.requireDrained(directory));
            assertArrayEquals(checkpoint,Files.readAllBytes(directory.resolve("checkpoint")));
            journal.acknowledge(id);
            LegacyJournalGuard.requireDrained(directory);
        }
    }

    @Test void invalidOrMissingCheckpointCannotBeTreatedAsEmpty() throws Exception {
        Path directory=temporary.resolve("journal"); Files.createDirectories(directory);
        Files.write(directory.resolve("orphan.log"),new byte[]{1});
        assertThrows(IOException.class,() -> LegacyJournalGuard.requireDrained(directory));
        Files.write(directory.resolve("checkpoint"),new byte[32]);
        assertThrows(IOException.class,() -> LegacyJournalGuard.requireDrained(directory));
    }
}
