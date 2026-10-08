import subprocess
import sys
import threading
import time

import pytest
from fastapi.testclient import TestClient

from liveclip.app import create_app
from liveclip.settings import Settings
from liveclip.store import Store
from liveclip.worker import CancelledProcessing, Engine


def setup(tmp_path):
    settings = Settings(
        data=tmp_path, password="long-local-test-password", start_worker=False
    )
    store = Store(tmp_path / "liveclip.db")
    session = store.create_session("https://kick.com/test", "kick")
    return settings, store, session


def test_cancel_interrupts_real_child_and_releases_queue(tmp_path, monkeypatch):
    settings, store, s = setup(tmp_path)
    e = Engine(store, settings)
    actual = subprocess.Popen
    children = []

    def slow(args, **kwargs):
        child = actual([sys.executable, "-c", "import time; time.sleep(60)"], **kwargs)
        children.append(child)
        return child

    monkeypatch.setattr(subprocess, "Popen", slow)
    timer = threading.Timer(0.25, lambda: e.cancel_session(s["id"]))
    timer.start()
    began = time.monotonic()
    with pytest.raises(CancelledProcessing):
        e.run_job("analysis", {"session_id": s["id"]}, tmp_path)
    timer.join()
    assert time.monotonic() - began < 4
    assert children[0].poll() is not None
    e.tick()
    assert store.session(s["id"])["status"] == "cancelled"
    assert store.create_session("https://kick.com/next", "kick")


def test_cancel_preserves_ready_clips_and_prevents_new_clips(tmp_path):
    settings, store, s = setup(tmp_path)
    store.update_session(s["id"], capture_seconds=120)
    ready = store.add_clip(s["id"], 0, 20, "Pronto", "Teste", 0.9)
    pending = store.add_clip(s["id"], 40, 60, "Na fila", "Teste", 0.9)
    store.update_clip(ready["id"], status="ready", filename=ready["id"] + ".mp4")
    e = Engine(store, settings)
    e.cancel_session(s["id"])
    e.tick()
    assert store.clip(ready["id"])["status"] == "ready"
    assert store.clip(pending["id"])["status"] == "cancelled"
    assert store.session(s["id"])["capture_seconds"] == 120
    assert store.add_clip(s["id"], 80, 100, "Tarde", "Teste", 0.9) is None
    store.request_reanalysis(s["id"])
    assert not store.session(s["id"])["cancel_requested"]
    assert store.clip(pending["id"])["status"] == "queued"


def test_api_cancel_is_idempotent_and_works_while_worker_locked(tmp_path):
    settings, store, s = setup(tmp_path)
    app = create_app(settings)
    with TestClient(app) as c:
        c.post("/api/login", json={"password": settings.password})
        app.state.engine.operation_lock.acquire()
        try:
            for _ in range(2):
                assert c.post("/api/sessions/" + s["id"] + "/stop").status_code == 200
            assert store.session(s["id"])["cancel_requested"] == 1
        finally:
            app.state.engine.operation_lock.release()
        app.state.engine.tick()
        assert store.session(s["id"])["status"] == "cancelled"


def test_new_activity_does_not_display_old_analysis_error(tmp_path):
    _, store, s = setup(tmp_path)
    store.update_session(
        s["id"], message="Análise indisponível. Verifique os modelos e os logs."
    )
    store.activity(s["id"], "transcribing", "Transcrevendo áudio.", progress=0)
    assert "indisponível" not in store.session(s["id"])["message"]


def test_release_recording_clears_stale_activity(tmp_path):
    _, store, s = setup(tmp_path)
    store.activity(s["id"], "analysis_error", "Falha antiga.")
    store.clear_segments(s["id"])
    fresh = store.session(s["id"])
    assert fresh["stage"] == ""
    assert "Falha antiga" not in fresh["activity"].get("detail", "")


def test_manifest_absolute_timestamps_do_not_inflate_received_duration(tmp_path):
    from liveclip.capture import Capture

    settings, store, s = setup(tmp_path)
    folder = tmp_path / "segments"
    folder.mkdir()
    for name in ("a.ts", "b.ts"):
        (folder / name).write_bytes(b"fixture")
    (folder / "segments.csv").write_text("a.ts,9000,9020\nb.ts,9020,9040\n")
    capture = Capture(store, settings, s, threading.Event())
    capture.import_manifest(folder, 0)
    assert store.session(s["id"])["capture_seconds"] == 40
    assert [x["start"] for x in store.segments(s["id"])] == [0, 20]


def test_invalid_url_port_returns_validation_error(tmp_path):
    settings, _, _ = setup(tmp_path)
    app = create_app(settings)
    with TestClient(app) as c:
        c.post("/api/login", json={"password": settings.password})
        assert (
            c.post(
                "/api/sessions", json={"url": "https://kick.com:invalid/test"}
            ).status_code
            == 422
        )


def test_graceful_shutdown_keeps_capture_resumable(tmp_path):
    from liveclip.capture import Capture

    settings, store, s = setup(tmp_path)
    store.update_session(s["id"], status="monitoring")
    capture_stop, shutdown = threading.Event(), threading.Event()
    capture_stop.set()
    shutdown.set()
    capture = Capture(store, settings, s, capture_stop, shutdown=shutdown)
    capture.run()
    assert store.session(s["id"])["status"] == "queued"


def test_reanalysis_repairs_old_absolute_timeline_without_clips(tmp_path):
    _, store, s = setup(tmp_path)
    store.add_segment(s["id"], tmp_path / "a.ts", 9000, 20)
    store.add_segment(s["id"], tmp_path / "b.ts", 9020, 20)
    store.update_session(s["id"], status="stopped", capture_seconds=9040)
    store.request_reanalysis(s["id"])
    assert store.session(s["id"])["capture_seconds"] == 40
    assert [x["start"] for x in store.segments(s["id"])] == [0, 20]


