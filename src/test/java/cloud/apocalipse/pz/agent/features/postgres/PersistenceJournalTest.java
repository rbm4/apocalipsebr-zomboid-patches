package cloud.apocalipse.pz.agent.features.postgres;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PersistenceJournalTest {
    @TempDir Path temporary;

    @Test
    void actualLegacyJournalAccountRecordReopensWithoutChangingOperationIdentity() throws Exception {
        Path classes = temporary.resolve("legacy-classes");
        Files.createDirectories(classes);
        Path dto = temporary.resolve("ApocBRPersistenceOperations.java");
        Files.writeString(dto, """
            package zombie.savefile;
            import java.util.*;
            final class ApocBRPersistenceOperations {
                record Sql(String sql, List<Object> parameters) implements java.io.Serializable {}
                record Account(List<Sql> statements) implements ApocBRPersistenceJournal.Operation {
                    public String kind() { return "account-write-v1"; }
                    public long estimatedBytes() { return 1024; }
                }
                record Unknown(String value) implements ApocBRPersistenceJournal.Operation {
                    public String kind() { return "unmigrated-operation"; }
                    public long estimatedBytes() { return 128; }
                }
            }
            """);
        Path writer = temporary.resolve("JournalCompatibilityWriter.java");
        Files.writeString(writer, """
            package zombie.savefile;
            import java.nio.file.Path;
            import java.util.*;
            public final class JournalCompatibilityWriter {
                public static void writeUnknown(Path directory) throws Exception {
                    try (var journal = new ApocBRPersistenceJournal(directory,4096,message -> {})) {
                        journal.submit(new ApocBRPersistenceOperations.Unknown("fixture"));
                        journal.flush();
                    }
                }
                public static String write(Path directory) throws Exception {
                    try (var journal = new ApocBRPersistenceJournal(directory,4096,message -> {})) {
                        UUID id = journal.submit(new ApocBRPersistenceOperations.Account(List.of(
                            new ApocBRPersistenceOperations.Sql("UPDATE whitelist SET role=? WHERE username=?",List.of(2,"fixture-user")))));
                        journal.flush();
                        return id.toString();
                    }
                }
            }
            """);
        int compiled = ToolProvider.getSystemJavaCompiler().run(null, null, null,
            "-encoding", "UTF-8", "-proc:none", "-d", classes.toString(),
            "42.22.2/src/zombie/savefile/ApocBRPersistenceJournal.java", dto.toString(), writer.toString());
        assertEquals(0, compiled);
        Path directory = temporary.resolve("journal");
        UUID originalId;
        Path unknownDirectory = temporary.resolve("unknown-journal");
        try (var loader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            originalId = UUID.fromString((String) loader.loadClass("zombie.savefile.JournalCompatibilityWriter")
                .getMethod("write", Path.class).invoke(null, directory));
            loader.loadClass("zombie.savefile.JournalCompatibilityWriter")
                .getMethod("writeUnknown", Path.class).invoke(null, unknownDirectory);
        }
        try (var journal = new PersistenceJournal(directory,4096,message -> {})) {
            var entry = journal.head();
            assertNotNull(entry);
            assertEquals(originalId, entry.id());
            var account = assertInstanceOf(PersistenceOperations.Account.class, entry.operation());
            assertEquals(List.of(2, "fixture-user"), account.statements().getFirst().parameters());
            journal.acknowledge(originalId);
            assertTrue(journal.empty());
        }
        try (var reopened = new PersistenceJournal(directory,4096,message -> {})) {
            assertTrue(reopened.empty());
        }
        byte[] checkpoint = Files.readAllBytes(unknownDirectory.resolve("checkpoint"));
        Path unknownLog;
        try (var paths = Files.list(unknownDirectory)) {
            unknownLog = paths.filter(p -> p.toString().endsWith(".log")).findFirst().orElseThrow();
        }
        byte[] bytes = Files.readAllBytes(unknownLog);
        assertThrows(java.io.IOException.class, () -> new PersistenceJournal(unknownDirectory,4096,message -> {}));
        assertArrayEquals(checkpoint, Files.readAllBytes(unknownDirectory.resolve("checkpoint")));
        assertArrayEquals(bytes, Files.readAllBytes(unknownLog));
    }

    @Test
    void unacknowledgedEntriesSurviveRestartInOrderAndExcludeConcurrentOwners() throws Exception {
        Path directory = temporary.resolve("journal");
        List<String> log = new ArrayList<>();
        UUID first, second;
        try (var journal = new PersistenceJournal(directory,4096,log::add)) {
            first = journal.submit(new PersistenceOperations.Account(List.of(new PersistenceOperations.Sql("SELECT 1", List.of()))));
            second = journal.submit(new PersistenceOperations.Account(List.of(new PersistenceOperations.Sql("SELECT 2", List.of()))));
            journal.flush();
            assertThrows(Exception.class, () -> new PersistenceJournal(directory,4096,log::add));
            assertEquals(first, journal.head().id());
        }
        try (var journal = new PersistenceJournal(directory,4096,log::add)) {
            assertEquals(first, journal.head().id());
            journal.acknowledge(first);
            assertEquals(second, journal.head().id());
        }
        try (var journal = new PersistenceJournal(directory,4096,log::add)) {
            assertEquals(second, journal.head().id());
            journal.acknowledge(second);
            assertTrue(journal.empty());
        }
    }

    @Test
    void partialTailRecoveryPreservesEarlierOperationAndCorruptionFailsClosed() throws Exception {
        Path directory = temporary.resolve("journal");
        UUID id;
        try (var journal = new PersistenceJournal(directory,4096,message -> {})) {
            id = journal.submitDurable(new PersistenceOperations.Account(List.of(new PersistenceOperations.Sql("SELECT 1", List.of()))));
        }
        Path log;
        try (var paths = Files.list(directory)) {
            log = paths.filter(p -> p.toString().endsWith(".log")).findFirst().orElseThrow();
        }
        byte[] original = Files.readAllBytes(log);
        Files.write(log, new byte[]{1,2,3}, java.nio.file.StandardOpenOption.APPEND);
        try (var journal = new PersistenceJournal(directory,4096,message -> {})) {
            assertEquals(id, journal.head().id());
            assertEquals(original.length, Files.size(log));
        }
        byte[] corrupt = original.clone();
        corrupt[corrupt.length - 1] ^= 1;
        Files.write(log, corrupt);
        byte[] checkpoint = Files.readAllBytes(directory.resolve("checkpoint"));
        assertThrows(java.io.IOException.class, () -> new PersistenceJournal(directory,4096,message -> {}));
        assertArrayEquals(checkpoint, Files.readAllBytes(directory.resolve("checkpoint")));
        assertArrayEquals(corrupt, Files.readAllBytes(log));
    }
}
