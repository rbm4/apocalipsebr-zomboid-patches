package zombie.savefile;

import java.sql.*;
import java.util.*;

/** Version-one detached adapters. Journal replay owns all database work. */
final class ApocBRPersistenceOperations {
    record Native(ApocBRPostgresConnector.Snapshot snapshot) implements ApocBRPersistenceJournal.Operation {
        public String kind(){return "player-native-v1";}
        public long estimatedBytes(){return snapshot.payloadBytes()+1024L;}
        public String playerKey(){return key(snapshot.world(),snapshot.steamCoop()?snapshot.steamid():snapshot.username(),snapshot.playerIndex(),snapshot.steamCoop());}
    }
    record Initial(ApocBRPlayerRelationalDatabase.Candidate candidate,UUID session,
                   ApocBRPlayerRelationalSnapshot.Snapshot snapshot) implements ApocBRPersistenceJournal.Operation {
        public String kind(){return "player-initial-v1";}
        // Existing SQLite imports may skip an already complete (but older) head.
        // A new life publishes a full graph; other imports resolve via their next live graph.
        public boolean completePlayerState(){return candidate.newLife()!=null;}
        public long estimatedBytes(){return snapshot.bytes()+4096L;}
        public String playerKey(){return key(candidate.world(),candidate.identity(),candidate.slot(),candidate.coop());}
    }
    record Live(ApocBRPlayerRelationalMutations.Batch batch) implements ApocBRPersistenceJournal.Operation {
        public String kind(){return "player-live-v1";}
        public boolean completePlayerState(){return batch.snapshot()!=null;}
        public long estimatedBytes(){return (batch.snapshot()==null?0:batch.snapshot().bytes())+64L*batch.fields().size()+4096;}
        public String playerKey(){var c=batch.candidate();return key(c.world(),c.identity(),c.slot(),c.coop());}
    }
    record Sql(String sql,List<Object> parameters) implements java.io.Serializable {
        Sql {
            for(Object value:parameters)if(value!=null&&!(value instanceof String||value instanceof Boolean||value instanceof Byte
                ||value instanceof Short||value instanceof Integer||value instanceof Long||value instanceof Float||value instanceof Double||value instanceof UUID))
                throw new IllegalArgumentException("Unsupported journal parameter type");
            parameters=Collections.unmodifiableList(new ArrayList<>(parameters));
        }
    }
    record Account(List<Sql> statements) implements ApocBRPersistenceJournal.Operation {
        Account {statements=List.copyOf(statements);}
        public String kind(){return "account-write-v1";}
        public long estimatedBytes(){long size=256;for(Sql sql:statements){size+=sql.sql().length()*2L+128;for(Object value:sql.parameters())size+=value instanceof String text?text.length()*2L+32:64;}return size;}
    }
    private static final ApocBRPlayerRelationalDatabase database=new ApocBRPlayerRelationalDatabase(ApocBRPostgresConnector::borrowPlayerConnection);
    static String key(String world,String identity,int slot,boolean coop){return world.length()+":"+world+":"+coop+":"+identity.length()+":"+identity+":"+slot;}
    static void apply(ApocBRPersistenceJournal.Operation operation)throws SQLException {
        if(operation instanceof Native nativeSave)ApocBRPostgresConnector.writeJournalSnapshot(nativeSave.snapshot());
        else if(operation instanceof Initial initial) {
            var receipt=database.inspect(initial.candidate());
            UUID character=initial.candidate().newLife()==null?receipt.character():initial.candidate().newLife();
            if(!receipt.complete()||initial.candidate().newLife()!=null&&!Objects.equals(receipt.character(),character)) {
                var graph=new ApocBRPlayerRelationalCapture(character,receipt.aliases(),Long.MAX_VALUE).capture(initial.snapshot(),initial.candidate().loadIssues());
                ApocBRPlayerRelationalCapture.requireComplete(graph,"initial_capture");
                if(!database.persist(initial.candidate(),receipt,graph))throw new SQLException("Journaled initial capture deferred","40001");
            } else {
                // An already complete import needs a receipt as well, including retries after commit loss.
                try(Connection connection=ApocBRPostgresConnector.borrowPlayerConnection()){ApocBRPersistence.claim(connection);connection.commit();}
            }
            ApocBRPlayerRelationalMutations.bind(initial.candidate(),initial.session(),character);
        } else if(operation instanceof Live live) {
            var batch=live.batch();var graph=batch.snapshot()==null?null:database.projectLive(batch);
            if(!database.applyFields(batch,graph))throw new SQLException("Journaled live capture deferred","40001");
        } else if(operation instanceof Account account) {
            try(Connection connection=ApocBRPostgresConnector.borrowPlayerConnection()) {
                try {
                    if(!ApocBRPersistence.claim(connection)){connection.rollback();return;}
                    for(Sql sql:account.statements())try(PreparedStatement statement=connection.prepareStatement(sql.sql())) {
                        statement.setQueryTimeout(2);for(int i=0;i<sql.parameters().size();i++)statement.setObject(i+1,sql.parameters().get(i));statement.executeUpdate();
                    }
                    connection.commit();
                }catch(SQLException|RuntimeException failure){try{connection.rollback();}catch(SQLException ignored){}throw failure;}
            }
        } else throw new SQLException("Unsupported journal operation","22023");
    }
}
