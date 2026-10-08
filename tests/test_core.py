import pytest


def test_platform_urls_and_rejection():
    from liveclip.security import validate_url

    assert validate_url("https://youtu.be/abc123")[0] == "youtube"
    assert validate_url("https://www.twitch.tv/channel")[0] == "twitch"
    assert validate_url("https://kick.com/channel")[0] == "kick"
    for url in [
        "http://127.0.0.1",
        "https://youtube.com.evil.org/watch?v=x",
        "https://user:pass@youtube.com/watch?v=x",
        "https://kick.com:123/a",
        "https://youtube.com/redirect?q=http://localhost",
        "file:///etc/passwd",
    ]:
        with pytest.raises(ValueError):
            validate_url(url)


def test_store_survives_restart_and_one_active_session(tmp_path):
    from liveclip.store import Store

    db = tmp_path / "test.db"
    s = Store(db)
    session = s.create_session("https://kick.com/test", "kick")
    with pytest.raises(ValueError):
        s.create_session("https://twitch.tv/test", "twitch")
    s.update_session(session["id"], status="stopped")
    again = Store(db)
    assert again.sessions()[0]["status"] == "stopped"
    assert (
        again.create_session("https://twitch.tv/test", "twitch")["id"] != session["id"]
    )


def test_clip_deduplication_and_checkpoint(tmp_path):
    from liveclip.store import Store

    s = Store(tmp_path / "test.db")
    session = s.create_session("https://kick.com/test", "kick")
    clip = s.add_clip(session["id"], 10, 40, "História", "Contexto", 0.8)
    assert clip and not s.add_clip(session["id"], 20, 45, "Repetido", "Contexto", 0.9)
    s.update_clip(clip["id"], status="ready", filename=clip["id"] + ".mp4")
    assert Store(tmp_path / "test.db").clips()[0]["status"] == "ready"
    s.checkpoint(session["id"], 180)
    assert s.session(session["id"])["analyzed_until"] == 180
