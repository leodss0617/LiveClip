import tempfile
import json
import unittest
from pathlib import Path
from unittest.mock import patch
import bridge


class ContinuousLibraryTests(unittest.TestCase):
    def test_continuous_capture_only_in_live_mode(self):
        self.assertEqual(bridge.limits(0, 240, True), (0, 240))
        with self.assertRaises(ValueError):
            bridge.limits(0, 240, False)
        self.assertEqual(bridge.limits(6, 120), (6, 120))

    def test_new_exports_grouped_and_legacy_kept(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp)
            root = base / 'jobs'
            root.mkdir()
            exports = base / 'exports'
            exports.mkdir()
            job = root / ('a' * 32)
            job.mkdir()
            spec = {'modo': 'ao_vivo', 'url': 'https://kick.com/gabepeixe', 'quantidade': 0, 'duracao': 120}
            (job / 'pedido.json').write_text(json.dumps(spec))
            with patch.object(bridge, 'EXPORTS', exports):
                output = bridge.public_folder(job)
                self.assertEqual(output, exports / 'PorStreamer' / 'gabepeixe' / job.name)
                output.mkdir(parents=True)
                (output / '01_teste_nota-8-0.mp4').write_bytes(b'demo')
                result = bridge.clip_library(root)
                self.assertEqual(result['pastas'][0]['streamer'], 'gabepeixe')
                self.assertEqual(result['pastas'][0]['clipes'][0]['nota'], 8.0)
                self.assertEqual(bridge.public_folder(job), output)
                legacy = exports / job.name
                legacy.mkdir()
                self.assertEqual(bridge.public_folder(job), legacy)


if __name__ == '__main__':
    unittest.main()
