import json
import math
import subprocess
import tempfile
from pathlib import Path


def probe(path):
    result = subprocess.run(
        [
            "ffprobe",
            "-v",
            "error",
            "-show_streams",
            "-show_format",
            "-of",
            "json",
            str(path),
        ],
        capture_output=True,
        timeout=30,
        check=True,
    )
    return json.loads(result.stdout)


def timestamp(value):
    total = max(0, round(value * 100))
    return f"{total // 360000}:{total // 6000 % 60:02}:{total // 100 % 60:02}.{total % 100:02}"


def write_ass(words, start, end, path):
    header = """[Script Info]
ScriptType: v4.00+
PlayResX: 1080
PlayResY: 1920
WrapStyle: 0
[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
Style: Default,DejaVu Sans,58,&H00FFFFFF,&H0000FFFF,&H00141414,&H90000000,-1,0,0,0,100,100,0,0,1,3,1,2,80,80,280,1
[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
"""
    selected = [w for w in words if float(w["end"]) > start and float(w["start"]) < end]
    groups, group = [], []
    for w in selected:
        if group and (
            len(group) >= 6
            or len(" ".join(x["word"] for x in group)) + len(w["word"]) > 38
            or w["start"] - group[-1]["end"] > 0.65
        ):
            groups.append(group)
            group = []
        group.append(w)
        if str(w["word"]).endswith((".", "?", "!")):
            groups.append(group)
            group = []
    if group:
        groups.append(group)
    for group in groups:
        # ASS overrides and escape sequences cannot be supplied by transcribed text.
        text = " ".join(
            str(w["word"])
            .replace("\\", "／")
            .replace("{", "(")
            .replace("}", ")")
            .replace("\n", " ")
            .replace("\r", " ")
            for w in group
        )
        a, b = (
            max(0, group[0]["start"] - start),
            min(end - start, group[-1]["end"] - start),
        )
        if b > a:
            header += (
                f"Dialogue: 0,{timestamp(a)},{timestamp(b)},Default,,0,0,0,,{text}\n"
            )
    Path(path).write_text(header, encoding="utf-8")


def choose_camera_face(faces, width, height, previous=None):
    candidates = [tuple(map(int, f)) for f in faces]
    if previous:
        px, py, pw, ph = previous
        candidates = [
            f
            for f in candidates
            if abs(f[0] + f[2] / 2 - px - pw / 2) < max(pw * 2, width * 0.10)
            and abs(f[1] + f[3] / 2 - py - ph / 2) < max(ph * 2, height * 0.12)
        ]
        return (
            min(candidates, key=lambda f: abs(f[0] - px) + abs(f[1] - py))
            if candidates
            else None
        )
    insets = [
        f
        for f in candidates
        if f[2] < width * 0.25
        and f[3] < height * 0.40
        and (f[0] < width * 0.3 or f[0] + f[2] > width * 0.7)
        and (f[1] < height * 0.35 or f[1] + f[3] > height * 0.65)
    ]
    return max(insets or candidates, key=lambda f: f[2] * f[3]) if candidates else None


def detect_camera_faces(frame, detector, profile):
    import cv2

    height, width = frame.shape[:2]
    rw, rh = int(width * 0.45), int(height * 0.55)
    regions = [(0, 0, width, height)] + [
        (x, y, rw, rh) for x in (0, width - rw) for y in (0, height - rh)
    ]
    found = []
    for x, y, w, h in regions:
        scale = min(2, 960 / max(w, h))
        small = cv2.resize(frame[y : y + h, x : x + w], None, fx=scale, fy=scale)
        gray = cv2.cvtColor(small, cv2.COLOR_BGR2GRAY)
        faces = list(detector.detectMultiScale(gray, 1.08, 4, minSize=(14, 14)))
        if not faces and w != width:
            faces = list(profile.detectMultiScale(gray, 1.08, 4, minSize=(14, 14)))
            for fx, fy, fw, fh in profile.detectMultiScale(
                cv2.flip(gray, 1), 1.08, 4, minSize=(14, 14)
            ):
                faces.append((gray.shape[1] - fx - fw, fy, fw, fh))
        for fx, fy, fw, fh in faces:
            face = (
                int(x + fx / scale),
                int(y + fy / scale),
                int(fw / scale),
                int(fh / scale),
            )
            xx, yy, ww, hh = face
            patch = frame[
                max(0, yy) : min(height, yy + hh), max(0, xx) : min(width, xx + ww)
            ]
            if patch.size == 0:
                continue
            # Reject blue/gray HUD textures falsely identified as faces.
            chroma = cv2.cvtColor(patch, cv2.COLOR_BGR2YCrCb)
            skin = cv2.inRange(chroma, (0, 133, 77), (255, 173, 127))
            if float(skin.mean()) / 255 < 0.06:
                continue
            if not any(
                abs(face[0] - old[0]) < face[2] * 0.4
                and abs(face[1] - old[1]) < face[3] * 0.4
                for old in found
            ):
                found.append(face)
    return found


