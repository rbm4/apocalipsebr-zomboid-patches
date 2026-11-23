package cloud.apocalipse.pz.agent.features.npc.persistence;

import cloud.apocalipse.pz.agent.config.AgentConfiguration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Typed boundary: detached values enter the existing journal; only replay executes SQL. */
public final class ApocBRHumanSales {
    public record Sale(UUID id, int serverId, String world, String username, String steamId,
                       int playerIndex, String npcKey, long soldAtMillis, List<Integer> itemIds, List<Integer> bundleItemIds)
            implements cloud.apocalipse.pz.agent.features.postgres.PersistenceJournal.Operation {
        public Sale(UUID id, int serverId, String world, String username, String steamId,
                    int playerIndex, String npcKey, long soldAtMillis, List<Integer> itemIds) {
            this(id, serverId, world, username, steamId, playerIndex, npcKey, soldAtMillis, itemIds, List.of());
        }
        public Sale {
            Objects.requireNonNull(id); Objects.requireNonNull(world); Objects.requireNonNull(username);
            Objects.requireNonNull(npcKey); itemIds = List.copyOf(itemIds);
            // Older serialized journal records have no bundle component.
            bundleItemIds = bundleItemIds == null ? List.of() : List.copyOf(bundleItemIds);
            if (serverId < 1 || playerIndex < 0 || playerIndex > 3 || itemIds.isEmpty() || itemIds.size() > 500
                || itemIds.stream().distinct().count() != itemIds.size()
                || bundleItemIds.stream().distinct().count() != bundleItemIds.size()
                || !itemIds.containsAll(bundleItemIds)) throw new IllegalArgumentException("Invalid NPC sale");
        }
        public int quantity() { return itemIds.size() + 99 * bundleItemIds.size(); }
        public String kind() { return "human-sale-v1"; }
        public long estimatedBytes() { return 2048L + (itemIds.size() + bundleItemIds.size()) * 64L; }
    }

    public static boolean available() {
        return Boolean.parseBoolean(AgentConfiguration.systemProperty(AgentConfiguration.NPC_ARG_APOCBR_GUIDE_TRADE_ENABLED, "true"))
            && cloud.apocalipse.pz.agent.features.postgres.NpcPersistence.journaling() && cloud.apocalipse.pz.agent.features.postgres.NpcPersistence.reachable();
    }

    /** Local fsync precedes consumption. No connection lease or SQL on the simulation thread.
     * Failure leaves inventory untouched; do not retry with a fresh UUID. */
    public static UUID enqueue(Sale sale) {
        if (!available()) throw new IllegalStateException("NPC sales persistence unavailable");
        return cloud.apocalipse.pz.agent.features.postgres.NpcPersistence.submitDurable(sale);
    }

    public static void validate(Connection connection) throws SQLException {
        if (!Boolean.parseBoolean(AgentConfiguration.systemProperty(AgentConfiguration.NPC_ARG_APOCBR_GUIDE_TRADE_ENABLED, "true"))) return;
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT sale_id, server_id, world, username, steam_id, player_index, npc_key,
                   sold_at, item_type, quantity, native_item_ids, bundle_item_ids, status
              FROM player_rel.apocbr_human_sale WHERE false
            """)) {
            statement.setQueryTimeout(2);
            try (var ignored = statement.executeQuery()) { }
        }
    }

    public static cloud.apocalipse.pz.agent.features.postgres.PersistenceOperations.Account operation(Sale sale) {
        String ids = sale.itemIds().toString(); // List<Integer> produces a JSON integer array, never client text.
        return new cloud.apocalipse.pz.agent.features.postgres.PersistenceOperations.Account(List.of(
            new cloud.apocalipse.pz.agent.features.postgres.PersistenceOperations.Sql("""
                INSERT INTO player_rel.apocbr_human_sale
                    (sale_id, server_id, world, username, steam_id, player_index, npc_key,
                     sold_at, item_type, quantity, native_item_ids, bundle_item_ids)
                VALUES (?, ?, ?, ?, ?, ?, ?, to_timestamp(? / 1000.0), 'Base.Money', ?, ?::jsonb, ?::jsonb)
                ON CONFLICT (sale_id) DO NOTHING
                """, java.util.Arrays.asList(sale.id(), sale.serverId(), sale.world(), sale.username(), sale.steamId(),
                    sale.playerIndex(), sale.npcKey(), sale.soldAtMillis(), sale.quantity(), ids, sale.bundleItemIds().toString())) ));
    }
    private ApocBRHumanSales() { }
}
