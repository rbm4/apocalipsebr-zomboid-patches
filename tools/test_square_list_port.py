"""Review56 square-list wiring and extracted entity cache with engine doubles."""
from pathlib import Path
import json
import subprocess
import tempfile

import test_iso_object_port as fixture

ROOT = Path(__file__).resolve().parents[1]
V = ROOT / "42.22.0"
REPORT = V / "docs/reports/2026-10-28-merge-56-square-mutation-tracking.json"


def main():
    record = json.loads(REPORT.read_text())
    with tempfile.TemporaryDirectory(prefix="square_list_port_") as directory:
        work = Path(directory)
        for rel in record["affected_sources"]:
            target = work / "42.22.0/src" / rel
            target.parent.mkdir(parents=True)
            target.write_bytes((V / "src" / rel).read_bytes())
        if not record["source_applied"]:
            subprocess.run(["git", "apply", str(REPORT.with_suffix(".diff"))], cwd=work, check=True)
        square = (work / "42.22.0/src/zombie/iso/IsoGridSquare.java").read_text()
        assert "protected final List<IsoObject> objects = new zombie.util.list.MutationTrackedArrayList<>(2);" in square
        for kind in ["src", "decompiled"]:
            rel = "zombie/iso/IsoObject.java"
            target = work / "42.22.0" / kind / rel
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes((V / kind / rel).read_bytes())
        fixture.VERSION = work / "42.22.0"
        # Reuse actual extracted cache/getter/decoder code and its existing
        # mutation cases. Add constructor coverage without replacing that code.
        fixture.HARNESS = fixture.HARNESS.replace(
            "cache();decode();",
            'MutationTrackedArrayList<Object> sized=new MutationTrackedArrayList<>(2);'
            'check(sized.isEmpty(),"capacity constructor starts empty");'
            'try{new MutationTrackedArrayList<Object>(-1);throw new AssertionError("negative capacity accepted");}'
            'catch(IllegalArgumentException expected){check(true,"ArrayList capacity validation");}'
            'cache();decode();',
        )
        fixture.main()
        print("Square allocation source contract passed; no full square/world/runtime claim")


if __name__ == "__main__":
    main()
