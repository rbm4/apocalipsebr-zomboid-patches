"""Test real runtime setup helpers on temporary installations, never the game."""
from pathlib import Path
import json
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
VERSION = ROOT / '42.21.0'
NAMES = ['postgresql-42.7.14.jar', 'HikariCP-7.1.0.jar']

def run(command):
    subprocess.run(command, check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)

with tempfile.TemporaryDirectory(prefix='apocbr runtime fixture ') as tmp:
    root = Path(tmp)
    win = root / 'Windows install with spaces'
    win.mkdir()
    launcher = win / 'ProjectZomboidServer.bat'
    baseline = b'@echo off\r\nset PZ_CLASSPATH=.;projectzomboid.jar\r\njava -Xmx3g -cp %PZ_CLASSPATH% zombie.network.GameServer\r\nPAUSE\r\n'
    launcher.write_bytes(baseline)
    command = ['powershell', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File',
               str(VERSION / 'tools/Set-PostgresRuntime.ps1'), '-PZDir', str(win)]
    run(command + ['-DryRun'])
    assert launcher.read_bytes() == baseline and not (win / 'apocbr-lib').exists()
    run(command)
    applied = launcher.read_bytes()
    assert applied.count(b'REM BEGIN APOCBR POSTGRES CLASSPATH') == 1
    assert b'-cp "%PZ_CLASSPATH%"' in applied
    assert b'set PZ_CLASSPATH=.;projectzomboid.jar' in applied
    assert str(win / 'apocbr-lib').encode() in applied
    assert launcher.with_name(launcher.name + '.apocbr-runtime.bak').read_bytes() == baseline
    for name in NAMES:
        assert (win / 'apocbr-lib' / name).read_bytes() == (VERSION / 'lib' / name).read_bytes()
    run(command)
    assert launcher.read_bytes() == applied
    # Revert only managed entries, preserving independent edits since deployment.
    launcher.write_bytes(applied.replace(b'-Xmx3g', b'-Xmx6g'))
    run(command + ['-Revert', '-DryRun'])
    assert b'BEGIN APOCBR' in launcher.read_bytes()
    run(command + ['-Revert'])
    assert b'APOCBR POSTGRES' not in launcher.read_bytes()
    assert b'-Xmx6g' in launcher.read_bytes()
    assert all(not (win / 'apocbr-lib' / name).exists() for name in NAMES)

    bad = root / 'Unsupported Windows launcher'
    bad.mkdir()
    (bad / 'ProjectZomboidServer.bat').write_text('custom launcher\n')
    failed = subprocess.run(command[:-1] + [str(bad)], capture_output=True)
    assert failed.returncode != 0 and not (bad / 'apocbr-lib').exists()

    linux = root / 'Linux install with spaces'
    linux.mkdir()
    config = {'mainClass': 'zombie/network/GameServer', 'classpath': ['.', 'projectzomboid.jar'],
              'vmArgs': ['-Xmx8g'], 'extra': {'preserve': True}}
    path = linux / 'ProjectZomboid64.json'
    path.write_text(json.dumps(config))
    original = path.read_bytes()
    command = [sys.executable, str(VERSION / 'tools/set_postgres_runtime.py'), '--pz-dir', str(linux)]
    run(command + ['--dry-run'])
    assert path.read_bytes() == original and not (linux / 'apocbr-lib').exists()
    run(command)
    first = path.read_bytes()
    cp = json.loads(first)['classpath']
    assert cp == config['classpath'] + ['apocbr-lib/' + name for name in NAMES]
    assert json.loads(first)['vmArgs'] == config['vmArgs']
    run(command)
    assert path.read_bytes() == first
    for name in NAMES:
        assert (linux / 'apocbr-lib' / name).read_bytes() == (VERSION / 'lib' / name).read_bytes()
    run(command + ['--revert', '--dry-run'])
    assert path.read_bytes() == first
    run(command + ['--revert'])
    assert json.loads(path.read_text()) == config
    assert all(not (linux / 'apocbr-lib' / name).exists() for name in NAMES)

print('Runtime deployment: Windows/Linux install, repeat apply, dry run, revert, spaces and preservation passed')
