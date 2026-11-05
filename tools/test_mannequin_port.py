"""Review23 encoder fixture with actual payload-provenance implementation."""
from pathlib import Path
import subprocess
import sys
import tempfile

sys.dont_write_bytecode = True
from test_unload_simulation_algorithms import method

ROOT = Path(__file__).resolve().parents[1]
V = ROOT / "42.22.0"


def main():
    with tempfile.TemporaryDirectory(prefix="pz-mannequin-port-") as directory:
        work = Path(directory)
        p = work / "42.22.0/src/zombie/iso/objects/IsoMannequin.java"
        p.parent.mkdir(parents=True)
        p.write_bytes((V / "decompiled/zombie/iso/objects/IsoMannequin.java").read_bytes())
        subprocess.run(["git", "apply", str(V / "docs/reports/2026-10-28-merge-23-iso-mannequin.diff")], cwd=work, check=True)
        candidate = p.read_text(encoding="utf-8-sig")
        applied = V / "src/zombie/iso/objects/IsoMannequin.java"
        if applied.exists():
            assert applied.read_text(encoding="utf-8-sig") == candidate
        encoder = method(candidate, "public void setCustomSettingsToItem(InventoryItem item)")
        assert "output.putInt(251)" in encoder and "Metadata(encodingOrigin,251)" in encoder
        java = r'''
import java.nio.*;import java.io.*;import java.util.*;import zombie.savefile.ApocBRPayloadProvenance;
class MannequinFixture {
 static int checks;static void check(boolean v){checks++;if(!v)throw new AssertionError("check "+checks);}
 static class InventoryItem {ByteBuffer byteData;float weight=2;float getActualWeight(){return weight;}void setActualWeight(float w){weight=w;}}
 static class Moveable extends InventoryItem {}
 static class Container {float getContentsWeight(){return 3;}}
 static class SliceY {static final Object SliceBufferLock=new Object();static final ByteBuffer SliceBuffer=ByteBuffer.allocate(64);}
 Container container;boolean fail;ApocBRPayloadProvenance publishDuringSave;
 void save(ByteBuffer b)throws IOException {if(fail)throw new IOException("save");b.putInt(42);if(publishDuringSave!=null)ApocBRPayloadProvenance.published(publishDuringSave);}
 ENCODER
 public static void main(String[] args)throws Exception {
  System.setProperty("apocbr.postgres.serverId","1");MannequinFixture m=new MannequinFixture();InventoryItem other=new InventoryItem();
  m.setCustomSettingsToItem(other);check(other.byteData==null);check(other.weight==2);
  Moveable first=new Moveable();m.setCustomSettingsToItem(first);var metadata=ApocBRPayloadProvenance.metadata(first.byteData);
  check(metadata.worldVersion()==251);check(metadata.origin()==null);check(first.byteData.getInt(0)==251);check(first.byteData.getInt(4)==42);check(first.byteData.position()==8);
  var a=new ApocBRPayloadProvenance(1,UUID.randomUUID(),UUID.randomUUID());var b=new ApocBRPayloadProvenance(1,a.namespaceId(),UUID.randomUUID());
  ApocBRPayloadProvenance.published(a);m.publishDuringSave=b;m.container=new Container();Moveable second=new Moveable();m.setCustomSettingsToItem(second);
  check(ApocBRPayloadProvenance.metadata(second.byteData).origin().equals(a));check(ApocBRPayloadProvenance.localEncoding().equals(b));check(second.weight==5);
  check(first.byteData!=second.byteData);check(first.byteData.getInt(4)==42);check(ApocBRPayloadProvenance.metadata(first.byteData).origin()==null);
  m.fail=true;ByteBuffer original=second.byteData;try{m.setCustomSettingsToItem(second);throw new AssertionError("expected failure");}catch(IOException expected){}
  check(second.byteData==original);check(ApocBRPayloadProvenance.metadata(original).origin().equals(a));check(second.weight==5);
  System.out.println("Mannequin encoder/provenance: "+checks+" assertions passed");
 }
}
'''.replace("ENCODER", encoder)
        p = work / "MannequinFixture.java"
        p.write_text(java, encoding="utf-8")
        subprocess.run(["javac", "-d", str(work), str(p), str(V / "src/zombie/savefile/ApocBRPayloadProvenance.java")], check=True)
        subprocess.run(["java", "-cp", str(work), "MannequinFixture"], check=True)


if __name__ == "__main__":
    main()