def test_diagnostic_download_is_authenticated_and_excludes_password(tmp_path):
    settings, store, s = setup(tmp_path)
    work = tmp_path / "work" / s["id"]
    work.mkdir(parents=True)
    (work / "processing.log").write_text("RuntimeError: controlled timeout\n")
    app = create_app(settings)
    with TestClient(app) as c:
        route = "/api/sessions/" + s["id"] + "/diagnostic"
        assert c.get(route).status_code == 401
        c.post("/api/login", json={"password": settings.password})
        response = c.get(route)
        assert response.status_code == 200
        assert "attachment" in response.headers["content-disposition"]
        assert "controlled timeout" in response.text
        assert settings.password not in response.text
        assert "auth-secret" not in response.text


def test_analysis_timeout_uses_actual_window(tmp_path, monkeypatch):
    settings, store, s = setup(tmp_path)
    store.update_session(s["id"], capture_seconds=1000, analyzed_until=420)
    store.add_segment(s["id"], tmp_path / "source.ts", 0, 1000)
    e = Engine(store, settings)
    payloads = []

    def record(kind, payload, work):
        payloads.append(payload)
        return {"words": [], "candidates": []}

    monkeypatch.setattr(e, "run_job", record)
    e.analyze(store.session(s["id"]), final=True)
    assert payloads[0]["end"] - payloads[0]["start"] == 420


def test_cancel_at_render_completion_cannot_publish_new_clip(tmp_path, monkeypatch):
    settings, store, s = setup(tmp_path)
    store.add_segment(s["id"], tmp_path / "source.ts", 0, 60)
    clip = store.add_clip(s["id"], 0, 20, "Um corte", "Teste", 0.9)
    e = Engine(store, settings)

    def race(kind, payload, work):
        from pathlib import Path

        target = Path(payload["out"])
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(b"finished-before-publication")
        e.cancel_session(s["id"])
        return {"layout": "integral"}

    monkeypatch.setattr(e, "run_job", race)
    e.render_queue()
    assert store.clip(clip["id"])["status"] == "cancelled"
    assert not (tmp_path / "clips" / (clip["id"] + ".mp4")).exists()


def test_clean_stream_end_does_not_recapture_same_recording(tmp_path, monkeypatch):
    from liveclip.capture import Capture

    settings, store, s = setup(tmp_path)
    fixture = tmp_path / "fixture.ts"
    subprocess.run(
        [
            "ffmpeg",
            "-v",
            "error",
            "-f",
            "lavfi",
            "-i",
            "testsrc2=size=160x90:rate=5",
            "-t",
            "2",
            "-c:v",
            "libx264",
            "-f",
            "mpegts",
            str(fixture),
        ],
        check=True,
    )
    actual = subprocess.Popen
    readers = []

    def reader(args, **kwargs):
        if "streamlink" in args:
            readers.append(args)
            # A second capture would prove an unwanted retry and must not block this test.
            if len(readers) > 1:
                raise RuntimeError("Repeated completed recording")
            args = [
                sys.executable,
                "-c",
                'import sys; sys.stdout.buffer.write(open(sys.argv[1],"rb").read())',
                str(fixture),
            ]
        return actual(args, **kwargs)

    monkeypatch.setattr(subprocess, "Popen", reader)
    capture = Capture(store, settings, s, threading.Event())
    capture.run()
    assert len(readers) == 1
    assert store.session(s["id"])["status"] == "finishing"
    assert 1 <= store.session(s["id"])["capture_seconds"] <= 3


def test_ai_noise_does_not_discard_valid_candidates():
    from liveclip.intelligence import validate_candidates

    transcript = [dict(start=0, end=20, text="Uma história completa.")]
    assert (
        validate_candidates(
            {"clips": [None, "ruído", {}]}, transcript, 20, require_arc=True
        )
        == []
    )
    valid = dict(
        start=0,
        end=20,
        title="Uma história",
        reason="Contexto e conclusão",
        score=0.9,
        complete=True,
    )
    assert (
        len(validate_candidates({"clips": [None, "ruído", {}, valid]}, transcript, 20))
        == 1
    )
    assert (
        validate_candidates({"clips": [dict(valid, title="   ")]}, transcript, 20) == []
    )


def test_disk_scan_tolerates_processing_files_disappearing(tmp_path, monkeypatch):
    from pathlib import Path

    from liveclip.capture import Capture

    settings, store, s = setup(tmp_path)
    settings.min_free_gb = 0
    victim = tmp_path / "analysis.mkv"
    victim.write_bytes(b"temporary work")
    original = Path.is_file

    def vanish(path):
        exists = original(path)
        if path == victim:
            path.unlink(missing_ok=True)
        return exists

    monkeypatch.setattr(Path, "is_file", vanish)
    assert Capture(store, settings, s, threading.Event()).disk_ok()


def test_cancel_reaches_pending_clip_older_than_dashboard_limit(tmp_path):
    settings, store, session = setup(tmp_path)
    oldest = store.add_clip(session["id"], 0, 20, "Antigo", "Teste", 0.9)
    for index in range(1, 501):
        clip = store.add_clip(
            session["id"], index * 40, index * 40 + 20, "Pronto", "Teste", 0.9
        )
        store.update_clip(clip["id"], status="ready", filename=clip["id"] + ".mp4")
    engine = Engine(store, settings)
    engine.cancel_session(session["id"])
    engine.finish_cancel(session["id"])
    assert store.session(session["id"])["status"] == "cancelled"
    assert store.clip(oldest["id"])["status"] == "cancelled"
    assert all(clip["status"] == "ready" for clip in store.clips())
