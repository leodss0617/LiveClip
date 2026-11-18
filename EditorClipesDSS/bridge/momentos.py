"""Choose self-contained, speech-led scenes from a timestamped transcript."""
from dataclasses import dataclass
from statistics import median
import re

HOOK = re.compile(r'\?|\b(?:será que|duvido|quer ver|eu aposto|vou tentar|e se|olha o que|vocês viram)\b', re.I)
PAYOFF = re.compile(r'\b(?:ganhei|ganhamos|consegui|conseguimos|matei|derrubei|virada|viramos|morri|perdi|perdemos|deu certo|deu errado|não acredito)\b', re.I)
DEPENDENT = re.compile(r'^(?:isso|esse|essa|esses|essas|então|daí|aí|e aí|eu por exemplo|por exemplo|porém|é que|só que|mas|tipo assim|porque|por isso|ou seja|e depois|e no fim|o motivo|o segredo)\b', re.I)
FILLER = frozenset('mano olha cara véi velho tipo né aí nossa ah é então chat bora e o a de que eu tu ele ela os as um uma pra para no na em só tá foi vai'.split())

TOPIC_SHIFT = re.compile(r'^(?:mudando de assunto|outro assunto|sobre outra coisa|agora (?:vamos |vou )?falar de|próximo assunto)\b', re.I)
OPEN_END = re.compile(r'(?:[,;:…]|\.{2,}|\b(?:porque|mas|então|que|e|o|a|no|na|com|pra|para))$', re.I)
CONTEXT_LOOKAHEAD_SECONDS = 24

AFTERTHOUGHT = re.compile(r'^(?:e foi assim|e é por isso|daí eu|a resposta|a conclusão|o resultado|o que aconteceu foi|sabe por que|foi por isso|no final das contas|e quando|eu explico|agora entendi|olha o resultado|por isso que)\b', re.I)
SETUP = re.compile(r'^(?:pra contextualizar|para contextualizar|no começo|no início|na primeira tentativa|'
                   r'a regra|o problema|o desafio|aconteceu o seguinte|agora só (?:tem|resta|sobrou)|'
                   r'(?:tô|estou) sem|eu tava tentando|antes disso|deixa eu explicar|'
                   r'a história começou|o objetivo é|a missão é|o combinado era)\b', re.I)
REACTION = re.compile(r'^(?:caraca|nossa|meu deus|mano|caramba|que isso|puta merda)[\s,!.:…-]+'
                      r'(?:eu achei|eu pensei|eu não|não acredito|não esperava|isso foi|quase|por pouco|'
                      r'que susto|olha isso|que jogada)\b', re.I)



@dataclass(frozen=True)
class Moment:
    start: float
    end: float
    title: str
    score: float
    reasons: tuple[str, ...] = ()


def phrases(words, duration):
    group = []
    for item in sorted(words, key=lambda entry: entry['s']):
        if not 0 <= item['s'] < item['e'] <= duration + 0.01 or not item['w'].strip():
            continue
        if group and (item['s'] - group[-1]['e'] >= 0.8 or item['e'] - group[0]['s'] > 11):
            yield group
            group = []
        group.append(item)
        if item['w'].rstrip().endswith(('.', '!', '?')):
            yield group
            group = []
    if group:
        yield group


def tokens(text):
    return {word for word in re.findall(r'[^\W\d_]+', text.casefold()) if word not in FILLER}


def related(left, right):
    common = left & right
    return len(common) >= 2 or any(len(word) >= 7 for word in common)


def continues(previous, following):
    _, last_end, last_text, last_words = previous
    next_start, _, next_text, next_words = following
    if TOPIC_SHIFT.search(next_text):
        return False
    gap = next_start - last_end
    if DEPENDENT.search(next_text) or AFTERTHOUGHT.search(next_text):
        return gap <= CONTEXT_LOOKAHEAD_SECONDS
    if REACTION.search(next_text):
        return gap <= 8
    if OPEN_END.search(last_text):
        return gap <= 9
    return gap <= 9 and related(last_words, next_words)


def opening_context(lines, anchor):
    first = anchor
    start, _, _, opening_words = lines[anchor]
    # Até três falas de preparação relacionadas: evita começar no meio do assunto.
    while first and anchor - first < 3:
        earlier_start, earlier_end, earlier_text, earlier_words = lines[first-1]
        if (start - earlier_start > 13 or lines[first][0] - earlier_end > 8
                or HOOK.search(earlier_text) or PAYOFF.search(earlier_text)
                or TOPIC_SHIFT.search(earlier_text)):
            break
        if not (SETUP.search(earlier_text) or
                (len(earlier_words) >= 4 and related(earlier_words, opening_words))
                or (first < anchor and continues(lines[first-1], lines[first]))):
            break
        first -= 1
    return first