def face_track(path, start, duration):
    import cv2

    cap = cv2.VideoCapture(str(path))
    width, height = (
        int(cap.get(cv2.CAP_PROP_FRAME_WIDTH)),
        int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT)),
    )
    detector = cv2.CascadeClassifier(
        cv2.data.haarcascades + "haarcascade_frontalface_default.xml"
    )
    profile = cv2.CascadeClassifier(
        cv2.data.haarcascades + "haarcascade_profileface.xml"
    )
    boxes = []
    previous = None
    missed = 0
    try:
        for second in range(max(1, math.ceil(duration))):
            cap.set(cv2.CAP_PROP_POS_MSEC, (start + second) * 1000)
            ok, frame = cap.read()
            if not ok:
                continue
            current = choose_camera_face(
                detect_camera_faces(frame, detector, profile), width, height, previous
            )
            if current is not None:
                if previous:
                    current = tuple(
                        int(0.65 * p + 0.35 * c) for p, c in zip(previous, current)
                    )
                previous, missed = current, 0
            else:
                missed += 1
            if previous and missed <= 6:
                boxes.append((second, *previous))
    finally:
        cap.release()
    return width, height, boxes


def concat_segments(segments, out):
    """All paths are internal immutable segment paths, never user-supplied URLs."""
    with tempfile.TemporaryDirectory(prefix="liveclip-concat-") as d:
        manifest = Path(d) / "inputs.txt"
        manifest.write_text(
            "".join(
                "file '" + str(Path(s["path"]).resolve()).replace("'", "'\\''") + "'\n"
                for s in segments
            )
        )
        subprocess.run(
            [
                "ffmpeg",
                "-v",
                "error",
                "-f",
                "concat",
                "-safe",
                "0",
                "-i",
                str(manifest),
                "-map",
                "0:v:0",
                "-map",
                "0:a:0?",
                "-c",
                "copy",
                "-y",
                str(out),
            ],
            check=True,
            capture_output=True,
            timeout=180,
        )


