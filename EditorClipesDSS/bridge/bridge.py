import hmac
import json
import math
import os
import re
import shutil
import signal
import subprocess
import struct
import sys
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit
from momentos import select_moments
from clipes import VOTES, adjusted_grade, clip_speech, details, review_bounds
from ao_vivo import channel_url, grade, live_playback, process_live
from live_clock import playback_mark
from trechos import clip_window, clip_timeline, usable_section, verify_render_duration

PLATFORMS = {'kick.com','www.kick.com','twitch.tv','www.twitch.tv','youtube.com','www.youtube.com','m.youtube.com','youtu.be'}
MACHINE = Path('/opt/maquina-de-cortes')
ROOT = Path('/root/editor-jobs')
EXPORTS = Path('/mnt/downloads/EditorClipesDSS')
TOKEN_FILE = Path('/root/.editorclipes-token')
MAX_LOGO = 2 * 1024 ** 2
WORKER = ThreadPoolExecutor(max_workers=1)
MAX_LIVES = 2
LIVE_WORKER = ThreadPoolExecutor(max_workers=MAX_LIVES)
LIVE_REQUEST_LOCK = threading.Lock()
PHONE_SLOT = threading.BoundedSemaphore(2)
# Duas capturas simultaneas, mas uma transcricao/renderizacao pesada de cada vez.
PHONE_ANALYSIS_SLOT = threading.BoundedSemaphore(1)
PORT = 18754
RETRY_LOCK = threading.Lock()
CLIP_LOCK = threading.Lock()
PLAYER_LOCK = threading.BoundedSemaphore(MAX_LIVES)
INTERRUPTED = 'O Termux foi encerrado. Envie o trabalho novamente.'

def stage_progress(phase, log):
    for line in reversed(log.splitlines()):
        if phase.startswith('Transcrevendo'):
            match=re.search(r'\((\d+)%\).*faltam ~([0-9]+) min', line)
            if match:
                return min(100,int(match[1])),int(match[2])*60
        elif phase.startswith('Baixando'):
            match=re.search(r'(\d+(?:\.\d+)?)%.* ETA (Unknown|[0-9:]+)', line)
            if match:
                eta=None if match[2]=='Unknown' else sum(int(part)*60**i for i,part in enumerate(reversed(match[2].split(':'))))
                return min(100,float(match[1])),eta
    return None,None

class JobStopped(Exception):
    pass

def check_stop(folder):
    if (folder/'parar').exists():
        raise JobStopped

def pending_jobs(root):
    for status in sorted(root.glob('*/estado.json')):
        try:
            previous=json.loads(status.read_text())
            if (status.parent/'parar').exists():
                continue
            spec_file=status.with_name('pedido.json')
            if spec_file.is_file() and (previous.get('status') in ('queued','running') or previous.get('erro') == INTERRUPTED):
                yield status.parent,json.loads(spec_file.read_text())
        except (ValueError,OSError):
            continue

def logo_id(value):
    return isinstance(value, str) and re.fullmatch(r'[a-f0-9]{32}', value) is not None

def validate_logo(data):
    if not 33 <= len(data) <= MAX_LOGO or data[:16] != b"\x89PNG\r\n\x1a\n\x00\x00\x00\x0dIHDR":
        raise ValueError('O logo precisa ser uma imagem PNG válida.')
    width, height = struct.unpack_from('>II', data, 16)
    if not (1 <= width <= 512 and 1 <= height <= 512):
        raise ValueError('O logo excede 512 pixels.')
    return width, height

def limits(count, seconds):
    if type(count) is not int or type(seconds) is not int or not 1 <= count <= 30 or not 10 <= seconds <= 240:
        raise ValueError('Escolha de 1 a 30 clipes e duração de 10 a 240 segundos.')
    return count, seconds

def live_url(link):
    try:
        u = urlsplit(link)
        return u.scheme == 'https' and u.hostname in PLATFORMS and u.username is None and u.password is None and u.port in (None,443) and bool(u.path.strip('/'))
    except (TypeError, ValueError):
        return False

def state(folder, phase, **fields):
    dest=folder/'estado.json'
    data=json.loads(dest.read_text()) if dest.exists() else {}
    data.update(fase=phase,**fields)
    temp=folder/'estado.tmp'
    temp.write_text(json.dumps(data,ensure_ascii=False))
    for attempt in range(30):
        try:
            temp.replace(dest)
            break
        except PermissionError:
            if os.name != 'nt' or attempt == 29:
                raise
            time.sleep(0.1)

def requeue_failed(folder):
    with RETRY_LOCK:
        status=folder/'estado.json'
        if not status.is_file():
            raise FileNotFoundError('Trabalho não encontrado.')
        if json.loads(status.read_text()).get('status') != 'error':
            return None
        request=folder/'pedido.json'
        if not request.is_file():
            raise ValueError('Este pedido antigo precisa ser enviado novamente.')
        spec=json.loads(request.read_text())
        if not spec.get('url') and not (folder/'entrada.mp4').is_file():
            raise ValueError('O vídeo original não está mais disponível.')
        (folder/'parar').unlink(missing_ok=True)
        state(folder,'Nova tentativa na fila',status='queued',erro=None)
        return spec

