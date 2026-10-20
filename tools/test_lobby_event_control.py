"""Execute production event worker/cache with fake JDBC and deterministic clocks; no live DB."""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
FILES = {
    "zombie/network/GameServer.java": 'package zombie.network; public class GameServer { public static boolean server=true; public static String serverName="test"; }',
    "zombie/core/Core.java": 'package zombie.core; public class Core { public static String gameSaveWorld="world"; }',
    "zombie/core/Translator.java": '''package zombie.core; public class Translator {
 public static String getText(String key,Object... args){return key+java.util.Arrays.toString(args);}
}''',
    "zombie/debug/DebugLog.java": 'package zombie.debug; public class DebugLog { public static void log(String text){} }',
    "zombie/Lua/LuaEventManager.java": '''package zombie.Lua; public class LuaEventManager {
 public static int calls; public static String event; public static Object[] arguments;
 public static void AddEvent(String name){}
 public static void triggerEvent(String name,Object... args){calls++;event=name;arguments=args;}
}''',
    "zombie/savefile/ApocBRPostgresConnector.java": '''package zombie.savefile;
public class ApocBRPostgresConnector {
 public static java.sql.Connection borrowServerConnection() throws java.sql.SQLException {throw new java.sql.SQLException("fixture unavailable","08006");}
 public static void runBackgroundDatabaseWork(Runnable action){action.run();}
}''',
}
HARNESS = r'''
package zombie.apocalipse.events;
import java.lang.reflect.*;import java.sql.*;import java.time.*;import java.util.*;
import zombie.Lua.LuaEventManager;import zombie.network.GameServer;
public class EventControlTest {
 static int checks;static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
 static final String ID="8a8769be-bd39-4448-9a75-cd8d1ac63b57";
 static final String OTHER="5db2e381-1386-4850-a530-17aca0126fc8";
 static long start=Instant.parse("2026-10-19T16:00:00Z").toEpochMilli();
 static long[] clock={start-1000,1_000_000_000L};
 static class Database {
  ApocBREventControl.Schedule row=new ApocBREventControl.Schedule(ID,1,"Extraction",start,"scheduled");
  boolean fail,queryFail;int opened,closed,reads,registrations,writes,queries;long reported;
  String reportedId;Thread openingThread;
  @SuppressWarnings("unchecked") <T>T proxy(Class<T> kind,InvocationHandler invocation){return (T)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{kind},invocation);}
  Connection open() throws SQLException {
   if(fail)throw new SQLException("no database","08006");opened++;openingThread=Thread.currentThread();
   return proxy(Connection.class,(p,m,a)->switch(m.getName()){
    case "prepareStatement" -> statement((String)a[0]);case "close" -> {closed++;yield null;}
    case "isClosed" -> false;default -> throw new AssertionError("Unexpected connection method: "+m.getName());});
  }
  PreparedStatement statement(String sql){
   check(Set.of(ApocBREventControl.REGISTER,ApocBREventControl.READ,ApocBREventControl.RECEIPT).contains(sql),"fixed schema-qualified SQL only");
   Map<Integer,Object> values=new HashMap<>();int[] timeout={0};
   return proxy(PreparedStatement.class,(p,m,a)->switch(m.getName()){
    case "setQueryTimeout" -> {timeout[0]=(int)a[0];yield null;}
    case "setString","setLong","setObject" -> {values.put((int)a[0],a[1]);yield null;}
    case "executeUpdate" -> {
     queries++;check(timeout[0]==2,"bounded statement timeout");
     if(sql.equals(ApocBREventControl.REGISTER)){check(values.get(1).equals("scope")&&values.get(2).equals("world"),"scope registration binding");registrations++;yield 1;}
     check(sql.equals(ApocBREventControl.RECEIPT),"receipt path");
     check(values.get(3).equals("scope")&&values.get(4).equals("world"),"receipt scope binding");
     if(reported<=(long)values.get(5)){
      reported=(long)values.get(2);reportedId=values.get(1).toString();writes++;yield 1;
     }yield 0;
    }
    case "executeQuery" -> {
     check(sql.equals(ApocBREventControl.READ)&&timeout[0]==2,"bounded read");
     check(values.get(1).equals("scope")&&values.get(2).equals("world"),"read scope binding");
     if(queryFail)throw new SQLException("missing schema","42P01");reads++;int[] cursor={0};
     yield proxy(ResultSet.class,(r,rm,ra)->switch(rm.getName()){
      case "next" -> ++cursor[0]==1;
      case "getString" -> switch((int)ra[0]){case 1->row.id();case 3->row.name();case 5->row.state();case 6->reportedId;case 8->reportedId==null?null:"release_requested";default->throw new AssertionError();};
      case "getLong" -> (int)ra[0]==7?reported:row.revision();
      case "getTimestamp" -> row.startsAt()==0?null:new Timestamp(row.startsAt());
      case "close" -> null;default -> throw new AssertionError("Unexpected result method "+rm.getName());});
    }
    case "close" -> null;default -> throw new AssertionError("Unexpected statement method "+m.getName());});
  }
 }
 static ApocBREventControl control(Database db) throws Exception {
  var c=new ApocBREventControl("scope","world",2000,10000,ZoneId.of("America/Fortaleza"),db::open,()->clock[0],()->clock[1]);
  Field field=ApocBREventControl.class.getDeclaredField("instance");field.setAccessible(true);field.set(null,c);return c;
 }
 static void poll(ApocBREventControl c) throws Exception {
  Throwable[] failure={null};int calls=LuaEventManager.calls;
  Thread worker=new Thread(()->{try{c.pollOnce();}catch(Throwable error){failure[0]=error;}},"fixture-jdbc-worker");
  worker.start();worker.join();if(failure[0]!=null)throw new RuntimeException(failure[0]);
  check(LuaEventManager.calls==calls,"JDBC worker must never call Lua");
 }
 static void invalid(Runnable action){try{action.run();throw new AssertionError("invalid schedule accepted");}catch(IllegalArgumentException|NullPointerException expected){checks++;}}
 public static void main(String[] ignored) throws Exception {
  Database db=new Database();var c=control(db);
  check(ApocBREventControl.getStatus().equals("STARTING"),"starting without a database result");
  poll(c);check(ApocBREventControl.getEventId()==null,"worker cannot install main-thread snapshot");
  ApocBREventControl.update();check(LuaEventManager.calls==0&&ApocBREventControl.getStatus().equals("READY"),"future schedule");
  check(db.openingThread!=Thread.currentThread(),"connection only used on worker");
  int queries=db.queries;
  String greeting=ApocBREventControl.guideGreeting("Oi!");
  check(greeting.contains("EventScheduled")&&greeting.contains("19/10/2026 13:00")&&greeting.contains("Extraction"),"real-time guide date in Fortaleza");
  check(ApocBREventControl.getStartsAtMillis()==start&&ApocBREventControl.getRevision()==1,"cached typed getters");
  check(!ApocBREventControl.isDue(ID,1)&&!ApocBREventControl.acknowledgeRelease(ID,1),"do not activate early");
  check(db.queries==queries,"Lua getters/greetings perform no SQL");
  clock[0]=start;ApocBREventControl.update();check(LuaEventManager.calls==1,"deadline signal without clients/game-time progression");
  check(LuaEventManager.event.equals("OnApocBRLobbyEventDue")&&LuaEventManager.arguments[0].equals(ID),"typed event signal");
  ApocBREventControl.update();check(LuaEventManager.calls==1,"bounded signal retries");
  clock[1]+=1_000_000_000L;ApocBREventControl.update();check(LuaEventManager.calls==2,"retry when mod has not acknowledged");
  check(ApocBREventControl.guideGreeting("Oi!").contains("EventPending"),"do not announce release before mod acceptance");
  check(!ApocBREventControl.acknowledgeRelease(OTHER,1)&&!ApocBREventControl.acknowledgeRelease(ID,Double.NaN)
    &&!ApocBREventControl.acknowledgeRelease(ID,1.5)&&!ApocBREventControl.acknowledgeRelease(ID,1e20),"reject wrong identities/revisions");
  check(ApocBREventControl.acknowledgeRelease(ID,1),"mod accepts removal intent");
  check(ApocBREventControl.guideGreeting("Oi!").contains("EventReleased"),"release greeting uses same accepted event");
  db.fail=true;try{poll(c);throw new AssertionError("connection outage accepted");}catch(RuntimeException expected){checks++;}
  check(db.reported==0,"failed receipt write is not a fabricated acknowledgement");db.fail=false;
  poll(c);ApocBREventControl.update();check(db.reported==1&&db.writes==1,"asynchronous scoped receipt");
  clock[1]+=1_000_000_000L;ApocBREventControl.update();check(LuaEventManager.calls==2,"no repeated signal after acceptance");
  db.row=new ApocBREventControl.Schedule(ID,2,"Changed",start+60000,"scheduled");poll(c);ApocBREventControl.update();
  check(!ApocBREventControl.isDue(ID,1)&&ApocBREventControl.guideGreeting("Oi!").contains("Changed"),"reschedule invalidates old activation");
  db.row=new ApocBREventControl.Schedule(ID,3,"Changed",start,"cancelled");poll(c);ApocBREventControl.update();
  check(!ApocBREventControl.isDue(ID,3)&&ApocBREventControl.guideGreeting("Oi!").equals("Oi!"),"cancel before activation");
  db.row=new ApocBREventControl.Schedule(ID,1,"Extraction",start,"scheduled");poll(c);ApocBREventControl.update();
  check(ApocBREventControl.getStatus().equals("STALE")&&!ApocBREventControl.isDue(ID,1),"reject revision rollback");
  db.row=new ApocBREventControl.Schedule(ID,3,"Changed",start,"cancelled");poll(c);ApocBREventControl.update();
  db.row=new ApocBREventControl.Schedule(ID,3,"Silent edit",start,"scheduled");poll(c);ApocBREventControl.update();
  check(ApocBREventControl.getStatus().equals("STALE"),"same-revision mutation fails closed");
  db.row=new ApocBREventControl.Schedule(OTHER,4,"Overdue",start,"scheduled");poll(c);ApocBREventControl.update();
  clock[1]+=11_000_000_000L;db.queryFail=true;
  try{poll(c);throw new AssertionError("missing schema accepted");}catch(RuntimeException expected){checks++;}
  check(ApocBREventControl.getStatus().equals("STALE")&&!ApocBREventControl.acknowledgeRelease(OTHER,4),"outage suppresses stale activation");
  check(ApocBREventControl.guideGreeting("Oi!").contains("EventUnavailable"),"outage greeting");
  db.queryFail=false;poll(c);ApocBREventControl.update();check(ApocBREventControl.isDue(OTHER,4),"overdue recovery");
  check(ApocBREventControl.acknowledgeRelease(OTHER,4),"recovery acknowledgement");
  db.row=new ApocBREventControl.Schedule(ID,5,"Replacement",start+60000,"scheduled");poll(c);ApocBREventControl.update();
  check(db.reported==4&&db.row.revision()==5,"record actual prior acceptance without acknowledging replacement revision");
  db.row=new ApocBREventControl.Schedule(OTHER,6,"Restart",start,"scheduled");
  c=control(db);poll(c);ApocBREventControl.update();check(ApocBREventControl.isDue(OTHER,6),"restart rereads overdue intent, not transient notifications");
  check(ApocBREventControl.acknowledgeRelease(OTHER,6),"restart safely reapplies desired state");
  poll(c);ApocBREventControl.update();
  c=control(db);poll(c);int before=LuaEventManager.calls;ApocBREventControl.update();
  check(LuaEventManager.calls==before+1,"even reported release must reconcile a world-save gap after restart");
  db.row=new ApocBREventControl.Schedule(OTHER,7,"Cancelled after acceptance",start,"cancelled");
  c=control(db);poll(c);ApocBREventControl.update();
  check(!ApocBREventControl.isDue(OTHER,6)&&ApocBREventControl.isReleaseAuthorized(OTHER,6),"durable receipt restores removal even after backend cancellation");
  check(ApocBREventControl.acknowledgeRelease(OTHER,6),"restore receipt across cancellation/world-save gap");
  db.row=new ApocBREventControl.Schedule(null,8,null,0,"idle");c=control(db);poll(c);ApocBREventControl.update();
  check(ApocBREventControl.isReleaseAuthorized(OTHER,6),"clearing desired schedule must not rebuild a released perimeter");
  check(db.opened==db.closed,"all JDBC leases closed, including failure");
  invalid(()->new ApocBREventControl.Schedule(ID,0,"Bad",start,"scheduled"));
  invalid(()->new ApocBREventControl.Schedule(ID,1,"Bad\nTitle",start,"scheduled"));
  invalid(()->new ApocBREventControl.Schedule("invalid",1,"Bad",start,"scheduled"));
  invalid(()->new ApocBREventControl.Schedule(ID,Long.MAX_VALUE,"Bad",start,"scheduled"));
  invalid(()->new ApocBREventControl.Schedule(ID,1,"Bad",Long.MAX_VALUE,"scheduled"));
  invalid(()->new ApocBREventControl.Schedule(ID,1,"Bad",start,"idle"));
  clock[1]=-20_000_000_000L;c=control(db);poll(c);before=LuaEventManager.calls;ApocBREventControl.update();
  check(LuaEventManager.calls==before+1,"System.nanoTime may be negative; first signal must still run");
  GameServer.server=false;check(ApocBREventControl.getStatus().equals("DISABLED")&&!ApocBREventControl.isDue(OTHER,6),"client guard");
  check(ApocBREventControl.guideGreeting("Oi!").equals("Oi!"),"disabled greeting preserves native text");
  GameServer.server=true;ApocBREventControl.shutdown();check(ApocBREventControl.getStatus().equals("DISABLED"),"shutdown");
  System.out.println("Passed "+checks+" production event SQL/cache/clock/revision/outage/restart/greeting assertions.");
 }
}
'''


def main():
    with tempfile.TemporaryDirectory(prefix="apocbr-event-tests-") as directory:
        work = Path(directory)
        sources = []
        for relative, content in {**FILES, "zombie/apocalipse/events/EventControlTest.java": HARNESS}.items():
            path = work / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8")
            sources.append(path)
        sources.append(ROOT / "42.21.0/src/zombie/apocalipse/events/ApocBREventControl.java")
        args = work / "javac.args"
        args.write_text("\n".join('"' + str(path).replace("\\", "/") + '"' for path in sources), encoding="utf-8")
        output = work / "classes"
        subprocess.run([str(ROOT / "jdk/bin/javac.exe"), "--release", "25", "-encoding", "UTF-8", "-d", str(output), "@" + str(args)], check=True)
        subprocess.run([str(ROOT / "jdk/bin/java.exe"), "-cp", str(output), "zombie.apocalipse.events.EventControlTest"], check=True)


if __name__ == "__main__":
    main()