def render(source, start, end, words, out):
    out = Path(out)
    if not (0 <= start < end and end - start <= 600):
        raise ValueError("Duração inválida.")
    out.parent.mkdir(parents=True, exist_ok=True)
    duration = end - start
    width, height, boxes = face_track(source, start, duration)
    if not width or not height:
        raise ValueError("Vídeo sem imagem válida.")
    with tempfile.TemporaryDirectory(prefix="liveclip-render-") as d:
        temp = Path(d)
        write_ass(words, start, end, temp / "captions.ass")
        layout = "integral"
        # Face inset near an edge suggests a reaction layout. The largest remaining
        # rectangle becomes the content window; absent evidence keep the full scene.
        content = None
        if boxes:
            _, x, y, w, h = boxes[len(boxes) // 2]
            if (
                w < width * 0.38
                and h < height * 0.5
                and (x < width * 0.3 or x + w > width * 0.7)
            ):
                left = max(0, int(x - w))
                right = min(width, int(x + w * 2))
                top = max(0, int(y - h * 0.4))
                bottom = min(height, int(y + h * 2.5))
                options = [
                    (0, 0, max(2, left - 8), height),
                    (right + 8, 0, max(2, width - right - 8), height),
                    (0, 0, width, max(2, top - 8)),
                    (0, bottom + 8, width, max(2, height - bottom - 8)),
                ]
                options = [
                    a for a in options if a[0] + a[2] <= width and a[1] + a[3] <= height
                ]
                content = max(options, key=lambda a: a[2] * a[3])
                # When a camera overlays a corner, preserve the full horizontal
                # action rather than discarding the player's side of the game.
                if y < height * 0.35:
                    below = next(
                        (a for a in options if a[0] == 0 and a[1] == bottom + 8), None
                    )
                    if below and below[2] * below[3] >= width * height * 0.48:
                        content = below
                if content[2] * content[3] < width * height * 0.4:
                    content = None
        if boxes and content:
            layout = "reacao"
            cw = min(width, max(64, int(max(b[3] for b in boxes) * 3.4))) // 2 * 2
            ch = min(height, max(48, int(max(b[4] for b in boxes) * 2.6))) // 2 * 2
            commands = []
            for t, x, y, w, h in boxes:
                xx, yy = (
                    max(0, min(width - cw, int(x + w / 2 - cw / 2))),
                    max(0, min(height - ch, int(y + h / 2 - ch * 0.4))),
                )
                commands.append(f"{t:.3f} crop@face x {xx}, crop@face y {yy};")
            (temp / "track.cmd").write_text("\n".join(commands))
            # Preserve the complete watched video: removing an entire side or
            # the top to exclude the camera cuts gameplay and story information.
            _, fx, fy, fw, fh = boxes[len(boxes) // 2]
            mask_x = max(1, int(fx - fw * 1.7))
            mask_y = max(1, int(fy - fh * 0.8))
            mask_right = min(width - 2, int(fx + fw * 1.7))
            mask_bottom = min(height - 2, int(fy + fh * 1.9))
            mask_w, mask_h = mask_right - mask_x, mask_bottom - mask_y
            graph = (
                f"[0:v]split=2[f][c];[f]sendcmd=f=track.cmd,crop@face={cw}:{ch}:0:0,"
                "scale=1080:680:force_original_aspect_ratio=decrease,pad=1080:680:(ow-iw)/2:(oh-ih)/2[face];"
                f"[c]format=rgb24,format=yuv420p,delogo=x={mask_x}:y={mask_y}:w={mask_w}:h={mask_h}:show=0,split=2[bg][fg];"
                "[bg]scale=1080:1240:force_original_aspect_ratio=increase,crop=1080:1240,boxblur=20:2[blur];"
                "[fg]scale=1080:1240:force_original_aspect_ratio=decrease[sharp];"
                "[blur][sharp]overlay=(W-w)/2:(H-h)/2[content];[face][content]vstack=inputs=2,"
                "setsar=1,subtitles=captions.ass[v]"
            )
        else:
            if boxes:
                layout = "rosto"
            graph = (
                "[0:v]split=2[b][f];[b]scale=1080:1920:force_original_aspect_ratio=increase,"
                "crop=1080:1920,boxblur=20:2[bg];[f]scale=1080:1920:force_original_aspect_ratio=decrease[fg];"
                "[bg][fg]overlay=(W-w)/2:(H-h)/2,setsar=1,subtitles=captions.ass[v]"
            )
        partial = out.with_suffix(".partial.mp4")
        cmd = [
            "ffmpeg",
            "-v",
            "error",
            "-ss",
            str(start),
            "-i",
            str(Path(source).resolve()),
            "-t",
            str(duration),
            "-filter_complex_threads",
            "1",
            "-filter_complex",
            graph,
            "-map",
            "[v]",
            "-map",
            "0:a:0?",
            "-c:v",
            "libx264",
            "-preset",
            "veryfast",
            "-crf",
            "21",
            "-threads",
            "2",
            "-pix_fmt",
            "yuv420p",
            "-r",
            "30",
            "-c:a",
            "aac",
            "-b:a",
            "160k",
            "-af",
            "loudnorm=I=-16:TP=-1.5:LRA=11",
            "-movflags",
            "+faststart",
            "-y",
            str(partial.resolve()),
        ]
        try:
            subprocess.run(
                cmd,
                cwd=temp,
                capture_output=True,
                check=True,
                timeout=max(180, duration * 15),
            )
            info = probe(partial)
            actual = float(info["format"]["duration"])
            if abs(actual - duration) > 1:
                raise ValueError("O arquivo renderizado tem duração inesperada.")
            partial.replace(out)
        finally:
            partial.unlink(missing_ok=True)
    return layout
