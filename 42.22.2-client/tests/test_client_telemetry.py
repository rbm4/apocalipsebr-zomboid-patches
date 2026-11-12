"""Compile and exercise the production JDK-only capture helper; no simulated game implementation."""
import json
import pathlib
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
JDK = ROOT.parent / 'jdk' / 'bin'


class ClientTelemetryTest(unittest.TestCase):
    def test_capture_and_disabled_mode(self):
        with tempfile.TemporaryDirectory(prefix='apocbr-client-telemetry-') as temp:
            work = pathlib.Path(temp)
            subprocess.run([str(JDK / 'javac.exe'), '--release', '25', '-d', str(work),
                            str(ROOT / 'src/zombie/ApocBRClientTelemetryLite.java'),
                            str(ROOT / 'tests/ClientTelemetryHarness.java')], check=True)
            output = work / 'nested' / 'capture.ndjson'
            command = [str(JDK / 'java.exe'), '-Dapocbr.clientTelemetry.intervalMs=60000',
                       '-Dapocbr.clientTelemetry.slowFrameMs=1',
                       f'-Dapocbr.clientTelemetry.path={output}', '-cp', str(work), 'ClientTelemetryHarness']
            subprocess.run(command[:1] + ['-Dapocbr.clientTelemetry.enabled=true'] + command[1:], check=True)
            windows = [json.loads(line) for line in output.read_text().splitlines()]
            self.assertEqual(len(windows), 2)
            first, second = windows
            self.assertEqual(first['frameWork']['count'], 25)
            self.assertEqual(first['frameCadence']['count'], 24)
            self.assertEqual(first['phases']['LOGIC']['count'], 25)
            self.assertEqual(first['phases']['NETWORK']['count'], 0)
            self.assertEqual(first['slowFrameCount'], 25)
            self.assertEqual(len(first['worstFrames']), 16)
            self.assertEqual(first['context'], {'loadedZombies': 224, 'movingObjects': 324,
                                               'screenWidth': 1920, 'screenHeight': 1080, 'playerZoom': 1.5})
            self.assertEqual(first['schemaVersion'], 2)
            self.assertLessEqual(first['frameCpu']['count'], 25)
            files = first['fileSystem']
            self.assertEqual(files['resultCallbacks'], 95)
            self.assertEqual(files['submitted'], 25)
            self.assertEqual(files['backlogMax'], 12)
            self.assertEqual(len(files['tasks']), 65)  # 64 named types plus overflow
            self.assertGreater(files['taskTypeOverflow'], 0)
            self.assertEqual(sum(t['handleResult']['count'] for t in files['tasks'].values()), 95)
            self.assertEqual(files['tasks']['java.lang.String']['done']['count'], 25)
            self.assertEqual(first['phases']['FILE_HANDLE_RESULT']['count'], 95)
            self.assertTrue(all(f['largestFileCallback'] is not None for f in first['worstFrames']))
            self.assertTrue(all('name' in c and c['countDelta'] == -1
                                for c in first['writerJvm']['collectors']))
            self.assertEqual(sum(first['frameWork']['histogram']), 25)
            self.assertGreater(first['frameWork']['maxMs'], 0)
            self.assertEqual(second['frameWork']['count'], 1)
            self.assertEqual(second['frameCadence']['count'], 0)
            self.assertEqual(second['phases']['LOGIC']['count'], 0)
            self.assertEqual(second['fileSystem']['resultCallbacks'], 0)
            self.assertEqual(sum(t['handleResult']['count'] for t in second['fileSystem']['tasks'].values()), 0)
            self.assertEqual(second['fileSystem']['backlogMax'], 0)
            self.assertEqual(second['seq'], 2)
            self.assertEqual(second['droppedWindows'], 0)
            self.assertEqual(second['writeErrors'], 0)
            before = output.read_bytes()
            subprocess.run(command, check=True)  # disabled by default
            self.assertEqual(output.read_bytes(), before)


if __name__ == '__main__':
    unittest.main()
