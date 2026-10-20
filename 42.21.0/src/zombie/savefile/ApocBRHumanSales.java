package zombie.savefile;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Typed boundary: detached values enter the existing journal; only replay executes SQL. */
public final class ApocBRHumanSales {
    public record Sale(UUID id, int serverId, String world, String username, String steamId,
                       int playerIndex, String npcKey, long soldAtMillis, List<Integer> itemIds)
            implements ApocBRPersistenceJournal.Operation {
        public Sale {
            Objects.requireNonNull(id); Objects.requireNonNull(world); Objects.requireNonNull(username);
            Objects.requireNonNull(npcKey); itemIds = List.copyOf(itemIds);
            if (serverId < 1 || playerIndex < 0 || playerIndex > 3 || itemIds.isEmpty() || itemIds.size() > 500
                || itemIds.stream().distinct().count() != itemIds.size()) throw new IllegalArgumentException("Invalid NPC sale");
        }
        public String kind() { return "human-sale-v1"; }
        public long estimatedBytes() { return 2048L + itemIds.size() * 64L; }
    }

    public static boolean available() {
        return Boolean.parseBoolean(System.getProperty("apocbr.guide.trade.enabled", "true"))
            && ApocBRPersistence.journaling() && ApocBRPersistence.reachable();
    }

    /** Local fsync precedes consumption. No connection lease or SQL on the simulation thread.
     * Failure leaves inventory untouched; do not retry with a fresh UUID. */
    public static UUID enqueue(Sale sale) {
        if (!available()) throw new IllegalStateException("NPC sales persistence unavailable");
        return ApocBRPersistence.submitDurable(sale);
    }

    static void validate(Connection connection) throws SQLException {
        if (!Boolean.getBoolean("apocbr.guide.enabled")
            || !Boolean.parseBoolean(System.getProperty("apocbr.guide.trade.enabled", "true"))) return;
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT sale_id, server_id, world, username, steam_id, player_index, npc_key,
                   sold_at, item_type, quantity, native_item_ids, status
              FROM player_rel.apocbr_human_sale WHERE false
            """)) {
            statement.setQueryTimeout(2);
            try (var ignored = statement.executeQuery()) { }
        }
    }

    static void apply(Sale sale) throws SQLException {
        String ids = sale.itemIds().toString(); // List<Integer> produces a JSON integer array, never client text.
        ApocBRPersistenceOperations.apply(new ApocBRPersistenceOperations.Account(List.of(
            new ApocBRPersistenceOperations.Sql("""
                INSERT INTO player_rel.apocbr_human_sale
                    (sale_id, server_id, world, username, steam_id, player_index, npc_key,
                     sold_at, item_type, quantity, native_item_ids)
                VALUES (?, ?, ?, ?, ?, ?, ?, to_timestamp(? / 1000.0), 'Base.Money', ?, ?::jsonb)
                ON CONFLICT (sale_id) DO NOTHING
                """, java.util.Arrays.asList(sale.id(), sale.serverId(), sale.world(), sale.username(), sale.steamId(),
                    sale.playerIndex(), sale.npcKey(), sale.soldAtMillis(), sale.itemIds().size(), ids)) )));
    }
    private ApocBRHumanSales() { }
}
