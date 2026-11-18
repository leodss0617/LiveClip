"""Pontuacao complementar do chat para trechos com inicio e desfecho."""
def activity_bonus(events, start, end, timeline, measurements):
    if not isinstance(events, list) or not events:
        return 0.0, 0
    selected = {}
    for part_number, offset, duration in timeline:
        if part_number not in measurements:
            continue
        completed_at = measurements[part_number][4]
        began_at = completed_at - duration
        for sample in events:
            try:
                recorded = float(sample['registrado'])
                delay = max(0.0, min(20.0, float(sample.get('atraso', 0))))
                adjusted = recorded - delay
                if not began_at - 4 <= adjusted <= completed_at + 4:
                    continue
                position = offset + max(0, min(duration, adjusted - began_at))
                if start - 8 <= position <= end + 8:
                    count = min(200, max(0, int(sample.get('mensagens', 0))))
                    excitement = min(1.0, max(0.0, float(sample.get('animacao', 0))))
                    users = max(0, int(sample.get('usuarios', 0)))
                    peak = bool(sample.get('pico', False))
                    score = min(1.0, max(0.0, float(sample.get('pontuacao', 0))))
                    if count > 0:
                        selected[recorded] = (count, excitement, users, peak, score)
            except (TypeError, ValueError, KeyError, IndexError, OverflowError):
                continue
    if not selected:
        return 0.0, 0
    total = sum(t[0] for t in selected.values())
    excited = max(t[1] for t in selected.values())
    spread = max(t[2] for t in selected.values())
    peaked = any(t[3] for t in selected.values())
    strength = max(t[4] for t in selected.values())
    bonus = min(0.85, 0.03 * min(total, 8) + 0.12 * excited +
                0.08 * min(spread, 5) / 5 + (0.22 if peaked else 0) +
                0.16 * strength)
    return round(bonus, 2), total
