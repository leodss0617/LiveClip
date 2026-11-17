"""Connect the Android player's time to the equivalent frame in the capture."""
import csv
import re
import urllib.request
from datetime import datetime
from urllib.parse import urljoin, urlsplit


def playback_mark(payload, now):
    delay = payload.get('atraso_ms')
    video = payload.get('video_ms')
    if delay is not None and (type(delay) is not int or not 0 <= delay <= 3600000):
        raise ValueError('Atraso de reprodução inválido.')
    if video is not None and (type(video) is not int or not now-3600 <= video/1000 <= now+120):
        raise ValueError('O horário informado pelo player está fora da janela da live.')
    return {'epoch': now-(delay or 0)/1000, 'video_epoch': video/1000 if video is not None else None,
            'registrado':now}


def capture_offsets(marks, offset, captured_until, duration, source_start):
    offsets=[]
    for mark in marks:
        if isinstance(mark,(int,float)):
            at,start=mark,captured_until-duration
        elif source_start is not None and mark.get('video_epoch') is not None:
            at,start=mark['video_epoch'],source_start
        else:
            at,start=mark['epoch'],captured_until-duration
        if start <= at <= start+duration:
            offsets.append(offset+at-start)
    return offsets


def manifest_times(text, base):
    durations=[]
    first_epoch=None
    elapsed=0.0
    length=None
    for raw in text.splitlines():
        line=raw.strip()
        if line.startswith('#EXT-X-PROGRAM-DATE-TIME:'):
            try:
                epoch = datetime.fromisoformat(line.split(':',1)[1].replace('Z','+00:00')).timestamp()
            except ValueError:
                continue
            if first_epoch is None:
                for i,(path,offset,_) in enumerate(durations):
                    durations[i]=(path,offset,epoch-elapsed)
            first_epoch=epoch-elapsed
        elif line.startswith('#EXTINF:'):
            try:
                length=float(line.split(':',1)[1].split(',',1)[0])
            except ValueError:
                length=None
        elif line and not line.startswith('#') and length is not None:
            durations.append((urlsplit(urljoin(base,line)).path,elapsed,first_epoch))
            elapsed+=length
            length=None
    return {path: offset+epoch for path,offset,epoch in durations if epoch is not None}


def fetch_manifest(url, headers):
    if urlsplit(url).scheme != 'https':
        return '',url
    with urllib.request.urlopen(urllib.request.Request(url,headers=headers),timeout=4) as response:
        return response.read(256*1024).decode('utf-8',errors='replace'),response.geturl()


class CaptureClock:
    def __init__(self, folder, source, stop):
        self.log=folder/'captura.log'
        self.csv=folder/'captura.csv'
        self.source=source
        self.stop=stop
        self.anchor=None

    def find_anchor(self):
        urls={self.source['url']}
        known={}
        for _ in range(8):
            if self.stop.is_set():
                return
            try:
                with self.log.open('rb') as report:
                    opened=re.findall(r"Opening '([^']+)' for reading",report.read(180000).decode(errors='replace'))
            except OSError:
                opened=[]
            for url in opened[:12]:
                if urlsplit(url).path.endswith('.m3u8'):
                    urls.add(url)
            for url in list(urls)[:4]:
                try:
                    text,base=fetch_manifest(url,self.source['headers'])
                except (OSError,ValueError):
                    continue
                known.update(manifest_times(text,base))
            media=[urlsplit(url).path for url in opened if urlsplit(url).path.endswith(('.ts','.m4s','.aac'))]
            if media and media[0] in known:
                self.anchor=known[media[0]]
                return
            if self.stop.wait(1):
                return

    def start(self, number):
        if self.anchor is None:
            return None
        try:
            with self.csv.open(newline='',encoding='utf-8') as f:
                rows=list(csv.reader(f))
            if rows:
                first=float(rows[0][1])
                for row in rows:
                    if len(row)>=3 and row[0].replace('\\','/').split('/')[-1]==f'{number:09d}.ts':
                        return self.anchor+float(row[1])-first
        except (OSError,ValueError,IndexError):
            pass
        return None
