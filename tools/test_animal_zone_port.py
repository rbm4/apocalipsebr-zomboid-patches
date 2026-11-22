"""Review-18 topology fixture; reconstruct candidate without changing target sources."""
from pathlib import Path
import subprocess
import sys
import tempfile

sys.dont_write_bytecode = True
from test_unload_simulation_algorithms import method, TOPOLOGY_TEST

ROOT = Path(__file__).resolve().parents[1]
V = ROOT / "42.22.0"


def main():
    native = (V / "decompiled/zombie/iso/areas/DesignationZoneAnimal.java").read_text(encoding="utf-8-sig")
    with tempfile.TemporaryDirectory(prefix="pz-zone-port-") as directory:
        work = Path(directory)
        target = work / "42.22.0/src/zombie/iso/areas/DesignationZoneAnimal.java"
        target.parent.mkdir(parents=True)
        target.write_text(native, encoding="utf-8", newline="\n")
        subprocess.run(["git", "apply", str(V / "docs/reports/2026-10-28-merge-18-designation-zone-animal.diff")], cwd=work, check=True)
        candidate = target.read_text(encoding="utf-8")
        applied = V / "src/zombie/iso/areas/DesignationZoneAnimal.java"
        if applied.exists():
            assert applied.read_text(encoding="utf-8-sig") == candidate
        for signature in ["public void doMeta(int hours)", "public Set<IsoAnimal> getAnimalsInZone()", "private void reAttachAnimal()"]:
            assert method(candidate, signature) == method(native, signature)
        assert ".animals.get(" not in candidate
        zone = "package zombie.iso.areas;import java.util.*;public class DesignationZoneAnimal {public int x,y,z,w,h;public static final ArrayList<DesignationZoneAnimal> designationAnimalZoneList=new ArrayList<>();"
        zone += method(native, "public static DesignationZoneAnimal getZone(int x, int y, int z)")
        zone += method(native, "public static ArrayList<DesignationZoneAnimal> getAllDZones(").replace("getAllDZones", "vanillaConnected")
        zone += method(candidate, "static void collectConnected(")
        zone += method(candidate, "private static void admitConnected(") + "}"
        sources = {
            "zombie/iso/areas/DesignationZoneAnimal.java": zone,
            "zombie/iso/areas/TopologyTest.java": TOPOLOGY_TEST.replace("public class TopologyTest {", "public class TopologyTest {public static void main(String[] args){run();}"),
            "zombie/ApocBRServerTelemetryLite.java": "package zombie;public class ApocBRServerTelemetryLite {public static final java.util.Map<String,Long> counts=new java.util.HashMap<>();public static void count(String n,long v){counts.merge(n,v,Long::sum);}public static void recordPhase(String n,long v){}}",
        }
        paths = []
        for relative, content in sources.items():
            p = work / relative
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(content, encoding="utf-8")
            paths.append(str(p))
        paths.append(str(V / "src/zombie/iso/areas/ServerAnimalZoneTopology.java"))
        subprocess.run(["javac", "-d", str(work), *paths], check=True)
        subprocess.run(["java", "-cp", str(work), "zombie.iso.areas.TopologyTest"], check=True)
        print("Native Set/snapshot/reattachment/meta method checks passed; native repeated baby loop preserved")


if __name__ == "__main__":
    main()
