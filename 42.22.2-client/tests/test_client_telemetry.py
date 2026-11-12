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
            self.assertEqual(first['context'], {'loadedZombies': 224, 'movingObjects': 324})
            self.assertEqual(sum(first['frameWork']['histogram']), 25)
            self.assertGreater(first['frameWork']['maxMs'], 0)
            self.assertEqual(second['frameWork']['count'], 1)
            self.assertEqual(second['frameCadence']['count'], 0)
            self.assertEqual(second['phases']['LOGIC']['count'], 0)
            self.assertEqual(second['seq'], 2)
            self.assertEqual(second['droppedWindows'], 0)
            self.assertEqual(second['writeErrors'], 0)
            before = output.read_bytes()
            subprocess.run(command, check=True)  # disabled by default
            self.assertEqual(output.read_bytes(), before)


if __name__ == '__main__':
    unittest.main()
