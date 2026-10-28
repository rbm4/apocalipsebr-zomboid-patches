"""Run vehicle differential fixtures on target sources plus review31 candidate."""
from pathlib import Path
import subprocess
import sys
import tempfile

sys.dont_write_bytecode = True
import test_vehicle_idle_algorithms as fixture

ROOT = Path(__file__).resolve().parents[1]
V = ROOT / "42.22.0"


def main():
    with tempfile.TemporaryDirectory(prefix="pz-vehicle-port-") as directory:
        work = Path(directory)
        version = work / "42.22.0"
        relatives = [
            "src/zombie/vehicles/BaseVehicle.java",
            "src/zombie/vehicles/VehiclePart.java",
            "src/zombie/vehicles/ServerVehicleUpdateTelemetry.java",
            "decompiled/zombie/vehicles/BaseVehicle.java",
            "decompiled/zombie/vehicles/VehiclePart.java",
            "decompiled/zombie/GameTime.java",
        ]
        for relative in relatives:
            p = version / relative
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_bytes((V / relative).read_bytes())
        target = version / "src/zombie/vehicles/VehicleParts.java"
        target.write_bytes((V / "decompiled/zombie/vehicles/VehicleParts.java").read_bytes())
        subprocess.run(["git", "apply", str(V / "docs/reports/2026-10-28-merge-31-vehicle-parts.diff")], cwd=work, check=True)
        candidate = target.read_text(encoding="utf-8-sig")
        applied = V / "src/zombie/vehicles/VehicleParts.java"
        if applied.exists():
            assert applied.read_text(encoding="utf-8-sig") == candidate
        assert "PZArrayList" not in candidate
        saved = fixture.V
        try:
            fixture.V = version
            fixture.main()
        finally:
            fixture.V = saved
        print("Target extracted vehicle/parts fixtures passed; full classes/native physics/Lua not executed")


if __name__ == "__main__":
    main()
