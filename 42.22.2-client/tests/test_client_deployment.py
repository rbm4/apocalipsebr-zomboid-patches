"""Exercise the actual client installer in disposable root/java-layout installations."""
import hashlib
import json
import pathlib
import shutil
import subprocess
import tempfile
import unittest
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
ORIGINAL = pathlib.Path(r'Z:\SteamLibrary\steamapps\common\ProjectZomboid\projectzomboid.jar')


class ClientDeploymentTest(unittest.TestCase):
    def run_patch(self, game, *args, succeeds=True):
        result = subprocess.run(['powershell', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File',
                                 str(ROOT / 'patchApocalipseBr.ps1'), '-PZDir', str(game), *args],
                                capture_output=True, text=True)
        self.assertEqual(result.returncode == 0, succeeds, result.stdout + result.stderr)
        return result

    def test_deployment_roundtrip(self):
        for layout in ('', 'java'):
            with self.subTest(layout=layout), tempfile.TemporaryDirectory(prefix='apocbr client install ') as temp:
                game = pathlib.Path(temp)
                deploy = game / layout
                deploy.mkdir(exist_ok=True)
                jar = deploy / 'projectzomboid.jar'
                shutil.copyfile(ORIGINAL, jar)
                original_hash = hashlib.sha256(jar.read_bytes()).hexdigest()
                original_mtime = jar.stat().st_mtime_ns
                prefix = 'java/' if layout else ''
                launcher = game / 'ProjectZomboid64.json'
                settings = {'mainClass': 'zombie/gameStates/MainScreenState',
                            'classpath': [prefix + 'projectzomboid.jar', 'other.jar'],
                            'vmArgs': ['-Xmx3G', '-Dcustom.setting=true'], 'custom': {'preserve': 42}}
                launcher.write_text(json.dumps(settings))
                zombie = deploy / 'zombie'
                zombie.mkdir()
                previous = zombie / 'GameWindow.class'
                previous.write_bytes(b'pre-existing override')
                self.run_patch(game, '-DryRun')
                self.assertEqual(json.loads(launcher.read_text()), settings)
                self.assertFalse((deploy / 'projectzomboid-apocbr.jar').exists())
                self.assertEqual(previous.read_bytes(), b'pre-existing override')
                self.run_patch(game)
                changed = json.loads(launcher.read_text(encoding='utf-8-sig'))
                self.assertEqual(changed['classpath'], [prefix + '.', prefix + 'projectzomboid-apocbr.jar', 'other.jar'])
                self.assertEqual(changed['vmArgs'], settings['vmArgs'])
                self.assertEqual(changed['mainClass'], settings['mainClass'])
                self.assertEqual(changed['custom'], settings['custom'])
                self.assertEqual(len(list(zombie.rglob('*.class'))), 31)
                derived = deploy / 'projectzomboid-apocbr.jar'
                with zipfile.ZipFile(jar) as source, zipfile.ZipFile(derived) as target:
                    self.assertEqual(source.read('zombie/GameWindow.class'), target.read('zombie/GameWindow.class'))
                    self.assertFalse(any(name.upper().endswith(('.SF', '.RSA', '.DSA', '.EC'))
                                         for name in target.namelist() if name.startswith('META-INF/') and name.count('/') == 1))
                runtime_mtime = derived.stat().st_mtime_ns
                changed['custom']['preserve'] = 99
                launcher.write_text(json.dumps(changed))
                self.run_patch(game)
                self.assertEqual(derived.stat().st_mtime_ns, runtime_mtime)
                self.run_patch(game, '-Revert')
                restored = json.loads(launcher.read_text(encoding='utf-8-sig'))
                self.assertEqual(restored['classpath'], [prefix + '.', prefix + 'projectzomboid.jar', 'other.jar'])
                self.assertEqual(restored['custom']['preserve'], 99)
                self.assertEqual(previous.read_bytes(), b'pre-existing override')
                self.assertEqual(len(list(zombie.rglob('*.class'))), 1)
                self.run_patch(game, '-Revert')
                self.assertEqual(hashlib.sha256(jar.read_bytes()).hexdigest(), original_hash)
                self.assertEqual(jar.stat().st_mtime_ns, original_mtime)


if __name__ == '__main__':
    unittest.main()
