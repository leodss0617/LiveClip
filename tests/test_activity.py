import sqlite3

from liveclip.settings import Settings
from liveclip.store import Store
from liveclip.worker import Engine


def test_activity_survives_restart_and_capture_updates(tmp_path):
    store = Store(tmp_path / "db")
    s = store.create_session("https://kick.com/test", "kick")
    store.activity(
        s["id"],
        "transcribing",
        "Lendo áudio",
        progress=35,
        window_start=0,
        window_end=90,
    )
    store.update_session(s["id"], message="Recebendo a live.")
    fresh = Store(tmp_path / "db").session(s["id"])
    assert fresh["activity"]["detail"] == "Lendo áudio"
    assert fresh["activity"]["progress"] == 35
    assert len(fresh["events"]) == 1
    started = fresh["activity"]["started"]
    store.activity(s["id"], "transcribing", "Lendo áudio", progress=60)
    fresh = store.session(s["id"])
    assert fresh["activity"]["started"] == started
    assert len(fresh["events"]) == 1


def test_old_database_gets_activity_columns(tmp_path):
    path = tmp_path / "old.db"
    with sqlite3.connect(path) as db:
        db.execute(
            'CREATE TABLE sessions (id TEXT PRIMARY KEY, url TEXT NOT NULL, platform TEXT NOT NULL, status TEXT NOT NULL, stage TEXT DEFAULT "", message TEXT DEFAULT "", created REAL NOT NULL, expires REAL NOT NULL, capture_seconds REAL DEFAULT 0, analyzed_until REAL DEFAULT 0, stop_requested INTEGER DEFAULT 0)'
        )
    store = Store(path)
    s = store.create_session("https://kick.com/test", "kick")
    assert s["activity"] == {}
    assert s["events"] == []


def test_analysis_reports_no_complete_story(tmp_path, monkeypatch):
    store = Store(tmp_path / "db")
    s = store.create_session("https://kick.com/test", "kick")
    store.update_session(s["id"], capture_seconds=90)
    store.add_segment(s["id"], tmp_path / "fixture.ts", 0, 90)
    engine = Engine(store, Settings(data=tmp_path, password="long-test-password"))
    monkeypatch.setattr(engine, "run_job", lambda *a: {"candidates": [], "words": []})
    engine.analyze(store.session(s["id"]), final=True)
    fresh = store.session(s["id"])
    assert fresh["analyzed_until"] == 90
    assert "Nenhuma situação completa" in fresh["activity"]["detail"]


def test_failed_job_exposes_actionable_error_without_traceback(tmp_path):
    engine = Engine(
        Store(tmp_path / "db"), Settings(data=tmp_path, password="long-test-password")
    )
    # A malformed job exercises the real child process and error reporting.
    import pytest

    with pytest.raises(RuntimeError) as error:
        engine.run_job(
            "analysis", {"segments": [], "source": str(tmp_path / "x")}, tmp_path
        )
    assert "Preparação do vídeo falhou" in str(error.value)
    assert "Traceback" not in str(error.value)


def test_final_analysis_failure_releases_session_after_three_attempts(
    tmp_path, monkeypatch
):
    store = Store(tmp_path / "db")
    s = store.create_session("https://kick.com/test", "kick")
    store.update_session(s["id"], status="finishing", capture_seconds=90)
    engine = Engine(store, Settings(data=tmp_path, password="long-test-password"))

    def fail(*args):
        raise RuntimeError("Transcrição falhou. Verifique memória livre.")

    monkeypatch.setattr(engine, "analyze", fail)
    for _ in range(3):
        engine.retry_at.clear()
        engine.tick()
    fresh = store.session(s["id"])
    assert fresh["status"] == "stopped"
    assert fresh["stage"] == "analysis_error"
    assert fresh["capture_seconds"] == 90
    assert fresh["analyzed_until"] == 0
    assert "3 tentativas" in fresh["activity"]["detail"]
    assert store.create_session("https://kick.com/next", "kick")


def test_retry_resumes_partial_recording_without_losing_checkpoint(tmp_path):
    store = Store(tmp_path / "db")
    s = store.create_session("https://kick.com/test", "kick")
    store.update_session(
        s["id"], status="stopped", capture_seconds=600, analyzed_until=180
    )
    store.request_reanalysis(s["id"])
    assert store.session(s["id"])["analyzed_until"] == 180


def test_long_whisper_segment_exposes_timed_passages_for_story_review():
    from liveclip.intelligence import speech_passages

    words = [
        dict(start=i, end=i + 0.8, word=("palavra," if i in (5, 11) else "palavra"))
        for i in range(19)
    ]
    passages = speech_passages(words, [dict(start=0, end=19, text="fala contínua")])
    assert len(passages) >= 3
    assert passages[0]["start"] == 0
    assert passages[-1]["end"] == words[-1]["end"]
    assert " ".join(p["text"] for p in passages) == " ".join(w["word"] for w in words)


def test_three_analysis_failures_pause_even_if_render_changes_stage(
    tmp_path, monkeypatch
):
    store = Store(tmp_path / "db")
    session = store.create_session("https://kick.com/test", "kick")
    store.update_session(session["id"], status="monitoring", capture_seconds=180)
    engine = Engine(store, Settings(data=tmp_path, password="activity-test-password"))
    engine.current = session["id"]

    def fail(*a, **kw):
        raise RuntimeError("Falha controlada da IA")

    monkeypatch.setattr(engine, "analyze", fail)
    monkeypatch.setattr(
        engine,
        "render_queue",
        lambda: store.activity(session["id"], "", "Corte pronto"),
    )
    for _ in range(3):
        engine.retry_at.clear()
        engine.tick()
    engine.tick()
    assert store.session(session["id"])["status"] == "stopped"
    assert engine.capture_stop.is_set()
    assert engine.analysis_failures[session["id"]] == 3
