import unittest
from clipes import details, preference_bonus, review_bounds
from test_momentos import speech


class ClipFeaturesTest(unittest.TestCase):
    def test_details_use_actual_speech_and_reasons(self):
        words=speech(1,'Consegui ganhar sozinho contra três jogadores.')+speech(7,'Foi assim que ganhei a rodada!')
        result=details({'titulo':'Ganhei a rodada','inicio':1,'fim':10,'nota':8.5,
                        'motivos':['Desfecho identificado']},words)
        self.assertIn('ganhei a rodada',result['legenda'].lower())
        self.assertEqual(result['motivos'],['Desfecho identificado'])
        self.assertEqual(result['nota'],8.5)

    def test_feedback_only_adjusts_related_clips_and_is_bounded(self):
        examples=[{'voto':'postaria','termos':['campeonato','flamengo']}] * 10
        self.assertGreater(preference_bonus('Flamengo venceu campeonato',examples),0)
        self.assertLessEqual(preference_bonus('Flamengo venceu campeonato',examples),0.35)
        self.assertEqual(preference_bonus('Motocicleta na garagem',examples),0)

    def test_review_includes_the_continuation_without_switching_topics(self):
        words=speech(8,'Será que vamos ganhar essa rodada difícil?')
        words+=speech(14,'Consegui ganhar a rodada sozinho!')
        words+=speech(20,'Por isso eu esperei os adversários saírem.')
        end=words[-1]['e']
        words+=speech(26,'Mudando de assunto vamos falar do campeonato.')
        a,b=review_bounds(words,36,8,16,25)
        self.assertLessEqual(a,8)
        self.assertGreaterEqual(b,end)
        self.assertLess(b,26)

    def test_review_exports_a_new_clip_from_only_the_surrounding_section(self):
        import json
        import os
        from pathlib import Path
        from tempfile import TemporaryDirectory
        from unittest.mock import patch
        from bridge import review_clip
        with TemporaryDirectory() as tmp:
            root=Path(tmp)
            folder=root/'review'
            folder.mkdir()
            calls=[]
            def run(log,*args):
                script=Path(args[1]).name
                calls.append(script)
                if script=='baixar.py':
                    self.assertIn('--inicio',args)
                    self.assertIn('--fim',args)
                    (folder/'analise'/'source.mp4').write_bytes(b'video')
                elif script=='ouvir.py':
                    words=speech(14,'Consegui ganhar a rodada sozinho!')
                    words+=speech(20,'Por isso eu esperei os adversários saírem.')
                    (folder/'analise'/'palavras.json').write_text(json.dumps(words))
                else:
                    settings=json.loads(Path(args[-1]).read_text())
                    self.assertGreater(settings['cortes'][0]['fim'],21)
                    (folder/'analise'/'cortes').mkdir()
                    (folder/'analise'/'cortes'/'01_revisao.mp4').write_bytes(b'review')
            info={'format':{'duration':61},'streams':[{'codec_type':'video','width':1920,'height':1080}]}
            with patch.dict(os.environ,{'EDITOR_EXPORTS':str(root/'exports')}), \
                 patch('bridge.run',side_effect=run),patch('bridge.probe',return_value=info):
                review_clip(folder,{'url':'https://kick.com/canal/videos/123','revisao':{
                    'inicio':14,'fim':16,'duracao_fonte':200,'titulo':'A rodada','nota':8.5}},
                    folder/'processo.log',60)
            self.assertEqual(calls,['baixar.py','ouvir.py','cortar.py'])
            self.assertTrue((root/'exports/review/01_revisao_nota-8-5.mp4').exists())
            found=json.loads((folder/'detalhes/01_revisao_nota-8-5.mp4.meta.json').read_text())
            self.assertGreater(found['fim'],21)
            self.assertIn('Nota do clipe original',found['motivos'])

    def test_marker_matches_a_nearby_moment_and_not_a_distant_one(self):
        from clipes import marked_moment
        self.assertTrue(marked_moment(30,42,[27]))
        self.assertFalse(marked_moment(30,42,[12,55]))

    def test_app_routes_save_feedback_mark_live_and_queue_comparison(self):
        import json
        import threading
        from http.server import ThreadingHTTPServer
        from pathlib import Path
        from tempfile import TemporaryDirectory
        from urllib.request import Request, urlopen
        from unittest.mock import Mock, patch
        import bridge

        with TemporaryDirectory() as tmp:
            root=Path(tmp)
            jobs=root/'jobs'
            exports=root/'exports'
            token=root/'token'
            token.write_text('test')
            ident='1'*32
            liveid='2'*32
            name='01_rodada_nota-8-5.mp4'
            folder=jobs/ident
            data=folder/'detalhes'
            data.mkdir(parents=True)
            media=exports/ident
            media.mkdir(parents=True)
            (media/name).write_bytes(b'test')
            vod='https://kick.com/canal/videos/test'
            (folder/'pedido.json').write_text(json.dumps({'url':vod,'duracao':60}))
            (data/(name+'.meta.json')).write_text(json.dumps({
                'titulo':'Rodada','legenda':'Ganhei a rodada.','nota':8.5,
                'motivos':['Reação identificada'],'termos':['rodada','ganhei'],
                'inicio':8,'fim':20,'url':vod,'duracao_fonte':120,
            }))
            live=jobs/liveid
            live.mkdir()
            (live/'pedido.json').write_text('{"modo":"ao_vivo"}')
            (live/'estado.json').write_text('{"status":"running"}')
            worker=Mock()
            with patch.multiple(bridge,ROOT=jobs,EXPORTS=exports,TOKEN_FILE=token,WORKER=worker):
                with ThreadingHTTPServer(('127.0.0.1',0),bridge.Handler) as server:
                    thread=threading.Thread(target=server.serve_forever,daemon=True)
                    thread.start()
                    try:
                        def api(path,data=None):
                            body=json.dumps(data).encode() if data is not None else None
                            request=Request(f'http://127.0.0.1:{server.server_port}'+path,data=body,
                                            headers={'Authorization':'Bearer test','Content-Type':'application/json'})
                            with urlopen(request,timeout=4) as response:
                                return json.load(response)
                        uri=f'/jobs/{ident}/{name}'
                        self.assertEqual(api(uri+'/details')['motivos'],['Reação identificada'])
                        self.assertEqual(api(uri+'/feedback',{'voto':'postaria'})['feedback'],'postaria')
                        self.assertEqual(bridge.channel_feedback(jobs)[f'{ident}/{name}']['voto'],'postaria')
                        api(f'/jobs/{liveid}/mark',{})
                        self.assertEqual(len(bridge.read_json(live/'marcacoes.json')),1)
                        revised=api(uri+'/review',{})['id']
                        self.assertEqual(api(f'/jobs/{revised}')['original']['arquivo'],name)
                        self.assertEqual(worker.submit.call_count,1)
                    finally:
                        server.shutdown()
                        thread.join()
