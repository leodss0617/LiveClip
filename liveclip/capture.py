import csv
import json
import math
import shutil
import subprocess
import sys
import time
import uuid
from pathlib import Path

from .limits import MAX_SECONDS


def capture_message(log):
    if "429" in log:
        return "A plataforma limitou o acesso (429). Nova tentativa após uma pausa."
    if "CERTIFICATE_VERIFY_FAILED" in log:
        return "A conexão segura falhou por certificado. Verifique o servidor."
    if "No playable streams" in log:
        return "A plataforma não está entregando vídeo. A live pode estar offline ou bloqueada para este servidor."
    return "Live indisponível ou conexão interrompida. Nova tentativa automática."


def terminate(process):
    if process is not None and process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=2)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=5)


class Capture:
    def __init__(self, store, settings, session, stop, shutdown=None):
        self.shutdown = shutdown
        self.store, self.settings, self.session, self.stop = (
            store,
            settings,
            session,
            stop,
        )
        self.folder = settings.data / "sessions" / session["id"]
        self.folder.mkdir(parents=True, exist_ok=True)
        self.process = None
        self.encoder = None

    def import_manifest(self, attempt, base):
        manifest = attempt / "segments.csv"
        until = max(base, self.store.session(self.session["id"])["capture_seconds"])
        if not manifest.exists():
            return until
        try:
            with manifest.open() as f:
                rows = list(csv.reader(f))
            elapsed = 0
            for row in rows:
                if len(row) != 3:
                    continue
                name, a, b = row
                a, b = float(a), float(b)
                p = (attempt / Path(name).name).resolve()
                if (
                    not p.is_file()
                    or not all(math.isfinite(v) for v in (a, b))
                    or b <= a
                ):
                    continue
                duration = min(b - a, max(0, MAX_SECONDS - base - elapsed))
                if duration <= 0:
                    break
                self.store.add_segment(self.session["id"], p, base + elapsed, duration)
                elapsed += duration
                until = max(until, base + elapsed)
            self.store.update_session(self.session["id"], capture_seconds=until)
        except (OSError, ValueError):
            pass  # A CSV write can be in progress; read it again.
        return until

    def disk_ok(self):
        used = 0
        for path in self.settings.data.rglob("*"):
            try:
                if path.is_file():
                    used += path.stat().st_size
            except FileNotFoundError:
                continue  # The worker may finish and remove a temporary source.
        return (
            shutil.disk_usage(self.folder).free > self.settings.min_free_gb * 1024**3
            and used < self.settings.max_data_gb * 1024**3
        )

    def run(self):
        ident, failures = self.session["id"], 0
        try:
            # Completed manifests from a killed process may not yet be in SQLite.
            for metadata in sorted(
                self.folder.glob("capture-*/base.json"), key=lambda p: p.stat().st_mtime
            ):
                self.import_manifest(
                    metadata.parent, json.loads(metadata.read_text())["base"]
                )
            while not self.stop.is_set() and time.time() < self.session["expires"]:
                if self.store.session(ident)["stop_requested"]:
                    break
                if not self.disk_ok():
                    raise RuntimeError(
                        "Limite de armazenamento atingido. Libere espaço antes de continuar."
                    )
                base = self.store.session(ident)["capture_seconds"]
                if base >= MAX_SECONDS:
                    break
                attempt = self.folder / ("capture-" + uuid.uuid4().hex)
                attempt.mkdir()
                (attempt / "base.json").write_text(json.dumps({"base": base}))
                self.store.update_session(
                    ident,
                    status="waiting" if not base else "reconnecting",
                    message="Conectando à transmissão…",
                )
                with (attempt / "capture.log").open("wb") as log:
                    self.process = subprocess.Popen(
                        [
                            sys.executable,
                            "-m",
                            "streamlink",
                            "--stdout",
                            "--http-timeout",
                            "20",
                            "--stream-timeout",
                            "30",
                            "--retry-open",
                            "2",
                            self.session["url"],
                            "720p,720p60,480p,best",
                        ],
                        stdout=subprocess.PIPE,
                        stderr=log,
                    )
                    self.encoder = subprocess.Popen(
                        [
                            "ffmpeg",
                            "-hide_banner",
                            "-loglevel",
                            "warning",
                            "-i",
                            "pipe:0",
                            "-t",
                            str(max(0.01, MAX_SECONDS - base)),
                            "-map",
                            "0:v:0",
                            "-map",
                            "0:a:0?",
                            "-c",
                            "copy",
                            "-f",
                            "segment",
                            "-segment_time",
                            "20",
                            "-segment_list",
                            "segments.csv",
                            "-segment_list_type",
                            "csv",
                            "-reset_timestamps",
                            "1",
                            "seg_%08d.ts",
                        ],
                        stdin=self.process.stdout,
                        stdout=subprocess.DEVNULL,
                        stderr=log,
                        cwd=attempt,
                    )
                    self.process.stdout.close()
                    last_media, last_value = (
                        time.monotonic(),
                        base,
                    )
                    while self.encoder.poll() is None and not self.stop.wait(2):
                        current = self.store.session(ident)
                        if (
                            current["stop_requested"]
                            or time.time() >= current["expires"]
                        ):
                            break
                        until = self.import_manifest(attempt, base)
                        if until > last_value:
                            failures, last_media, last_value = (
                                0,
                                time.monotonic(),
                                until,
                            )
                            self.store.update_session(
                                ident, status="monitoring", message="Recebendo a live."
                            )
                        if time.monotonic() - last_media > 100:
                            break
                        if not self.disk_ok():
                            raise RuntimeError("Limite de armazenamento atingido.")
                    # End input first so FFmpeg can close and register the final segment.
                    terminate(self.process)
                    try:
                        self.encoder.wait(timeout=3)
                    except subprocess.TimeoutExpired:
                        terminate(self.encoder)
                    self.import_manifest(attempt, base)
                if self.stop.is_set() or self.store.session(ident)["stop_requested"]:
                    break
                if (
                    self.process.returncode == 0
                    and self.encoder.returncode == 0
                    and self.store.session(ident)["capture_seconds"] > base
                ):
                    break  # A clean end is complete, not a reason to replay a VOD.
                failures += 1
                error_log = (attempt / "capture.log").read_text(errors="replace")[
                    -30000:
                ]
                self.store.update_session(
                    ident,
                    status="reconnecting",
                    message=capture_message(error_log),
                )
                delay = (
                    300 if "429" in error_log else min(60, 5 * 2 ** min(failures, 4))
                )
                if self.stop.wait(delay):
                    break
            current = self.store.session(ident)
            resuming = (
                self.shutdown
                and self.shutdown.is_set()
                and time.time() < self.session["expires"]
            )
            if (
                not current["capture_seconds"]
                and not current["cancel_requested"]
                and not resuming
            ):
                raise RuntimeError(
                    "Nenhum vídeo foi recebido. A captura terminou sem material para analisar. Confira o link e o acesso à plataforma; você pode excluir esta tarefa e tentar novamente."
                )
            self.store.update_session(
                ident,
                status="stopping"
                if self.store.session(ident)["cancel_requested"]
                else "queued"
                if resuming
                else "finishing",
                message="Cancelando a tarefa."
                if self.store.session(ident)["cancel_requested"]
                else "Finalizando os trechos já recebidos.",
            )
        except Exception as e:
            self.store.update_session(
                ident,
                status="stopping"
                if self.store.session(ident)["cancel_requested"]
                else "error",
                message=str(e)[:500],
            )
        finally:
            terminate(self.process)
            terminate(self.encoder)
