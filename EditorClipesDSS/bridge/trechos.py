"""Map timestamps from the original live to a padded source excerpt."""

from math import ceil, floor, isfinite


def clip_window(start, end, duration):
    return max(0, floor(start) - 3), min(duration, ceil(end) + 3)


def clip_timeline(cut, words, energy, start, end):
    local = dict(cut, inicio=cut['inicio'] - start, fim=cut['fim'] - start)
    if 'efeitos' in cut:
        local['efeitos'] = [[second - start, name] for second, name in cut['efeitos']]
    captions = [
        dict(word, s=word['s'] - start, e=word['e'] - start)
        for word in words if start <= word['s'] and word['e'] <= end
    ]
    return local, captions, energy[start:ceil(end)] or [-99]


def usable_section(actual, start, end, cut_end):
    return isfinite(actual) and cut_end - start <= actual + 0.15 and abs(actual - (end - start)) <= 1.5


def verify_render_duration(actual, maximum, *, tolerance=0.08):
    """Never shorten a finished story silently just to meet a duration limit."""
    if not isfinite(actual) or actual <= 0:
        raise ValueError('O clipe exportado tem duração inválida.')
    if actual > maximum + tolerance:
        raise ValueError(
            f'O clipe renderizado tem {actual:.2f}s, acima do limite de {maximum}s. '
            'Não foi cortado automaticamente para não perder o desfecho.'
        )
    return actual
