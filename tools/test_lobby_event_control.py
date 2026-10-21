"""Execute production event worker/cache with fake JDBC and deterministic clocks; no live DB."""
from pathlib import Path
import subprocess
import os
import tempfile

ROOT = Path(__file__).resolve().parents[1]
GAME_JAR = Path(os.environ.get("PZ_GAME_JAR", "Z:/SteamLibrary/steamapps/common/ProjectZomboid/projectzomboid.jar"))
FILES = {
    "se/krka/kahlua/vm/KahluaTable.java": 'package se.krka.kahlua.vm; public class KahluaTable { private final java.util.Map<Object,Object> data=new java.util.HashMap<>(); public void rawset(Object key,Object value){data.put(key,value);} public Object rawget(Object key){return data.get(key);} }',
    "zombie/Lua/LuaManager.java": 'package zombie.Lua; public class LuaManager { public static final Platform platform=new Platform(); public static class Platform {public se.krka.kahlua.vm.KahluaTable newTable(){return new se.krka.kahlua.vm.KahluaTable();}} }',

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
  String nextName="Extraction",activeName;long nextStart=start,activeEnd,updatedAt=start-1000;
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
      case "getString" -> switch((int)ra[0]){case 1->row.id();case 3->row.name();case 5->row.state();case 6->reportedId;case 8->reportedId==null?null:"release_requested";case 9->nextName;case 11->activeName;case 14->row.metadataJson();default->throw new AssertionError();};
      case "getLong" -> (int)ra[0]==7?reported:row.revision();
      case "getTimestamp" -> {long value=switch((int)ra[0]){case 4->row.startsAt();case 10->nextStart;case 12->activeEnd;case 13->updatedAt;default->throw new AssertionError();};yield value==0?null:new Timestamp(value);}
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
 static se.krka.kahlua.vm.KahluaTable context(){return (se.krka.kahlua.vm.KahluaTable)LuaEventManager.arguments[2];}
 static void invalid(Runnable action){try{action.run();throw new AssertionError("invalid schedule accepted");}catch(IllegalArgumentException|NullPointerException expected){checks++;}}
 public static void main(String[] ignored) throws Exception {
  Database db=new Database();var c=control(db);
  String metadata="{\"parameters\":{\"objectives\":[{\"target\":100}]}}";
  db.row=new ApocBREventControl.Schedule(ID,1,"Extraction",start,"scheduled",metadata);
  check(ApocBREventControl.DEFAULT_POLL_MILLIS==30000&&ApocBREventControl.DEFAULT_FRESH_MILLIS==90000,"30 second polling with compatible freshness");
  check(ApocBREventControl.getStatus().equals("STARTING"),"starting without a database result");
  poll(c);check(ApocBREventControl.getEventId()==null,"worker cannot install main-thread snapshot");
  ApocBREventControl.update();check(LuaEventManager.calls==0&&ApocBREventControl.getStatus().equals("READY"),"future schedule");
  check(db.openingThread!=Thread.currentThread(),"connection only used on worker");
  int queries=db.queries;
  String greeting=ApocBREventControl.guideAnnouncement();
  check(greeting.contains("A pr\u00f3xima opera\u00e7\u00e3o")&&greeting.contains("1s")&&greeting.contains("Extraction"),"relative countdown without greeting");
  check(ApocBREventControl.remaining(90061001).equals("1d 1h 1min 2s"),"days/hours/minutes/seconds rounded up");
  check(ApocBREventControl.remaining(1).equals("1s"),"positive subsecond countdown never says zero");
  check(ApocBREventControl.getStartsAtMillis()==start&&ApocBREventControl.getRevision()==1,"cached typed getters");
  var cached=ApocBREventControl.getEventContext(ID,1);
  check(cached.rawget("metadataJson").equals(metadata),"matching cached metadata before deadline");
  cached.rawset("metadataJson","mutated");
  check(ApocBREventControl.getEventContext(ID,1).rawget("metadataJson").equals(metadata),"Lua mutation cannot alter cache");
  check(ApocBREventControl.getEventContext(OTHER,1)==null&&ApocBREventControl.getEventContext(ID,2)==null,"context requires exact identity/revision");
  check(!ApocBREventControl.isDue(ID,1)&&!ApocBREventControl.acknowledgeRelease(ID,1),"do not activate early");
  check(db.queries==queries,"Lua getters/greetings perform no SQL");
  clock[0]=start;ApocBREventControl.update();check(LuaEventManager.calls==1,"deadline signal without clients/game-time progression");
  check(LuaEventManager.event.equals("OnApocBRLobbyEventDue")&&LuaEventManager.arguments[0].equals(ID),"typed event signal");
  check(LuaEventManager.arguments.length==3&&context().rawget("metadataJson").equals(metadata)
    &&Boolean.FALSE.equals(context().rawget("isReplay")),"third argument is matching metadata table");
  ApocBREventControl.update();check(LuaEventManager.calls==1,"bounded signal retries");
  clock[1]+=1_000_000_000L;ApocBREventControl.update();check(LuaEventManager.calls==2,"retry when mod has not acknowledged");
  check(ApocBREventControl.guideGreeting("Oi!").contains("aguardando a libera\u00e7\u00e3o"),"do not announce release before mod acceptance");
  check(!ApocBREventControl.acknowledgeRelease(OTHER,1)&&!ApocBREventControl.acknowledgeRelease(ID,Double.NaN)
    &&!ApocBREventControl.acknowledgeRelease(ID,1.5)&&!ApocBREventControl.acknowledgeRelease(ID,1e20),"reject wrong identities/revisions");
  check(ApocBREventControl.acknowledgeRelease(ID,1),"mod accepts removal intent");
  db.fail=true;try{poll(c);throw new AssertionError("connection outage accepted");}catch(RuntimeException expected){checks++;}
  check(db.reported==0,"failed receipt write is not a fabricated acknowledgement");db.fail=false;
  db.activeName="Extraction";db.activeEnd=start+3600000;db.nextName="Next";db.nextStart=start+7200000;
  poll(c);ApocBREventControl.update();
  String both=ApocBREventControl.guideAnnouncement();
  var phrases=ApocBREventControl.guideAnnouncementLines();
  check(phrases.size()==2&&phrases.get(0).contains("est\u00e1 ativa")&&phrases.get(1).contains("A pr\u00f3xima opera\u00e7\u00e3o"),"active and next operation exposed as two ordered phrases");
  try{phrases.clear();throw new AssertionError("mutable phrases");}catch(UnsupportedOperationException expected){checks++;}
  int beforePhrases=db.queries;ApocBREventControl.guideAnnouncementLines();
  check(db.queries==beforePhrases,"structured announcement reads cached data without SQL");
  check(both.contains("est\u00e1 ativa")&&both.contains("1h 0min 0s")&&both.contains("A pr\u00f3xima opera\u00e7\u00e3o")&&both.contains("2h 0min 0s"),"confirmed active estimate and next countdown in separate follow-up");
  db.activeName=null;db.activeEnd=0;db.nextName="Changed";db.nextStart=start+60000;
  poll(c);ApocBREventControl.update();check(db.reported==1&&db.writes==1,"asynchronous scoped receipt");
  clock[1]+=1_000_000_000L;ApocBREventControl.update();check(LuaEventManager.calls==2,"no repeated signal after acceptance");
  check(phrases.get(1).contains("Next")&&!phrases.get(1).contains("Changed"),"previous phrase snapshot survives schedule changes");
  db.row=new ApocBREventControl.Schedule(ID,2,"Changed",start+60000,"scheduled");poll(c);ApocBREventControl.update();
  check(!ApocBREventControl.isDue(ID,1)&&ApocBREventControl.guideGreeting("Oi!").contains("Changed"),"reschedule invalidates old activation");
  db.row=new ApocBREventControl.Schedule(ID,3,"Changed",start,"cancelled");poll(c);ApocBREventControl.update();
  db.nextName=null;db.nextStart=0;poll(c);ApocBREventControl.update();
  check(!ApocBREventControl.isDue(ID,3)&&ApocBREventControl.guideAnnouncement().contains("N\u00e3o h\u00e1 pr\u00f3ximas"),"cancel clears next announcement");
  db.row=new ApocBREventControl.Schedule(ID,1,"Extraction",start,"scheduled");poll(c);ApocBREventControl.update();
  check(ApocBREventControl.getStatus().equals("STALE")&&!ApocBREventControl.isDue(ID,1),"reject revision rollback");
  db.row=new ApocBREventControl.Schedule(ID,3,"Changed",start,"cancelled");poll(c);ApocBREventControl.update();
  db.row=new ApocBREventControl.Schedule(ID,3,"Silent edit",start,"scheduled");poll(c);ApocBREventControl.update();
  check(ApocBREventControl.getStatus().equals("STALE"),"same-revision mutation fails closed");
  db.row=new ApocBREventControl.Schedule(ID,3,"Changed",start,"cancelled");poll(c);ApocBREventControl.update();
  db.row=new ApocBREventControl.Schedule(ID,3,"Changed",start,"cancelled","{\"changed\":true}");poll(c);ApocBREventControl.update();
  check(ApocBREventControl.getStatus().equals("STALE")&&ApocBREventControl.getEventContext(ID,3)==null,"same-revision metadata edit fails closed");
  db.row=new ApocBREventControl.Schedule(OTHER,4,"Overdue",start,"scheduled");poll(c);ApocBREventControl.update();
  clock[1]+=11_000_000_000L;db.queryFail=true;
  try{poll(c);throw new AssertionError("missing schema accepted");}catch(RuntimeException expected){checks++;}
  check(ApocBREventControl.getStatus().equals("STALE")&&!ApocBREventControl.acknowledgeRelease(OTHER,4),"outage suppresses stale activation");
  check(ApocBREventControl.guideGreeting("Oi!").contains("temporariamente indispon\u00edvel"),"outage greeting");
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
  check(Boolean.TRUE.equals(context().rawget("isReplay")),"matching overdue receipt explicitly marked as replay");
  db.row=new ApocBREventControl.Schedule(OTHER,7,"Cancelled after acceptance",start,"cancelled");
  c=control(db);poll(c);ApocBREventControl.update();
  check(!ApocBREventControl.isDue(OTHER,6)&&ApocBREventControl.isReleaseAuthorized(OTHER,6),"durable receipt restores removal even after backend cancellation");
  check(Boolean.TRUE.equals(context().rawget("isReplay"))&&context().rawget("eventId").equals(OTHER)
    &&context().rawget("metadataJson")==null,"old receipt never receives new schedule metadata");
  check(ApocBREventControl.acknowledgeRelease(OTHER,6),"restore receipt across cancellation/world-save gap");
  db.row=new ApocBREventControl.Schedule(null,8,null,0,"idle");c=control(db);poll(c);ApocBREventControl.update();
  check(ApocBREventControl.isReleaseAuthorized(OTHER,6),"clearing desired schedule must not rebuild a released perimeter");
  db.activeName="Expired";db.activeEnd=clock[0];poll(c);ApocBREventControl.update();
  check(ApocBREventControl.guideAnnouncement().contains("N\u00e3o h\u00e1 pr\u00f3ximas"),"expired active estimate omitted");
  db.updatedAt=clock[0]-60001;poll(c);ApocBREventControl.update();
  check(ApocBREventControl.getStatus().equals("READY")&&ApocBREventControl.guideAnnouncement().contains("temporariamente indispon\u00edvel"),"manager snapshot ages even with healthy engine polling");
  db.updatedAt=clock[0]+1000;poll(c);ApocBREventControl.update();
  check(ApocBREventControl.guideAnnouncement().contains("temporariamente indispon\u00edvel"),"future manager timestamps fail closed");
  check(db.opened==db.closed,"all JDBC leases closed, including failure");
  invalid(()->new ApocBREventControl.Schedule(ID,0,"Bad",start,"scheduled"));
  invalid(()->new ApocBREventControl.Schedule(ID,1,"Bad\nTitle",start,"scheduled"));
  invalid(()->new ApocBREventControl.Schedule("invalid",1,"Bad",start,"scheduled"));
  invalid(()->new ApocBREventControl.Schedule(ID,Long.MAX_VALUE,"Bad",start,"scheduled"));
  invalid(()->new ApocBREventControl.Schedule(ID,1,"Bad",Long.MAX_VALUE,"scheduled"));
  invalid(()->new ApocBREventControl.Schedule(ID,1,"Bad",start,"idle"));
  invalid(()->new ApocBREventControl.Schedule(ID,1,"Bad",start,"scheduled","[]"));
  invalid(()->new ApocBREventControl.Schedule(ID,1,"Bad",start,"scheduled","{} trailing"));
  invalid(()->new ApocBREventControl.Schedule(ID,1,"Bad",start,"scheduled"," ".repeat(32769)));
  var slow=new ApocBREventControl("scope","world",30000,90000,ZoneId.of("UTC"),db::open,()->clock[0],()->clock[1]);
  poll(slow);slow.acceptIncoming();clock[1]+=30_000_000_000L;check(slow.fresh(),"default cache stays fresh between 30 second polls");
  clock[1]+=60_000_000_001L;check(!slow.fresh(),"90 second outage expires default snapshot");
  clock[1]=-20_000_000_000L;c=control(db);poll(c);before=LuaEventManager.calls;ApocBREventControl.update();
  check(LuaEventManager.calls==before+1,"System.nanoTime may be negative; first signal must still run");
  GameServer.server=false;check(ApocBREventControl.getStatus().equals("DISABLED")&&!ApocBREventControl.isDue(OTHER,6),"client guard");
  check(ApocBREventControl.guideGreeting("Oi!").equals("Oi!"),"disabled greeting preserves native text");
  check(ApocBREventControl.guideAnnouncementLines().isEmpty(),"disabled controller supplies empty announcement");
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
        sources.extend(ROOT / ("42.21.0/src/zombie/apocalipse/humans/dialogue/"+name+".java") for name in ("HumanLanguage","HumanDialogueCatalog"))
        args = work / "javac.args"
        args.write_text("\n".join('"' + str(path).replace("\\", "/") + '"' for path in sources), encoding="utf-8")
        output = work / "classes"
        subprocess.run([str(ROOT / "jdk/bin/javac.exe"), "--release", "25", "-encoding", "UTF-8", "-cp", str(GAME_JAR), "-d", str(output), "@" + str(args)], check=True)
        subprocess.run([str(ROOT / "jdk/bin/java.exe"), "-cp", os.pathsep.join((str(output), str(GAME_JAR))), "zombie.apocalipse.events.EventControlTest"], check=True)


if __name__ == "__main__":
    main()
