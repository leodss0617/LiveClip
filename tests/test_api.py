from fastapi.testclient import TestClient


def client(tmp_path):
    from liveclip.app import create_app
    from liveclip.settings import Settings

    return TestClient(
        create_app(
            Settings(
                data=tmp_path, password="a-strong-password-123", start_worker=False
            )
        )
    )


def login(c):
    return c.post("/api/login", json={"password": "a-strong-password-123"})


def test_auth_and_session_validation(tmp_path):
    with client(tmp_path) as c:
        assert c.get("/api/sessions").status_code == 401
        assert c.post("/api/login", json={"password": "wrong"}).status_code == 401
        assert login(c).status_code == 200
        assert (
            c.post("/api/sessions", json={"url": "http://localhost"}).status_code == 422
        )
        r = c.post("/api/sessions", json={"url": "https://kick.com/test"})
        assert r.status_code == 201
        assert (
            c.post("/api/sessions", json={"url": "https://twitch.tv/test"}).status_code
            == 409
        )
        assert c.post("/api/sessions/" + r.json()["id"] + "/stop").status_code == 200
        assert c.get("/api/clips/unknown/download").status_code == 404
        assert c.post("/api/logout").status_code == 200
        assert c.get("/api/clips").status_code == 401


def test_cross_site_mutation_is_blocked(tmp_path):
    with client(tmp_path) as c:
        login(c)
        assert (
            c.post(
                "/api/sessions",
                json={"url": "https://kick.com/test"},
                headers={"Origin": "https://evil.example"},
            ).status_code
            == 403
        )


def test_download_is_protected_and_cannot_escape_data(tmp_path):
    with client(tmp_path) as c:
        login(c)
        store = c.app.state.store
        session = store.create_session("https://kick.com/test", "kick")
        clip = store.add_clip(session["id"], 0, 20, "Clip", "Contexto", 0.9)
        store.update_clip(clip["id"], status="ready", filename="../../secret.mp4")
        assert c.get("/api/clips/" + clip["id"] + "/download").status_code == 404


def test_security_headers_and_plaintext_password_not_sent(tmp_path):
    with client(tmp_path) as c:
        r = c.get("/")
        assert r.status_code == 200
        assert r.headers["x-content-type-options"] == "nosniff"
        assert "a-strong-password-123" not in r.text


def test_authenticated_video_range_and_download(tmp_path):
    with client(tmp_path) as c:
        login(c)
        s = c.app.state.store.create_session("https://kick.com/test", "kick")
        clip = c.app.state.store.add_clip(s["id"], 0, 20, "Teste", "Teste", 0.9)
        c.app.state.store.update_clip(
            clip["id"], status="ready", filename=clip["id"] + ".mp4"
        )
        folder = tmp_path / "clips"
        folder.mkdir()
        (folder / (clip["id"] + ".mp4")).write_bytes(
            b"0123456789abcdefghijklmnopqrstuv"
        )
        r = c.get("/api/clips/" + clip["id"] + "/video", headers={"Range": "bytes=0-9"})
        assert r.status_code == 206 and r.content == b"0123456789"
        download = c.get("/api/clips/" + clip["id"] + "/download")
        assert (
            download.status_code == 200
            and "attachment" in download.headers["content-disposition"]
        )
