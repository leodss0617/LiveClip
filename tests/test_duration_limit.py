import threading

import pytest

from liveclip.capture import Capture
from liveclip.settings import Settings
from liveclip.store import Store


@pytest.mark.parametrize("platform", ["youtube", "kick", "twitch"])
def test_session_allows_fifteen_hours(tmp_path, platform):
    session = Store(tmp_path / "db").create_session("https://example.com", platform)
    assert session["expires"] - session["created"] == 54000


def test_capture_imports_beyond_ten_hours_and_caps_at_fifteen(tmp_path):
    store = Store(tmp_path / "db")
    session = store.create_session("https://kick.com/test", "kick")
    capture = Capture(
        store,
        Settings(data=tmp_path, password="duration-test-password"),
        session,
        threading.Event(),
    )
    attempt = tmp_path / "attempt"
    attempt.mkdir()
    (attempt / "part.ts").write_bytes(b"test")
    (attempt / "segments.csv").write_text("part.ts,0,20\n")
    assert capture.import_manifest(attempt, 53990) == 54000
    assert store.segments(session["id"])[0]["duration"] == 10
