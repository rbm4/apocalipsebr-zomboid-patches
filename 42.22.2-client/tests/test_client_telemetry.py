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
                       '-Dapocbr.clientTelemetry.slowLuaMs=1',
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
            self.assertEqual(first['schemaVersion'], 8)
            self.assertEqual(first['buildings']['cells'],50)
            self.assertEqual(first['buildings']['reindexedCells'],25)
            self.assertEqual(first['buildings']['workerPlans'],25)
            self.assertEqual(first['buildings']['workerPlanMs'],25)
            self.assertEqual(first['buildings']['workerCollectMs'],10)
            self.assertEqual(first['buildings']['workerGeometryMs'],15)
            self.assertTrue(all(f['buildings']['cells']==2 for f in first['worstFrames']))
            network=first['network']
            self.assertEqual(network['polled'],50)
            self.assertEqual(network['ownerBacklogMax'],12)
            self.assertEqual(len(network['types']),257)
            self.assertEqual(network['typeOverflow'],45)
            self.assertEqual(sum(t['dispatch']['count'] for t in network['types'].values()),325)
            self.assertEqual(sum(t['payloadBytes'] for t in network['types'].values()),600)
            self.assertEqual(sum(t['errors'] for t in network['types'].values()),1)
            self.assertNotIn('wrong-thread',network['types'])
            self.assertEqual(network['types']['test']['stages']['PARSE']['count'],25)
            self.assertEqual(first['slowLua']['count'], 65)
            self.assertEqual(len(first['slowLua']['callbacks']), 32)
            self.assertEqual(first['phases']['LUA_ONTICK_CALLBACK']['count'], 65)
            self.assertTrue(all('closureId' in c for c in first['slowLua']['callbacks']))
            self.assertIn('PREPROCESSED', first['workerTextures'])
            self.assertTrue(first['workerTextures']['enabled'])
            self.assertIn('OVERSIZED', first['workerTextures'])
            self.assertIn('CAPACITY_WAIT_NANOS', first['workerTextures'])
            self.assertTrue(first['translucentWorker']['enabled'])
            self.assertIn('REUSED', first['translucentWorker'])
            self.assertIn('OWNER_CATCHUP', first['translucentWorker'])
            self.assertEqual(first['textures']['limitCalls'], 25)
            self.assertEqual(first['textures']['scaled'], 25)
            self.assertEqual(first['textures']['inputPixels'], 25 * 4096 * 2048)
            self.assertEqual(first['textures']['outputPixels'], 25 * 1024 * 512)
            self.assertEqual(first['textures']['largestLimit']['maxSize'], 1024)
            self.assertTrue(all(f['largestTextureLimit']['scaled'] for f in first['worstFrames']))
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
            self.assertEqual(second['textures']['limitCalls'], 0)
            self.assertIsNone(second['textures']['largestLimit'])
            self.assertEqual(second['slowLua']['count'], 0)
            self.assertEqual(second['slowLua']['callbacks'], [])
            self.assertEqual(second['seq'], 2)
            self.assertEqual(second['network']['polled'],0)
            self.assertEqual(second['buildings']['cells'],0)
            self.assertEqual(second['network']['ownerBacklogMax'],0)
            self.assertEqual(sum(t['dispatch']['count'] for t in second['network']['types'].values()),0)
            self.assertTrue(all(f['network']['packets']>0 for f in first['worstFrames']))
            self.assertEqual(second['droppedWindows'], 0)
            self.assertEqual(second['writeErrors'], 0)
            before = output.read_bytes()
            subprocess.run(command, check=True)  # disabled by default
            self.assertEqual(output.read_bytes(), before)


if __name__ == '__main__':
    unittest.main()
