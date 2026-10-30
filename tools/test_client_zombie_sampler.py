"""Exercise production bounded trace output and verify observational vanilla hooks."""
from pathlib import Path
import json
import os
import subprocess
import sys
import tempfile
sys.dont_write_bytecode = True
from build_zombie_sampler_client import ROOT, VERSION, NATIVE, instrument

HARNESS = r'''package zombie.apocalipse.diagnostics;
import java.nio.file.*;
public class CaptureTest {
 static int checks;
 static void check(boolean value,String reason){checks++;if(!value)throw new AssertionError(reason);}
 public static void main(String[] args)throws Exception {
  Path directory=Path.of(args[0]);
  boolean client=args.length>1;
  zombie.network.GameClient.client=client;
  ClientZombieSampler.deferred(null,1,2,true);
  ClientZombieSampler.movement(null,"unused",1,2,1);
  ClientZombieSampler.post(null);
  ClientZombieSampler.packet(null,"unused");
  ClientZombieSampler.received(null,null,"unused");
  ClientZombieSampler.animation(null,null);
  var field=ClientZombieSampler.class.getDeclaredField("writer");field.setAccessible(true);
  check(field.get(null)==null,"server or disabled client never starts capture");
  var bodies=ClientZombieSampler.class.getDeclaredField("bodies");bodies.setAccessible(true);
  check(((java.util.Map<?,?>)bodies.get(null)).isEmpty(),"no native entities retained while gated off");
  var quote=ClientZombieSampler.class.getDeclaredMethod("quote",String.class);quote.setAccessible(true);
  var number=ClientZombieSampler.class.getDeclaredMethod("number",float.class);number.setAccessible(true);
  String escaped=(String)quote.invoke(null,"clip\"\\\n\t\u0001");
  Files.writeString(directory.resolve("strings.json"),"{\"text\":"+escaped+",\"bad\":"+number.invoke(null,Float.NaN)+"}");
  check(number.invoke(null,Float.POSITIVE_INFINITY).equals("null"),"nonfinite floats cannot corrupt JSON");
  var rows=new ZombieCaptureWriter(directory.resolve("rows.ndjson"),32,5,65536,5_000_000_000L,"{\"event\":\"metadata\"}");
  for(int n=0;n<100;n++)rows.offer("{\"event\":\"row\",\"n\":"+n+"}");
  rows.close();check(rows.failure()==null&&!rows.accepting(),"row-limited worker closes cleanly");
  check(!rows.offer("{}"),"closed writer refuses new rows");
  var bytes=new ZombieCaptureWriter(directory.resolve("bytes.ndjson"),4,100,64,5_000_000_000L,"{\"event\":\"metadata\"}");
  bytes.offer("{\"text\":\""+"x".repeat(100)+"\"}");bytes.close();check(bytes.failure()==null,"byte cap is a normal completion");
  var duration=new ZombieCaptureWriter(directory.resolve("duration.ndjson"),4,100,65536,50_000_000L,"{\"event\":\"metadata\"}");
  Thread.sleep(350);check(!duration.accepting(),"duration expires even without game hooks");duration.close();
  var pressure=new ZombieCaptureWriter(directory.resolve("pressure.ndjson"),1,1000000,10000000,5_000_000_000L,"{\"event\":\"metadata\"}");
  for(int n=0;n<100000;n++)pressure.offer("{\"event\":\"row\"}");
  pressure.close();check(pressure.dropped()>0,"bounded queue reports dropped rows under pressure");
  try{new ZombieCaptureWriter(directory.resolve("rows.ndjson"),1,1,64,1000000000L,"{}");throw new AssertionError("overwrote previous capture");}
  catch(java.nio.file.FileAlreadyExistsException expected){checks++;}
  System.out.println("Passed "+checks+" client-sampler output/gating assertions.");
 }
}'''


def main():
    for name in NATIVE:
        before = (VERSION / "decompiled" / (name+".java")).read_text(encoding="utf-8")
        after = instrument(name, before)
        lines = [line for line in after.splitlines(keepends=True)
                 if "ClientZombieSampler." not in line and "import zombie.apocalipse.diagnostics.ClientZombieSampler;" not in line]
        if "import zombie.network.GameClient;" not in before:
            lines = [line for line in lines if "import zombie.network.GameClient;" not in line]
        assert "".join(lines) == before, f"Native operations changed beyond observational calls: {name}"
        assert all("if (GameClient.client && ClientZombieSampler.enabled())" in line for line in after.splitlines() if "ClientZombieSampler." in line)
    with tempfile.TemporaryDirectory(prefix="apocbr-sampler-test-") as directory:
        work = Path(directory)
        harness = work / "zombie/apocalipse/diagnostics/CaptureTest.java"
        harness.parent.mkdir(parents=True)
        harness.write_text(HARNESS, encoding="utf-8")
        client = work / "zombie/network/GameClient.java"
        client.parent.mkdir(parents=True)
        client.write_text("package zombie.network;public class GameClient {public static boolean client;public static zombie.characters.IsoZombie getZombie(short id){return null;}}", encoding="utf-8")
        game_jar = Path(r"Z:\SteamLibrary\steamapps\common\ProjectZomboid\projectzomboid.jar")
        sources = list((VERSION / "src/zombie/apocalipse/diagnostics").glob("*.java")) + [harness, client]
        args = work / "javac.args"
        args.write_text("\n".join('"'+str(p).replace("\\", "/")+'"' for p in sources), encoding="utf-8")
        classes = work / "classes"
        subprocess.run([str(ROOT / "jdk/bin/javac.exe"), "--release", "25", "-cp", str(game_jar), "-d", str(classes), "@"+str(args)], check=True)
        for label, flag, extra in [("server", "true", []), ("disabled-client", "false", ["client"])]:
            output = work / label
            output.mkdir()
            subprocess.run([str(ROOT / "jdk/bin/java.exe"), "-Dapocbr.zombieSampler.enabled="+flag,
                            "-cp", str(classes)+os.pathsep+str(game_jar), "zombie.apocalipse.diagnostics.CaptureTest", str(output)]+extra, check=True)
            for path in output.glob("*.ndjson"):
                rows = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()]
                assert rows[0]["event"] == "metadata" and rows[-1]["event"] == "summary"
                assert rows[-1]["rows"] == len(rows)-2
            rows = [json.loads(line) for line in (output / "rows.ndjson").read_text().splitlines()]
            assert rows[-1]["rows"] == 5
            rows = [json.loads(line) for line in (output / "bytes.ndjson").read_text().splitlines()]
            assert rows[-1]["rows"] == 0
            assert json.loads((output / "strings.json").read_text())["text"] == 'clip"\\\n\t\x01'
    print("Passed exact-vanilla hook preservation, JSON parsing, row/byte/time limits and client/server gates.")


if __name__ == "__main__":
    main()
