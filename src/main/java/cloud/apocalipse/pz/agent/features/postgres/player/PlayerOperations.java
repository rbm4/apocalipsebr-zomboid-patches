package cloud.apocalipse.pz.agent.features.postgres.player;

import cloud.apocalipse.pz.agent.features.postgres.PlayerJournal;
import cloud.apocalipse.pz.agent.features.postgres.PostgresRuntime;
import java.sql.*;
import java.util.*;

/** Agent journal format, separate from unconverted legacy player records. */
public final class PlayerOperations {
    private static final PlayerDatabase DATABASE = new PlayerDatabase(PostgresRuntime::borrowBackground);
    record Initial(PlayerDatabase.Candidate candidate, UUID session, PlayerSnapshot.Snapshot snapshot) implements PlayerJournal.ReplayOperation {
        public String kind() { return "agent-player-initial-v1"; }
        public boolean completePlayerState() { return candidate.newLife() != null; }
        public long estimatedBytes() { return snapshot.bytes() + 4096L; }
        public String playerKey() { return key(candidate.world(), candidate.identity(), candidate.slot(), candidate.coop()); }
        public void apply() throws SQLException {
            var effective = DATABASE.creationCandidate(candidate); var receipt = DATABASE.inspect(effective);
            UUID character = candidate.newLife() == null ? receipt.character() : candidate.newLife();
            if (!receipt.complete() || candidate.newLife() != null && !Objects.equals(receipt.character(), character)) {
                var graph = new PlayerCapture(character, receipt.aliases(), Long.MAX_VALUE).capture(snapshot, candidate.loadIssues());
                PlayerCapture.requireComplete(graph, "initial_capture");
                if (!DATABASE.persist(effective, receipt, graph)) throw new SQLException("Initial capture deferred", "40001");
            } else try (var connection = PostgresRuntime.borrowBackground()) { PlayerJournal.claim(connection); connection.commit(); }
            PlayerMutations.bind(candidate, session, character, receipt.aliases()); PlayerSync.creationCommitted(session);
        }
    }
    record Live(PlayerMutations.Batch batch) implements PlayerJournal.ReplayOperation {
        public String kind() { return "agent-player-live-v1"; }
        public boolean completePlayerState() { return batch.snapshot() != null; }
        public long estimatedBytes() { return (batch.snapshot() == null ? 0 : batch.snapshot().bytes()) + 64L * batch.fields().size() + 4096; }
        public String playerKey() { var c = batch.candidate(); return key(c.world(), c.identity(), c.slot(), c.coop()); }
        public void apply() throws SQLException {
            var effective = DATABASE.creationBatch(batch); var graph = effective.snapshot() == null ? null : DATABASE.projectLive(effective);
            if (!DATABASE.applyFields(effective, graph)) throw new SQLException("Live capture deferred", "40001");
        }
    }
    static String key(String world, String identity, int slot, boolean coop) { return world.length() + ":" + world + ":" + coop + ":" + identity.length() + ":" + identity + ":" + slot; }
    /** Exact detached types only; no native class or arbitrary agent object is deserialized. */
    public static boolean journalType(Class<?> type) {
        return Set.of(Initial.class, Live.class, PlayerNativeSave.class, PlayerDatabase.Candidate.class, PlayerSnapshot.Snapshot.class, PlayerSnapshot.Node.class,
            PlayerSnapshot.Table.class, PlayerSnapshot.Reference.class, PlayerSnapshot.Stat.class, PlayerMutations.Batch.class,
            PlayerMutations.Field.class, PlayerMutations.Value.class,
            cloud.apocalipse.pz.agent.features.postgres.dictionary.DictionaryOrigin.class,
            cloud.apocalipse.pz.agent.features.postgres.dictionary.PayloadProvenance.Metadata.class).contains(type);
    }
    private PlayerOperations() { }
}
