package cloud.apocalipse.pz.agent.features.postgres;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SuccessfulLoginJournalTest {
    @Test void persistsTheExactAuditIdentityAndReopensWithoutLosingPendingWork() throws Exception {
        var path=Files.createTempDirectory("login-journal-test-");
        var login=new SuccessfulLoginJournal.Login(UUID.randomUUID(),1000,1,"fixture","user",false,0,"user",null,null);
        UUID journalId;
        try(var journal=new PersistenceJournal(path,4096,ignored->{})){journalId=journal.submitDurable(login);}
        try(var journal=new PersistenceJournal(path,4096,ignored->{})){
            assertEquals(journalId,journal.head().id());assertEquals(login,journal.head().operation());
            journal.acknowledgeCommitted(journalId);assertNull(journal.head());
        }
        Map<Integer,Object> parameters=new HashMap<>();
        var statement=(PreparedStatement)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{PreparedStatement.class},(p,m,a)->{
            if(m.getName().startsWith("set")&&a.length==2){parameters.put((Integer)a[0],a[1]);return null;}
            if(m.getName().equals("executeQuery")){
                var next=new java.util.concurrent.atomic.AtomicBoolean(true);
                return Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{ResultSet.class},(r,method,args)->switch(method.getName()){
                    case "next"->next.getAndSet(false);case "getInt"->7;default->null;
                });
            }
            return null;
        });
        var connection=(Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{
            if(m.getName().equals("prepareStatement")){assertEquals("SELECT player_rel.record_successful_login(?,?,?,?,?,?,?,?,?,?)",a[0]);return statement;}
            throw new AssertionError("Unexpected connection operation: "+m.getName());
        });
        assertEquals(7,SuccessfulLoginJournal.record(connection,login));
        assertEquals(login.event(),parameters.get(1));assertEquals(1,parameters.get(3));assertEquals("username",parameters.get(5));
        assertTrue(parameters.containsKey(10));assertNull(parameters.get(10));
    }
}
