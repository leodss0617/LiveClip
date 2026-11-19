"""Capture a public Kick stream and export qualifying moments as it runs."""
import json
from math import ceil
import os
import re
import shutil
import subprocess
import sys
import threading
import time
import unicodedata
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path
from urllib.parse import urlsplit

from momentos import CONTEXT_LOOKAHEAD_SECONDS, select_moments
from clipes import adjusted_grade, clip_speech, details, marked_moment
from live_clock import CaptureClock, capture_offsets
from trechos import verify_render_duration
from chat_activity import activity_bonus
from live_chat_monitor import monitor_chat

CONTEXT_SECONDS = CONTEXT_LOOKAHEAD_SECONDS
TRENDS_URL = 'https://trends.google.com/trending/rss?geo=BR'
STOPWORDS = set('para pela pelo sobre hoje como onde mais muito com sem das dos uma por que este esta'.split())


def channel_url(value):
    try:
        url = urlsplit(value)
        slug = url.path.strip('/')
        if (url.scheme == 'https' and url.hostname in ('kick.com', 'www.kick.com', 'twitch.tv', 'www.twitch.tv')
                and url.username is None and url.password is None and url.port in (None, 443)
                and re.fullmatch(r'[a-zA-Z0-9_-]{1,40}', slug)
                and slug.lower() not in {'browse', 'categories', 'search', 'following', 'videos', 'clips'}):
            return 'https://' + ('twitch.tv' if url.hostname.endswith('twitch.tv') and 'kick.com' not in url.hostname else 'kick.com') + '/' + slug.lower()
    except (TypeError, ValueError, AttributeError):
        pass
    return None


def terms(text):
    plain = unicodedata.normalize('NFKD', text.casefold()).encode('ascii', 'ignore').decode()
    return set(re.findall(r'[a-z0-9]{3,}', plain)) - STOPWORDS


def trend_match(transcript, topics):
    spoken = terms(transcript)
    for title in topics:
        needed = terms(title)
        if len(needed) >= 2 and needed <= spoken:
            return title
    return None


def grade(score, trending=False, visual=False):
    base = max(1, min(10, round(score * 0.85, 1)))
    if base < 7:
        return base
    return min(10, round(base + 0.5*trending + 0.2*visual, 1))


def scene_changes(frames):
    frame_size = 64*36
    changes = []
    with frames.open('rb') as stream:
        previous = stream.read(frame_size)
        second = 1
        while len(current := stream.read(frame_size)) == frame_size:
            difference = sum(abs(a-b) for a,b in zip(previous,current))/frame_size
            if difference >= 30:
                changes.append(second)
            previous = current
            second += 1
    return changes


def trending_topics():
    try:
        request = urllib.request.Request(TRENDS_URL, headers={'User-Agent': 'EditorClipesDSS/2.0'})
        with urllib.request.urlopen(request, timeout=4) as response:
            data = response.read(524289)
        if len(data) <= 524288:
            return tuple(node.text[:120] for node in ET.fromstring(data).findall('./channel/item/title') if node.text)
    except (OSError, ValueError, ET.ParseError):
        pass
    return ()


def https_media_url(value):
    if not isinstance(value, str):
        return False
    try:
        parsed = urlsplit(value)
        return parsed.scheme == 'https' and bool(parsed.hostname) and parsed.username is None
    except ValueError:
        return False


def live_playback(channel, mobile=False):
    import yt_dlp
    options = {'format': 'b[height<=1080]/b', 'quiet': True, 'no_warnings': True,
               'noplaylist': True, 'socket_timeout': 6, 'retries': 1, 'cachedir': False}
    try:
        with yt_dlp.YoutubeDL(options) as downloader:
            info = downloader.extract_info(channel, download=False)
    except yt_dlp.utils.DownloadError as exc:
        detail = str(exc).casefold()
        if any(reason in detail for reason in ('not currently live', 'is not live', 'channel is offline')):
            raise ValueError('O canal estava offline ao iniciar o monitoramento. Tente quando voltar ao vivo.') from None
        raise ValueError('A plataforma não entregou a transmissão. Tente novamente em instantes.') from None

    if not info or not info.get('is_live'):
        raise ValueError('O canal estava offline ao iniciar o monitoramento. Tente quando voltar ao vivo.')
    selected = info.get('url')
    if not https_media_url(selected):
        raise ValueError('A plataforma não entregou um endereço de vídeo válido.')
    manifest = info.get('manifest_url')
    if mobile and not https_media_url(manifest):
        manifest = next((entry['manifest_url'] for entry in info.get('formats', ())
                         if entry.get('url') == selected and https_media_url(entry.get('manifest_url'))), None)
    url = manifest if mobile and https_media_url(manifest) else selected
    return {'url':url, 'headers':info.get('http_headers',{}), 'canal':channel}


