"""42.22.0 SpriteConfig capture/restore using its exact native source.

Reuse shared Java fixture definitions without executing the 42.21.0 test scripts.
Compile the 42.22.0 native SpriteConfig baseline with available game dependencies.
Only selected tests run; this is not full target-JAR or login validation.
"""
import ast
import os
from pathlib import Path
import subprocess
import tempfile
from postgres_test_support import inactive_persistence_stubs

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / '42.22.0/src/zombie/savefile'
GAME = Path(os.environ.get('APOCBR_TEST_GAME_JAR', r'Z:\SteamLibrary\steamapps\common\ProjectZomboid\projectzomboid.jar'))
JDK = ROOT / 'jdk/bin'


def harness(name, class_name):
    tree = ast.parse((ROOT / 'tools' / name).read_text(encoding='utf-8'))
    return next(node.value for node in ast.walk(tree)
                if isinstance(node, ast.Constant) and isinstance(node.value, str)
                and f'public class {class_name} {{' in node.value)


def method(source, signature):
    start = source.rfind('\n', 0, source.index(signature)) + 1
    end = source.index('\n }', start) + len('\n }')
    return source[start:end] + '\n'


relational = harness('test_postgres_player_relational.py', 'RelationalTest')
reconstruction = harness('test_postgres_player_reconstruction.py', 'ReconstructionTest')
models = relational[relational.index(' static class Color'):relational.index(' static void captures()')]
models = models.replace('public float actualWeight=0f;', 'public float actualWeight=0f,weight=0f;')
java = '''package zombie.savefile;
import java.util.*;
import java.lang.reflect.*;
public class SpriteConfigTargetTest {
 static int checks;
 static void check(boolean ok,String text){if(!ok)throw new AssertionError(text);checks++;}
 static Object blank(Class<?> type)throws Exception {
  var f=sun.misc.Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);
  return ((sun.misc.Unsafe)f.get(null)).allocateInstance(type);
 }
 static Object invoke(String name,Class<?>[] types,Object... args)throws Exception {
  var m=ApocBRPlayerRelationalReconstruction.class.getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(null,args);
 }
'''
java += models
for signature in ('static <T> T nativeComponent(', 'static void field(', 'static List<String> canonical(',
                  'static void detached(', 'static ApocBRPlayerRelationalCapture.Graph liveGraph(',
                  'static void spriteConfigCodec('):
    java += method(relational, signature)
java += method(reconstruction, 'static void spriteConfig(')
java += ''' public static void main(String[] args)throws Exception {
 spriteConfigCodec();spriteConfig();System.out.println("42.22.0 SpriteConfig checks="+checks);
 }
}
'''

with tempfile.TemporaryDirectory(prefix='apocbr_sprite_4222_') as folder:
    target = Path(folder)
    fixture = target / 'SpriteConfigTargetTest.java'
    fixture.write_text(java, encoding='utf-8')
    connector = target / 'ApocBRPostgresConnector.java'
    connector.write_text('''package zombie.savefile;
public class ApocBRPostgresConnector {
 public static int dictionaryPublicationTimeoutSeconds(){return 30;}
 public static String sqlDiagnostic(Throwable error,boolean hints){return "exception="+error.getClass().getSimpleName();}
 public static java.sql.Connection borrowDictionaryConnection()throws java.sql.SQLException{throw new java.sql.SQLException();}
 public static java.sql.Connection borrowPlayerConnection()throws java.sql.SQLException{throw new java.sql.SQLException();}
}''', encoding='utf-8')
    names = ['Limits', 'Schema', 'Access', 'Snapshot', 'Codecs', 'Capture', 'Mutations',
             'Delta', 'Metrics', 'Database', 'Reader', 'Reconstruction']
    sources = [SRC / 'ApocBRPayloadProvenance.java', SRC / 'ApocBRPlayerReconstructionLog.java']
    sources += [SRC / f'ApocBRPlayerRelational{name}.java' for name in names]
    sources += [SRC.parent / path for path in ('world/ApocBRWorldDictionaryDecode.java',
                'world/ApocBRWorldDictionarySnapshot.java', 'savefile/ApocBRWorldDictionaryDatabase.java')]
    sources += inactive_persistence_stubs(target)
    sources += [ROOT / '42.22.0/decompiled/zombie/entity/components/spriteconfig/SpriteConfig.java']
    subprocess.run([str(JDK / 'javac.exe'), '--release', '25', '-cp', str(GAME), '-d', folder,
                    *map(str, sources), str(connector), str(fixture)], check=True)
    subprocess.run([str(JDK / 'java.exe'), '-cp', folder + os.pathsep + str(GAME),
                    'zombie.savefile.SpriteConfigTargetTest'], check=True)
