package cloud.apocalipse.pz.agent.features.postgres;

import cloud.apocalipse.pz.agent.features.npc.persistence.ApocBRHumanSales;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class NpcPersistenceTest {
    @TempDir Path directory;
    @Test void durableSaleAndCheckpointSurviveRestartAndRetainStableIdentities()throws Exception {
        var sale=new ApocBRHumanSales.Sale(UUID.randomUUID(),1,"fixture-world","fixture-user",null,0,"guide",1L,List.of(10,11),List.of(11));
        UUID first,second;
        try(var journal=new PersistenceJournal(directory,4096,s->{})){
            first=journal.submitDurable(sale);
            second=journal.submitDurable(new PersistenceOperations.Account(List.of(new PersistenceOperations.Sql("SELECT ?",List.of(1)))));
        }
        try(var journal=new PersistenceJournal(directory,4096,s->{})){
            assertEquals(first,journal.head().id());assertEquals(sale,journal.head().operation());
            assertEquals(101,((ApocBRHumanSales.Sale)journal.head().operation()).quantity());
            assertThrows(java.io.IOException.class,()->journal.acknowledgeCommitted(second));
            journal.acknowledgeCommitted(first);assertEquals(second,journal.head().id());
        }
    }
    @Test void receiptAndNpcWritesCommitTogetherAndLostCommitReplyDoesNotDuplicate()throws Exception {
        var database=new AccountReplayTest.Database();UUID id=UUID.randomUUID();
        var operation=new PersistenceOperations.Account(List.of(new PersistenceOperations.Sql("UPDATE npc_engine.runtime_state SET x=?",List.of(1))));
        database.loseCommitResponse=true;
        try(var c=database.connection()){
            NpcPersistence.apply(c,id,operation);assertThrows(SQLException.class,c::commit);
        }
        assertEquals(1,database.writes);
        try(var c=database.connection()){NpcPersistence.apply(c,id,operation);c.commit();}
        assertEquals(1,database.writes);assertTrue(database.receipts.contains(id));
    }
    @Test void failedCheckpointDoesNotCommitReceiptAndCanBeReplayed()throws Exception {
        var database=new AccountReplayTest.Database();UUID id=UUID.randomUUID();
        var operation=new PersistenceOperations.Account(List.of(new PersistenceOperations.Sql("UPDATE npc_engine.runtime_state SET x=?",List.of(1))));
        database.failMutation=true;
        try(var c=database.connection()){assertThrows(SQLException.class,()->NpcPersistence.apply(c,id,operation));c.rollback();}
        assertTrue(database.receipts.isEmpty());assertEquals(0,database.writes);
        database.failMutation=false;
        try(var c=database.connection()){NpcPersistence.apply(c,id,operation);c.commit();}
        assertEquals(1,database.writes);assertEquals(Set.of(id),database.receipts);
    }
    @Test void saleOperationRetainsBundleConversionAndNativeIds(){
        var sale=new ApocBRHumanSales.Sale(UUID.randomUUID(),1,"world","user",null,0,"npc",1L,List.of(1,2),List.of(2));
        var sql=ApocBRHumanSales.operation(sale).statements().getFirst();
        assertTrue(sql.sql().contains("ON CONFLICT (sale_id) DO NOTHING"));
        assertEquals(101,sql.parameters().get(8));assertEquals("[1, 2]",sql.parameters().get(9));assertEquals("[2]",sql.parameters().get(10));
    }

    @Test void actualLegacySaleRecordAndOlderBundleFreeRecordRemainReadable()throws Exception {
        for(boolean older:List.of(false,true)){
            Path root=directory.resolve(older?"older":"legacy");java.nio.file.Files.createDirectories(root);
            Path classes=root.resolve("classes");java.nio.file.Files.createDirectories(classes);
            Path sale=root.resolve("ApocBRHumanSales.java"),writer=root.resolve("SaleWriter.java");
            String actual=java.nio.file.Files.readString(Path.of("42.22.2/src/zombie/savefile/ApocBRHumanSales.java"));
            actual=actual.substring(0,actual.indexOf("    public static boolean available()"))+"}";
            if(older)actual="""
                package zombie.savefile;
                import java.util.*;
                public final class ApocBRHumanSales {
                    public record Sale(UUID id,int serverId,String world,String username,String steamId,int playerIndex,
                        String npcKey,long soldAtMillis,List<Integer> itemIds) implements ApocBRPersistenceJournal.Operation {
                        public String kind(){return "human-sale-v1";}public long estimatedBytes(){return 2048;}
                    }
                }
                """;
            java.nio.file.Files.writeString(sale,actual);
            java.nio.file.Files.writeString(writer,"""
                package zombie.savefile;
                import java.nio.file.Path;import java.util.*;
                public final class SaleWriter {
                    public static String write(Path path,UUID sale)throws Exception{
                        try(var journal=new ApocBRPersistenceJournal(path,4096,s->{})){
                            return journal.submitDurable(new ApocBRHumanSales.Sale(sale,1,"fixture-world","user",null,0,"guide",1,List.of(10))).toString();
                        }
                    }
                }
                """);
            assertEquals(0,javax.tools.ToolProvider.getSystemJavaCompiler().run(null,null,null,"-proc:none","-d",classes.toString(),
                "42.22.2/src/zombie/savefile/ApocBRPersistenceJournal.java",sale.toString(),writer.toString()));
            Path records=root.resolve("journal");UUID saleId=UUID.randomUUID(),entry;
            try(var loader=new java.net.URLClassLoader(new java.net.URL[]{classes.toUri().toURL()},ClassLoader.getPlatformClassLoader())){
                entry=UUID.fromString((String)loader.loadClass("zombie.savefile.SaleWriter").getMethod("write",Path.class,UUID.class).invoke(null,records,saleId));
            }
            try(var journal=new PersistenceJournal(records,4096,s->{})){
                assertEquals(entry,journal.head().id());
                var restored=assertInstanceOf(ApocBRHumanSales.Sale.class,journal.head().operation());
                assertEquals(saleId,restored.id());assertEquals(List.of(10),restored.itemIds());assertTrue(restored.bundleItemIds().isEmpty());
            }
        }
    }
}
