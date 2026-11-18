"""Monitor Kick em thread daemon da captura. Somente agrega estatisticas."""
import time
from urllib.parse import urlsplit
from chat_intelligence import ChatHeatDetector
from kick_chat import recent_messages

EXCITED_WORDS = ('kkkk', 'haha', '🔥', '😂', '😱', '😮', 'caraca', 'mds', 'clipa')
POLL_LIVE_SECONDS = 5.5
POLL_OFFLINE_SECONDS = 15
MAX_BACKOFF_SECONDS = 45


def signal_from_updates(previous, snapshot):
    """Compatibilidade com monitor anterior: IDs, novos, fracao de reacoes."""
    items = snapshot.get('mensagens', [])
    if not isinstance(items, list):
        return previous, 0, 0.0
    ids = {str(item.get('id', '')) for item in items
           if isinstance(item, dict) and item.get('id')}
    if previous is None or not snapshot.get('ao_vivo') or snapshot.get('atrasado'):
        return ids, 0, 0.0
    fresh = [item for item in items
             if isinstance(item, dict) and str(item.get('id', '')) in ids - previous]
    if not fresh:
        return ids, 0, 0.0
    new = fresh[-200:]
    excited = sum(any(word in str(item.get('mensagem', '')).lower() for word in EXCITED_WORDS)
                  or str(item.get('mensagem', '')).count('!') +
                  str(item.get('mensagem', '')).count('?') >= 2 for item in new)
    return ids, len(new), round(excited / len(new), 2)


def monitor_chat(folder, channel, stop, interval=POLL_LIVE_SECONDS):
    """Consulta chat mesmo sem tela aberta, enquanto captura estiver ativa."""
    from bridge import CLIP_LOCK, read_json, write_checkpoint
    parts = urlsplit(str(channel))
    slug = parts.path.strip('/').lower()
    if parts.hostname not in ('kick.com', 'www.kick.com') or not slug:
        return
    detector = ChatHeatDetector(chat_delay_seconds=5)
    failures = 0
    while not stop.is_set():
        seconds = interval
        try:
            snap = recent_messages(slug)
            pulse = detector.analyze(snap)
            failures = 0
            if not snap.get('ao_vivo'):
                seconds = max(interval, POLL_OFFLINE_SECONDS)
            if pulse.messages > 0:
                with CLIP_LOCK:
                    location = folder / 'chat-atividade.json'
                    history = read_json(location, [])
                    if not isinstance(history, list):
                        history = []
                    record = {'registrado': pulse.timestamp,
                              'mensagens': pulse.messages,
                              'animacao': pulse.reactions,
                              'usuarios': pulse.unique_authors,
                              'pico': pulse.peak,
                              'pontuacao': pulse.score,
                              'atraso': detector.chat_delay_seconds}
                    if not history or pulse.timestamp - float(history[-1].get('registrado', 0)) >= 4:
                        history.append(record)
                        write_checkpoint(location, history[-600:])
        except (OSError, ValueError, TypeError, AttributeError, KeyError, IndexError):
            failures += 1
            seconds = min(MAX_BACKOFF_SECONDS, max(interval, interval * (1.8 ** min(4, failures))))
        stop.wait(max(4.5, seconds))
