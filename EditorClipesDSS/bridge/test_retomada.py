import io
import json
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
from types import SimpleNamespace
from unittest.mock import patch

from bridge import perform, resume_analysis


class ResumeTest(unittest.TestCase):
    def test_local_checkpoint_preserves_analysis_across_restarts(self):
        with TemporaryDirectory() as temp:
            folder = Path(temp)
            analysis = folder/'analise'
            analysis.mkdir()
            reference = {'duracao': 120, 'audio': False}
            plan = {'referencia': reference, 'palavras': [{'w':'teste'}],
                    'energia': [-40], 'cortes': [{'id':1, 'inicio':10, 'fim':25}]}
            (analysis/'referencia.json').write_text(json.dumps(reference))
            (analysis/'plano-retomada.json').write_text(json.dumps(plan))
            self.assertEqual(resume_analysis(folder, analysis, reference), plan)
            self.assertIsNone(resume_analysis(folder, analysis, {'duracao':200,'audio':False}))

    def test_snapshot_resumes_only_matching_audio_and_duration(self):
        with TemporaryDirectory() as temp:
            folder = Path(temp)
            source = folder/'progresso-pc'
            source.mkdir()
            analysis = folder/'analise'
            analysis.mkdir()
            (source/'referencia.json').write_text('{"duracao":100,"audio":true}')
            payload = b'{"fim":22,"w":[]}\n'
            (source/'_parcial.jsonl').write_bytes(payload)
            resume_analysis(folder,analysis,{'duracao':100,'audio':False})
            self.assertFalse((analysis/'_parcial.jsonl').exists())
            resume_analysis(folder,analysis,{'duracao':100.1,'audio':True})
            self.assertEqual((analysis/'_parcial.jsonl').read_bytes(),payload)

    def test_completed_pc_plan_is_reused_and_delivered_clip_is_not_rendered_again(self):
        with TemporaryDirectory() as temp:
            root = Path(temp)
            folder = root/'job'
            source = folder/'progresso-pc'
            source.mkdir(parents=True)
            reference = {'duracao':100,'audio':False}
            (source/'referencia.json').write_text(json.dumps(reference))
            (source/'plano-retomada.json').write_text(json.dumps({
                'referencia':reference,'palavras':[],'energia':[-40]*100,
                'cortes':[
                    {'id':1,'inicio':4,'fim':12,'titulo':'primeiro','nota':9},
                    {'id':2,'inicio':42,'fim':58,'titulo':'segundo','nota':9},
                ],
            }))
            (folder/'estado.json').write_text('{"motor_ativo":"termux","status":"running"}')
            (folder/'entrada.mp4').write_bytes(b'input')
            out = root/'exports'/'job'
            out.mkdir(parents=True)
            existing = out/'01_primeiro_nota-9-0.mp4'
            existing.write_bytes(b'already delivered')
            exported = []
            def run(log,*args):
                self.assertTrue(args[1].endswith('cortar.py'),args)
                config = Path(args[-1])
                cut = json.loads(config.read_text())['cortes'][0]
                exported.append(cut['id'])
                (config.parent/'cortes').mkdir()
                (config.parent/'cortes'/'02_segundo.mp4').write_bytes(b'new clip')
            def probe(path):
                return {'format':{'duration':100 if path.name=='entrada.mp4' else 16},
                        'streams':[{'codec_type':'video','width':1080,'height':1920},
                                   {'codec_type':'audio'}]}
            with patch.dict('os.environ',{'EDITOR_EXPORTS':str(root/'exports')}), \
                 patch('bridge.run',side_effect=run), patch('bridge.probe',side_effect=probe):
                perform(folder,{'motor':'pc','quantidade':2,'duracao':60})
            self.assertEqual(exported,[2])
            self.assertEqual(existing.read_bytes(),b'already delivered')
            self.assertTrue((out/'02_segundo_nota-9-0.mp4').exists())
            self.assertEqual(json.loads((folder/'estado.json').read_text())['prontos'],2)

    def test_reconnection_reattaches_instead_of_starting_second_remote_process(self):
        try:
            import pc_client
        except ModuleNotFoundError:
            self.skipTest('Paramiko faz parte do ambiente de produção')
        with TemporaryDirectory() as tmp:
            root=Path(tmp)
            folder=root/'job'
            folder.mkdir()
            job=pc_client.JOBS+'/job'
            class Sftp:
                def __enter__(self): return self
                def __exit__(self,*args): pass
                def get_channel(self): return SimpleNamespace(settimeout=lambda _:None)
                def mkdir(self,path): self.created += 1
                def put(self,*args,**kwargs): pass
                def listdir(self,path): return []
                def open(self,path,mode='r'):
                    if 'w' in mode:
                        return io.StringIO()
                    if path==job+'/estado.json':
                        if self.drop:
                            raise EOFError('interrupted')
                        return io.StringIO('{"status":"done","fase":"Concluído","prontos":0,"total":1}')
                    raise FileNotFoundError(path)
                def stat(self,path):
                    if path==job and not self.acked:
                        return SimpleNamespace(st_mtime=1)
                    raise FileNotFoundError(path)
                def __init__(self,drop):
                    self.created=0
                    self.drop=drop
                    self.acked=False
            from unittest.mock import Mock
            channel=Mock()
            channel.recv_ready.return_value=False
            transport=Mock()
            transport.open_session.return_value=channel
            first,second=Sftp(True),Sftp(False)
            def client(sftp):
                handle=Mock()
                handle.__enter__=Mock(return_value=handle)
                handle.__exit__=Mock(return_value=False)
                handle.open_sftp.return_value=sftp
                handle.get_transport.return_value=transport
                return handle
            states=[]
            with patch.object(pc_client,'connect_pc',side_effect=[client(first),client(second)]), \
                 patch.object(pc_client,'worker_available',return_value=True), \
                 patch.object(pc_client,'apply_pending_update'), patch.object(pc_client,'clean_retired'), \
                 patch.object(pc_client,'OUTPUT',root/'out'), patch.object(pc_client.time,'sleep'):
                pc_client.execute(folder,{'quantidade':1,'modo':'ao_vivo'},
                                  lambda folder,phase,**fields:states.append((phase,fields)),lambda _:None)
            self.assertEqual(first.created,1)
            self.assertEqual(second.created,0)
            channel.exec_command.assert_called_once()
            self.assertTrue(any('Reconectando' in phase for phase,_ in states))
            self.assertEqual(states[-1][1]['status'],'done')

    def test_expired_lease_fences_old_pc_before_attempting_local_fallback(self):
        import os
        import time
        try:
            import pc_client
        except ModuleNotFoundError:
            self.skipTest('Paramiko faz parte do ambiente de produção')
        with TemporaryDirectory() as tmp:
            folder=Path(tmp)/'job'
            folder.mkdir()
            (folder/'pc-iniciado').touch()
            lease=folder/'pc-ultima-conexao'
            lease.touch()
            old=time.time()-pc_client.TAKEOVER_GRACE-1
            os.utime(lease,(old,old))
            with patch.object(pc_client,'OUTPUT',Path(tmp)/'out'),patch.object(pc_client,'connect_pc') as attach:
                with self.assertRaises(pc_client.PCUnavailable) as caught:
                    pc_client.execute(folder,{'quantidade':2},lambda *a,**kw:None,lambda _:None)
            self.assertTrue(caught.exception.retomar)
            self.assertTrue((folder/'pc-abandonado').exists())
            attach.assert_not_called()
