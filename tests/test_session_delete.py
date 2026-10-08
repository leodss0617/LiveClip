from test_api import client, login

from liveclip.store import Store


def test_delete_stopped_session_preserves_ready_clip(tmp_path):
    store = Store(tmp_path / "liveclip.db")
    s = store.create_session("https://kick.com/test", "kick")
    store.update_session(s["id"], status="stopped")
    clip = store.add_clip(s["id"], 0, 20, "Teste", "Teste", 0.9)
    store.update_clip(clip["id"], status="ready", filename=clip["id"] + ".mp4")
    folder = tmp_path / "sessions" / s["id"]
    folder.mkdir(parents=True)
    (folder / "part.ts").write_bytes(b"video")
    with client(tmp_path) as c:
        login(c)
        result = c.delete("/api/sessions/" + s["id"])
        assert result.status_code == 200
        assert c.get("/api/sessions").json() == []
        assert c.get("/api/clips").json()[0]["status"] == "ready"
    assert not folder.exists()


def test_delete_active_session_is_rejected(tmp_path):
    with client(tmp_path) as c:
        login(c)
        s = c.post("/api/sessions", json={"url": "https://kick.com/test"}).json()
        assert c.delete("/api/sessions/" + s["id"]).status_code == 409
        assert len(c.get("/api/sessions").json()) == 1


def test_delete_no_footage_and_missing_session(tmp_path):
    store = Store(tmp_path / "liveclip.db")
    s = store.create_session("https://kick.com/test", "kick")
    store.update_session(s["id"], status="stopped")
    with client(tmp_path) as c:
        assert c.delete("/api/sessions/" + s["id"]).status_code == 401
        login(c)
        assert c.delete("/api/sessions/" + s["id"]).status_code == 200
        assert c.delete("/api/sessions/" + s["id"]).status_code == 404


def test_delete_stopped_session_while_other_session_is_processing(tmp_path):
    store = Store(tmp_path / "liveclip.db")
    old = store.create_session("https://kick.com/old", "kick")
    store.update_session(old["id"], status="stopped")
    with client(tmp_path) as c:
        login(c)
        active = c.post("/api/sessions", json={"url": "https://kick.com/new"}).json()
        c.app.state.engine.operation_lock.acquire()
        try:
            assert c.delete("/api/sessions/" + old["id"]).status_code == 200
            assert c.get("/api/sessions").json()[0]["id"] == active["id"]
        finally:
            c.app.state.engine.operation_lock.release()