def completed_sources(folder):
    return sorted(video for video in folder.glob('*.mp4')
                  if not any(folder.glob(video.name+'.part*'))
                  and not video.with_name(video.name+'.ytdl').exists())

def run(log,*args):
    check_stop(log.parent)
    with log.open('ab') as output:
        process=subprocess.Popen(args,cwd=MACHINE,stdout=output,stderr=subprocess.STDOUT,start_new_session=True)
        while True:
            try:
                code=process.wait(timeout=1)
                break
            except subprocess.TimeoutExpired:
                if (log.parent/'parar').exists():
                    if os.name == 'nt':
                        subprocess.run(['taskkill','/PID',str(process.pid),'/T','/F'],
                                       stdout=output,stderr=subprocess.STDOUT,check=True)
                        process.wait(timeout=15)
                        raise JobStopped
                    for sig in (signal.SIGTERM,signal.SIGKILL):
                        try:
                            os.killpg(process.pid,sig)
                        except ProcessLookupError:
                            break
                        try:
                            process.wait(timeout=4)
                            break
                        except subprocess.TimeoutExpired:
                            continue
                    raise JobStopped
        check_stop(log.parent)
        if code:
            raise subprocess.CalledProcessError(code,args)


def run_heavy(log, *args):
    """Allow concurrent live capture, but serialize memory-intensive processing."""
    while not PHONE_ANALYSIS_SLOT.acquire(timeout=1):
        check_stop(log.parent)
    try:
        return run(log, *args)
    finally:
        PHONE_ANALYSIS_SLOT.release()

def probe(video):
    return json.loads(subprocess.check_output(['ffprobe','-v','error','-show_streams','-show_format','-of','json',str(video)]))

def timecode(t):
    return f'{int(t)//3600:02}:{int(t)//60%60:02}:{t%60:06.3f}'

def full_source(folder, url, log):
    source = folder/'fonte'
    completed = completed_sources(source)
    if not completed:
        state(folder,'Baixando ou retomando o download da live')
        run(log,sys.executable,'scripts/baixar.py',url,'--pasta',str(source))
        completed = completed_sources(source)
    if not completed:
        raise ValueError('A plataforma não retornou um vídeo para esse link.')
    return completed[0]


def section_source(folder, url, log, cut, words, energy, duration):
    start, end = clip_window(cut['inicio'],cut['fim'],duration)
    section = folder/'trechos'/str(cut['id'])
    run(log,sys.executable,'scripts/baixar.py',url,'--pasta',str(section),
        '--inicio',timecode(start),'--fim',timecode(end))
    video = next(iter(completed_sources(section)),None)
    if video is None:
        raise ValueError('A plataforma não retornou o trecho solicitado.')
    info = probe(video)
    actual = float(info['format']['duration'])
    if not usable_section(actual,start,end,cut['fim']) or not any(
        stream['codec_type'] == 'audio' for stream in info['streams']
    ):
        raise ValueError('A plataforma retornou um trecho incompleto ou fora do intervalo.')
    local, captions, levels = clip_timeline(cut,words,energy,start,end)
    (section/'palavras.json').write_text(json.dumps(captions))
    (section/'energia.json').write_text(json.dumps(levels))
    return video, info, section, local


def log_fallback(log, exc):
    with log.open('a',encoding='utf-8') as report:
        print(f'O link não permitiu usar o fluxo por trechos. Baixando o vídeo completo. ({exc})',file=report)


