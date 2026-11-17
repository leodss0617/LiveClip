import unittest

from trechos import clip_window, clip_timeline, usable_section, verify_render_duration


class ClipTimelineTest(unittest.TestCase):
    def test_uses_padding_and_shifts_words_effects_and_energy(self):
        start, end = clip_window(35.8, 49.2, 100)
        self.assertEqual((start, end), (32, 53))
        cut = {'id': 2, 'inicio': 35.8, 'fim': 49.2, 'efeitos': [[40, 'olhos']]}
        words = [{'s': 31, 'e': 32.4, 'w': 'antes'}, {'s': 39, 'e': 40, 'w': 'agora'},
                 {'s': 54, 'e': 55, 'w': 'depois'}]
        local, captions, energy = clip_timeline(cut, words, list(range(100)), start, end)
        self.assertAlmostEqual(local['inicio'], 3.8)
        self.assertAlmostEqual(local['fim'], 17.2)
        self.assertEqual(local['efeitos'], [[8, 'olhos']])
        self.assertEqual(captions, [{'s': 7, 'e': 8, 'w': 'agora'}])
        self.assertEqual(energy, list(range(32, 53)))
        self.assertEqual(cut['inicio'], 35.8)

    def test_clamps_to_video_boundaries(self):
        self.assertEqual(clip_window(0.4, 19, 20.2), (0, 20.2))

    def test_export_cannot_silently_remove_the_story_ending(self):
        self.assertEqual(verify_render_duration(59.98, 60), 59.98)
        self.assertEqual(verify_render_duration(60.04, 60), 60.04)
        with self.assertRaisesRegex(ValueError, 'Não foi cortado automaticamente'):
            verify_render_duration(62.3, 60)
        with self.assertRaises(ValueError):
            verify_render_duration(float('nan'), 60)

    def test_rejects_full_or_truncated_download(self):
        self.assertTrue(usable_section(21.1, 32, 53, 49.2))
        self.assertFalse(usable_section(99, 32, 53, 49.2))
        self.assertFalse(usable_section(10, 32, 53, 49.2))

    def test_link_uses_audio_then_renders_localized_video_section(self):
        import json
        import os
        from pathlib import Path
        from tempfile import TemporaryDirectory
        from types import SimpleNamespace
        from unittest.mock import patch
        from bridge import perform

        with TemporaryDirectory() as tmp:
            root = Path(tmp)
            folder = root/'job'
            folder.mkdir()
            calls = []
            cut_data = []
            def simulate(log, *args):
                script = Path(args[1]).name
                calls.append(script)
                if script == 'baixar.py':
                    dest = Path(args[args.index('--pasta')+1])
                    dest.mkdir(parents=True)
                    if '--somente-audio' in args:
                        (dest/'analise.m4a').touch()
                    else:
                        self.assertIn('--inicio', args)
                        (dest/'video.mp4').touch()
                elif script == 'ouvir.py':
                    self.assertEqual(Path(args[2]).suffix,'.m4a')
                    dest = Path(args[-1])
                    dest.mkdir(exist_ok=True)
                    (dest/'palavras.json').write_text('[{"s":39,"e":40,"w":"agora"}]')
                    (dest/'energia.json').write_text(json.dumps([-20]*100))
                elif script == 'cortar.py':
                    cfg = Path(args[-1])
                    cut_data.append(json.loads(cfg.read_text())['cortes'][0])
                    (cfg.parent/'cortes').mkdir()
                    (cfg.parent/'cortes'/'01_agora.mp4').write_bytes(b'test')

            def probe(video):
                duration = 100 if video.suffix == '.m4a' else 21
                return {'format':{'duration':duration},'streams':[
                    {'codec_type':'video','width':1920,'height':1080},{'codec_type':'audio'}]}

            with patch.dict(os.environ,{'EDITOR_LIVE_SECTIONS':'1','EDITOR_EXPORTS':str(root/'exports')}), \
                 patch('bridge.run',side_effect=simulate), patch('bridge.probe',side_effect=probe), \
                 patch('bridge.select_moments',return_value=[SimpleNamespace(start=35.8,end=49.2,title='agora',score=10)]):
                perform(folder,{'url':'https://kick.com/test','quantidade':1,'duracao':60})
            self.assertEqual(json.loads((folder/'estado.json').read_text())['status'],'done')
            self.assertEqual(calls,['baixar.py','ouvir.py','baixar.py','cortar.py'])
            self.assertAlmostEqual(cut_data[0]['inicio'],3.8)
            self.assertTrue((root/'exports/job/01_agora_nota-8-5.mp4').is_file())
            self.assertFalse((folder/'trechos/1').exists())