def resolve(channel, output):
    try:
        Path(output).write_text(json.dumps(live_playback(channel)))
    except ValueError as exc:
        sys.exit(f'ERROR: {exc}')


def segment_ranges(start, end, timeline):
    return [(number, max(0, start-offset), min(duration, end-offset))
            for number,offset,duration in timeline
            if end > offset and start < offset+duration]


def keep_recent(capture, process, stop, lock):
    while not stop.wait(1):
        if process.poll() is not None:
            return
        with lock:
            for segment in sorted(capture.glob('*.ts'))[:-7]:
                try:
                    segment.unlink(missing_ok=True)
                except PermissionError:
                    continue


def process_live(folder, spec, count, maximum, log):
    from bridge import check_stop, clip_metadata, probe, read_json, render_config, run, run_heavy, state, public_folder

    channel = channel_url(spec.get('url'))
    if not channel:
        raise ValueError('Use o link de um canal da Kick para o modo Ao vivo.')
    state(folder, 'Conectando à transmissão ao vivo')
    run(log, sys.executable, '-c',
        "import sys; sys.path.insert(0,'scripts'); import licenca; licenca.exigir('link')")
    reference = folder/'live-source.json'
    run(log, sys.executable, str(Path(__file__).resolve()), 'resolve', channel, str(reference))
    source = json.loads(reference.read_text())
    reference.unlink()
    capture = folder/'captura'
    analysis = folder/'janela'
    for directory in (capture,analysis):
        if directory.exists():
            shutil.rmtree(directory)
    capture.mkdir()
    out = public_folder(folder, spec)
    out.mkdir(parents=True, exist_ok=True)
    headers = ''.join(f'{key}: {value}\r\n' for key,value in source['headers'].items()
                      if '\r' not in str(value) and '\n' not in str(value))
    segment_seconds = max(35, min(90, maximum // 2 + 5))
    retained_segments = ceil((maximum + CONTEXT_SECONDS) / segment_seconds) + 1
    cmd = ['ffmpeg','-nostdin','-nostats','-v','info','-rw_timeout','15000000']
    if headers:
        cmd += ['-headers', headers]
    cmd += ['-live_start_index','-1','-i',source['url'],'-map','0:v:0','-map','0:a:0',
            '-c','copy','-f','segment','-segment_time',str(segment_seconds),
            '-segment_list',str(folder/'captura.csv'),'-segment_list_type','csv',
            '-reset_timestamps','1',str(capture/'%09d.ts')]
    flags = subprocess.CREATE_NO_WINDOW | subprocess.BELOW_NORMAL_PRIORITY_CLASS if os.name == 'nt' else 0
    with (folder/'captura.log').open('wb') as capture_log:
        process = subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=capture_log, creationflags=flags)
        stop = threading.Event()
        clock = CaptureClock(folder,source,stop)
        threading.Thread(target=clock.find_anchor,daemon=True).start()
        # O chat alimenta a analise mesmo com o aplicativo minimizado ou painel fechado.
        threading.Thread(target=monitor_chat,args=(folder,channel,stop),daemon=True).start()
        lock = threading.Lock()
        pruner = threading.Thread(target=keep_recent, args=(capture,process,stop,lock), daemon=True)
        pruner.start()
        try:
            last_number = -1
            delivered = len(list(out.glob('*.mp4')))
            last_check = -float("inf")
            topics = ()
            used_ranges = []
            measurements = {}
            activity = time.monotonic()
            state(folder,'Capturando a live em segundo plano')
            while count == 0 or delivered < count:
                check_stop(folder)
                finished = process.poll() is not None
                with lock:
                    ready = sorted(capture.glob('*.ts'))
                    if not finished:
                        ready = ready[:-1]
                    new = [path for path in ready if int(path.stem) > last_number]
                    if new:
                        chosen = new[0]
                        number = int(chosen.stem)
                        sources = [chosen]
                        for previous_number in range(number-1, number-retained_segments, -1):
                            previous = capture/f'{previous_number:09d}.ts'
                            if previous_number not in measurements or not previous.is_file():
                                break
                            sources.insert(0, previous)
                        is_last_segment = finished and chosen == ready[-1]
                        captured_until = chosen.stat().st_mtime
                        analysis.mkdir()
                        for index, part in enumerate(sources):
                            shutil.copyfile(part, analysis/f'{index}.ts')
                        last_number = number
                if not new:
                    if finished:
                        if last_number < 0:
                            raise ValueError('A captura ao vivo encerrou antes de receber vídeo.')
                        break
                    if time.monotonic() - activity > max(150, segment_seconds * 3):
                        raise ValueError('A transmissão parou de enviar vídeo. Tente novamente.')
                    time.sleep(1)
                    continue
                activity = time.monotonic()
                try:
                    if spec.get('tendencias', True) and time.monotonic() - last_check > 900:
                        topics, last_check = trending_topics(), time.monotonic()
                    playlist = analysis/'janela.txt'
                    playlist.write_text(''.join(f"file '{index}.ts'\n" for index in range(len(sources))))
                    video = analysis/'janela.mp4'
                    latest = analysis/f'{len(sources)-1}.ts'
                    part = analysis/'bloco.mp4'
                    run(log,'ffmpeg','-nostdin','-v','error','-y','-i',str(latest),
                        '-map','0:v:0','-map','0:a:0','-c','copy',str(part))
                    length = float(probe(latest)['format']['duration'])
                    if length < 2:
                        continue
                    state(folder,'Analisando fala, áudio e mudanças na imagem')
                    frames = analysis/'quadros.raw'
                    run(log,'ffmpeg','-nostdin','-v','error','-y','-i',str(part),'-an',
                        '-vf','fps=1,scale=64:36,format=gray','-f','rawvideo',str(frames))
                    run_heavy(log,sys.executable,'scripts/ouvir.py',str(part),'--modelo','base','--saida',str(analysis))
                    measured_words = json.loads((analysis/'palavras.json').read_text())
                    measured_energy = json.loads((analysis/'energia.json').read_text())
                    measurements[number] = length,measured_words,measured_energy,scene_changes(frames),captured_until
                    words,energy,changes,timeline,marked_offsets = [],[],[],[],[]
                    marks = read_json(folder/'marcacoes.json',[])
                    duration = 0.0
                    boundary = 0.0
                    for source in sources:
                        part_number = int(source.stem)
                        part_length,part_words,part_energy,part_changes,closed_at = measurements[part_number]
                        marked_offsets.extend(capture_offsets(marks,duration,closed_at,part_length,
                                                              clock.start(part_number)))
                        timeline.append((part_number,duration,part_length))
                        words.extend(dict(word,s=word['s']+duration,e=word['e']+duration) for word in part_words)
                        changes.extend(second+duration for second in part_changes)
                        boundary = duration
                        duration += part_length
                        slots = ceil(duration)-len(energy)
                        energy.extend((part_energy + [-99]*max(0,slots-len(part_energy)))[:slots])
                    first_number = timeline[0][0]
                    measurements = {key:value for key,value in measurements.items() if key >= first_number}
                    used_ranges = [(segment,a,b) for segment,a,b in used_ranges if segment >= first_number]
                    state(folder,'Conferindo se o assunto terminou')
                    candidates = select_moments(duration,30,maximum,words,energy,
                                                end_after=max(0,boundary-CONTEXT_SECONDS),
                                                end_before=duration if is_last_segment else duration-CONTEXT_SECONDS)
                    chat_samples = read_json(folder/'chat-atividade.json', [])
                    candidates.sort(key=lambda moment: not marked_moment(moment.start,moment.end,marked_offsets))
                    for moment in candidates:
                        check_stop(folder)
                        if count != 0 and delivered >= count:
                            break
                        ranges = segment_ranges(moment.start,moment.end,timeline)
                        if any(segment == old and a < y and x < b
                               for segment,a,b in ranges for old,x,y in used_ranges):
                            continue
                        transcript = clip_speech(words,moment.start,moment.end)
                        topic = trend_match(transcript, topics)
                        visual = any(moment.start < change < moment.end for change in changes)
                        marked = marked_moment(moment.start,moment.end,marked_offsets)
                        base = grade(moment.score, bool(topic), visual)
                        rating = adjusted_grade(base,transcript,spec.get('feedback',[]))
                        chat_bonus, chat_count = activity_bonus(
                            chat_samples,moment.start,moment.end,timeline,measurements)
                        # O chat nao cria clipe de um momento sem contexto.
                        if chat_bonus and rating >= 7.0 and len(transcript.split()) >= 12:
                            rating = round(min(10, rating + chat_bonus), 1)
                        else:
                            chat_bonus, chat_count = 0, 0
                        if rating <= 7.5:
                            continue
                        reasons = list(getattr(moment,'reasons',()))
                        if chat_bonus: reasons.append('Chat mais ativo: '+str(chat_count)+' mensagens agregadas')
                        if topic: reasons.append('Assunto em alta relacionado: '+topic)
                        if visual: reasons.append('Mudança de imagem no trecho')
                        if marked: reasons.append('Perto do momento marcado por você')
                        if rating != base: reasons.append('Nota ajustada pelo feedback do canal')
                        index = delivered + 1
                        label = f'{rating:.1f}'.replace('.', '-')
                        cut = {'id':index,'inicio':moment.start,'fim':moment.end,'titulo':moment.title,
                               'nota':rating,'motivos':reasons}
                        if spec.get('efeitos') and getattr(moment, 'effects', ()):
                            cut['efeitos'] = list(moment.effects)
                        if not video.is_file():
                            run(log,'ffmpeg','-nostdin','-v','error','-y','-f','concat','-safe','1',
                                '-i',str(playlist),'-map','0:v:0','-map','0:a:0','-c','copy',str(video))
                            info = probe(video)
                            (analysis/'palavras.json').write_text(json.dumps(words))
                            (analysis/'energia.json').write_text(json.dumps(energy))
                        config = render_config(folder,spec,video,info)
                        state(folder,f'Exportando clipe {index} · nota {rating:.1f} (mínima >7,5)')
                        config_file = analysis/'cortes.json'
                        config_file.write_text(json.dumps(dict(config,cortes=[cut])))
                        run_heavy(log,sys.executable,'scripts/cortar.py',str(config_file))
                        rendered = next((analysis/'cortes').glob(f'{index:02d}_*.mp4'))
                        if shutil.disk_usage(out).free < 700 * 1024 * 1024:
                            raise ValueError("Pouco espaço disponível. O monitoramento foi interrompido para proteger o armazenamento.")
                        target = out/f'{rendered.stem}_nota-{label}.mp4'
                        temp = out/(target.name+'.tmp')
                        verify_render_duration(float(probe(rendered)['format']['duration']), maximum)
                        shutil.move(str(rendered),temp)
                        clip_metadata(folder,out,target.name,details(cut,words))
                        temp.replace(target)
                        delivered = index
                        used_ranges.extend(ranges)
                        if topic:
                            with log.open('a',encoding='utf-8') as report:
                                print(f'Clipe {index} • tendência relacionada: {topic}',file=report)
                        state(folder,'Clipe ao vivo pronto para envio',prontos=delivered)
                finally:
                    shutil.rmtree(analysis)
                activity = time.monotonic()
                state(folder,'Acompanhando a transmissão ao vivo')
            check_stop(folder)
            phase = 'Concluído' if count != 0 and delivered == count else 'Transmissão encerrada'
            if not delivered:
                phase = 'Transmissão encerrada sem clipes com nota acima de 7,5'
            state(folder,phase,status='done',prontos=delivered,total=delivered)
        finally:
            stop.set()
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=8)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()
            pruner.join()
            shutil.rmtree(capture)


if __name__ == '__main__':
    if len(sys.argv) == 4 and sys.argv[1] == 'resolve':
        resolve(sys.argv[2],sys.argv[3])
