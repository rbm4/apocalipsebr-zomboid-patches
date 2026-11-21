package cloud.apocalipse.pz.agent.features.postgres.player;

import cloud.apocalipse.pz.agent.features.postgres.PlayerJournal;
import cloud.apocalipse.pz.agent.features.postgres.dictionary.*;
import java.util.*;

/** Detached DTO fixture; no native class is allocated or serialized. */
public final class PlayerJournalPayloadFixture {
    public static PlayerJournal.ReplayOperation operation(DictionaryOrigin origin) throws Exception {
        byte[] bytes = {4, 5, 6}; PayloadProvenance.remember(bytes, new PayloadProvenance.Metadata(origin, 249));
        var node = new PlayerSnapshot.Node(new Object());
        var field = PlayerSnapshot.Node.class.getDeclaredField("fields"); field.setAccessible(true); field.set(node, Map.of("opaque", bytes));
        var snapshot = new PlayerSnapshot.Snapshot(node, java.time.Instant.now(), 128);
        var candidate = new PlayerDatabase.Candidate(UUID.randomUUID(), "fixture", "world", "user", false, 0, 0, 251, null, List.of(), UUID.randomUUID());
        return new PlayerOperations.Initial(candidate, UUID.randomUUID(), snapshot);
    }
    public static void verify(PlayerJournal.ReplayOperation value, DictionaryOrigin origin) {
        var operation = (PlayerOperations.Initial) value;
        byte[] bytes = (byte[]) operation.snapshot().player().get("opaque");
        var metadata = PayloadProvenance.metadata(bytes);
        if (!Arrays.equals(bytes, new byte[]{4, 5, 6}) || metadata == null || !origin.equals(metadata.origin()) || metadata.worldVersion() != 249)
            throw new AssertionError("journal lost detached payload provenance or world version");
    }
}
