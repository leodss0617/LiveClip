import unittest
from ao_vivo import channel_url, grade, scene_changes, trend_match

class LiveTest(unittest.TestCase):
    def test_only_kick_channel_urls(self):
        self.assertEqual(channel_url('https://www.kick.com/coringa/?x=1'), 'https://kick.com/coringa')
        for value in ('https://kick.com/coringa/videos/abc', 'https://kick.com:123/coringa',
                      'https://fake@kick.com/coringa', 'https://www.youtube.com/coringa'):
            self.assertIsNone(channel_url(value))

    def test_trend_matches_multiword_topic_and_cannot_rescue_weak_clip(self):
        self.assertEqual(trend_match('Na live falaram do título do Flamengo campeão hoje', ('Flamengo campeão',)), 'Flamengo campeão')
        self.assertIsNone(trend_match('Só falaram do Flamengo', ('Flamengo campeão',)))
        self.assertLessEqual(grade(6, True), 7)
        self.assertGreater(grade(10, False), 7)
        self.assertEqual(grade(100, True), 10)

    def test_detects_visible_change_and_limits_its_influence(self):
        from tempfile import TemporaryDirectory
        from pathlib import Path
        with TemporaryDirectory() as tmp:
            frames = Path(tmp)/'frames'
            frames.write_bytes(bytes(2304)*2 + bytes([255])*2304)
            self.assertEqual(scene_changes(frames), [2])
        self.assertLessEqual(grade(6, True, True), 7)

    def test_multi_segment_scene_keeps_its_original_boundaries(self):
        from ao_vivo import segment_ranges
        self.assertEqual(segment_ranges(30, 80, [(4,0,35),(5,35,35),(6,70,35)]),
                         [(4,30,35),(5,0,35),(6,0,10)])

    def test_does_not_export_a_partial_story_between_live_windows(self):
        import json
        from pathlib import Path
        from tempfile import TemporaryDirectory
        from unittest.mock import Mock, patch
        from ao_vivo import process_live
        from test_momentos import speech

        transcripts = [
            speech(28, 'Será que dá pra ganhar sozinho contra os três?'),
            speech(4, 'Consegui ganhar a rodada sozinho!') +
            speech(12, 'Só que o segredo foi esperar a equipe inteira sair.') +
            speech(23, 'Por isso eu fiquei escondido atrás da parede.'),
            speech(1, 'Por isso consegui virar a partida com um único tiro.') +
            speech(8, 'Mudando de assunto vamos falar sobre o torneio de amanhã.'),
        ]
        with TemporaryDirectory() as tmp:
            folder = Path(tmp)/'pedido'
            folder.mkdir()
            result = Path(tmp)/'exports'
            calls = []
            cuts = []
            def spawn(*args, **kwargs):
                for number in range(3):
                    (folder/'captura'/f'{number:09d}.ts').touch()
                return Mock(poll=Mock(return_value=0))
            def run(log, *args):
                if args[1] == '-c':
                    return
                if 'resolve' in args:
                    Path(args[-1]).write_text(json.dumps({'url':'https://kick.com/test','headers':{}}))
                    return
                target = Path(args[-1])
                if 'ouvir.py' in args[1]:
                    iteration = len(calls)
                    calls.append(iteration)
                    (target/'palavras.json').write_text(json.dumps(transcripts[iteration]))
                    (target/'energia.json').write_text(json.dumps([-40]*35))
                elif 'cortar.py' in args[1]:
                    self.assertEqual(calls,[0,1,2])
                    cuts.extend(json.loads(target.read_text())['cortes'])
                    (target.parent/'cortes').mkdir()
                    (target.parent/'cortes'/'01_assunto.mp4').write_bytes(b'test')
                else:
                    target.write_bytes(b'')
            def probe(path):
                return {'format':{'duration': 105 if path.name == 'janela.mp4' else 35},
                        'streams':[{'codec_type':'video','width':1280,'height':720}]}
            with patch.dict('os.environ',{'EDITOR_EXPORTS':str(result)}), \
                 patch('ao_vivo.subprocess.Popen',side_effect=spawn), \
                 patch('bridge.run',side_effect=run), patch('bridge.probe',side_effect=probe):
                process_live(folder,{'url':'https://kick.com/test','tendencias':False},1,110,folder/'processo.log')
            self.assertEqual(len(cuts),1)
            self.assertLess(cuts[0]['inicio'],28)
            self.assertGreater(cuts[0]['fim'],73)
            self.assertLess(cuts[0]['fim'],78)
            self.assertEqual(len(list((result/'pedido').glob('*.mp4'))),1)
            self.assertFalse((folder/'captura').exists())
