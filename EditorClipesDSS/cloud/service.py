#!/usr/bin/env python3
"""Servidor local para trabalhos MP4 do EditorClipesDSS (sem dependencias Python)."""
import argparse
import hmac
import json
import os
import queue
import secrets
import subprocess
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit

ROOT = Path(os.environ.get("EDITOR_CLOUD_DATA", str(Path.home() / ".editorclipes-cloud"))).resolve()
MAX_UPLOAD = int(os.environ.get("EDITOR_CLOUD_MAX_UPLOAD_MB", "100")) * 1024 * 1024
WORK = queue.Queue()
STATE_LOCK = threading.Lock()


def metadata(folder):
    return json.loads((folder / "status.json").read_text(encoding="utf-8"))


def save(folder, value):
    temp = folder / "status.json.tmp"
    temp.write_text(json.dumps(value, ensure_ascii=False), encoding="utf-8")
    temp.replace(folder / "status.json")


def process_jobs():
    while True:
        folder = WORK.get()
        try:
            with STATE_LOCK:
                state = metadata(folder)
                state.update(status="running", started_at=int(time.time()))
                save(folder, state)
            cmd = [
                "ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error",
                "-y", "-i", str(folder / "entrada.mp4"), "-map", "0:v:0",
                "-map", "0:a?", "-vf", "scale=720:1280:force_original_aspect_ratio=decrease,"
                "pad=720:1280:(ow-iw)/2:(oh-ih)/2,setsar=1",
                "-c:v", "libx264", "-preset", "veryfast", "-crf", "25",
                "-pix_fmt", "yuv420p", "-c:a", "aac", "-movflags", "+faststart",
                str(folder / "resultado.mp4")
            ]
            result = subprocess.run(cmd, capture_output=True, text=True, timeout=1800, check=False)
            if result.returncode:
                raise RuntimeError((result.stderr or "Falha ao renderizar")[-500:])
            with STATE_LOCK:
                state = metadata(folder)
                state.update(status="done", finished_at=int(time.time()))
                save(folder, state)
        except Exception as exc:
            with STATE_LOCK:
                state = metadata(folder)
                state.update(status="error", error=str(exc)[:500], finished_at=int(time.time()))
                save(folder, state)
        finally:
            WORK.task_done()


def make_handler(token):
    class Handler(BaseHTTPRequestHandler):
        server_version = "EditorClipesCloud/0.1"

        def reply(self, status, payload):
            data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(data)

        def authorize(self):
            given = self.headers.get("Authorization", "")
            if not hmac.compare_digest(given, "Bearer " + token):
                self.reply(401, {"error": "Não autorizado"})
                return False
            return True

        def do_GET(self):
            if not self.authorize():
                return
            parts = urlsplit(self.path).path.strip("/").split("/")
            if parts == ["health"]:
                self.reply(200, {"status": "ok", "service": "EditorClipesDSS Cloud"})
                return
            if parts == ["jobs"]:
                self.reply(200, {"jobs": [metadata(p) for p in sorted(ROOT.iterdir())
                                          if p.is_dir() and (p / "status.json").is_file()]})
                return
            if len(parts) in (2, 3) and parts[0] == "jobs" and len(parts[1]) == 32:
                identifier = parts[1]
                if not all(x in "0123456789abcdef" for x in identifier):
                    self.reply(404, {"error": "Não encontrado"})
                    return
                folder = ROOT / identifier
                if not (folder / "status.json").is_file():
                    self.reply(404, {"error": "Não encontrado"})
                    return
                if len(parts) == 2:
                    self.reply(200, metadata(folder))
                    return
                if parts[2] == "output" and metadata(folder)["status"] == "done":
                    file = folder / "resultado.mp4"
                    if not file.is_file():
                        self.reply(404, {"error": "Arquivo indisponível"})
                        return
                    self.send_response(200)
                    self.send_header("Content-Type", "video/mp4")
                    self.send_header("Content-Disposition", 'attachment; filename="clipe.mp4"')
                    self.send_header("Content-Length", str(file.stat().st_size))
                    self.send_header("Cache-Control", "no-store")
                    self.end_headers()
                    with file.open("rb") as stream:
                        while data := stream.read(1024 * 1024):
                            self.wfile.write(data)
                    return
            self.reply(404, {"error": "Não encontrado"})

        def do_POST(self):
            if not self.authorize():
                return
            if urlsplit(self.path).path != "/jobs":
                self.reply(404, {"error": "Não encontrado"})
                return
            try:
                size = int(self.headers.get("Content-Length", "0"))
            except ValueError:
                self.reply(400, {"error": "Tamanho inválido"})
                return
            if size <= 0 or size > MAX_UPLOAD:
                self.reply(413, {"error": "Vídeo vazio ou maior que o limite"})
                return
            identifier = uuid.uuid4().hex
            folder = ROOT / identifier
            folder.mkdir(mode=0o700)
            destination = folder / "entrada.mp4"
            try:
                with destination.open("wb") as output:
                    left = size
                    while left:
                        piece = self.rfile.read(min(left, 1024 * 1024))
                        if not piece:
                            raise ValueError("Upload incompleto")
                        output.write(piece)
                        left -= len(piece)
                state = {"id": identifier, "status": "queued", "created_at": int(time.time())}
                save(folder, state)
                WORK.put(folder)
                self.reply(202, state)
            except Exception:
                destination.unlink(missing_ok=True)
                self.reply(400, {"error": "Falha ao receber vídeo"})

        def log_message(self, format, *args):
            # Nunca registrar cabecalhos Authorization.
            pass

    return Handler


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1", help="Use 127.0.0.1 por padrão")
    parser.add_argument("--port", type=int, default=18755)
    args = parser.parse_args()
    ROOT.mkdir(parents=True, exist_ok=True, mode=0o700)
    tokenfile = ROOT / "token"
    if not tokenfile.exists():
        tokenfile.write_text(secrets.token_urlsafe(36), encoding="utf-8")
        tokenfile.chmod(0o600)
    token = tokenfile.read_text(encoding="utf-8").strip()
    if not token:
        raise SystemExit("Token vazio")
    threading.Thread(target=process_jobs, daemon=True).start()
    for folder in ROOT.iterdir():
        if folder.is_dir() and (folder / "status.json").exists():
            state = metadata(folder)
            if state["status"] in ("queued", "running"):
                state["status"] = "queued"
                save(folder, state)
                WORK.put(folder)
    server = ThreadingHTTPServer((args.host, args.port), make_handler(token))
    print("EditorClipesDSS Cloud pronto em http://%s:%d" % (args.host, args.port), flush=True)
    print("Token salvo localmente em %s (não compartilhe)" % tokenfile, flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
