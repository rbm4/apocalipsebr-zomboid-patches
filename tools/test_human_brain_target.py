"""Run the existing brain/composite regression harnesses against real target-build classes.

Requires a completed BuildOnly output; uses its actual production bytecode and
the installed game libraries, without creating a native game or deploying files.
"""
from pathlib import Path
import argparse
import os
import subprocess
import sys
import tempfile
sys.dont_write_bytecode = True
from test_human_brain import HARNESS as BRAIN
from test_human_sequences import HARNESS as SEQUENCES

ROOT = Path(__file__).resolve().parents[1]


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--classes", required=True, type=Path)
    parser.add_argument("--game", type=Path, default=Path("Z:/SteamLibrary/steamapps/common/ProjectZomboid"))
    args = parser.parse_args()
    classes = args.classes.resolve()
    assert (classes / "zombie/apocalipse/humans/brain/HumanActivityController.class").exists()
    classpath = os.pathsep.join((str(classes), str(args.game / "*"), str(ROOT / "42.22.2/lib/*")))
    with tempfile.TemporaryDirectory(prefix="apocbr-target-brain-") as directory:
        work = Path(directory)
        for name, harness in (("BrainTest", BRAIN), ("SequenceTest", SEQUENCES)):
            source = work / (name + ".java")
            source.write_text(harness, encoding="utf-8")
            subprocess.run([str(ROOT / "jdk/bin/javac.exe"), "--release", "25", "-encoding", "UTF-8", "-cp", classpath, "-d", str(work), str(source)], check=True)
            subprocess.run([str(ROOT / "jdk/bin/java.exe"), "-cp", os.pathsep.join((str(work), classpath)), name], check=True)