def select_moments(duration, count, maximum, words, energy, *, end_after=0, end_before=None):
    lines = []
    for phrase in phrases(words, duration):
        text = ' '.join(w['w'] for w in phrase)
        lines.append((phrase[0]['s'], min(duration, phrase[-1]['e']), text, tokens(text)))
    continuation = [continues(left, right) for left,right in zip(lines,lines[1:])] + [False]
    end_before = duration if end_before is None else end_before
    baseline = median(energy) if energy else -45
    candidates = []
    for i, (start, _, opening, opening_tokens) in enumerate(lines):
        if len(opening_tokens) < 3 or DEPENDENT.search(opening) or (i > 0 and continuation[i-1]):
            continue
        hook = bool(HOOK.search(opening))
        first = opening_context(lines, i) if hook else i
        start = lines[first][0]
        payoff = False
        terms = set().union(*(line[3] for line in lines[first:i]))
        gaps = 0.0
        for j in range(i, len(lines)):
            line_start, end, ending, ending_tokens = lines[j]
            span = end - start
            connected = j > i and continuation[j-1]
            if span > maximum - 0.75 or end > end_before or (j > i and (
                TOPIC_SHIFT.search(ending) or (not connected and line_start-lines[j-1][1] > 8)
            )):
                break
            terms.update(ending_tokens)
            answered = not ending.rstrip().endswith('?')
            payoff = payoff or (answered and bool(PAYOFF.search(ending))
                                and not re.search(r'\bnão (?:consegui|ganhei)\b', ending, re.I))
            if j > i:
                gaps += max(0, line_start-lines[j-1][1]-1.5)
            if (end <= end_after or span < min(5, duration * 0.6) or len(ending_tokens) < 2
                or not answered or OPEN_END.search(ending) or continuation[j]):
                continue
            exchange = j > i and ending.endswith(('.', '!')) and (
                connected or related(opening_tokens,ending_tokens)
            )
            if not (payoff or (hook and exchange)):
                continue
            if not hook and (not payoff or j == i):
                continue
            if len(terms) < 6:
                continue
            audio = sorted(energy[int(start):int(end)+1])
            lift = max(0, min(2, (audio[len(audio)*3//4] - baseline) / 8)) if audio else 0
            if gaps > span * (0.72 if payoff else 0.45):
                continue
            score = min(1.5, len(opening_tokens)*0.16) + 4*hook + 4*payoff + int(exchange) + lift + min(2,len(terms)/8) - min(2.4,gaps*0.08) - min(1.6,max(0,span-55)*0.02)
            if not hook and score < 5.5:
                continue
            title = opening.strip(' ,.!?')
            if len(opening_tokens) < 5 and i < j:
                next_line = lines[i+1][2].strip(' ,.!?')
                title = title + ('? ' if opening.endswith('?') else ' — ') + next_line
            if opening.endswith('?') and len(opening_tokens) >= 5 and len(title) < 68:
                title += '?'
            if len(title) > 68:
                title = title[:67].rsplit(' ',1)[0].rstrip(' ,.!?—') + '…'
            next_start = lines[j+1][0] if j+1 < len(lines) else duration
            clip_start = max(0,start-0.4)
            clip_end = min(duration,clip_start+maximum,end+1.3,max(end,next_start))
            reasons = []
            if first < i:
                reasons.append('Preparação da cena incluída antes do gancho')
            if hook: reasons.append('Gancho identificado no trecho')
            if payoff: reasons.append('Reação ou resultado identificado')
            if exchange: reasons.append('Resposta ligada ao assunto')
            if j > i and (AFTERTHOUGHT.search(ending) or REACTION.search(ending)):
                reasons.append('Explicação ou reação final incluída')
            if lift >= 0.75: reasons.append('Intensidade do áudio acima do normal')
            reasons.append('Corte termina no fim de uma fala')
            candidates.append((Moment(clip_start,clip_end,title,score,tuple(reasons)),frozenset(terms)))
            break
    picked = []
    for moment, signature in sorted(candidates,key=lambda entry: (-entry[0].score, entry[0].start)):
        if any(moment.start < old.end and old.start < moment.end or
               len(signature & known) / min(len(signature), len(known)) >= 0.85 for old,known in picked):
            continue
        picked.append((moment, signature))
        if len(picked) == count:
            break
    return [moment for moment,_ in picked]
