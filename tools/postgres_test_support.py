"""Inactive persistence boundary for the pre-existing isolated SQL/engine fixtures.

Those fixtures don't start a server lifecycle. Outage/journal integration is exercised
against the real coordinator and adapters in test_postgres_resilience.py instead.
"""
from pathlib import Path


def inactive_persistence_stubs(target: Path, *, mutations=False):
    sources = {
        'ApocBRPersistence': '''package zombie.savefile; final class ApocBRPersistence {
 static <T>T status(){return null;}
 static boolean enabled(){return false;} static boolean journaling(){return false;}
 static boolean background(){return false;} static void background(boolean value){} static boolean reachable(){return true;}
 static boolean pendingPlayer(String key){return false;}
 static boolean admitting(){return true;} static String state(){return "STARTING";}
 static void failed(java.sql.SQLException e){} static void startup(){} static void finishStartup(){}
 static void drain(){} static void shutdownWork(Runnable work){work.run();} static void close(){}
 static java.util.UUID submit(Object value){throw new AssertionError("Inactive persistence boundary invoked");}
 static boolean claim(java.sql.Connection c){return true;}
}''',
        'ApocBRPersistenceOperations': '''package zombie.savefile; final class ApocBRPersistenceOperations {
 static String key(String world,String identity,int slot,boolean coop){return world+identity+slot+coop;}
 record Native(Object snapshot){} record Initial(Object candidate,java.util.UUID session,Object snapshot){}
 record Live(Object batch) implements ApocBRPersistenceJournal.Operation {public String kind(){return "fixture-live-v1";}public long estimatedBytes(){return 1024;}} record Sql(String sql,java.util.List<Object> parameters){}
 record Account(java.util.List<Sql> statements){}
}''',
        'ApocBRLoginGuard': '''package zombie.savefile; public final class ApocBRLoginGuard {
 public static boolean enabled(){return false;} public static boolean ready(Object c){return true;}
 public static void deny(Object c){throw new AssertionError("Unexpected profile denial");}
}''',
    }
    if mutations:
        sources['ApocBRPlayerRelationalMutations'] = '''package zombie.savefile;
public final class ApocBRPlayerRelationalMutations {public static boolean hasPending(){return false;}static boolean hasPending(String world,String identity,int slot,boolean coop){return false;}}'''
    files = []
    for name, content in sources.items():
        path = target / 'persistence-boundary' / 'zombie' / 'savefile' / (name + '.java')
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding='utf-8')
        files.append(path)
    files.append(Path(__file__).resolve().parents[1] / '42.21.0/src/zombie/savefile/ApocBRForegroundDatabase.java')
    files.append(Path(__file__).resolve().parents[1] / '42.21.0/src/zombie/savefile/ApocBRPersistenceJournal.java')
    return files
