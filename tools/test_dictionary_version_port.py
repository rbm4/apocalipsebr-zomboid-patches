"""Review51 namespace guard extraction with JDBC doubles; no SQL/game runtime."""
from pathlib import Path
import json
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
V = ROOT / "42.22.0"
REPORT = V / "docs/reports/2026-10-28-merge-51-dictionary-build-version.json"
REL = "42.22.0/src/zombie/savefile/ApocBRWorldDictionaryDatabase.java"


def main():
    record = json.loads(REPORT.read_text())
    with tempfile.TemporaryDirectory(prefix="dictionary_version_") as folder:
        tmp = Path(folder)
        target = tmp / REL
        target.parent.mkdir(parents=True)
        target.write_bytes((ROOT / REL).read_bytes())
        if not record["source_applied"]:
            subprocess.run(["git", "apply", str(REPORT.with_suffix(".diff"))], cwd=tmp, check=True)
        source = target.read_text()
        assert "OR (n.game_build='42.21.0' AND n.world_version=249)" in source
        assert source.count("n.game_build='42.22.0' AND n.world_version=251 AND n.dictionary_format_version=1") == 1
        assert "VALUES(?,?,'42.22.0',251,1) ON CONFLICT(id) DO NOTHING" in source
        assert "SELECT current_revision,name,game_build,world_version,dictionary_format_version" in source
        start = source.index('                        if(!rows.next()')
        end = source.index('revision=Math.addExact(rows.getLong(1),1);', start)
        guard = 'boolean previousBuild;' + source[start:end] + 'return Math.addExact(rows.getLong(1),1);'
        # Execute the actual candidate namespace validation, with ResultSet data
        # supplied by a JDBC proxy. The complete repository is not compiled here.
        program = '''import java.sql.*; import java.lang.reflect.*;
public class DictionaryVersionGuard {
 record Origin(String name) {}
 static long validate(ResultSet rows, Origin origin)throws SQLException {
 GUARD
 }
 static int checks;
 static void run(boolean present,String name,String build,int world,int format,long revision,boolean accepted)throws Exception {
  Object[] values={revision,name,build,world,format};
  ResultSet rows=(ResultSet)Proxy.newProxyInstance(DictionaryVersionGuard.class.getClassLoader(),new Class[]{ResultSet.class},(p,m,a)->switch(m.getName()) {
   case "next" -> present;
   case "getString" -> values[(int)a[0]-1];
   case "getInt" -> ((Number)values[(int)a[0]-1]).intValue();
   case "getLong" -> ((Number)values[(int)a[0]-1]).longValue();
   default -> throw new UnsupportedOperationException(m.getName());
  });
  try {long next=validate(rows,new Origin("expected"));if(!accepted||next!=revision+1)throw new AssertionError("accepted incompatible namespace");}
  catch(SQLException e){if(accepted||!"22023".equals(e.getSQLState()))throw e;}
  checks++;
 }
 public static void main(String[] args)throws Exception {
  run(true,"expected","42.22.0",251,1,0,true);
  run(true,"expected","42.22.0",251,1,100,true);
  run(false,"expected","42.22.0",251,1,0,false);
  run(true,"other","42.22.0",251,1,0,false);
  run(true,"expected","42.21.0",249,1,0,true); // owner/build rollover is checked later
  run(true,"expected","42.21.0",251,1,0,false);
  run(true,"expected",null,251,1,0,false);
  run(true,"expected","42.22.0",249,1,0,false);
  run(true,"expected","42.22.0",251,2,0,false);
  System.out.println("Namespace guard: "+checks+" cases passed (extracted code, JDBC doubles)");
 }
}'''.replace("GUARD", guard)
        java = tmp / "DictionaryVersionGuard.java"
        java.write_text(program)
        javac = shutil.which("javac")
        if javac is None:
            raise RuntimeError("javac required")
        subprocess.run([javac, "-d", str(tmp), str(java)], check=True)
        subprocess.run([str(Path(javac).with_name("java.exe")), "-cp", str(tmp), "DictionaryVersionGuard"], check=True)
        print("Insert/reuse/load metadata source contracts passed; no full repository/JAR/SQL/runtime claim")


if __name__ == "__main__":
    main()
