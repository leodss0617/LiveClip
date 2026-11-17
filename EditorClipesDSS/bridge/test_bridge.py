import unittest
from bridge import pending_jobs, logo_id, validate_logo, live_url, limits

class BridgeTest(unittest.TestCase):
    def test_progress_uses_only_current_stage(self):
        from bridge import stage_progress
        log = '45% ETA 03:51 (frag 4/10)\n  ouvido 03:10:00 de 07:02:27 (45%) - faltam ~120 min'
        self.assertEqual(stage_progress('Transcrevendo',log), (45,7200))
        self.assertEqual(stage_progress('Baixando a live',log), (45,231))
        self.assertEqual(stage_progress('Exportando clipe 1',log), (None,None))

    def test_cancelled_jobs_are_not_recovered(self):
        from pathlib import Path
        from tempfile import TemporaryDirectory
        from bridge import pending_jobs
        with TemporaryDirectory() as tmp:
            folder=Path(tmp)/'job'
            folder.mkdir()
            (folder/'pedido.json').write_text('{}')
            (folder/'estado.json').write_text('{"status":"running"}')
            (folder/'parar').touch()
            self.assertEqual(list(pending_jobs(Path(tmp))), [])
    def test_retry_preserves_id_and_prevents_duplicate_queueing(self):
        from pathlib import Path
        from tempfile import TemporaryDirectory
        from bridge import requeue_failed, state
        with TemporaryDirectory() as tmp:
            folder=Path(tmp)
            state(folder, 'Falha', status='error', id='same', prontos=2)
            (folder/'pedido.json').write_text('{"quantidade":4,"duracao":240}')
            (folder/'entrada.mp4').touch()
            (folder/'parar').touch()
            self.assertEqual(requeue_failed(folder), {'quantidade':4,'duracao':240})
            self.assertFalse((folder/'parar').exists())
            self.assertIn('"prontos": 2',(folder/'estado.json').read_text())
            self.assertIsNone(requeue_failed(folder))

    def test_source_with_unfinished_fragments_is_not_reused(self):
        from pathlib import Path
        from tempfile import TemporaryDirectory
        from bridge import completed_sources
        with TemporaryDirectory() as tmp:
            folder=Path(tmp)
            source=folder/'live.mp4'
            source.touch()
            fragment=folder/'live.mp4.part-Frag6.part'
            fragment.touch()
            self.assertEqual(completed_sources(folder), [])
            fragment.unlink()
            self.assertEqual(completed_sources(folder), [source])

    def test_limits(self):
        self.assertEqual(limits(30, 240), (30, 240))
        for pair in [(0,60),(31,60),(1,241),(True,60)]:
            with self.assertRaises(ValueError):
                limits(*pair)

    def test_logo_identifiers_and_image_bounds(self):
        import struct
        self.assertTrue(logo_id("a" * 32))
        self.assertFalse(logo_id("../logo.png"))
        def png(w, h):
            return b"\x89PNG\r\n\x1a\n" + struct.pack(">I4sII", 13, b"IHDR", w, h) + b"123456789"
        self.assertEqual(validate_logo(png(512, 180)), (512, 180))
        for bad in (b"invalid", png(513, 4), png(0, 4)):
            with self.assertRaises(ValueError):
                validate_logo(bad)

    def test_pending_jobs_preserve_requests_after_restart(self):
        from pathlib import Path
        from tempfile import TemporaryDirectory
        import json
        with TemporaryDirectory() as tmp:
            root=Path(tmp)
            for name,status in [('working','running'),('waiting','queued'),('complete','done'),('failed','error'),('legacy','queued')]:
                folder=root/name
                folder.mkdir()
                (folder/'estado.json').write_text(json.dumps({'status':status}))
                if name!='legacy':
                    (folder/'pedido.json').write_text(json.dumps({'quantidade':3,'duracao':240}))
            self.assertEqual([(p.name,s['quantidade']) for p,s in pending_jobs(root)],
                             [('waiting',3),('working',3)])

    def test_link(self):
        self.assertTrue(live_url('https://kick.com/coringa/videos/abc?t='))
        for url in ['file:///sdcard/video.mp4','https://kick.com.example.com/a','https://kick.com@127.0.0.1/x']:
            self.assertFalse(live_url(url))

