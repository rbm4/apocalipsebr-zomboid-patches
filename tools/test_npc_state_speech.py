"""42.22.1 state speech policy plus V31 -> V44 on a disposable local PostgreSQL.

Uses actual catalog JDBC hydration with small statement/journal collaborators.
Never connects to an existing database. Requires portable PostgreSQL and JDK25.
"""
from pathlib import Path
import os
import socket
import subprocess
import tempfile
import sys

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / '42.22.1/src/zombie'
MIGRATIONS = ROOT.parent / 'apocalipsebr-manager/manager/src/main/resources/db/game-runtime-migration'
PG = Path(os.environ.get('APOCBR_TEST_POSTGRES_BIN', 'Z:/postgresql/bin'))
HARNESS = r'''package zombie.savefile;
import java.util.*;import java.sql.*;import java.nio.file.*;
import zombie.apocalipse.humans.speech.*;
public class StateSpeechTest {
static int checks;static final long S=1_000_000_000L;
static void check(boolean v,String m){checks++;if(!v)throw new AssertionError(m);}
static SpeechContext context(){return new SpeechContext(null,.7f,0,SpeechContext.Temperament.STEADY,SpeechContext.AttackKind.OTHER,0,false);}
static HumanSpeechEngine engine(HumanSpeechCatalog catalog){return new HumanSpeechEngine("unit","PTBR",new HumanSpeechMemory(),()->catalog,()->0);}
static void policy(HumanSpeechCatalog catalog){
var e=engine(catalog);var first=e.onEvent(SpeechEvent.HEALING,context(),0);
check(first.speaks()&&!first.text().isBlank(),"dressing transition has localized speech");e.delivered(first);
for(int i=1;i<=140;i++)check(!e.onEvent(i%2==0?SpeechEvent.DESPERATION:SpeechEvent.ALLY_ASSIST,context(),i*S/10).speaks(),"rapid state oscillation cannot bypass shared cooldown");
e.cancel();check(!e.onEvent(SpeechEvent.DESPERATION,context(),14*S).speaks(),"cancellation preserves internal state-speech cooldown");
check(e.onEvent(SpeechEvent.DESPERATION,context(),15*S).speaks(),"a different state can speak after shared gap");
check(!e.onEvent(SpeechEvent.HEALING,context(),30*S).speaks(),"per-event dressing cooldown survives cancellation");
check(e.onEvent(SpeechEvent.ALLY_ASSIST,context(),30*S).speaks(),"assistance gets its own pool after shared gap");
check(!e.onEvent(SpeechEvent.DESPERATION,context(),45*S).speaks(),"desperation has a separate 45-second cooldown");
var later=e.onEvent(SpeechEvent.HEALING,context(),60*S);check(later.speaks()&&!later.messageId().equals(first.messageId()),"delivered line repeat avoidance retained");
var tinyRules=new EnumMap<SpeechEvent,HumanSpeechCatalog.Rule>(SpeechEvent.class);for(var event:List.of(SpeechEvent.HEALING,SpeechEvent.DESPERATION,SpeechEvent.ALLY_ASSIST))tinyRules.put(event,new HumanSpeechCatalog.Rule(100,0,50));
var floor=engine(new HumanSpeechCatalog(tinyRules,HumanStateSpeechDefaults.lines()));
check(floor.onEvent(SpeechEvent.HEALING,context(),0).speaks(),"configured state rule admitted");floor.cancel();
check(!floor.onEvent(SpeechEvent.HEALING,context(),15*S).speaks(),"hard minimum event cooldown cannot be configured away");
check(floor.onEvent(SpeechEvent.HEALING,context(),30*S).speaks(),"hard minimum expires at exact boundary");
var actorB=engine(catalog);check(actorB.onEvent(SpeechEvent.HEALING,context(),0).speaks(),"actor engines do not share cooldown state");
}
public static void main(String[] args)throws Exception{
policy(HumanSpeechDefaults.catalog());
String url=args[0];Path migration=Path.of(args[1]);
try(var c=DriverManager.getConnection(url,"postgres","");var s=c.createStatement()){
s.execute("CREATE ROLE apocalipsebr LOGIN;CREATE ROLE pz_server_01 LOGIN;GRANT CREATE ON DATABASE postgres TO apocalipsebr");
s.execute("SET ROLE apocalipsebr;CREATE SCHEMA npc_engine AUTHORIZATION apocalipsebr;CREATE TABLE npc_engine.scope(server_key varchar(128),world varchar(128),PRIMARY KEY(server_key,world))");
s.execute(Files.readString(migration.resolve("V31__Npc_Speech_Engine.sql")));
s.execute(Files.readString(migration.resolve("V44__Npc_Field_State_Speech.sql")));
}
try(var c=DriverManager.getConnection(url,"pz_server_01","");var s=c.createStatement()){
var hydrated=ApocBRNpcSpeech.catalog(c);policy(hydrated);
for(var event:List.of(SpeechEvent.HEALING,SpeechEvent.DESPERATION,SpeechEvent.ALLY_ASSIST))for(var locale:List.of("EN","PTBR")){
var lines=hydrated.lines(event,locale);check(lines.size()==10,"ten variants per event and language");
var expected=HumanSpeechDefaults.catalog().lines(event,locale);
for(var line:lines)check(expected.contains(line),"migration/default content parity: "+line.id());
check(hydrated.rule(event).equals(HumanSpeechDefaults.catalog().rule(event)),"rule parity");
}
try(var r=s.executeQuery("SELECT count(*) FROM npc_engine.speech_rule")){r.next();check(r.getInt(1)==SpeechEvent.values().length-4,"existing pool rules preserved and exactly three added");}
s.execute("INSERT INTO npc_engine.speech_pool_line(message_id,event,locale,text) VALUES('permission.probe','HEALING','EN','Hold the line.')");
check(s.executeUpdate("DELETE FROM npc_engine.speech_pool_line WHERE message_id='permission.probe'")==1,"runtime role retains content table privileges");
try{s.execute("INSERT INTO npc_engine.speech_rule VALUES('NOT_A_REAL_EVENT',100,1000,10)");throw new AssertionError("constraint accepts unknown event");}
catch(SQLException expected){check(expected.getSQLState().equals("23514"),"forward migration retains closed vocabulary");}
try(var r=s.executeQuery("SELECT count(*) FROM pg_tables WHERE schemaname='npc_engine' AND tablename IN ('speech_rule','speech_pool_line') AND tableowner='apocalipsebr'")){r.next();check(r.getInt(1)==2,"migration and engine roles stay distinct");}
}
System.out.println("State speech/migration: "+checks+" assertions passed (production 42.22.1 policy/JDBC; isolated PostgreSQL)");
}}
'''


