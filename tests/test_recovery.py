import threading
import time

from liveclip.settings import Settings
from liveclip.store import Store
from liveclip.worker import CancelledProcessing, Engine


def engine(tmp_path):
    return Engine(
        Store(tmp_path / "test.db"),
        Settings(data=tmp_path, password="recovery-test-password"),
    )


def test_bounded_backlog_retains_whole_story(tmp_path):
    e = engine(tmp_path)
    s = {"capture_seconds": 54000, "analyzed_until": 0}
    bounds = []
    while s["analyzed_until"] < 54000:
        a, b = e.analysis_bounds(s, True)
        bounds.append((a, b))
        s["analyzed_until"] = b
    assert all(b - a <= 420 for a, b in bounds)
    assert any(a <= 20 and b >= 310 for a, b in bounds)
    assert bounds[-1][1] == 54000


def test_final_tail_after_stop(tmp_path, monkeypatch):
    e = engine(tmp_path)
    s = e.store.create_session("https://kick.com/test", "kick")
    e.store.update_session(
        s["id"], status="finishing", stop_requested=1, capture_seconds=110
    )
    e.current = s["id"]

    class Done:
        def is_alive(self):
            return False

    e.capture_thread = Done()
    calls = []

    def analyze(session, final=False):
        calls.append(session["capture_seconds"])
        e.store.checkpoint(session["id"], session["capture_seconds"])
        e.store.update_session(session["id"], final_reviewed=session["capture_seconds"])
        if len(calls) == 1:
            e.store.update_session(session["id"], capture_seconds=130)

    monkeypatch.setattr(e, "analyze", analyze)
    e.tick()
    assert e.store.session(s["id"])["status"] == "finishing"
    e.tick()
    assert calls == [110, 130]
    assert e.store.session(s["id"])["status"] == "stopped"


def test_shutdown_preserves_reanalysis(tmp_path):
    e = engine(tmp_path)
    s = e.store.create_session("https://kick.com/test", "kick")
    e.store.update_session(s["id"], status="finishing")
    e.current = s["id"]
    e.stop()
    assert e.store.session(s["id"])["status"] == "finishing"


def test_cancellable_child(tmp_path, monkeypatch):
    import subprocess
    import sys

    import pytest

    e = engine(tmp_path)
    actual = subprocess.Popen
    children = []

    def slow(args, **kwargs):
        p = actual([sys.executable, "-c", "import time;time.sleep(60)"], **kwargs)
        children.append(p)
        return p

    monkeypatch.setattr(subprocess, "Popen", slow)
    timer = threading.Timer(0.2, e.stop_event.set)
    timer.start()
    began = time.monotonic()
    with pytest.raises(CancelledProcessing):
        e.run_job("analysis", {"start": 0, "end": 20}, tmp_path)
    assert time.monotonic() - began < 4 and children[0].poll() is not None


def test_owned_recording_cannot_be_deleted(tmp_path):
    from fastapi.testclient import TestClient

    from liveclip.app import create_app

    app = create_app(
        Settings(data=tmp_path, password="recovery-test-password", start_worker=False)
    )
    s = app.state.store.create_session("https://kick.com/test", "kick")
    app.state.store.update_session(s["id"], status="error", capture_seconds=100)
    with TestClient(app) as c:
        c.post("/api/login", json={"password": "recovery-test-password"})
        app.state.engine.operation_lock.acquire()
        try:
            assert (
                c.delete("/api/sessions/" + s["id"] + "/recording").status_code == 409
            )
            assert c.post("/api/sessions/" + s["id"] + "/reanalyze").status_code == 409
        finally:
            app.state.engine.operation_lock.release()
