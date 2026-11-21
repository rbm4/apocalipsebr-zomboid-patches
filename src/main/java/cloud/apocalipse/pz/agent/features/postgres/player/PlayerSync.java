package cloud.apocalipse.pz.agent.features.postgres.player;

import cloud.apocalipse.pz.agent.config.AgentConfiguration;
import cloud.apocalipse.pz.agent.features.postgres.PlayerJournal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import zombie.characters.IsoPlayer;
import zombie.ZomboidFileSystem;

/** Native identity binding and durable creation capture. Engine state stays on the simulation thread. */
final class PlayerSync {
    private static final Map<UUID, Boolean> CREATIONS = new ConcurrentHashMap<>();
    static boolean creationIsCommitted(UUID session) { return Boolean.TRUE.equals(CREATIONS.get(session)); }
    static void creationCommitted(UUID session) { CREATIONS.computeIfPresent(session, (key, previous) -> true); }
    static void forgetCreation(UUID session) { if (session != null) CREATIONS.remove(session); }
    static void register(IsoPlayer player, String world, String identity, int slot, boolean coop, PlayerReader.Profile profile) {
        var metadata = profile.one("snapshot");
        UUID character = UUID.fromString(Objects.requireNonNull(metadata.get("persisted_character_id")).toString());
        UUID dataset = UUID.fromString(Objects.requireNonNull(metadata.get("source_dataset_id")).toString());
        var candidate = new PlayerDatabase.Candidate(dataset, ZomboidFileSystem.instance.getCurrentSaveDir(), world, identity, coop, slot,
            0, ((Number) metadata.get("source_world_version")).intValue(), null, List.of());
        var aliases = new HashMap<Integer, UUID>();
        for (var item : profile.rows("item")) aliases.put(((Number) item.get("native_id")).intValue(), UUID.fromString(Objects.requireNonNull(item.get("persisted_item_id")).toString()));
        UUID session = PlayerMutations.register(player, candidate);
        if (session == null) throw new IllegalStateException("Player tracking capacity exceeded");
        PlayerMutations.bind(candidate, session, character, aliases);
    }
    static UUID create(IsoPlayer player, String world, String identity, int slot, boolean coop, int version) {
        String label = ZomboidFileSystem.instance.getCurrentSaveDir();
        String configured = AgentConfiguration.systemProperty(AgentConfiguration.PLAYER_DATASET_PROPERTY);
        UUID dataset = configured == null ? PlayerDatabase.named("sqlite-save:" + label) : UUID.fromString(configured);
        var candidate = new PlayerDatabase.Candidate(dataset, label, world, identity, coop, slot, 0, version, null, List.of(), UUID.randomUUID());
        UUID session = PlayerMutations.register(player, candidate);
        if (session == null) throw new IllegalStateException("Player tracking capacity exceeded");
        var snapshot = PlayerSnapshot.copyLive(player);
        CREATIONS.put(session, false);
        try { PlayerJournal.submit(new PlayerOperations.Initial(candidate, session, snapshot)); PlayerMutations.bind(candidate, session, candidate.newLife()); return session; }
        catch (RuntimeException failure) { CREATIONS.remove(session); throw failure; }
    }
    static boolean readable(String world, String identity, int slot, boolean coop) {
        return PlayerJournal.readable(PlayerOperations.key(world, identity, slot, coop)) && !PlayerMutations.hasPending(world, identity, slot, coop);
    }
    static void flush() { PlayerMutations.journalPending(false); }
    static void forceFlush() { PlayerMutations.journalPending(true); }
    static void shutdown() { CREATIONS.clear(); PlayerMutations.shutdown(); }
    private PlayerSync() { }
}
