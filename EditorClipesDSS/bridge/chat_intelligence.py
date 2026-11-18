"""Detector leve de picos de chat para priorizar clipes.
Nao grava mensagens, senhas nem nomes de espectadores.
"""
from collections import deque
from dataclasses import dataclass
from statistics import median
import re
import time

HYPE = re.compile(r'(?:k{3,}|ha(?:ha){2,}|rsrs|\b(?:mds|caraca|caramba|nossa|clipa|pog|gg|wow|wtf|no way)\b|[😂🤣😱🔥😮😲👏💀])', re.I)
SPAM = re.compile(r'^(?:!\S+|https?://\S+|[.\-_=]{3,})$', re.I)


@dataclass(frozen=True)
class ChatPulse:
    timestamp: float
    messages: int
    unique_authors: int
    reactions: float
    baseline: float
    ratio: float
    score: float
    peak: bool
    suggested_video_time: float


class ChatHeatDetector:
    """Cada leitura remove duplicatas, ignora historico inicial e mede picos."""

    def __init__(self, *, window_seconds=10, history_seconds=180,
                 chat_delay_seconds=5, max_seen=1500):
        if not 2 <= window_seconds <= 60:
            raise ValueError('Janela invalida')
        self.window_seconds = int(window_seconds)
        self.history_seconds = max(4 * self.window_seconds, int(history_seconds))
        self.chat_delay_seconds = max(0, min(20, float(chat_delay_seconds)))
        self.max_seen = max(100, int(max_seen))
        self.seen = set()
        self.seen_queue = deque()
        self.bins = deque()
        self.initialized = False
        self.last_timestamp = None

    def _remember(self, ident):
        if ident in self.seen:
            return False
        self.seen.add(ident)
        self.seen_queue.append(ident)
        while len(self.seen_queue) > self.max_seen:
            self.seen.discard(self.seen_queue.popleft())
        return True

    def analyze(self, snapshot, *, now=None):
        now = time.time() if now is None else float(now)
        if self.last_timestamp is not None and now < self.last_timestamp:
            self.bins.clear()
        self.last_timestamp = now
        rows = snapshot.get('mensagens', [])
        if not isinstance(rows, list):
            rows = []
        valid = []
        for item in rows[-100:]:
            if not isinstance(item, dict):
                continue
            ident = str(item.get('id') or '')[:100]
            if not ident or not self._remember(ident):
                continue
            author = str(item.get('autor') or '').strip().casefold()
            body = str(item.get('mensagem') or '')[:300].strip()
            if not body or SPAM.search(body):
                continue
            valid.append((author, body))
        if not self.initialized:
            self.initialized = True
            return self._pulse(now, 0, 0, 0.0, False)
        if snapshot.get('atrasado') or not snapshot.get('ao_vivo', False):
            return self._pulse(now, 0, 0, 0.0, False)
        count = min(200, len(valid))
        authors = len({author for author, _ in valid if author})
        reactions = sum(bool(HYPE.search(body) or body.count('!') + body.count('?') >= 2)
                        for _, body in valid) / len(valid) if valid else 0.0
        return self._pulse(now, count, authors, reactions, True)

    def _pulse(self, now, count, authors, reactions, live):
        limit = now - self.history_seconds
        while self.bins and self.bins[0][0] < limit:
            self.bins.popleft()
        previous = [n for timestamp, n, _ in self.bins
                    if timestamp <= now - self.window_seconds]
        normal = median(previous) if previous else 0.0
        warm = len(previous) >= 3
        ratio = count / max(1.0, normal)
        diversity = min(1.0, authors / max(2.0, count * 0.7))
        strength = (min(3.0, ratio) / 3.0) * 0.38 + min(1.0, count / 12.0) * 0.32 \
                   + reactions * 0.20 + diversity * 0.10
        peak = bool(live and warm and count >= 5 and authors >= 2
                    and ratio >= 2.0 and strength >= 0.50)
        bucket = int(now // self.window_seconds) * self.window_seconds
        if live:
            if self.bins and self.bins[-1][0] == bucket:
                old_bucket, old_count, old_users = self.bins.pop()
                self.bins.append((old_bucket, min(200, old_count + count),
                                  min(200, old_users + authors)))
            else:
                self.bins.append((bucket, count, authors))
        return ChatPulse(now, count, authors, round(reactions, 3), round(normal, 2),
                         round(ratio, 2), round(strength if live else 0.0, 3),
                         peak, max(0.0, now - self.chat_delay_seconds))


def priority_boost(pulse, transcript_present=True):
    """Chat complementa o contexto de fala, nunca cria cortes sozinho."""
    if not transcript_present or pulse.messages < 3:
        return 0.0
    return round(min(0.90, 0.14 * pulse.score + (0.52 if pulse.peak else 0.0)), 3)