def run(args, **kwargs):
    subprocess.run([str(x) for x in args], check=True, **kwargs)


def main():
    for binary in ('initdb.exe', 'pg_ctl.exe'):
        assert (PG / binary).is_file(), f'Provide APOCBR_TEST_POSTGRES_BIN: missing {PG / binary}'
    stubs = {
        'zombie/savefile/ApocBRNpcStore.java': '''package zombie.savefile;import java.sql.*;
class ApocBRNpcStore {static PreparedStatement statement(Connection c,String sql,Object... values)throws SQLException {var s=c.prepareStatement(sql);for(int i=0;i<values.length;i++)s.setObject(i+1,values[i]);return s;}}''',
        'zombie/savefile/ApocBRPersistenceOperations.java': '''package zombie.savefile;import java.util.*;
class ApocBRPersistenceOperations {record Sql(String sql,List<Object> values){}record Account(List<Sql> statements){}}''',
        'zombie/savefile/ApocBRPersistence.java': 'package zombie.savefile;class ApocBRPersistence {static void submit(Object work){throw new AssertionError("unexpected journal submission");}}',
        'zombie/savefile/StateSpeechTest.java': HARNESS,
    }
    with tempfile.TemporaryDirectory(prefix='npc_state_speech_') as folder:
        work = Path(folder).resolve()
        assert work.is_relative_to(Path(tempfile.gettempdir()).resolve())
        sources = list((SOURCE / 'apocalipse/humans/speech').glob('*.java')) + [SOURCE / 'savefile/ApocBRNpcSpeech.java']
        for relative, content in stubs.items():
            path = work / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding='utf-8')
            sources.append(path)
        args = work / 'javac.args'
        args.write_text('\n'.join('"' + str(p).replace('\\', '/') + '"' for p in sources), encoding='utf-8')
        driver = ROOT / '42.22.1/lib/postgresql-42.7.14.jar'
        run([ROOT/'jdk/bin/javac.exe', '--release', '25', '-encoding', 'UTF-8', '-d', work/'classes', '@'+str(args)])
        with socket.socket() as sock:
            sock.bind(('127.0.0.1', 0))
            port = sock.getsockname()[1]
        run([PG/'initdb.exe', '-D', work/'data', '-U', 'postgres', '-A', 'trust', '--no-locale', '-E', 'UTF8'], stdout=subprocess.DEVNULL)
        try:
            run([PG/'pg_ctl.exe', '-D', work/'data', '-l', work/'postgres.log', '-o', f'-h 127.0.0.1 -p {port}', '-w', 'start'], stdout=subprocess.DEVNULL)
            run([ROOT/'jdk/bin/java.exe', '-cp', str(work/'classes')+os.pathsep+str(driver), 'zombie.savefile.StateSpeechTest', f'jdbc:postgresql://127.0.0.1:{port}/postgres', MIGRATIONS])
            if '--manager-test' in sys.argv:
                manager=MIGRATIONS.parents[4]
                env=dict(os.environ,JAVA_HOME=str(ROOT/'jdk'),GAME_RUNTIME_TEST_POSTGRES_URL=f'jdbc:postgresql://127.0.0.1:{port}/postgres')
                run([manager/'mvnw.cmd','-o','-Dtest=NpcFieldStateSpeechMigrationTest','test'],cwd=manager,env=env)
        finally:
            run([PG/'pg_ctl.exe', '-D', work/'data', '-m', 'immediate', '-w', 'stop'], stdout=subprocess.DEVNULL)


if __name__ == '__main__':
    main()
