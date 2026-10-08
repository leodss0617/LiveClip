"""Local audio evidence for musical boundaries, not a song recognition service."""

import hashlib
import math
import subprocess
import tempfile
from pathlib import Path

ASSETS = Path(__file__).parent / 'assets' / 'yamnet'
MODEL_SHA256 = 'd3835ffbbd4a1bb3e777f0ca217b5007907f5171dd5d17c4236b95b2af8f908e'
MAX_EVENT_SECONDS = 600


def music_spans(frames, duration):
    spans, current = [], None
    for time, music, speech, background in frames:
        positive = music >= .60 and speech < .60 and background < .50
        if current is not None and music >= .60:
            current.update(end=min(duration,time+1), last=time)
        if current is not None and time - current['last'] >= 8:
            current['closed_end'] = True
            spans.append(current)
            current = None
        if positive:
            if current is None:
                current = dict(start=time, end=min(duration,time+1), last=time,
                               confidence=music, closed_start=time >= 3, closed_end=False)
            else:
                current.update(end=min(duration,time+1), last=time,
                               confidence=max(current['confidence'],music))
    if current is not None:
        current['closed_end'] = duration - current['end'] >= 8
        spans.append(current)
    return spans


def classify_music(source, duration, cpu_threads=2, report=None):
    import numpy as np
    import onnxruntime as ort
    from .media import probe

    if not any(s['codec_type']=='audio' for s in probe(source)['streams']):
        return []
    if not math.isfinite(duration) or not 0 < duration <= 900:
        raise ValueError('Trecho de áudio excede o limite de análise.')
    model = ASSETS / 'yamnet.onnx'
    if hashlib.sha256(model.read_bytes()).hexdigest() != MODEL_SHA256:
        raise RuntimeError('Modelo de áudio ausente ou danificado. Reinstale o ZIP atualizado.')
    options=ort.SessionOptions()
    options.intra_op_num_threads=max(1,min(4,cpu_threads))
    options.inter_op_num_threads=1
    session=ort.InferenceSession(str(model),sess_options=options,providers=['CPUExecutionProvider'])
    frames=[]
    with tempfile.TemporaryDirectory(prefix='liveclip-audio-') as directory:
        raw=Path(directory)/'audio.f32'
        subprocess.run(['ffmpeg','-v','error','-i',str(source),'-t',str(duration),
                        '-vn','-ac','1','-ar','16000','-f','f32le',str(raw)],check=True,
                       stdout=subprocess.DEVNULL,stderr=subprocess.PIPE,timeout=120)
        with raw.open('rb') as stream:
            offset=0.0
            while block:=stream.read(16000*4*10):
                waveform=np.frombuffer(block,dtype='<f4').copy()
                scores=session.run(['output_0'],{'waveform':waveform})[0]
                block_duration=len(waveform)/16000
                for index,row in enumerate(scores):
                    time=offset+index*.48
                    if time >= offset+block_duration or time >= duration: break
                    frames.append((time,float(max(row[132],row[24])),float(row[0]),float(row[262])))
                offset+=block_duration
                if report:
                    report('listening','Classificando música, canto e fala no áudio.',round(min(100,100*offset/duration),1))
    return music_spans(frames,duration)


def music_candidates(spans,duration):
    candidates=[]
    for span in spans:
        start=max(0,span['start']-3)
        end=min(duration,span['end']+3)
        if span['closed_start'] and span['closed_end'] and 20 <= end-start <= MAX_EVENT_SECONDS:
            candidates.append(dict(start=start,end=end,score=min(.9,span['confidence']),
                title='Momento musical',reason='Música ou canto identificado no áudio; início e fim estimados por mudanças de som, com margem para preservar a apresentação.'))
    return candidates[:2]


def protect_candidates(candidates,spans,duration,*,final=False):
    approved=[]
    pending=[s['start'] for s in spans if s['closed_start'] and not s['closed_end'] and duration-s['start'] <= MAX_EVENT_SECONDS]
    for candidate in candidates:
        start,end=candidate['start'],candidate['end']
        overlap=[s for s in spans if min(end,s['end'])-max(start,s['start']) > 1]
        if any(not s['closed_start'] or not s['closed_end'] for s in overlap): continue
        if overlap:
            start=min(start,*[max(0,s['start']-3) for s in overlap])
            end=max(end,*[min(duration,s['end']+3) for s in overlap])
        elif not final and duration-end < 12:
            continue
        if end-start > MAX_EVENT_SECONDS: continue
        approved.append(dict(candidate,start=start,end=end) if overlap else candidate)
    return approved,min(pending) if pending else None


def merge_candidates(candidates):
    result=[]
    for candidate in sorted(candidates,key=lambda c:c['score'],reverse=True):
        if any(max(0,min(candidate['end'],old['end'])-max(candidate['start'],old['start']))/
               min(candidate['end']-candidate['start'],old['end']-old['start']) >= .5 for old in result): continue
        result.append(candidate)
    return result[:4]
