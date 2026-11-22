"""Exercise actual 42.22.1 deploy helpers against signed, disposable game JARs."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
VERSION = ROOT / '42.22.1'
JDK = ROOT / 'jdk/bin'
SUFFIX = '.exe' if os.name == 'nt' else ''
if not (JDK / ('java' + SUFFIX)).exists():
    JDK = Path(shutil.which('java')).resolve().parent
NAMES = ['postgresql-42.7.14.jar', 'HikariCP-7.1.0.jar']
RUNTIME = 'projectzomboid-apocbr.jar'
HELPERS = ['python'] + (['powershell'] if os.name == 'nt' else [])


def run(command, ok=True, **kwargs):
    result = subprocess.run([str(s) for s in command], capture_output=True, text=True, timeout=90, **kwargs)
    if ok and result.returncode:
        raise AssertionError(f'{command}\n{result.stdout}\n{result.stderr}')
    if not ok:
        assert result.returncode != 0, result.stdout
    return result


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def snapshot(root):
    return {str(p.relative_to(root)): (sha(p), p.stat().st_mtime_ns)
            for p in root.rglob('*') if p.is_file()}


def command(helper, root):
    if helper == 'python':
        return [sys.executable, VERSION / 'tools/set_postgres_runtime.py', '--pz-dir', root]
    return ['powershell', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File',
            VERSION / 'tools/Set-PostgresRuntime.ps1', '-PZDir', root]


def flags(helper, *values):
    return [('--' + s) if helper == 'python' else ('-' + s.replace('-', '')) for s in values]


def verify_copy(game):
    with zipfile.ZipFile(game) as original, zipfile.ZipFile(game.with_name(RUNTIME)) as runtime:
        assert runtime.testzip() is None
        expected = [n for n in original.namelist() if n not in ('META-INF/TEST.SF', 'META-INF/TEST.RSA')]
        assert runtime.namelist() == expected
        for name in expected:
            assert runtime.read(name) == original.read(name), name


with tempfile.TemporaryDirectory(prefix='apocbr unsigned deployment ') as tmp:
    base = Path(tmp)
    source = base / 'source'
    source.mkdir()
    (source / 'Vanilla.java').write_text('package runtimefixture; public class Vanilla {}')
    (source / 'Patched.java').write_text('package runtimefixture; public class Patched {}')
    (source / 'RuntimeProbe.java').write_text('''
public class RuntimeProbe {
 public static void main(String[] args) throws Exception {
  for (String name : new String[]{"runtimefixture.Patched", "runtimefixture.Vanilla"}) {
   Class<?> c = Class.forName(name, false, ClassLoader.getSystemClassLoader());
   if(c.getSigners()!=null) throw new AssertionError("Unexpected signed class: "+name);
  }
  System.out.println("Loose patch + unsigned vanilla class loaded successfully");
 }
}
''')
    compiled = base / 'compiled'
    run([JDK / ('javac' + SUFFIX), '-d', compiled, *source.glob('*.java')])
    keystore = base / 'fixture.p12'
    run([JDK / ('keytool' + SUFFIX), '-genkeypair', '-alias', 'test', '-keyalg', 'RSA',
         '-keystore', keystore, '-storepass', 'fixturepass', '-keypass', 'fixturepass',
         '-dname', 'CN=Disposable Runtime Test', '-validity', '1', '-noprompt'])
    signed = []
    for revision in range(2):
        jar = base / f'signed-{revision}.jar'
        with zipfile.ZipFile(jar, 'w', zipfile.ZIP_DEFLATED) as z:
            z.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\r\n\r\n')
            for path in (compiled / 'runtimefixture').glob('*.class'):
                z.write(path, 'runtimefixture/' + path.name)
            z.writestr('media/revision.txt', str(revision))
            # A nested .SF is a resource, not JAR signature metadata.
            z.writestr('META-INF/resources/keep.SF', 'preserved')
        run([JDK / ('jarsigner' + SUFFIX), '-keystore', keystore, '-storepass', 'fixturepass', jar, 'test'])
        signed.append(jar)

    for helper in HELPERS:
        for nested in (False, True):
            root = base / f'{helper} install with spaces {nested}'
            root.mkdir()
            game_dir = root / 'java' if nested else root
            game_dir.mkdir(exist_ok=True)
            game = game_dir / 'projectzomboid.jar'
            shutil.copy2(signed[0], game)
            original = (sha(game), game.stat().st_mtime_ns)
            prefix = 'java/' if nested else ''
            config = {'mainClass': 'zombie/network/GameServer',
                      'classpath': [prefix + '.', prefix + 'projectzomboid.jar', 'other.jar'],
                      'vmArgs': ['-Xmx8g'], 'extra': {'preserve': True}}
            json_paths = [root / f'ProjectZomboid{bits}.json' for bits in (64, 32)]
            for path in json_paths:
                path.write_text(json.dumps(config), encoding='utf-8')
            batch = root / 'ProjectZomboidServer.bat'
            baseline = (f'@echo off\r\nset PZ_CLASSPATH={prefix}.;{prefix}projectzomboid.jar\r\n'
                        'java -Xmx3g -cp %PZ_CLASSPATH% zombie.network.GameServer\r\n').encode()
            if helper == 'powershell':
                batch.write_bytes(baseline)
            cmd = command(helper, root)
            before = snapshot(root)
            run(cmd + flags(helper, 'dry-run'))
            assert snapshot(root) == before
            run(cmd)
            verify_copy(game)
            expected_cp = [prefix + '.', prefix + RUNTIME, 'other.jar'] + ['apocbr-lib/' + n for n in NAMES]
            for path in json_paths:
                applied = json.loads(path.read_text(encoding='utf-8-sig'))
                assert applied == dict(config, classpath=expected_cp), applied
                assert json.loads(path.with_name(path.name + '.apocbr-runtime.bak').read_text()) == config
            if helper == 'powershell':
                assert batch.read_bytes().count(b'REM BEGIN APOCBR POSTGRES CLASSPATH') == 1
                assert b'%PZ_CLASSPATH:projectzomboid.jar=projectzomboid-apocbr.jar%' in batch.read_bytes()
            before = snapshot(root)
            run(cmd)
            assert snapshot(root) == before, 'Repeat deploy must not rewrite managed files'
            assert (sha(game), game.stat().st_mtime_ns) == original

            # Confirm the original failure and its resolution using an actual signed JAR.
            loose = game_dir / 'runtimefixture'
            loose.mkdir()
            shutil.copy2(compiled / 'runtimefixture/Patched.class', loose / 'Patched.class')
            cp = os.pathsep.join([str(compiled.parent / 'probe'), str(game_dir), str(game)])
            probe = compiled.parent / 'probe'
            probe.mkdir(exist_ok=True)
            shutil.copy2(compiled / 'RuntimeProbe.class', probe / 'RuntimeProbe.class')
            failed = run([JDK / ('java' + SUFFIX), '-cp', cp, 'RuntimeProbe'], ok=False)
            assert 'signer information does not match' in failed.stderr
            cp = os.pathsep.join([str(probe), str(game_dir), str(game.with_name(RUNTIME))])
            run([JDK / ('java' + SUFFIX), '-cp', cp, 'RuntimeProbe'])

            # Preserve other patches, deduplicate mixed old/new entries, repair reset JSON.
            edited = dict(config, classpath=list(config['classpath']), vmArgs=['-Xmx12g', '-Dfuture.patch=true'])
            edited['classpath'] += [prefix + RUNTIME, 'apocbr-lib/' + NAMES[0]]
            for path in json_paths:
                path.write_text(json.dumps(edited))
            run(cmd)
            for path in json_paths:
                applied = json.loads(path.read_text())
                assert applied['vmArgs'] == edited['vmArgs']
                assert applied['classpath'] == expected_cp

            # A game update replaces the publisher JAR; the next deployment refreshes only the copy.
            shutil.copy2(signed[1], game)
            updated_original = (sha(game), game.stat().st_mtime_ns)
            run(cmd)
            verify_copy(game)
            assert (sha(game), game.stat().st_mtime_ns) == updated_original

            # Absolute JSON entries are supported without duplicating the game JAR.
            absolute = dict(edited, classpath=[str(game), 'other.jar'])
            json_paths[0].write_text(json.dumps(absolute))
            run(cmd)
            current = json.loads(json_paths[0].read_text())
            assert str(game.with_name(RUNTIME)).replace('\\', '/') in current['classpath']
            # Restore relative layout before checking revert expectations.
            json_paths[0].write_text(json.dumps(edited))
            run(cmd)
            game.with_name(RUNTIME).write_bytes(b'damaged disposable runtime')
            run(cmd)
            verify_copy(game)
            assert (sha(game), game.stat().st_mtime_ns) == updated_original

            before = snapshot(root)
            run(cmd + flags(helper, 'revert', 'dry-run'))
            assert snapshot(root) == before
            run(cmd + flags(helper, 'revert'))
            for path in json_paths:
                applied = json.loads(path.read_text())
                assert applied['classpath'] == config['classpath']
                assert applied['vmArgs'] == edited['vmArgs']
            if helper == 'powershell':
                assert b'BEGIN APOCBR' not in batch.read_bytes()
                assert b'projectzomboid.jar' in batch.read_bytes()
            assert (sha(game), game.stat().st_mtime_ns) == updated_original
            before = snapshot(root)
            run(cmd + flags(helper, 'revert'))
            assert snapshot(root) == before

            if helper == 'powershell':
                # Each Windows launcher is optional; update the one that exists.
                json_paths[0].unlink()
                json_paths[1].unlink()
                run(cmd)
                run(cmd + flags(helper, 'revert'))
                json_paths[0].write_text(json.dumps(config))
                batch.unlink()
                run(cmd)
                assert json.loads(json_paths[0].read_text())['classpath'] == expected_cp
                run(cmd + flags(helper, 'revert'))
                json_paths[1].write_text(json.dumps(config))

            # Validate all descriptors before writing any launcher or derived JAR.
            json_paths[1].write_text('{"classpath": "unsupported"}')
            before = snapshot(root)
            run(cmd, ok=False)
            assert snapshot(root) == before
            # Never fall back to the managed JAR if the original goes missing.
            game.unlink()
            before = snapshot(root)
            run(cmd, ok=False)
            assert snapshot(root) == before
            print(f'{helper}, nested={nested}: apply/reapply/update/repair/revert/dry-run/original preservation passed')

print('Signed class-loading mismatch reproduced; both unsigned runtimes load loose and vanilla classes together')
