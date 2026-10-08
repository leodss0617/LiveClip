import hashlib
import hmac
import json
import logging
import re
import secrets
import shutil
import time
from collections import defaultdict, deque
from contextlib import asynccontextmanager, contextmanager
from pathlib import Path
from urllib.parse import urlsplit

from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import FileResponse, JSONResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field

from .limits import MAX_HOURS
from .security import validate_url
from .settings import Settings
from .store import Store
from .version import VERSION
from .worker import Engine


class Login(BaseModel):
    password: str = Field(max_length=512)


class Feedback(BaseModel):
    rating: float = Field(ge=0, le=10, strict=True, allow_inf_nan=False)
    note: str = Field(default="", max_length=600)


class NewSession(BaseModel):
    url: str = Field(max_length=2048)


def create_app(settings=None):
    settings = settings or Settings()
    logging.basicConfig(level=logging.INFO)
    store = Store(settings.data / "liveclip.db")
    engine = Engine(store, settings)
    secret_path = settings.data / "auth-secret"
    if not secret_path.exists():
        secret_path.write_bytes(secrets.token_bytes(32))
        secret_path.chmod(0o600)
    secret = hmac.digest(secret_path.read_bytes(), settings.password.encode(), "sha256")
    attempts = defaultdict(deque)

    @asynccontextmanager
    async def lifespan(app):
        if settings.start_worker and settings.processing_enabled:
            engine.start()
        yield
        if settings.start_worker and settings.processing_enabled:
            engine.stop()

    app = FastAPI(title="LiveClip", lifespan=lifespan, docs_url=None, redoc_url=None)
    app.state.store, app.state.engine, app.state.settings = store, engine, settings

    @contextmanager
    def owned_operation():
        if not engine.operation_lock.acquire(blocking=False):
            raise HTTPException(
                409, "O servidor está processando vídeo. Aguarde essa etapa terminar."
            )
        try:
            yield
        finally:
            engine.operation_lock.release()

    def authenticated(request):
        token = request.cookies.get("liveclip", "")
        try:
            value, signature = token.rsplit(".", 1)
            return hmac.compare_digest(
                signature, hmac.new(secret, value.encode(), hashlib.sha256).hexdigest()
            ) and time.time() < float(value.split(":", 1)[0])
        except (ValueError, TypeError):
            return False

    @app.middleware("http")
    async def guard(request, call_next):
        path = request.url.path
        if request.method not in ("GET", "HEAD", "OPTIONS"):
            origin = request.headers.get("origin")
            if origin and urlsplit(origin).netloc != request.headers.get("host"):
                return JSONResponse(
                    {"detail": "Origem da solicitação não autorizada."}, status_code=403
                )
        if (
            path.startswith("/api/")
            and path not in ("/api/login", "/api/health")
            and not authenticated(request)
        ):
            response = JSONResponse(
                {"detail": "Entre para continuar."}, status_code=401
            )
        else:
            response = await call_next(request)
        response.headers["X-Content-Type-Options"] = "nosniff"
        response.headers["X-Frame-Options"] = "DENY"
        response.headers["Referrer-Policy"] = "same-origin"
        response.headers["Content-Security-Policy"] = (
            "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; media-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'"
        )
        if path.startswith("/api/"):
            response.headers["Cache-Control"] = "no-store"
        return response

    @app.get("/api/health")
    def health():
        return {"ok": True, "service": "LiveClip", "version": VERSION}

    @app.post("/api/login")
    def login(body: Login, request: Request):
        key = request.client.host if request.client else "unknown"
        now = time.monotonic()
        if len(attempts) > 10000:
            attempts.clear()
        queue = attempts[key]
        while queue and now - queue[0] > 300:
            queue.popleft()
        if len(queue) >= 10:
            raise HTTPException(429, "Muitas tentativas. Aguarde cinco minutos.")
        queue.append(now)
        if not hmac.compare_digest(body.password.encode(), settings.password.encode()):
            raise HTTPException(401, "Senha incorreta.")
        queue.clear()
        value = f"{int(time.time() + 86400)}:{secrets.token_hex(16)}"
        token = (
            value + "." + hmac.new(secret, value.encode(), hashlib.sha256).hexdigest()
        )
        response = JSONResponse({"ok": True})
        response.set_cookie(
            "liveclip",
            token,
            httponly=True,
            secure=settings.secure_cookie,
            samesite="strict",
            max_age=86400,
        )
        return response

    @app.post("/api/logout")
    def logout():
        response = JSONResponse({"ok": True})
        response.delete_cookie("liveclip")
        return response

    @app.get("/api/status")
    def status():
        import urllib.request

        ai = False
        try:
            with urllib.request.urlopen(
                settings.ollama_url.rstrip("/") + "/api/tags", timeout=2
            ) as r:
                models = json.loads(r.read(1_000_000)).get("models", [])
            ai = any(m.get("name") == settings.ollama_model for m in models)
        except Exception:
            pass
        checked = False
        try:
            stamp = json.loads((settings.data / "model-check.json").read_text())
            checked = (
                ai
                and stamp.get("ok") is True
                and stamp.get("model") == settings.ollama_model
                and 0 <= time.time() - float(stamp["checked_at"]) < 86400
            )
        except (OSError, ValueError, TypeError, KeyError, AttributeError):
            pass
        return {
            "ai_ready": ai,
            "ai_checked": checked,
            "selection_model": settings.ollama_model,
            "processing_enabled": settings.processing_enabled,
            "processing_error": settings.processing_error,
            "ai_state": "model_present" if ai else "unavailable",
            "free_gb": round(shutil.disk_usage(settings.data).free / 1024**3, 1),
            "worker_running": engine.thread.is_alive(),
            "version": VERSION,
            "whisper_model": settings.whisper_model,
            "max_hours": MAX_HOURS,
            "review_minutes": 20,
            "minimum_rating": 7,
            "knowledge": engine.knowledge.context(),
        }

    @app.get("/api/sessions")
    def sessions():
        return store.sessions()

    @app.post("/api/sessions", status_code=201)
    def create(body: NewSession):
        if not settings.processing_enabled:
            raise HTTPException(
                503,
                settings.processing_error
                or "Processamento pausado. Reinicie para testar a IA novamente.",
            )
        try:
            platform, url = validate_url(body.url)
        except ValueError as e:
            raise HTTPException(422, str(e)) from e
        try:
            return store.create_session(url, platform)
        except ValueError as e:
            raise HTTPException(409, str(e)) from e

    @app.post("/api/sessions/{ident}/stop")
    def stop(ident: str):
        if not store.session(ident):
            raise HTTPException(404, "Sessão não encontrada.")
        engine.cancel_session(ident)
        if not settings.processing_enabled:
            engine.finish_cancel(ident)
        return {"ok": True}

    @app.post("/api/sessions/{ident}/reanalyze")
    def reanalyze(ident: str):
        if not settings.processing_enabled:
            raise HTTPException(
                503,
                settings.processing_error
                or "Processamento pausado. Reinicie para testar a IA novamente.",
            )

        with owned_operation():
            session = store.session(ident)
            if not session:
                raise HTTPException(404, "Sessão não encontrada.")
            if not session["capture_seconds"]:
                raise HTTPException(409, "Não há vídeo capturado para analisar.")
            try:
                store.request_reanalysis(ident)
                engine.analysis_failures.pop(ident, None)
                engine.retry_at.pop(ident, None)
                store.activity(
                    ident,
                    "queued",
                    "Análise solicitada. Aguardando processamento.",
                    progress=None,
                )
            except ValueError as e:
                raise HTTPException(409, str(e)) from e
            return {"ok": True}

    @app.get("/api/sessions/{ident}/diagnostic")
    def diagnostic(ident: str):
        session = store.session(ident)
        if not session or not re.fullmatch(r"[a-f0-9]{32}", ident):
            raise HTTPException(404, "Sessão não encontrada.")
        logs = []
        ids = [ident] + [c["id"] for c in store.clips() if c["session_id"] == ident][
            :10
        ]
        for item in ids:
            path = settings.data / "work" / item / "processing.log"
            try:
                with path.open("rb") as stream:
                    stream.seek(0, 2)
                    stream.seek(max(0, stream.tell() - 12000))
                    logs.append(
                        dict(job=item, log=stream.read(12000).decode(errors="replace"))
                    )
            except FileNotFoundError:
                pass
        runtime = {"ollama": {}, "memory": {}}
        transcription = {}
        try:
            with (
                settings.data / "work" / ident / "transcription.json"
            ).open() as cached:
                saved = json.loads(cached.read(2_000_000))
            transcription = {
                "passages": saved.get("transcript", [])[:100],
                "words_count": len(saved.get("words", [])),
            }
        except (OSError, ValueError, TypeError):
            pass
        import urllib.request

        for name in ("ps", "version"):
            try:
                with urllib.request.urlopen(
                    settings.ollama_url.rstrip("/") + "/api/" + name, timeout=2
                ) as response:
                    runtime["ollama"][name] = json.loads(response.read(1_000_000))
            except Exception as error:
                runtime["ollama"][name] = {
                    "available": False,
                    "error_type": type(error).__name__,
                }
        try:
            for line in Path("/proc/meminfo").read_text().splitlines():
                key, value = line.split(":", 1)
                if key in ("MemTotal", "MemAvailable", "SwapTotal", "SwapFree"):
                    runtime["memory"][key] = value.strip()
        except OSError:
            pass
        model_log = Path(__file__).resolve().parents[1] / "logs-native" / "ollama.log"
        try:
            with model_log.open("rb") as stream:
                stream.seek(0, 2)
                stream.seek(max(0, stream.tell() - 12000))
                runtime["ollama_log"] = (
                    stream.read(12000)
                    .decode(errors="replace")
                    .replace(settings.password, "[senha omitida]")
                )
        except OSError:
            runtime["ollama_log"] = "Log nativo não disponível neste ambiente."
        return JSONResponse(
            dict(
                runtime=runtime,
                transcription=transcription,
                version=VERSION,
                session=session,
                models=dict(
                    transcription=settings.whisper_model,
                    selection=settings.ollama_model,
                ),
                clips=[c for c in store.clips() if c["session_id"] == ident],
                logs=logs,
            ),
            headers={
                "Content-Disposition": f'attachment; filename="liveclip-diagnostico-{ident[:8]}.json"'
            },
        )

    @app.get("/api/knowledge")
    def knowledge():
        return engine.knowledge.context()

    @app.delete("/api/knowledge")
    def clear_knowledge():
        engine.knowledge.clear()
        return {"ok": True}

    @app.post("/api/clips/{ident}/feedback")
    def feedback(ident: str, body: Feedback):
        clip = store.clip(ident)
        if not clip:
            raise HTTPException(404, "Corte não encontrado.")
        if not store.set_feedback(ident, body.rating):
            raise HTTPException(409, "Assista a um corte pronto antes de avaliar.")
        engine.knowledge.record(
            ident,
            "render",
            dict(
                title=clip["title"],
                rating=round(clip["score"] * 10, 1),
                duration=clip["end"] - clip["start"],
                status="ready",
                layout=clip["layout"],
            ),
        )
        engine.knowledge.feedback(ident, body.rating, body.note)
        return {"ok": True, "rating": body.rating}

    @app.get("/api/clips")
    def clips():
        return store.clips()

    @app.delete("/api/sessions/{ident}")
    def delete_session(ident: str):
        if not re.fullmatch(r"[a-f0-9]{32}", ident):
            raise HTTPException(404, "Sessão não encontrada.")
        try:
            store.delete_session(ident)
        except KeyError:
            raise HTTPException(404, "Sessão não encontrada.")
        except ValueError as error:
            raise HTTPException(409, str(error)) from error
        shutil.rmtree(settings.data / "sessions" / ident, ignore_errors=True)
        shutil.rmtree(settings.data / "work" / ident, ignore_errors=True)
        engine.retry_at.pop(ident, None)
        engine.analysis_failures.pop(ident, None)
        return {"ok": True}

    @app.delete("/api/sessions/{ident}/recording")
    def free_recording(ident: str):
        from .store import ACTIVE

        with owned_operation():
            session = store.session(ident)
            if not session:
                raise HTTPException(404, "Sessão não encontrada.")
            if session["status"] in ACTIVE or any(
                c["session_id"] == ident and c["status"] in ("queued", "rendering")
                for c in store.clips()
            ):
                raise HTTPException(409, "Aguarde o processamento finalizar.")
            if not re.fullmatch(r"[a-f0-9]{32}", ident):
                raise HTTPException(404, "Identificador inválido.")
            shutil.rmtree(settings.data / "sessions" / ident, ignore_errors=True)
            store.clear_segments(ident)
            return {"ok": True}

    def clip_path(ident):
        clip = store.clip(ident)
        if (
            not clip
            or clip["status"] != "ready"
            or clip["filename"] != ident + ".mp4"
            or not re.fullmatch(r"[a-f0-9]{32}", ident)
        ):
            raise HTTPException(404, "Corte não disponível.")
        path = settings.data / "clips" / clip["filename"]
        if not path.is_file():
            raise HTTPException(404, "Arquivo não encontrado.")
        return path

    @app.get("/api/clips/{ident}/download")
    def download(ident: str):
        return FileResponse(
            clip_path(ident),
            media_type="video/mp4",
            filename="liveclip-" + ident[:8] + ".mp4",
        )

    @app.get("/api/clips/{ident}/video")
    def video(ident: str):
        return FileResponse(clip_path(ident), media_type="video/mp4")

    @app.delete("/api/clips/{ident}")
    def remove(ident: str):
        clip = store.clip(ident)
        if not clip:
            raise HTTPException(404, "Corte não encontrado.")
        if clip["status"] in ("queued", "rendering"):
            raise HTTPException(409, "Aguarde a edição terminar.")
        if re.fullmatch(r"[a-f0-9]{32}", ident):
            (settings.data / "clips" / (ident + ".mp4")).unlink(missing_ok=True)
        store.delete_clip(ident)
        return {"ok": True}

    static = Path(__file__).parent / "static"
    app.mount("/static", StaticFiles(directory=static), name="static")

    @app.get("/")
    def home():
        return FileResponse(static / "index.html")

    return app
