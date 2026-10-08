from fastapi.testclient import TestClient
from liveclip.app import create_app
from liveclip.settings import Settings


def setup(tmp_path):
    app = create_app(
        Settings(data=tmp_path, password="long-local-test-password", start_worker=False)
    )
    session = app.state.store.create_session("https://kick.com/test", "kick")
    clip = app.state.store.add_clip(
        session["id"], 0, 30, "Um evento", "Contexto e desfecho", 0.9
    )
    return app, clip


def test_feedback_requires_login_and_a_ready_clip(tmp_path):
    app, clip = setup(tmp_path)
    with TestClient(app) as client:
        url = "/api/clips/" + clip["id"] + "/feedback"
        assert client.post(url, json=dict(rating=8, note="Gostei")).status_code == 401
        client.post("/api/login", json=dict(password="long-local-test-password"))
        assert client.post(url, json=dict(rating=8, note="Gostei")).status_code == 409
        app.state.store.update_clip(clip["id"], status="ready", filename="fixture.mp4")
        assert client.post(url, json=dict(rating=11, note="Teste")).status_code == 422
        assert client.post(url, json=dict(rating=True, note="Teste")).status_code == 422
        assert (
            client.post(url, json=dict(rating=3, note="Começou no meio")).status_code
            == 200
        )
        assert client.get("/api/knowledge").json()["examples"][0]["user_rating"] == 3
        assert client.get("/api/clips").json()[0]["user_rating"] == 3
        assert client.delete("/api/knowledge").status_code == 200
        assert client.get("/api/knowledge").json()["records"] == 0


def test_clip_evaluation_survives_database_restart(tmp_path):
    from liveclip.store import Store

    app, clip = setup(tmp_path)
    app.state.store.set_evaluation(
        clip["id"], dict(rating=9, interest=9, context=9, ending=9)
    )
    import json

    assert (
        json.loads(Store(tmp_path / "liveclip.db").clip(clip["id"])["evaluation"])[
            "rating"
        ]
        == 9
    )
