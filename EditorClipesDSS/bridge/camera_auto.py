"""Detecção leve da janela de câmera dentro de capturas de live.

Usa um frame reduzido do próprio FFmpeg, sem modelos externos. Analisa bordas
retangulares no topo dos dois lados e nunca assume que rostos do jogo sejam o
streamer. Se a borda não puder ser detectada, usa o padrão conhecido de câmera
sobreposta no canto superior esquerdo e registra a baixa confiança.
"""
import json
import math
import subprocess
from pathlib import Path

SMALL_W, SMALL_H = 256, 144
# Proporção da parte inferior numa exportação vertical 720x1280.
BOTTOM_RATIO = 720 / (1280 - ((int(1280 * .37) // 2) * 2))


def center_main_region(width, height, camera):
    """Enquadra a região principal sem repetir a webcam de cima.

    Mantém todo o eixo vertical da reação, prioriza o centro horizontal e evita
    colocar a própria câmera dentro do segundo quadro.
    """
    cx, cy, cw, ch = camera
    target = max(64, min(width, (math.ceil(height * BOTTOM_RATIO / 2) + 1) * 2))
    middle = max(0,(width - target)//2)
    # Garante espaço e margem de segurança antes de excluir a faixa da webcam.
    if cx < width // 3 and width - (cx + cw) >= target:
        middle = max(middle, cx + cw)
    elif cx > width // 2 and cx >= target:
        middle = min(middle, cx - target)
    middle = min(max(0,middle),width - target)
    return [middle // 2 * 2, 0, target, height // 2 * 2]

def _pix(frame, x, y):
    return frame[y * SMALL_W + x]

def _edge_vertical(frame, x, right):
    values = []
    y_min, y_max = 5, int(SMALL_H * .46)
    for y in range(y_min, y_max):
        a = _pix(frame, x, y)
        b = _pix(frame, x - 2 if right else x + 2, y)
        values.append(abs(a - b))
    return sum(values) / max(1, len(values))

def _edge_horizontal(frame, y, right, bound):
    start = SMALL_W - bound if right else 0
    end = SMALL_W if right else bound
    values = []
    for x in range(start + 5, end - 5):
        values.append(abs(_pix(frame, x, y) - _pix(frame, x, y + 2)))
    return sum(values) / max(1, len(values))

def find_webcam(frame, width, height):
    """Devolve (cam, resto, confiança) em coordenadas do vídeo original."""
    if len(frame) != SMALL_W * SMALL_H or width <= height:
        return None
    candidates = {}
    for right in (False, True):
        chosen = None
        # Procura divisória que termina a janelinha no topo.
        for width_px in range(48, 105, 4):
            x = SMALL_W - width_px if right else width_px
            v = _edge_vertical(frame, x, right)
            for y in range(25, 63, 3):
                h = _edge_horizontal(frame, y, right, width_px)
                score = v * .58 + h * .42
                if chosen is None or score > chosen[0]:
                    chosen = score, right, width_px, y
        candidates[right] = chosen
    left, right_candidate = candidates[False], candidates[True]
    # Interfaces de jogos costumam ter menus, painéis e placares à direita.
    # Damos preferência ao contorno plausível da webcam à esquerda, exceto
    # quando há evidência muito superior de uma janela à direita.
    chosen = left if left[0] >= 25 and left[0] >= right_candidate[0] * .62 else (
        right_candidate if right_candidate[0] >= 26 else left)
    score, right, crop_w, crop_h = chosen
    confidence = "marcada" if score >= 26 else "estimada"
    if score < 26:
        right, crop_w, crop_h = False, 72, 44
    cw = min(width - 4, max(64, int(width * crop_w / SMALL_W) // 2 * 2))
    ch = min(height - 4, max(64, int(height * crop_h / SMALL_H) // 2 * 2))
    x = (width - cw) if right else 0
    cam = [x, 0, cw, ch]
    return cam, center_main_region(width,height,cam), confidence


def detect_webcam(video, width, height, store=None):
    if width <= height:
        return None
    if store is not None and Path(store).is_file():
        try:
            saved = json.loads(Path(store).read_text())
            if saved["size"] == [width, height] and len(saved["camera"]) == 4:
                return saved
        except (ValueError, OSError, KeyError, TypeError):
            pass
    try:
        completed = subprocess.run(
            ["ffmpeg", "-nostdin", "-v", "error", "-ss", "2", "-i", str(video),
             "-an", "-frames:v", "1", "-vf", f"scale={SMALL_W}:{SMALL_H},format=gray",
             "-f", "rawvideo", "pipe:1"],
            timeout=22, check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        )
        best = find_webcam(completed.stdout, width, height)
    except (subprocess.CalledProcessError, OSError, subprocess.TimeoutExpired, TypeError):
        best = None
    if best is None:
        cw, ch = width // 3 // 2 * 2, height // 3 // 2 * 2
        cam = [0,0,cw,ch]
        best = cam, center_main_region(width,height,cam), "estimada"
    camera, principal, confidence = best
    output = {"size": [width, height], "camera": camera,
              "jogo": principal, "confianca": confidence}
    if store is not None:
        try:
            Path(store).write_text(json.dumps(output))
        except OSError:
            pass
    return output
