"""Compatibility entry point for the replaced rectangle-power fixture."""
from pathlib import Path
import runpy
runpy.run_path(str(Path(__file__).with_name("test_managed_generators.py")), run_name="__main__")
