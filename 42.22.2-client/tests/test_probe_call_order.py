"""Supplement runtime tests by checking vanilla call-site order in timing-only large replacements."""
import pathlib
import re
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]


def calls(path):
    text = path.read_text()
    if path.name == 'FBORenderCell.java':
        # Ordering preparation is now intentionally asynchronous and covered by
        # production-helper differential tests. Compare the actual draw loop and
        # every other vanilla call site, excluding the new owner-side cache hook.
        text = '\n'.join(line for line in text.splitlines() if 'apocTranslucentOrder' not in line)
        start = text.index('private void renderOneLevel_Translucent(')
        end = text.index('for (int ixx = 0; ixx < sorted.size(); ixx++)', start)
        text = text[:start] + 'ORDER_PREPARATION\n' + text[end:]
    return [call for call in re.findall(r'\b([\w.]+)\s*\(', text)
            if not call.startswith('zombie.ApocBRClientTelemetryLite.')]


class ProbeCallOrderTest(unittest.TestCase):
    def test_region_and_cell_call_sites(self):
        for relative in ('iso/areas/isoregion/IsoRegions.java', 'iso/fboRenderChunk/FBORenderCell.java'):
            with self.subTest(source=relative):
                self.assertEqual(calls(ROOT/'decompiled/zombie'/relative), calls(ROOT/'src/zombie'/relative))


if __name__ == '__main__':
    unittest.main()
