"""Leitura anonima do chat publico Kick. Endpoint web nao oficial."""
import json
import re
import threading
import time
from urllib.request import Request, urlopen
from urllib.error import HTTPError, URLError

_LOCK = threading.RLock()
_META = {}
_METADATA = _META  # compatibilidade com codigo e testes anteriores
_MESSAGES = {}
HEADERS = {
    'User-Agent': 'Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/128.0 Safari/537.36',
    'Accept': 'application/json',
}


def _get(url):
    with urlopen(Request(url, headers=HEADERS), timeout=7) as response:
        if response.status != 200:
            raise ValueError('Kick indisponivel')
        body = response.read(480001)
        if len(body) > 480000:
            raise ValueError('Resposta da Kick muito grande')
        return json.loads(body)


def recent_messages(slug):
    if not isinstance(slug, str) or not re.fullmatch(r'[a-z0-9_-]{2,40}', slug):
        raise ValueError('Nome de canal invalido')
    now = time.monotonic()
    with _LOCK:
        cached = _MESSAGES.get(slug)
        if cached and now - cached[0] < 4:
            return cached[1]
        meta = _META.get(slug)
    try:
        if not meta or now - meta[0] >= 25:
            info = _get('https://kick.com/api/v2/channels/' + slug)
            if info.get('slug', '').lower() != slug:
                raise ValueError('Canal nao encontrado')
            meta = (now, {'id': int(info['id']),
                          'ao_vivo': bool(info.get('livestream'))})
            with _LOCK:
                _META[slug] = meta
        res = _get('https://kick.com/api/v2/channels/' + str(meta[1]['id']) + '/messages')
        raw = res.get('data', {}).get('messages', [])
        if not isinstance(raw, list):
            raise ValueError('Formato do chat alterado')
        parsed = []
        for item in raw[:75]:
            if not isinstance(item, dict) or item.get('type') not in ('message', 'reply'):
                continue
            sender = item.get('sender') or {}
            ident = str(item.get('id') or '')
            body = item.get('content')
            name = sender.get('username') or sender.get('slug') or ''
            if not ident or not isinstance(body, str) or not isinstance(name, str):
                continue
            parsed.append({
                'id': ident[:100],
                'autor': name[:42],
                'mensagem': body[:300],
                'hora': str(item.get('created_at') or '')[:40]
            })
        parsed.reverse()
        result = {
            'canal': slug, 'mensagens': parsed[-50:],
            'ao_vivo': meta[1]['ao_vivo'], 'fonte': 'kick-web-publica'
        }
        with _LOCK:
            _MESSAGES[slug] = (time.monotonic(), result)
        return result
    except (HTTPError, URLError, TimeoutError, OSError, ValueError, KeyError, TypeError) as exc:
        if cached and time.monotonic() - cached[0] < 35:
            return dict(cached[1], atrasado=True)
        raise ValueError('Falha no chat publico da Kick: ' + type(exc).__name__) from exc
