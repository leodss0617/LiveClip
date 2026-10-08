import io
import json
from types import SimpleNamespace

import pytest


def settings(tmp_path):
    return SimpleNamespace(
        data=tmp_path,
        ollama_model="new-model",
        ollama_url="http://local",
        cpu_threads=2,
    )


def test_model_check_records_real_http_answer(tmp_path, monkeypatch):
    from liveclip.model_check import verify_model

    seen = {}

    def answer(request, **kwargs):
        seen.update(json.loads(request.data))
        return io.BytesIO(
            b'{"message":{"content":"{\\"liveclip_check\\":\\"ok\\"}"},"done":true}\n'
        )

    monkeypatch.setattr("urllib.request.urlopen", answer)
    result = verify_model(settings(tmp_path))
    assert result["model"] == "new-model"
    assert result["ok"] is True
    assert seen["think"] is False
    assert seen["options"]["num_ctx"] == 1024
    assert seen["options"]["num_predict"] == 32
    assert json.loads((tmp_path / "model-check.json").read_text())["ok"] is True


def test_failed_check_removes_stale_success(tmp_path, monkeypatch):
    from liveclip.model_check import verify_model

    (tmp_path / "model-check.json").write_text('{"ok":true}')
    monkeypatch.setattr(
        "urllib.request.urlopen",
        lambda *args, **kwargs: io.BytesIO(
            b'{"message":{"content":"{}"},"done":true}\n'
        ),
    )
    with pytest.raises(RuntimeError, match="teste"):
        verify_model(settings(tmp_path))
    assert not (tmp_path / "model-check.json").exists()


def test_panel_reports_success_only_for_current_model(tmp_path, monkeypatch):
    import time

    from test_api import client, login

    from liveclip.settings import Settings

    current = Settings(data=tmp_path, password="a-strong-password-123").ollama_model
    monkeypatch.setattr(
        "urllib.request.urlopen",
        lambda *a, **k: io.BytesIO(
            json.dumps({"models": [{"name": current}]}).encode()
        ),
    )
    stamp = tmp_path / "model-check.json"
    stamp.write_text(
        json.dumps({"ok": True, "model": "old-model", "checked_at": time.time()})
    )
    with client(tmp_path) as c:
        login(c)
        assert c.get("/api/status").json()["ai_checked"] is False
        stamp.write_text(
            json.dumps(
                {"ok": True, "model": current, "checked_at": time.time(), "seconds": 12}
            )
        )
        status = c.get("/api/status").json()
        assert status["ai_checked"] is True
        assert status["selection_model"] == current
        stamp.write_text(
            json.dumps(
                {"ok": True, "model": current, "checked_at": time.time() - 90000}
            )
        )
        assert c.get("/api/status").json()["ai_checked"] is False


def test_paused_processing_preserves_panel_and_cancellation(tmp_path):
    from fastapi.testclient import TestClient
    from test_api import login

    from liveclip.app import create_app
    from liveclip.settings import Settings

    app = create_app(
        Settings(
            data=tmp_path,
            password="a-strong-password-123",
            start_worker=False,
            processing_enabled=False,
            processing_error="Teste da IA falhou.",
        )
    )
    store = app.state.store
    session = store.create_session("https://kick.com/test", "kick")
    clip = store.add_clip(session["id"], 0, 20, "Salvo", "Corte existente", 0.9)
    store.update_clip(clip["id"], status="ready", filename=clip["id"] + ".mp4")
    folder = tmp_path / "clips"
    folder.mkdir()
    saved = b"saved-video-bytes"
    (folder / (clip["id"] + ".mp4")).write_bytes(saved)
    with TestClient(app) as c:
        login(c)
        assert c.get("/api/sessions").status_code == 200
        assert c.get("/api/clips").status_code == 200
        assert c.get("/api/clips/" + clip["id"] + "/download").content == saved
        assert c.get("/api/status").json()["processing_error"] == "Teste da IA falhou."
        assert (
            c.post("/api/sessions", json={"url": "https://kick.com/other"}).status_code
            == 503
        )
        assert (
            c.post("/api/sessions/" + session["id"] + "/reanalyze").status_code == 503
        )
        assert c.post("/api/sessions/" + session["id"] + "/stop").status_code == 200
        assert store.session(session["id"])["status"] == "cancelled"
        assert c.delete("/api/sessions/" + session["id"]).status_code == 200
