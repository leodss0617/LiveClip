"""Clip metadata, channel preferences, and context review."""

from momentos import TOPIC_SHIFT, continues, phrases, tokens


VOTES = {'postaria', 'sem_graca', 'sem_contexto'}


def clip_speech(words, start, end):
    return ' '.join(word['w'] for word in words if start <= word['s'] < end)


def preference_terms(text):
    return sorted(word for word in tokens(text) if len(word) >= 4)[:40]


def preference_bonus(text, examples):
    current = set(preference_terms(text))
    votes = {'postaria': 1, 'sem_graca': -1, 'sem_contexto': -1}
    score = 0.0
    for item in examples[-100:]:
        shared = current & set(item.get('termos', []))
        if len(shared) >= 2:
            score += votes.get(item.get('voto'), 0) * min(len(shared), 5) * 0.025
    return max(-0.35, min(0.35, score))


def adjusted_grade(grade, speech, examples):
    adjustment = preference_bonus(speech, examples)
    # Preferences reorder a promising clip without admitting a weak one.
    if grade <= 7 and adjustment > 0:
        adjustment = 0
    return max(1, min(10, round(grade+adjustment, 1)))


def details(cut, words, url=None, original_duration=None):
    start,end = cut['inicio'],cut['fim']
    raw = clip_speech(words,start,end)
    lines = [' '.join(w['w'] for w in phrase) for phrase in phrases(
        [w for w in words if start <= w['s'] < end], end
    )]
    title = cut['titulo'].strip(' ,.!?')[:75]
    last = lines[-1].strip() if lines else ''
    caption = ' '.join(dict.fromkeys(
        text for text in (lines[0].strip() if lines else '',last) if text
    ))
    return {
        'titulo':title,'legenda':caption[:320].strip(),'nota':cut['nota'],
        'motivos':list(cut.get('motivos',[])),'termos':preference_terms(raw),
        'inicio':start,'fim':end,'url':url,'duracao_fonte':original_duration,
    }


def review_bounds(words, duration, start, end, maximum):
    lines = []
    for phrase in phrases(words,duration):
        text = ' '.join(w['w'] for w in phrase)
        lines.append((phrase[0]['s'],phrase[-1]['e'],text,tokens(text)))
    matches = [i for i,(a,b,_,_) in enumerate(lines) if a < end and b > start]
    if not matches:
        raise ValueError('A gravação não trouxe fala suficiente para revisar este clipe.')
    first,last = matches[0],matches[-1]
    while first and continues(lines[first-1],lines[first]):
        first -= 1
    while last+1 < len(lines) and continues(lines[last],lines[last+1]):
        last += 1
    # A short utterance on either side can supply context even without repeated words.
    if first == matches[0] and first and lines[first][0]-lines[first-1][1] <= 3 and not TOPIC_SHIFT.search(lines[first][2]):
        first -= 1
    if last == matches[-1] and last+1 < len(lines) and lines[last+1][0]-lines[last][1] <= 3 and not TOPIC_SHIFT.search(lines[last+1][2]):
        last += 1
    begin = max(0,min(start,lines[first][0]-0.2))
    finish = min(duration,max(end,lines[last][1]+0.45))
    if finish-begin > maximum-0.1:
        raise ValueError('O assunto completo passa do limite de duração deste pedido.')
    if start-begin < 0.5 and finish-end < 0.5:
        raise ValueError('A revisão não encontrou contexto adicional neste trecho.')
    return begin,finish


def marked_moment(start, end, offsets):
    return any(start-8 <= at <= end+8 for at in offsets)
