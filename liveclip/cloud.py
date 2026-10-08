import json
import math
import os
import re
import threading
import time
from pathlib import Path

import httpx


class CloudMeter:
    """Saldo manual: desconta somente tempo observado pelo processo do painel."""
    def __init__(self, data, clock=time.monotonic):
        self.path = Path(data) / 'cloud-meter.json'
        self.clock, self.last = clock, clock()
        self.lock = threading.Lock()
        try:
            value = json.loads(self.path.read_text())
            remaining = value.get('remaining_seconds')
            self.remaining = float(remaining) if remaining is not None and math.isfinite(float(remaining)) and float(remaining) >= 0 else None
        except (OSError, ValueError, TypeError, AttributeError):
            self.remaining = None
        self.elapsed = 0.0

    def _tick(self):
        now = self.clock()
        delta = max(0, now - self.last)
        self.last = now
        self.elapsed += delta
        if self.remaining is not None:
            self.remaining = max(0, self.remaining - delta)
        tmp = self.path.with_suffix('.tmp')
        tmp.write_text(json.dumps({'remaining_seconds': self.remaining}))
        os.replace(tmp, self.path)

    def set_hours(self, hours):
        if isinstance(hours, bool) or not math.isfinite(hours) or not 0 <= hours <= 10000:
            raise ValueError('Informe horas válidas de 0 a 10000.')
        with self.lock:
            self._tick()
            self.remaining = hours * 3600
            self._tick()

    def status(self):
        with self.lock:
            self._tick()
            return {'enabled': bool(os.getenv('CODESPACE_NAME')), 'remaining_seconds': self.remaining,
                    'observed_seconds': self.elapsed, 'estimated': True,
                    'stop_authorized': bool(os.getenv('LIVECLIP_CODESPACES_TOKEN')),
                    'manage_url': 'https://github.com/codespaces',
                    'billing_url': 'https://github.com/settings/billing'}


def stop_codespace():
    name = os.getenv('CODESPACE_NAME', '')
    token = os.getenv('LIVECLIP_CODESPACES_TOKEN', '')
    if not token or not re.fullmatch(r'[A-Za-z0-9-]{1,200}', name):
        raise ValueError('Autorize a parada nas configurações do Codespaces ou pare a máquina pela página do GitHub.')
    try:
        response = httpx.post('https://api.github.com/user/codespaces/' + name + '/stop',
                              headers={'Authorization': 'Bearer ' + token, 'Accept': 'application/vnd.github+json'}, timeout=10)
    except httpx.HTTPError:
        raise ValueError('Não foi possível confirmar a solicitação. Confira o estado da máquina no GitHub.') from None
    if response.status_code != 200:
        raise ValueError('GitHub não aceitou a parada. Confira a permissão do token e pare pela página do GitHub.')
    return {'accepted': True, 'detail': 'GitHub aceitou a solicitação de parada. Confira o estado em github.com/codespaces.'}