def render_config(folder, spec, video, info):
    config={'video':str(video),'estilo':'amarelo','layout':'desfoque','zoom_automatico':False,'bordas_confirmadas':True}
    if (folder/'logo.png').is_file():
        config['logo']=str(folder/'logo.png')
    stream=next(x for x in info['streams'] if x['codec_type']=='video')
    w,h=int(stream['width']),int(stream['height'])
    camera=spec.get('camera')
    if w>h and camera in {'esquerda','direita'}:
        cw,ch=w//6*2,h//6*2
        config.update(layout='camera',camera=[0 if camera=='esquerda' else (w-cw)//2*2,0,cw,ch],
                      jogo=[cw if camera=='esquerda' else 0,0,(w-cw)//2*2,h//2*2])
    elif h>=w: config['layout']='cheio'
    if spec.get('efeitos'): config.update(efeitos_auto=True,sons_volume=0.43)
    return config


def write_checkpoint(path, value):
    pending = path.with_suffix(path.suffix+'.tmp')
    pending.write_text(json.dumps(value),encoding='utf-8')
    pending.replace(path)


def read_json(path, default=None):
    try:
        return json.loads(path.read_text(encoding='utf-8'))
    except (OSError, ValueError):
        return default


def clip_metadata(folder, output, name, data):
    if os.name == 'nt':
        directory = output
    else:
        directory = folder/'detalhes'
        directory.mkdir(exist_ok=True)
    write_checkpoint(directory/(name+'.meta.json'),data)


def channel_feedback(root):
    return read_json(root/'feedback-clipes.json',{}) or {}


def review_clip(folder, spec, log, maximum):
    request = spec['revisao']
    out = Path(os.environ.get('EDITOR_EXPORTS',EXPORTS))/folder.name
    out.mkdir(parents=True,exist_ok=True)
    if list(out.glob('*.mp4')):
        state(folder,'Revisão concluída',status='done',prontos=1,total=1)
        return
    start,end,duration = (float(request[key]) for key in ('inicio','fim','duracao_fonte'))
    if not all(map(math.isfinite,(start,end,duration))) or not 0 <= start < end <= duration+0.1:
        raise ValueError('Os tempos da gravação original são inválidos.')
    left = max(0,int(start)-45)
    right = min(duration,math.ceil(end)+45)
    analysis = folder/'analise'
    analysis.mkdir(exist_ok=True)
    state(folder,'Baixando contexto ao redor do clipe')
    run(log,sys.executable,'scripts/baixar.py',spec['url'],'--pasta',str(analysis),
        '--inicio',timecode(left),'--fim',timecode(right))
    source = next(iter(completed_sources(analysis)),None)
    if source is None:
        raise ValueError('A plataforma não disponibilizou a gravação original.')
    info = probe(source)
    length = float(info['format']['duration'])
    if not usable_section(length,left,right,end):
        raise ValueError('A plataforma não retornou o trecho da gravação que foi solicitado.')
    state(folder,'Procurando o começo e o fim da conversa')
    run(log,sys.executable,'scripts/ouvir.py',str(source),'--modelo','base','--saida',str(analysis))
    words = json.loads((analysis/'palavras.json').read_text())
    begin,finish = review_bounds(words,length,start-left,end-left,maximum)
    cut = {'id':1,'titulo':request['titulo'],'inicio':begin,'fim':finish,
           'nota':request['nota'],'motivos':['Nota do clipe original','Contexto estendido com base na gravação']}
    config = render_config(folder,spec,source,info)
    settings = analysis/'cortes.json'
    write_checkpoint(settings,dict(config,cortes=[cut]))
    state(folder,'Exportando a revisão de contexto')
    run(log,sys.executable,'scripts/cortar.py',str(settings))
    rendered = next((analysis/'cortes').glob('01_*.mp4'))
    rating = f"{cut['nota']:.1f}".replace('.','-')
    target = out/f'{rendered.stem}_nota-{rating}.mp4'
    metadata = details(cut,words,spec['url'],duration)
    metadata.update(inicio=begin+left,fim=finish+left)
    clip_metadata(folder,out,target.name,metadata)
    temp = target.with_suffix('.mp4.tmp')
    shutil.copyfile(rendered,temp)
    temp.replace(target)
    state(folder,'Revisão concluída',status='done',prontos=1,total=1)


def compatible_checkpoint(source, reference):
    try:
        old = json.loads((source/'referencia.json').read_text(encoding='utf-8'))
        return old['audio'] == reference['audio'] and abs(old['duracao']-reference['duracao']) < 1
    except (OSError, ValueError, KeyError, TypeError):
        return False


def resume_analysis(folder, analysis, reference):
    """Reuse a verified local or PC snapshot without repeating finished clips."""
    # The local plan is durable across a stopped Termux process. Never use a
    # plan from another source or an incompatible version of the same video.
    if compatible_checkpoint(analysis, reference):
        local_plan = read_json(analysis/'plano-retomada.json', {})
        if (isinstance(local_plan, dict) and local_plan.get('referencia') == reference
                and all(key in local_plan for key in ('palavras', 'energia', 'cortes'))):
            return local_plan
    source = folder/'progresso-pc'
    if not compatible_checkpoint(source,reference):
        return None
    prepared = source/'plano-retomada.json'
    if prepared.is_file():
        value = json.loads(prepared.read_text(encoding='utf-8'))
        if value['referencia'] == json.loads((source/'referencia.json').read_text(encoding='utf-8')):
            return value
    checkpoint = source/'_parcial.jsonl'
    local = analysis/'_parcial.jsonl'
    if checkpoint.is_file() and not (analysis/'info.json').is_file():
        if not local.exists() or checkpoint.stat().st_size > local.stat().st_size:
            shutil.copyfile(checkpoint,local)
    return None


def submit_job(folder, spec):
    worker = LIVE_WORKER if spec.get('modo') == 'ao_vivo' else WORKER
    return worker.submit(perform, folder, spec)


def live_jobs(root, exports=None):
    latest = {}
    for folder in root.iterdir():
        if not re.fullmatch(r'[a-f0-9]{32}', folder.name):
            continue
        order = folder / 'pedido.json'
        spec = read_json(order, {})
        if spec.get('modo') != 'ao_vivo':
            continue
        channel = channel_url(spec.get('url'))
        info = read_json(folder / 'estado.json', {})
        if not channel or not info:
            continue
        try:
            priority = (info.get('status') in ('queued','running'), order.stat().st_mtime)
        except OSError:
            continue
        previous = latest.get(channel)
        if previous is not None and previous[0] >= priority:
            continue
        info.update(id=folder.name, canal=channel)
        if exports is not None:
            info['arquivos'] = sorted(path.name for path in (exports / folder.name).glob('*.mp4'))
        latest[channel] = priority, info
    return [info for _, info in sorted(latest.values(), key=lambda entry: entry[0], reverse=True)]


def check_live_capacity(channel):
    active = [job for job in live_jobs(ROOT) if job.get('status') in ('queued','running')]
    if any(job['canal'] == channel for job in active):
        raise ValueError('Esse canal já está sendo monitorado.')
    if len(active) >= MAX_LIVES:
        raise ValueError('Já existem duas lives em monitoramento. Pare uma antes de iniciar outra.')


def processing_error(log, attempt_start, exc):
    if isinstance(exc, subprocess.CalledProcessError) and log.is_file():
        with log.open('rb') as report:
            report.seek(max(attempt_start, log.stat().st_size - 24_000))
            recent = report.read().decode(errors='replace').splitlines()[-8:]
        relevant = [line for line in recent if any(word in line.upper()
                    for word in ('LICEN', 'LIBERADO', 'NAO BAIXOU', 'ERROR:', 'NAO CONSEGUI'))]
        if relevant:
            return relevant[-1].removeprefix('ERROR: ').strip()[:420]
    return (str(exc).strip() or type(exc).__name__)[:420]


def perform(folder, spec):
    log=folder/'processo.log'
    attempt_start = log.stat().st_size if log.exists() else 0
    using_phone = False
    phone_slot = False
    try:
        check_stop(folder)
        previous = folder/'estado.json'
        using_phone = previous.is_file() and json.loads(previous.read_text()).get('motor_ativo') == 'termux'
        if spec.get('motor') == 'pc' and not using_phone:
            import pc_client
            try:
                pc_client.execute(folder,spec,state,check_stop)
                return
            except pc_client.PCUnavailable as exc:
                resumed = getattr(exc,'retomar',False)
                if (folder/'pc-iniciado').exists() and not resumed:
                    raise RuntimeError('O pedido já foi enviado ao PC. Reconecte para verificar o processamento.')
                check_stop(folder)
                using_phone = True
                phase = 'Retomando no Termux após perder a conexão com o PC' if resumed else 'PC indisponível. Usando o Termux no celular'
                state(folder,phase,status='running',motor_ativo='termux',erro=None)
                (folder/'pc-iniciado').unlink(missing_ok=True)
                with log.open('a',encoding='utf-8') as report:
                    print('PC indisponível. Processamento iniciado no Termux do celular.',file=report)
        if using_phone:
            while not PHONE_SLOT.acquire(timeout=1):
                check_stop(folder)
                state(folder,'Aguardando a vez no Termux',status='queued',motor_ativo='termux',erro=None)
            phone_slot = True
        check_stop(folder)
        count, maximum=limits(spec['quantidade'],spec['duracao'])
        state(folder,'Preparando o trabalho',status='running',erro=None)
        if 'revisao' in spec:
            review_clip(folder,spec,log,maximum)
            return
        if spec.get('modo') == 'ao_vivo':
            process_live(folder,spec,count,maximum,log)
            return
        video=folder/'entrada.mp4'
        recovered=folder/'fonte-verificada.mp4'
        url=spec.get('url')
        by_section=False
        render_info=None
        if recovered.is_file():
            video=recovered
        elif url:
            if using_phone or os.getenv('EDITOR_LIVE_SECTIONS') == '1':
                try:
                    audio=folder/'audio'/'analise.m4a'
                    state(folder,'Obtendo o áudio da live para análise')
                    run(log,sys.executable,'scripts/baixar.py',url,'--pasta',str(audio.parent),'--somente-audio')
                    audio_info=probe(audio)
                    if not any(x['codec_type']=='audio' for x in audio_info['streams']):
                        raise ValueError('Áudio indisponível.')
                    duration=float(audio_info['format']['duration'])
                    if not 0 < duration < float('inf'):
                        raise ValueError('Duração do áudio indisponível.')
                    video=audio
                    by_section=True
                except (subprocess.CalledProcessError, OSError, ValueError, KeyError) as exc:
                    log_fallback(log,exc)
            if not by_section:
                video=full_source(folder,url,log)
        if not by_section:
            state(folder,'Verificando o vídeo')
            render_info=probe(video)
            duration=float(render_info['format']['duration'])
            if not any(x['codec_type']=='audio' for x in render_info['streams']):
                state(folder,'Preparando o vídeo sem áudio')
                with_audio=folder/'sem-fala.mp4'
                if not with_audio.exists():
                    temporary=with_audio.with_suffix('.tmp.mp4')
                    run(log,'ffmpeg','-v','error','-y','-i',str(video),'-f','lavfi','-i','anullsrc=r=48000:cl=stereo','-map','0:v:0','-map','1:a:0','-c:v','copy','-c:a','aac','-t',str(duration),str(temporary))
                    temporary.replace(with_audio)
                video=with_audio
        state(folder,'Transcrevendo e procurando momentos')
        analysis=folder/'analise'
        out=Path(os.environ.get('EDITOR_EXPORTS',EXPORTS))/folder.name
        out.mkdir(parents=True,exist_ok=True)
        already = len(list(out.glob('*.mp4')))
        if already >= count:
            state(folder,'Concluído',status='done',prontos=already,total=count)
            return
        analysis.mkdir(exist_ok=True)
        reference = {'duracao':duration,'audio':by_section}
        same_local_reference = compatible_checkpoint(analysis, reference)
        resumed_plan = resume_analysis(folder,analysis,reference) if using_phone else None
        write_checkpoint(analysis/'referencia.json',reference)
        if resumed_plan is None:
            # Re-transcribe if the source has changed, even when stale info exists.
            if not (analysis/'info.json').is_file() or not same_local_reference:
                run(log,sys.executable,'scripts/ouvir.py',str(video),'--modelo','base','--saida',str(analysis))
            words=json.loads((analysis/'palavras.json').read_text())
            energy=json.loads((analysis/'energia.json').read_text())
            state(folder,'Selecionando trechos com gancho e desfecho')
            profile = spec.get('feedback',[])
            picks = []
            for moment in select_moments(duration,30,maximum,words,energy):
                speech = clip_speech(words,moment.start,moment.end)
                base = grade(moment.score)
                rating = adjusted_grade(base,speech,profile)
                if rating > 7:
                    picks.append((moment,rating,rating != base))
            cuts=[]
            for moment,rating,changed in sorted(picks,key=lambda pick:-pick[1])[:count]:
                a,b=moment.start,moment.end
                reasons = list(getattr(moment,'reasons',()))
                if changed:
                    reasons.append('Nota ajustada pelo feedback do canal')
                cut={'id':len(cuts)+1,'inicio':a,'fim':b,'titulo':moment.title,'nota':rating,'motivos':reasons}
                if spec.get('efeitos'):
                    peaks=sorted(range(int(a)+3,min(int(b)-2,len(energy))),key=lambda t:energy[t],reverse=True)
                    marks=[]
                    for t in peaks:
                        if all(abs(t-v)>=8 for v in marks): marks.append(t)
                        if len(marks)==2: break
                    cut['efeitos']=[[t,'exclamacao' if j==0 else 'olhos'] for j,t in enumerate(sorted(marks))]
                cuts.append(cut)
            write_checkpoint(analysis/'plano-retomada.json',
                             {'referencia':reference,'palavras':words,'energia':energy,'cortes':cuts})
        else:
            words,energy,cuts = (resumed_plan[key] for key in ('palavras','energia','cortes'))
            write_checkpoint(analysis/'palavras.json',words)
            write_checkpoint(analysis/'energia.json',energy)
        if not cuts:
            phase = 'Concluído' if already else 'Nenhum clipe com nota acima de 7'
            state(folder,phase,status='done',prontos=already,total=already)
            return
        for cut in cuts:
            check_stop(folder)
            n=cut['id']
            if any(out.glob(f'{n:02d}_*.mp4')):
                continue
            render_dir, render_cut = analysis, cut
            render_video, info = video, render_info
            if by_section:
                state(folder,f'Obtendo o trecho {n} de {len(cuts)} em alta qualidade',total=len(cuts))
                try:
                    render_video, info, render_dir, render_cut = section_source(
                        folder,url,log,cut,words,energy,duration
                    )
                except (subprocess.CalledProcessError, OSError, ValueError, KeyError) as exc:
                    log_fallback(log,exc)
                    video=render_video=full_source(folder,url,log)
                    state(folder,'Verificando o vídeo')
                    render_info=info=probe(video)
                    by_section=False
            config=render_config(folder,spec,render_video,info)
            state(folder,f'Exportando clipe {n} de {len(cuts)}',total=len(cuts))
            config_file=render_dir/'cortes.json'
            config_file.write_text(json.dumps(dict(config,cortes=[render_cut])))
            run(log,sys.executable,'scripts/cortar.py',str(config_file))
            video_out=next((render_dir/'cortes').glob(f'{n:02d}_*.mp4'))
            verify_render_duration(float(probe(video_out)['format']['duration']), maximum)
            rating=f"{cut['nota']:.1f}".replace('.','-')
            target=out/f'{video_out.stem}_nota-{rating}.mp4'
            temp=out/(target.name+'.tmp')
            clip_metadata(folder,out,target.name,details(cut,words,url,duration))
            shutil.copyfile(video_out,temp)
            temp.replace(target)
            if render_dir != analysis:
                shutil.rmtree(render_dir,ignore_errors=True)
            state(folder,f'{n} de {len(cuts)} clipes prontos',prontos=n)
        check_stop(folder)
        state(folder,'Concluído',status='done',prontos=len(list(out.glob('*.mp4'))),total=len(cuts))
    except JobStopped:
        state(folder,'Parado pelo usuário',status='cancelled',erro=None)
    except Exception as exc:
        message=processing_error(log, attempt_start, exc)
        state(folder,'Falha no processamento',status='error',erro=message)
    finally:
        if phone_slot:
            PHONE_SLOT.release()
        if using_phone:
            current = json.loads((folder/'estado.json').read_text())
            if current.get('status') in ('done','cancelled'):
                for name in ('audio','fonte','analise','trechos','captura','janela','progresso-pc'):
                    shutil.rmtree(folder/name,ignore_errors=True)
                for name in ('entrada.mp4','sem-fala.mp4','captura.log','captura.csv'):
                    (folder/name).unlink(missing_ok=True)

class Handler(BaseHTTPRequestHandler):
    def reply(self, value, code=200):
        data=json.dumps(value,ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header('Content-Type','application/json; charset=utf-8')
        self.send_header('Content-Length',str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def authorized(self):
        expected='Bearer '+TOKEN_FILE.read_text().strip()
        if hmac.compare_digest(self.headers.get('Authorization',''),expected): return True
        self.reply({'erro':'Código de conexão inválido.'},401)
        return False

    def do_POST(self):
        if not self.authorized(): return
        if self.path == '/live/player':
            return self.open_live_player()
        stop=re.fullmatch(r'/jobs/([a-f0-9]{32})/stop',self.path)
        if stop:
            return self.stop_job(ROOT/stop[1])
        retry=re.fullmatch(r'/jobs/([a-f0-9]{32})/retry',self.path)
        if retry:
            return self.retry_job(ROOT/retry[1])
        mark=re.fullmatch(r'/jobs/([a-f0-9]{32})/mark',self.path)
        if mark:
            return self.mark_job(ROOT/mark[1])
        clip_action=re.fullmatch(r'/jobs/([a-f0-9]{32})/([A-Za-z0-9_.-]+\.mp4)/(feedback|review)',self.path)
        if clip_action:
            ident,name,action=clip_action.groups()
            return self.clip_action(ROOT/ident,name,action)
        if self.path == '/logo':
            return self.upload_logo()
        if self.path != '/jobs':
            return self.reply({'erro':'Rota inválida.'},404)
        folder=None
        live_request=False
        try:
            length=int(self.headers.get('Content-Length',0))
            if not 0<length<=8*1024**3: raise ValueError('Arquivo vazio ou acima de 8 GB.')
            if self.headers.get_content_type()=='application/json':
                if length>8192: raise ValueError('Pedido muito grande.')
                spec=json.loads(self.rfile.read(length))
                if spec.get('modo') == 'ao_vivo':
                    channel = channel_url(spec.get('url'))
                    if not channel or spec.get('motor') != 'pc':
                        raise ValueError('Use o endereço de um canal da Kick no modo Ao vivo.')
                    spec['url'] = channel
                    LIVE_REQUEST_LOCK.acquire()
                    live_request = True
                    check_live_capacity(channel)
                elif not live_url(spec.get('url','')):
                    raise ValueError('Use um link HTTPS da Kick, Twitch ou YouTube.')
                limits(spec['quantidade'],spec['duracao'])
                folder=ROOT/uuid.uuid4().hex
                folder.mkdir()
            else:
                spec={'quantidade':int(self.headers.get('X-Quantidade','0')),'duracao':int(self.headers.get('X-Duracao','0')),
                      'efeitos':self.headers.get('X-Efeitos')=='true','camera':self.headers.get('X-Camera',''), 'logo':self.headers.get('X-Logo',''), 'motor':self.headers.get('X-Motor','')}
                limits(spec['quantidade'],spec['duracao'])
                if length+512*1024**2>shutil.disk_usage(ROOT).free: raise ValueError('Espaço insuficiente no celular.')
                folder=ROOT/uuid.uuid4().hex
                folder.mkdir()
                with (folder/'entrada.mp4').open('wb') as output:
                    while length:
                        chunk=self.rfile.read(min(length,1024**2))
                        if not chunk: raise ValueError('O envio do vídeo foi interrompido.')
                        output.write(chunk)
                        length-=len(chunk)
            logo=spec.get('logo')
            if logo:
                if not logo_id(logo) or not (ROOT/'logos'/f'{logo}.png').is_file():
                    raise ValueError('Envie o logo novamente.')
                (ROOT/'logos'/f'{logo}.png').replace(folder/'logo.png')
            spec['feedback'] = list(channel_feedback(ROOT).values())[-100:]
            (folder/'pedido.json').write_text(json.dumps(spec))
            state(folder,'Na fila',status='queued',id=folder.name,prontos=0,total=spec['quantidade'],
                  solicitados=spec['quantidade'],modo=spec.get('modo','gravacao'))
        except (ValueError, OSError, KeyError) as exc:
            if folder: shutil.rmtree(folder,ignore_errors=True)
            return self.reply({'erro':str(exc)},400)
        finally:
            if live_request:
                LIVE_REQUEST_LOCK.release()
        submit_job(folder,spec)
        self.reply({'id':folder.name},201)

    def clip_details(self, folder, name):
        path=EXPORTS/folder.name/name
        if not path.is_file():
            raise FileNotFoundError('Clipe indisponível.')
        found = read_json(folder/'detalhes'/(name+'.meta.json'),{})
        if not found:
            label = name.removesuffix('.mp4')
            rating = re.search(r'_nota-(\d+)-(\d+)$',label)
            found = {'titulo':label.split('_nota-')[0].split('_',1)[-1].replace('-',' '),
                     'nota':float(rating[1]+'.'+rating[2]) if rating else None,
                     'motivos':['Explicação indisponível para este clipe antigo.']}
        vote = channel_feedback(ROOT).get(folder.name+'/'+name,{})
        return dict(found,feedback=vote.get('voto'),pode_revisar=bool(found.get('url') and found.get('duracao_fonte')))

    def clip_action(self, folder, name, action):
        new_folder = None
        try:
            payload_length = int(self.headers.get('Content-Length',0))
            if not 0 <= payload_length <= 4096:
                raise ValueError('Pedido inválido.')
            payload = json.loads(self.rfile.read(payload_length) or b'{}')
            data = self.clip_details(folder,name)
            if action == 'feedback':
                vote=payload.get('voto')
                if vote not in VOTES:
                    raise ValueError('Feedback inválido.')
                with CLIP_LOCK:
                    profile=channel_feedback(ROOT)
                    key=folder.name+'/'+name
                    profile.pop(key,None)
                    profile[key]={'voto':vote,'termos':data.get('termos',[])}
                    write_checkpoint(ROOT/'feedback-clipes.json',dict(list(profile.items())[-100:]))
                return self.reply(dict(data,feedback=vote))
            original=read_json(folder/'pedido.json',{})
            if not data['pode_revisar'] or not live_url(original.get('url')):
                raise ValueError('A revisão precisa do link da gravação original.')
            if original.get('modo') == 'ao_vivo' or original['url'] != data['url']:
                raise ValueError('A gravação original ainda não está disponível para revisão.')
            maximum=original['duracao']
            limits(1,maximum)
            new_folder=ROOT/uuid.uuid4().hex
            new_folder.mkdir()
            if (folder/'logo.png').is_file():
                shutil.copyfile(folder/'logo.png',new_folder/'logo.png')
            spec={key:original[key] for key in ('url','camera','efeitos') if key in original}
            spec.update(motor='pc',quantidade=1,duracao=maximum,revisao=data)
            write_checkpoint(new_folder/'pedido.json',spec)
            state(new_folder,'Revisão de contexto na fila',id=new_folder.name,status='queued',prontos=0,
                  total=1,solicitados=1,original={'job':folder.name,'arquivo':name})
            submit_job(new_folder,spec)
            return self.reply({'id':new_folder.name},201)
        except (ValueError,OSError,KeyError,TypeError,AttributeError) as exc:
            if new_folder is not None:
                shutil.rmtree(new_folder,ignore_errors=True)
            return self.reply({'erro':str(exc)},404 if isinstance(exc,FileNotFoundError) else 409)

    def player_payload(self):
        size = int(self.headers.get('Content-Length',0))
        if not 0 <= size <= 4096:
            raise ValueError('Pedido inválido.')
        value = json.loads(self.rfile.read(size) or b'{}')
        if not isinstance(value,dict):
            raise ValueError('Pedido inválido.')
        return value

    def open_live_player(self):
        if not PLAYER_LOCK.acquire(blocking=False):
            return self.reply({'erro':'Aguarde a tentativa de abrir a live.'},409)
        try:
            link=channel_url(self.player_payload().get('url'))
            if not link:
                raise ValueError('Use o link de um canal da Kick.')
            source=live_playback(link,mobile=True)
            allowed={'user-agent','origin','referer','accept'}
            source['headers']={k:v for k,v in source['headers'].items() if k.lower() in allowed}
            return self.reply(source)
        except (OSError,ValueError) as exc:
            return self.reply({'erro':str(exc)},409)
        finally:
            PLAYER_LOCK.release()

    def mark_job(self, folder):
        try:
            payload = self.player_payload()
            mark = playback_mark(payload,time.time())
        except (OSError,ValueError) as exc:
            return self.reply({'erro':str(exc)},400)
        with CLIP_LOCK:
            request=read_json(folder/'pedido.json',{})
            current=read_json(folder/'estado.json',{})
            if request.get('modo')!='ao_vivo' or current.get('status')!='running' or (folder/'parar').exists():
                return self.reply({'erro':'Inicie uma captura ao vivo antes de marcar o momento.'},409)
            if payload.get('canal') and (channel_url(payload['canal']) or '').casefold() != request['url'].casefold():
                return self.reply({'erro':'O player está em outro canal. Abra a transmissão que está sendo capturada.'},409)
            path=folder/'marcacoes.json'
            marks=read_json(path,[])
            last=marks[-1] if marks else 0
            previous=last['registrado'] if isinstance(last,dict) else last
            if mark['registrado']-previous >= 3:
                write_checkpoint(path,(marks+[mark])[-60:])
        return self.reply({'ok':True},201)

    def retry_job(self, folder):
        try:
            with LIVE_REQUEST_LOCK:
                original = read_json(folder/'pedido.json',{})
                if original.get('modo') == 'ao_vivo':
                    check_live_capacity(original.get('url'))
                spec=requeue_failed(folder)
        except FileNotFoundError as exc:
            return self.reply({'erro':str(exc)},404)
        except (OSError,ValueError) as exc:
            return self.reply({'erro':str(exc)},409)
        if spec is None:
            return self.reply({'erro':'Só é possível reenfileirar um trabalho com falha.'},409)
        submit_job(folder,spec)
        return self.reply({'id':folder.name},202)

    def stop_job(self, folder):
        status=folder/'estado.json'
        if not status.is_file():
            return self.reply({'erro':'Trabalho não encontrado.'},404)
        data=json.loads(status.read_text())
        if data.get('status') in ('queued','running'):
            (folder/'parar').touch()
        return self.reply({'ok':True})

    def upload_logo(self):
        try:
            length=int(self.headers.get('Content-Length',0))
            if not 33 <= length <= MAX_LOGO:
                raise ValueError('O logo precisa ter até 2 MB.')
            data=self.rfile.read(length)
            validate_logo(data)
            ident=uuid.uuid4().hex
            directory=ROOT/'logos'
            directory.mkdir(exist_ok=True)
            (directory/f'{ident}.png').write_bytes(data)
            self.reply({'id':ident},201)
        except (OSError,ValueError) as exc:
            self.reply({'erro':str(exc)},400)

    def do_GET(self):
        if not self.authorized(): return
        if self.path=='/health': return self.reply({'ok':True,'lives':MAX_LIVES,'qualidade_player':'adaptativa'})
        if self.path=='/jobs/lives': return self.reply({'lives':live_jobs(ROOT, EXPORTS)})
        meta=re.fullmatch(r'/jobs/([a-f0-9]{32})/([A-Za-z0-9_.-]+\.mp4)/details',self.path)
        if meta:
            try:
                return self.reply(self.clip_details(ROOT/meta[1],meta[2]))
            except FileNotFoundError as exc:
                return self.reply({'erro':str(exc)},404)
        m=re.fullmatch(r'/jobs/([a-f0-9]{32})(?:/(log|[A-Za-z0-9_.-]+\.mp4))?',self.path)
        if not m: return self.reply({'erro':'Rota inválida.'},404)
        ident,filename=m.groups()
        folder=ROOT/ident
        status=folder/'estado.json'
        public=EXPORTS/ident
        if not status.exists(): return self.reply({'erro':'Trabalho não encontrado.'},404)
        if filename == 'log':
            log=folder/'processo.log'
            data=json.loads(status.read_text())
            output=''
            if log.is_file():
                with log.open('rb') as source:
                    source.seek(max(0,log.stat().st_size-12000))
                    output=source.read(12000).decode('utf-8',errors='replace')
            active=data.get('status') in ('queued','running')
            stopping=active and (folder/'parar').exists()
            percent,eta=stage_progress(data['fase'],output) if active and not stopping else (None,None)
            return self.reply(dict(data,log=output,progresso=percent,eta_segundos=eta,parando=stopping))
        if filename:
            video=public/filename
            if not video.is_file(): return self.reply({'erro':'Clipe indisponível.'},404)
            with video.open('rb') as source:
                self.send_response(200)
                self.send_header('Content-Type','video/mp4')
                self.send_header('Content-Length',str(video.stat().st_size))
                self.end_headers()
                shutil.copyfileobj(source,self.wfile)
            return
        data=json.loads(status.read_text())
        request=read_json(folder/'pedido.json',{})
        if request.get('modo')=='ao_vivo':
            data.update(modo='ao_vivo',canal=request.get('url',''))
        data['arquivos']=sorted(p.name for p in public.glob('*.mp4'))
        self.reply(data)

if __name__=='__main__':
    ROOT.mkdir(exist_ok=True)
    server=ThreadingHTTPServer(('127.0.0.1',PORT),Handler)
    for marker in ROOT.glob('*/parar'):
        status=marker.with_name('estado.json')
        if status.is_file() and json.loads(status.read_text()).get('status') in ('queued','running'):
            state(marker.parent,'Parado pelo usuário',status='cancelled',erro=None)
    for folder,spec in pending_jobs(ROOT):
        state(folder,'Retomando trabalho salvo',status='queued',erro=None)
        submit_job(folder,spec)
    server.serve_forever()
