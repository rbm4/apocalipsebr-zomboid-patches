"""Review32 source checks and actual SQL helper with isolated JDBC proxies only."""
import ast
from pathlib import Path
import subprocess
import sys
import tempfile

sys.dont_write_bytecode = True
from test_unload_simulation_algorithms import method

ROOT = Path(__file__).resolve().parents[1]
V = ROOT / "42.22.0"


def main():
    tree = ast.parse((ROOT / "tools/test_postgres_vehicles.py").read_text(encoding="utf-8-sig"))
    harness = next(ast.literal_eval(n.value) for n in tree.body if isinstance(n, ast.Assign) and any(isinstance(t, ast.Name) and t.id == "HARNESS" for t in n.targets))
    # Reuse SQL/provenance/rollback checks, excluding its old-JAR adapter section.
    harness = harness[:harness.index("  // Exercise the actual VehiclesDB2 store selection")]
    harness += 'System.out.println("Vehicle SQL helper/JDBC proxies: "+checks+" assertions passed");}}'
    harness = harness.replace("static void check(boolean ok,String message){", "static int checks;static void check(boolean ok,String message){checks++;")
    with tempfile.TemporaryDirectory(prefix="pz-vehicle-db-port-") as directory:
        work = Path(directory)
        p = work / "42.22.0/src/zombie/vehicles/VehiclesDB2.java"
        p.parent.mkdir(parents=True)
        p.write_bytes((V / "decompiled/zombie/vehicles/VehiclesDB2.java").read_bytes())
        subprocess.run(["git", "apply", str(V / "docs/reports/2026-10-28-merge-32-vehicles-db.diff")], cwd=work, check=True)
        candidate = p.read_text(encoding="utf-8-sig")
        applied = V / "src/zombie/vehicles/VehiclesDB2.java"
        if applied.exists():
            assert applied.read_text(encoding="utf-8-sig") == candidate
        native = (V / "decompiled/zombie/vehicles/VehiclesDB2.java").read_text(encoding="utf-8-sig")
        for signature in ["private void repairStoredVehicleChunks()", "boolean isSafeToSetScript()", "void unloadChunk(IsoChunk chunk)"]:
            assert method(candidate, signature) == method(native, signature)
        assert "if (!Float.isFinite(row.x()) || !Float.isFinite(row.y())) return null;" in candidate
        assert "this.wx = PZMath.fastfloor(this.x / 8.0F)" in candidate
        p = work / "VehicleDatabaseTest.java"
        p.write_text(harness, encoding="utf-8")
        connector = work / "zombie/savefile/ApocBRPostgresConnector.java"
        connector.parent.mkdir(parents=True)
        connector.write_text('package zombie.savefile;public class ApocBRPostgresConnector {public static java.sql.Connection borrowVehicleConnection()throws java.sql.SQLException{throw new java.sql.SQLException("No live database allowed in this fixture");}}', encoding="utf-8")
        sources = [p, connector, V / "src/zombie/savefile/ApocBRVehicleDatabase.java", V / "src/zombie/savefile/ApocBRPayloadProvenance.java"]
        subprocess.run(["javac", "-d", str(work), *map(str, sources)], check=True)
        subprocess.run(["java", "-cp", str(work), "zombie.savefile.VehicleDatabaseTest"], check=True)
        print("Native repair/thread/unload source equality passed; full VehiclesDB2/connector/live database not executed")


if __name__ == "__main__":
    main()
