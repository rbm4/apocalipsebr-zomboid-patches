from pathlib import Path
import subprocess, tempfile
root = Path(__file__).resolve().parents[1]
source = root / '42.22.2/src/zombie/network/ApocBRFixedPower.java'
jdk = root / 'jdk/bin'
with tempfile.TemporaryDirectory() as d:
    p = Path(d)
    (p/'GameServer.java').write_text('package zombie.network; public class GameServer { public static boolean server = true; }')
    (p/'Test.java').write_text('''import zombie.network.*;
public class Test { public static void main(String[] a) {
if (!ApocBRFixedPower.setRegion("a", 10, 20, 5, 15, 2, 0)) throw new AssertionError();
if (!ApocBRFixedPower.contains(5,15,0) || !ApocBRFixedPower.contains(10,20,2) || ApocBRFixedPower.contains(4,15,0) || ApocBRFixedPower.contains(5,15,3)) throw new AssertionError();
if (!ApocBRFixedPower.setRegion("b", 7, 17, 8, 18, 0, 1) || !ApocBRFixedPower.removeRegion("a") || !ApocBRFixedPower.contains(7,17,1) || ApocBRFixedPower.contains(5,15,0)) throw new AssertionError();
if (ApocBRFixedPower.setRegion("b", -1, 0, 1, 1, 0, 1) || !ApocBRFixedPower.contains(7,17,1)) throw new AssertionError();
if (!ApocBRFixedPower.setRegion("b", 100,100,101,101,0,0) || ApocBRFixedPower.contains(7,17,1)) throw new AssertionError();
GameServer.server = false;
if (ApocBRFixedPower.contains(100,100,0) || ApocBRFixedPower.setRegion("c",0,0,1,1,0,1) || ApocBRFixedPower.removeRegion("b")) throw new AssertionError();
} }''')
    subprocess.run([str(jdk/'javac.exe'), '-d', d, str(p/'GameServer.java'), str(source), str(p/'Test.java')], check=True)
    subprocess.run([str(jdk/'java.exe'), '-cp', d, 'Test'], check=True)
print('fixed power registry tests passed')
