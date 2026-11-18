import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import bridge
from camera_auto import SMALL_W, SMALL_H, find_webcam
from chat_activity import activity_bonus


class StreamingFixTests(unittest.TestCase):
    def test_camera_top_and_content_below(self):
        # Webcam artificial no canto superior esquerdo, contorno claro.
        data = bytearray([25] * (SMALL_W * SMALL_H))
        for y in range(0, 45):
            for x in range(0, 75):
                data[y * SMALL_W + x] = 80
        for y in range(0,45):
            data[y * SMALL_W + 75] = 250
        for x in range(0,75):
            data[45 * SMALL_W + x] = 250
        cam, content, confidence = find_webcam(bytes(data), 1920, 1080)
        self.assertEqual(cam[0],0)
        self.assertEqual(cam[1],0)
        self.assertGreater(cam[2],200)
        self.assertGreaterEqual(content[0],cam[2])
        self.assertEqual(content[1],0)
        self.assertLess(content[2],1920)
        self.assertEqual(content[3],1080)

    def test_chat_signal_adds_small_bonus_only_when_aligned(self):
        timeline = [(0,0,60)]
        measurements={0:(60,[],[],[],1700000060)}
        events=[{'registrado':1700000040,'mensagens':7,'animacao':0.9}]
        bonus,count=activity_bonus(events,25,50,timeline,measurements)
        self.assertGreater(bonus,0)
        self.assertLessEqual(bonus,0.35)
        self.assertEqual(count,7)
        self.assertEqual(activity_bonus(events,0,10,timeline,measurements)[0],0.0)

    def test_delete_requires_explicit_selection_and_valid_path(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)/'jobs'
            exported=Path(tmp)/'exports'
            root.mkdir()
            exported.mkdir()
            job=root/('c'*32)
            job.mkdir()
            (job/'pedido.json').write_text(json.dumps({
                'modo':'ao_vivo','url':'https://kick.com/gabepeixe'}))
            (job/'estado.json').write_text(json.dumps({'status':'done'}))
            clip_dir=exported/'PorStreamer'/'gabepeixe'/job.name
            clip_dir.mkdir(parents=True)
            selected=clip_dir/'momento_nota-8-5.mp4'
            preserved=clip_dir/'preservado_nota-9-0.mp4'
            selected.write_bytes(b'selected')
            preserved.write_bytes(b'keep')
            with patch.object(bridge,'EXPORTS',exported):
                with self.assertRaises(ValueError):
                    bridge.delete_selected_clips(root,[])
                with self.assertRaises(ValueError):
                    bridge.delete_selected_clips(root,[{'job':job.name,'arquivo':'../../foo.mp4'}])
                outcome=bridge.delete_selected_clips(root,[{'job':job.name,'arquivo':selected.name}])
                self.assertEqual(outcome['excluidos'],1)
                self.assertFalse(selected.exists())
                self.assertTrue(preserved.exists())

    def test_deletion_blocks_running_job(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)/'jobs';root.mkdir()
            exported=Path(tmp)/'exports';exported.mkdir()
            job=root/('d'*32);job.mkdir()
            (job/'pedido.json').write_text(json.dumps({'modo':'ao_vivo','url':'https://kick.com/gabepeixe'}))
            (job/'estado.json').write_text(json.dumps({'status':'running'}))
            dest=exported/'PorStreamer'/'gabepeixe'/job.name
            dest.mkdir(parents=True)
            f=dest/'test.mp4';f.write_bytes(b'keep')
            with patch.object(bridge,'EXPORTS',exported):
                with self.assertRaises(ValueError):
                    bridge.delete_selected_clips(root,[{'job':job.name,'arquivo':'test.mp4'}])
            self.assertTrue(f.exists())


if __name__ == '__main__':
    unittest.main()
