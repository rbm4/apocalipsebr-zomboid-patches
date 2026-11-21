package cloud.apocalipse.pz.agent.features.postgres.dictionary;

import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DictionaryDatabaseTest {
    @TempDir Path directory;
    @AfterEach void cleanup() { DictionaryRuntime.shutdown(); }
    static DictionarySnapshot sample() {
        return new DictionarySnapshot(List.of(new DictionarySnapshot.Registry("info","",1,null)),
            List.of(new DictionarySnapshot.Entry("info","",0,"item","Base.Axe","Base","Axe",true,
                "example",true,true,false,false,true,null,List.of("pz-vanilla","old-mod"))));
    }

    @Test void publicationIsTransactionalReusesExactViewAndPreservesHistory() throws Exception {
        var fake=new DictionaryJdbcFixture.Fake();
        var database=new DictionaryDatabase(fake::open);
        var origin=new DictionaryDatabase.Origin(UUID.randomUUID(),"test",1,"save");
        var first=database.publish(origin,sample());
        assertEquals(1,first.revision()); assertEquals(1,fake.commits); assertEquals(1,fake.notifications);
        assertTrue(fake.order.indexOf("notify")<fake.order.indexOf("commit"));
        assertEquals(first,new DictionaryDatabase(fake::open).publish(origin,sample()));
        assertEquals(1,fake.entries.size()); assertEquals(1,fake.notifications);
        fake.registries.get("info:").set(3,99);
        assertThrows(DictionaryDatabase.HistoryChangedException.class,()->database.publish(origin,sample()));
        assertEquals(1,fake.rollbacks); assertEquals(fake.opened,fake.closed);
    }

    @Test void publicationRejectsOwnerAndSchemaFailuresWithoutLeakingDriverData() throws Exception {
        var fake=new DictionaryJdbcFixture.Fake();fake.wrongOwner=true;
        var origin=new DictionaryDatabase.Origin(UUID.randomUUID(),"test",1,"save");
        var wrongOwner=new DictionaryDatabase(fake::open);
        assertThrows(SQLException.class,()->wrongOwner.publish(origin,sample()));
        assertEquals(0,fake.commits);assertEquals(1,fake.rollbacks);
        fake=new DictionaryJdbcFixture.Fake();fake.failNotify=true;
        var failing=new DictionaryDatabase(fake::open);
        var failure=assertThrows(SQLException.class,()->failing.publish(origin,sample()));
        assertTrue(failure.getMessage().contains("stage=NOTIFY"));assertFalse(failure.toString().contains("secret"));
        assertNull(failure.getCause());assertEquals(0,fake.commits);assertEquals(1,fake.rollbacks);
        assertEquals(fake.opened,fake.closed);
    }

    @Test void countsDriveKeysetPaginationAndOrderedOverrides() throws Exception {
        for(int count:new int[]{0,1,1000,1001,10001,200_000}) {
            var fake=new DictionaryReadFixture.Fake(count,0);
            var result=fake.load();assertEquals(count,result.entries().size());
            int pageSize=Math.max(1000,(count+9)/10);
            assertEquals((count+pageSize-1)/pageSize,fake.entryQueries);
            assertEquals(0,fake.overrideQueries);assertEquals(1,fake.commits);assertEquals(1,fake.closed);
        }
        var fake=new DictionaryReadFixture.Fake(11,10001);var snapshot=fake.load();
        assertEquals(10,fake.overrideQueries);
        for(var entry:snapshot.entries()) {
            var expected=fake.overrides.stream().filter(row->row[0].equals(entry.family())&&row[1].equals(entry.register())
                &&row[2].equals(entry.id())).map(row->(String)row[3]).toList();
            assertEquals(expected,entry.overrides());
        }
    }

    @Test void failedPageNeverInstallsPartialViewAndRetryLoadsCompleteView() throws Exception {
        var fake=new DictionaryReadFixture.Fake(10001,0);fake.failPage=2;
        fake.failure=new SQLException("PRIVATE password=secret row", "57014");
        var database=new DictionaryDatabase(fake::open);
        var error=assertThrows(SQLException.class,()->DictionaryCache.prepare(fake.origin,database));
        assertEquals("57014",error.getSQLState());assertFalse(error.toString().contains("PRIVATE"));
        assertThrows(IllegalStateException.class,()->DictionaryCache.ready(fake.origin));
        assertEquals(1,fake.rollbacks);assertEquals(1,fake.closed);
        fake.failPage=0;DictionaryCache.prepare(fake.origin,database);int calls=fake.entryQueries;
        DictionaryCache.prepare(fake.origin,database);assertEquals(calls,fake.entryQueries);
        assertEquals(10001,DictionaryCache.ready(fake.origin).snapshot().entries().size());
        assertThrows(IllegalStateException.class,()->DictionaryCache.ready(new DictionaryOrigin(3,fake.origin.namespaceId(),fake.origin.viewId())));
    }

    @Test void malformedViewsAndBudgetsRollbackBeforePublication() throws Exception {
        for(int scenario=0;scenario<4;scenario++) {
            var fake=new DictionaryReadFixture.Fake(1001,0);
            if(scenario==0)fake.claimedEntries=200001;
            if(scenario==1)fake.claimedOverrides=1000001;
            if(scenario==2)fake.missingState=true;
            if(scenario==3)fake.truncate=true;
            assertThrows(SQLException.class,fake::load);assertEquals(0,fake.commits);
            assertEquals(1,fake.rollbacks);assertEquals(1,fake.closed);
            if(scenario<2)assertEquals(0,fake.entryQueries);
        }
    }

    @Test void buildOwnershipAndFailedBatchesPreserveTheOldHistory() throws Exception {
        var origin=new DictionaryDatabase.Origin(UUID.randomUUID(),"test",1,"save");
        var old=new DictionaryJdbcFixture.Fake();old.build="42.21.0";old.world=249;
        assertThrows(DictionaryDatabase.BuildChangedException.class,()->new DictionaryDatabase(old::open).publish(origin,sample()));
        assertEquals("42.21.0",old.build);assertEquals(249,old.world);assertEquals(0,old.commits);assertEquals(1,old.rollbacks);
        var bad=new DictionaryJdbcFixture.Fake();bad.failBatch=true;
        assertThrows(SQLException.class,()->new DictionaryDatabase(bad::open).publish(origin,sample()));
        assertEquals(0,bad.commits);assertEquals(1,bad.rollbacks);assertEquals(bad.opened,bad.closed);
    }

    @Test void completedConcurrentInstallationSurvivesTheOldReaderFailure() throws Exception {
        var origin=new DictionaryOrigin(2,UUID.randomUUID(),UUID.randomUUID());
        var started=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        var database=new DictionaryDatabase(()-> {
            started.countDown();
            try {if(!release.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new SQLException("fixture timeout","57014");}
            catch(InterruptedException stopped){Thread.currentThread().interrupt();throw new SQLException("fixture stopped","57014");}
            throw new SQLException("private failed old reader","08006");
        });
        var work=new java.util.concurrent.FutureTask<Void>(()->{DictionaryCache.prepare(origin,database);return null;});
        var thread=new Thread(work,"dictionary-cache-fixture");thread.start();
        try {
            assertTrue(started.await(5,java.util.concurrent.TimeUnit.SECONDS));
            DictionaryCache.install(origin,sample());release.countDown();
            work.get(5,java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(sample(),DictionaryCache.ready(origin).snapshot());
        } finally {release.countDown();thread.join(5000);assertFalse(thread.isAlive());}
    }

    @Test void namespaceRolloverPersistsReplacementBeforeFailedPublicationAndReusesIt() throws Exception {
        class Multi extends DictionaryJdbcFixture.Fake {
            final Map<UUID,DictionaryJdbcFixture.Fake> histories=new HashMap<>();UUID active;
            @Override int write(String sql,List<Object> args)throws Exception {
                if(sql.startsWith("INSERT INTO player_rel.world_dictionary_namespace")) {
                    active=(UUID)args.getFirst();var state=histories.computeIfAbsent(active,key->new DictionaryJdbcFixture.Fake());
                    build=state.build;world=state.world;registries=state.registries;entries=state.entries;revision=state.revision;
                    viewValues=state.viewValues;states=state.states;overrides=state.overrides;registryStates=state.registryStates;
                }
                int result=super.write(sql,args);
                if(sql.startsWith("UPDATE player_rel.world_dictionary_namespace"))histories.get(active).revision=revision;
                if(sql.startsWith("INSERT INTO player_rel.world_dictionary_runtime_view("))histories.get(active).viewValues=viewValues;
                return result;
            }
        }
        var fake=new Multi();var database=new DictionaryDatabase(fake::open);UUID namespace=UUID.randomUUID();
        var origin=new DictionaryDatabase.Origin(namespace,"vanilla:"+namespace,1,"save");Path marker=directory.resolve("namespace.txt");
        var first=DictionaryRuntime.publish(database,origin,sample(),marker);
        fake.histories.get(namespace).registries.get("info:").set(3,99);fake.failNotify=true;
        assertThrows(SQLException.class,()->DictionaryRuntime.publish(database,origin,sample(),marker));
        UUID replacement=UUID.fromString(Files.readAllLines(marker).get(1));assertNotEquals(first.namespace(),replacement);
        fake.failNotify=false;var retried=DictionaryRuntime.publish(database,origin,sample(),marker);
        assertEquals(replacement,retried.namespace());assertEquals(retried,DictionaryRuntime.publish(database,origin,sample(),marker));
        assertEquals(99,fake.histories.get(namespace).registries.get("info:").get(3));assertEquals(fake.opened,fake.closed);
    }
}
