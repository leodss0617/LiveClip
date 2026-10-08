import threading
import time

from liveclip.capture import Capture
from liveclip.settings import Settings
from liveclip.store import Store
from liveclip.worker import CancelledProcessing, Engine


def setup(tmp_path):
    store = Store(tmp_path / "db")
    settings = Settings(data=tmp_path, password="completion-test-password")
    session = store.create_session("https://kick.com/test", "kick")
    return store, settings, session["id"]


def test_expired_capture_without_media_is_error(tmp_path):
    store, settings, ident = setup(tmp_path)
    with store.connect() as db:
        db.execute("UPDATE sessions SET expires=? WHERE id=?", (time.time() - 1, ident))
    Capture(store, settings, store.session(ident), threading.Event()).run()
    session = store.session(ident)
    assert session["status"] == "error"
    assert "Nenhum vídeo" in session["message"]


def test_legacy_empty_finalization_does_not_claim_success(tmp_path):
    store, settings, ident = setup(tmp_path)
    store.update_session(ident, status="finishing")
    Engine(store, settings).tick()
    session = store.session(ident)
    assert session["status"] == "error"
    assert "Nenhum vídeo" in session["message"]
    assert store.create_session("https://kick.com/next", "kick")


def test_final_analysis_without_selected_moments_explains_result(tmp_path):
    store, settings, ident = setup(tmp_path)
    store.update_session(
        ident,
        status="finishing",
        capture_seconds=90,
        analyzed_until=90,
        final_reviewed=90,
    )
    Engine(store, settings).tick()
    session = store.session(ident)
    assert session["status"] == "completed"
    assert "Nenhuma história completa" in session["message"]


def test_failed_render_does_not_claim_success(tmp_path):
    store, settings, ident = setup(tmp_path)
    store.update_session(
        ident,
        status="finishing",
        capture_seconds=90,
        analyzed_until=90,
        final_reviewed=90,
    )
    clip = store.add_clip(ident, 0, 30, "Teste", "Teste", 0.9)
    store.update_clip(clip["id"], status="error", error="Falha controlada")
    Engine(store, settings).tick()
    session = store.session(ident)
    assert session["status"] == "error"
    assert session["stage"] == "render_error"
    assert "1 corte(s)" in session["message"]


def test_shutdown_during_render_keeps_session_resumable(tmp_path, monkeypatch):
    store, settings, ident = setup(tmp_path)
    store.update_session(
        ident,
        status="finishing",
        capture_seconds=90,
        analyzed_until=90,
        final_reviewed=90,
    )
    store.add_clip(ident, 0, 30, "Teste", "Teste", 0.9)
    engine = Engine(store, settings)
    store.add_segment(ident, tmp_path / "source.ts", 0, 90)

    def interrupt(kind, payload, work):
        engine.stop_event.set()
        raise CancelledProcessing()

    monkeypatch.setattr(engine, "run_job", interrupt)
    engine.tick()
    assert store.session(ident)["status"] == "finishing"
    assert store.clips()[0]["status"] == "queued"
