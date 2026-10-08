from liveclip.settings import Settings
from liveclip.store import Store


def test_manifest_import_is_idempotent_and_recovers_after_restart(tmp_path):
    import threading

    from liveclip.capture import Capture

    settings = Settings(data=tmp_path, password="strong-local-test-password")
    store = Store(tmp_path / "test.db")
    session = store.create_session("https://kick.com/test", "kick")
    attempt = tmp_path / "segments"
    attempt.mkdir()
    (attempt / "seg_00000000.ts").write_bytes(b"fixture")
    (attempt / "segments.csv").write_text("seg_00000000.ts,0,20\n")
    capture = Capture(store, settings, session, threading.Event())
    capture.import_manifest(attempt, 30)
    capture.import_manifest(attempt, 30)
    assert len(store.segments(session["id"])) == 1
    assert Store(tmp_path / "test.db").session(session["id"])["capture_seconds"] == 50


def test_words_and_clip_are_enqueued_atomically(tmp_path):
    store = Store(tmp_path / "test.db")
    session = store.create_session("https://kick.com/test", "kick")
    words = [{"start": 0, "end": 1, "word": "Olá"}]
    clip = store.add_clip(session["id"], 0, 20, "Título", "Razão", 0.9, words=words)
    assert Store(tmp_path / "test.db").words(clip["id"]) == words


def test_free_recording_preserves_finished_clip(tmp_path):
    from fastapi.testclient import TestClient

    from liveclip.app import create_app

    app = create_app(
        Settings(
            data=tmp_path, password="strong-local-test-password", start_worker=False
        )
    )
    with TestClient(app) as c:
        c.post("/api/login", json={"password": "strong-local-test-password"})
        s = app.state.store.create_session("https://kick.com/test", "kick")
        app.state.store.update_session(s["id"], status="completed", capture_seconds=20)
        clip = app.state.store.add_clip(s["id"], 0, 20, "Teste", "Teste", 0.9)
        app.state.store.update_clip(
            clip["id"], status="ready", filename=clip["id"] + ".mp4"
        )
        assert c.delete("/api/sessions/" + s["id"] + "/recording").status_code == 200
        assert app.state.store.clip(clip["id"])["status"] == "ready"
        assert app.state.store.session(s["id"])["capture_seconds"] == 0
