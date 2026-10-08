import json
import logging
import os
import signal
import subprocess
import sys
import threading
import time

from .capture import Capture
from .knowledge import Knowledge
from .store import ACTIVE

log = logging.getLogger(__name__)


REVIEW_SECONDS = 1200


def review_target(received, final=False):
    return received if final else (int(received) // REVIEW_SECONDS) * REVIEW_SECONDS


class CancelledProcessing(Exception):
    pass


class AIWaitBudget:
    """Enforce elapsed time outside the model client, independent of socket reads."""

    def __init__(self):
        self.stage = None
        self.started = 0
        self.output_at = 0
        self.detail = ""

    def check(self, stage, detail, now):
        if stage not in ("selecting", "reviewing", "grading"):
            self.stage = None
            return
        if stage != self.stage:
            self.stage, self.started, self.output_at, self.detail = stage, now, now, ""
        if detail != self.detail and "partes recebidas do modelo" in detail:
            self.output_at = now
        self.detail = detail
        if now - self.started >= 900:
            raise RuntimeError(
                "A IA excedeu 15 minutos nesta etapa. A resposta foi interrompida; a gravação e a transcrição foram preservadas."
            )
        if now - self.output_at >= 300:
            raise RuntimeError(
                "O Ollama ficou 5 minutos sem entregar novas partes da resposta. A tentativa foi interrompida. Baixe o diagnóstico para verificar memória e o log do modelo; a transcrição foi preservada."
            )


class Engine:
    def __init__(self, store, settings):
        self.store, self.settings = store, settings
        self.knowledge = Knowledge(store.path)
        self.stop_event = threading.Event()
        self.capture_stop = threading.Event()
        self.capture_thread = None
        self.current = None
        self.thread = threading.Thread(
            target=self.loop, name="liveclip-worker", daemon=True
        )
        self.operation_lock = threading.Lock()
        self.retry_at = {}
        self.analysis_failures = {}

    def start(self):
        for s in self.store.sessions():
            if s["status"] in ACTIVE and s["stop_requested"]:
                self.store.request_cancel(s["id"])
        for c in self.store.clips(complete=True):
            if c["status"] == "rendering":
                session = self.store.session(c["session_id"])
                self.store.update_clip(
                    c["id"],
                    status="cancelled"
                    if session and session["cancel_requested"]
                    else "queued",
                )
        self.thread.start()

    def cancel_session(self, ident):
        self.store.request_cancel(ident)
        if self.current == ident:
            self.capture_stop.set()

    def is_cancelled(self, ident):
        session = self.store.session(ident) if ident else None
        return bool(session and session["cancel_requested"])

    def finish_cancel(self, ident):
        if self.current == ident:
            self.capture_stop.set()
            if self.capture_thread and self.capture_thread.is_alive():
                self.capture_thread.join(timeout=0.2)
                if self.capture_thread.is_alive():
                    return
            self.current = None
        for clip in self.store.clips(complete=True):
            if clip["session_id"] == ident and clip["status"] in (
                "queued",
                "rendering",
            ):
                self.store.update_clip(clip["id"], status="cancelled")
                for suffix in (".mp4", ".partial.mp4"):
                    (self.settings.data / "clips" / (clip["id"] + suffix)).unlink(
                        missing_ok=True
                    )
        self.store.update_session(
            ident,
            status="cancelled",
            message="Tarefa cancelada. Gravação e cortes prontos preservados.",
        )
        self.store.activity(
            ident,
            "",
            "Cancelado: captura, análise e edição interrompidas.",
            progress=None,
        )

    def stop(self):
        self.stop_event.set()
        self.capture_stop.set()
        if self.capture_thread:
            self.capture_thread.join(timeout=20)
        if self.thread.is_alive():
            self.thread.join(timeout=10)
        if self.current:
            s = self.store.session(self.current)
            if (
                s
                and not s["stop_requested"]
                and time.time() < s["expires"]
                and s["status"] not in ("error", "finishing", "completed", "stopped")
            ):
                self.store.update_session(
                    self.current,
                    status="queued",
                    stage="",
                    message="A captura será retomada após o reinício.",
                )

    def start_capture(self, s):
        self.current = s["id"]
        self.capture_stop = threading.Event()
        c = Capture(
            self.store, self.settings, s, self.capture_stop, shutdown=self.stop_event
        )
        self.capture_thread = threading.Thread(
            target=c.run, name="liveclip-capture", daemon=True
        )
        self.capture_thread.start()

    def analysis_bounds(self, s, final=False):
        start = max(0, s["analyzed_until"] - 330)
        pending = s.get("activity", {}).get("pending_music_start")
        if isinstance(pending, (int, float)):
            start = max(0, s["analyzed_until"] - 660, min(start, pending - 10))
        return start, min(
            review_target(s["capture_seconds"], final), s["analyzed_until"] + 90
        )

    def run_job(self, kind, payload, work):
        if self.is_cancelled(payload.get("session_id")):
            raise CancelledProcessing()
        request, result, stage = (
            work / "job.json",
            work / "result.json",
            work / "stage.txt",
        )
        request.write_text(json.dumps(payload))
        result.unlink(missing_ok=True)
        stage.unlink(missing_ok=True)
        activity_file, error_file = work / "activity.json", work / "error.json"
        activity_file.unlink(missing_ok=True)
        error_file.unlink(missing_ok=True)
        process = None
        ai_budget = AIWaitBudget()
        try:
            with (work / "processing.log").open("wb") as output:
                process = subprocess.Popen(
                    [
                        sys.executable,
                        "-m",
                        "liveclip.job",
                        kind,
                        str(request),
                        str(result),
                    ],
                    stdout=output,
                    stderr=output,
                    start_new_session=True,
                )
                deadline = time.monotonic() + max(
                    900, float(payload.get("end", 0) - payload.get("start", 0)) * 15
                )
                while process.poll() is None:
                    if self.stop_event.wait(0.5):
                        raise CancelledProcessing()
                    if self.is_cancelled(payload.get("session_id")):
                        raise CancelledProcessing()
                    if time.monotonic() > deadline:
                        raise RuntimeError("Tempo máximo de processamento atingido.")
                    if payload.get("session_id") and activity_file.exists():
                        update = json.loads(activity_file.read_text())
                        ai_budget.check(
                            update["stage"], update["detail"], time.monotonic()
                        )
                        self.store.activity(
                            payload["session_id"],
                            update["stage"],
                            update["detail"],
                            progress=update.get("progress"),
                        )
                if self.stop_event.is_set() or self.is_cancelled(
                    payload.get("session_id")
                ):
                    raise CancelledProcessing()
                if process.returncode or not result.exists():
                    if error_file.exists():
                        raise RuntimeError(json.loads(error_file.read_text())["detail"])
                    raise RuntimeError(
                        "Falha no processamento. Consulte processing.log."
                    )
                return json.loads(result.read_text())
        finally:
            if process:
                try:
                    os.killpg(process.pid, signal.SIGTERM)
                except ProcessLookupError:
                    pass
                try:
                    process.wait(timeout=2)
                except subprocess.TimeoutExpired:
                    pass
                try:
                    os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
                process.wait(timeout=5)

    def analyze(self, s, final=False):
        target = review_target(s["capture_seconds"], final)
        replay_final = bool(
            final
            and s["capture_seconds"] > 0
            and s["analyzed_until"] >= target - 0.01
            and s.get("final_reviewed", -1) < target - 0.01
        )
        if target - s["analyzed_until"] < 0.01 and not replay_final:
            if not final:
                self.store.activity(
                    s["id"],
                    "waiting_review",
                    "Recebendo vídeo: próxima avaliação ao completar o bloco de 20 minutos. Não há obrigação de gerar cortes.",
                    review_target=(int(s["capture_seconds"]) // REVIEW_SECONDS + 1)
                    * REVIEW_SECONDS,
                    progress=None,
                )
            return
        start, end = self.analysis_bounds(s, final)
        segments = self.store.segments(s["id"], start, end)
        if not segments:
            raise RuntimeError("Segmentos da gravação indisponíveis.")
        ident, base = s["id"], segments[0]["start"]
        work = self.settings.data / "work" / ident
        work.mkdir(parents=True, exist_ok=True)
        source = work / "analysis.mkv"
        try:
            self.store.activity(
                ident,
                "preparing",
                "Preparando o trecho para análise.",
                window_start=start,
                window_end=end,
                progress=None,
            )
            r = self.run_job(
                "analysis",
                {
                    "source": str(source.resolve()),
                    "start": start,
                    "end": end,
                    "segments": segments,
                    "session_id": ident,
                    "final": bool(final and end >= s["capture_seconds"] - 0.01),
                    "knowledge": self.knowledge.context(),
                    "data": str(self.settings.data),
                    "whisper_model": self.settings.whisper_model,
                    "cpu_threads": self.settings.cpu_threads,
                    "ollama_model": self.settings.ollama_model,
                    "ollama_url": self.settings.ollama_url,
                },
                work,
            )
            for c in r["candidates"]:
                added = self.store.add_clip(
                    ident,
                    base + c["start"],
                    base + c["end"],
                    c["title"],
                    c["reason"],
                    c["score"],
                    words=[
                        dict(w, start=base + w["start"], end=base + w["end"])
                        for w in r["words"]
                    ],
                )
                if added and c.get("evaluation"):
                    self.store.set_evaluation(added["id"], c["evaluation"])
            self.knowledge.record(
                f"analysis:{ident}:{start}:{end}",
                "analysis",
                dict(
                    selected=len(r["candidates"]),
                    reviews=r.get("reviews", []),
                    duration=end - start,
                ),
            )
            self.store.checkpoint(ident, end)
            if final and end >= s["capture_seconds"] - 0.01:
                self.store.update_session(ident, final_reviewed=end)
            self.store.update_session(ident, stage="", message="Análise concluída.")
            self.store.activity(
                ident,
                "",
                f"Análise concluída: {len(r['candidates'])} candidato(s) selecionado(s); duplicados são descartados."
                if r["candidates"]
                else (
                    "Música ainda em andamento: aguardando o fim para preservar a apresentação."
                    if r.get("pending_music_start") is not None
                    else "Nenhuma situação completa aprovada neste trecho. A análise continuará com mais contexto."
                ),
                window_start=start,
                window_end=end,
                reviewed_count=len(r.get("reviews", [])),
                rejected_count=sum(
                    not v.get("accepted", False) for v in r.get("reviews", [])
                ),
                best_rating=max(
                    (v.get("rating", 0) for v in r.get("reviews", [])), default=0
                ),
                pending_music_start=(
                    base + r["pending_music_start"]
                    if r.get("pending_music_start") is not None
                    else None
                ),
                warning=r.get("warning"),
                progress=None,
            )
        finally:
            source.unlink(missing_ok=True)

    def render_queue(self):
        for c in reversed(self.store.clips(complete=True)):
            if self.stop_event.is_set():
                return
            if c["status"] != "queued":
                continue
            if self.is_cancelled(c["session_id"]):
                self.store.update_clip(c["id"], status="cancelled")
                continue
            self.store.update_clip(c["id"], status="rendering", error="")
            work = self.settings.data / "work" / c["id"]
            work.mkdir(parents=True, exist_ok=True)
            source = work / "source.mkv"
            try:
                self.store.activity(
                    c["session_id"],
                    "rendering",
                    f"Editando: {c['title']}",
                    progress=None,
                )
                segments = self.store.segments(c["session_id"], c["start"], c["end"])
                if not segments:
                    raise ValueError("Segmentos indisponíveis.")
                base = segments[0]["start"]
                filename = c["id"] + ".mp4"
                words = [
                    dict(w, start=w["start"] - base, end=w["end"] - base)
                    for w in self.store.words(c["id"])
                ]
                r = self.run_job(
                    "render",
                    {
                        "source": str(source.resolve()),
                        "session_id": c["session_id"],
                        "segments": segments,
                        "start": c["start"] - base,
                        "end": c["end"] - base,
                        "words": words,
                        "out": str((self.settings.data / "clips" / filename).resolve()),
                    },
                    work,
                )
                published = self.store.update_clip(
                    c["id"], status="ready", filename=filename, layout=r["layout"]
                )
                if not published:
                    raise CancelledProcessing()
                if r.get("technical"):
                    self.store.set_evaluation(c["id"], r["technical"], technical=True)
                self.knowledge.record(
                    c["id"],
                    "render",
                    dict(
                        title=c["title"],
                        rating=round(c["score"] * 10, 1),
                        duration=c["end"] - c["start"],
                        status="ready",
                        layout=r["layout"],
                        technical=r.get("technical", {}),
                    ),
                )
                self.store.activity(
                    c["session_id"], "", f"Corte pronto: {c['title']}", progress=None
                )
            except CancelledProcessing:
                cancelled = self.is_cancelled(c["session_id"])
                self.store.update_clip(
                    c["id"], status="cancelled" if cancelled else "queued"
                )
                if cancelled:
                    for suffix in (".mp4", ".partial.mp4"):
                        (self.settings.data / "clips" / (c["id"] + suffix)).unlink(
                            missing_ok=True
                        )
                return
            except Exception as e:
                log.exception("Falha de edição: %s", c["id"])
                self.store.update_clip(
                    c["id"],
                    status="error",
                    error=str(e)[:500],
                )
                self.knowledge.record(
                    c["id"],
                    "render",
                    dict(
                        title=c["title"],
                        rating=round(c["score"] * 10, 1),
                        duration=c["end"] - c["start"],
                        status="error",
                        error=str(e)[:500],
                    ),
                )
                self.store.activity(
                    c["session_id"], "render_error", str(e)[:500], progress=None
                )
            finally:
                source.unlink(missing_ok=True)

    def tick(self):
        with self.operation_lock:
            sessions = [s for s in self.store.sessions() if s["status"] in ACTIVE]
            if not sessions:
                self.render_queue()
                return
            s = sessions[0]
            ident = s["id"]
            if s["cancel_requested"]:
                self.finish_cancel(ident)
                return
            if self.analysis_failures.get(ident, 0) >= 3:
                self.capture_stop.set()
                if (
                    self.capture_thread
                    and self.capture_thread.is_alive()
                    and self.current == ident
                ):
                    self.store.update_session(ident, status="finishing")
                    return
                self.store.update_session(ident, status="stopped")
                self.store.activity(
                    ident,
                    "analysis_error",
                    "Análise pausada após 3 falhas consecutivas. A gravação e a transcrição foram preservadas. Confira o diagnóstico e use Continuar análise.",
                    progress=None,
                )
                self.current = None
                return
            if s["stop_requested"]:
                self.capture_stop.set()
            if (
                self.current != ident
                and not s["stop_requested"]
                and s["status"] != "finishing"
            ):
                self.start_capture(s)
            fresh = self.store.session(ident)
            capture_done = not self.capture_thread or not self.capture_thread.is_alive()
            final = bool(
                (fresh["status"] == "finishing" or fresh["stop_requested"])
                and capture_done
            )
            if final and fresh["capture_seconds"] <= 0:
                message = "Nenhum vídeo foi recebido. Não há material para analisar; confira o link e o acesso à plataforma."
                self.store.update_session(ident, status="error", message=message)
                self.store.activity(ident, "capture_error", message, progress=None)
                self.current = None
                return
            failed = False
            if time.monotonic() >= self.retry_at.get(ident, 0):
                try:
                    self.analyze(fresh, final)
                    self.analysis_failures[ident] = 0
                except CancelledProcessing:
                    if self.is_cancelled(ident):
                        self.finish_cancel(ident)
                    return
                except Exception as e:
                    failed = True
                    self.analysis_failures[ident] = (
                        self.analysis_failures.get(ident, 0) + 1
                    )
                    log.exception("Falha de análise: %s", ident)
                    self.store.update_session(
                        ident,
                        stage="analysis_error",
                        message=str(e)[:500],
                    )
                    self.store.activity(
                        ident, "analysis_error", str(e)[:500], progress=None
                    )
                    self.retry_at[ident] = time.monotonic() + 45
            if self.stop_event.is_set():
                return
            self.render_queue()
            if self.stop_event.is_set():
                return
            fresh = self.store.session(ident)
            if fresh["cancel_requested"]:
                self.finish_cancel(ident)
                return
            if (
                final
                and not failed
                and fresh["analyzed_until"] >= fresh["capture_seconds"] - 0.01
                and fresh.get("final_reviewed", -1) >= fresh["capture_seconds"] - 0.01
            ):
                clips = [
                    c
                    for c in self.store.clips(complete=True)
                    if c["session_id"] == ident
                ]
                failed_clips = sum(c["status"] == "error" for c in clips)
                ready = sum(c["status"] == "ready" for c in clips)
                stage = "render_error" if failed_clips else ""
                message = (
                    f"Análise encerrada. {ready} corte(s) pronto(s); {failed_clips} corte(s) com falha na edição. Confira o erro em cada corte."
                    if failed_clips
                    else f"Processamento encerrado: {ready} corte(s) pronto(s) para baixar."
                    if ready
                    else "Análise encerrada. Nenhuma história completa foi aprovada para corte nesta gravação."
                )
                self.store.update_session(
                    ident,
                    status="error"
                    if failed_clips
                    else "stopped"
                    if fresh["stop_requested"]
                    else "completed",
                    stage=stage,
                    message=message,
                )
                self.store.activity(ident, stage, message, progress=None)
                self.current = None
            elif (
                final
                and failed
                and (
                    fresh["stop_requested"] or self.analysis_failures.get(ident, 0) >= 3
                )
            ):
                self.store.update_session(
                    ident, status="stopped", stage="analysis_error"
                )
                self.current = None
                if self.analysis_failures.get(ident, 0) >= 3:
                    self.store.activity(
                        ident,
                        "analysis_error",
                        "Análise pausada após 3 tentativas com falha. A gravação foi preservada. Corrija o problema informado e use Reanalisar gravação.",
                        progress=None,
                    )

    def loop(self):
        while not self.stop_event.is_set():
            try:
                self.tick()
            except Exception:
                log.exception("Falha no worker; nova tentativa em cinco segundos.")
            self.stop_event.wait(5)
