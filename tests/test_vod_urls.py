import pytest

from liveclip.security import validate_url

UUID = "4acba712-a1cd-45b0-8ce0-4b534d4c271f"


@pytest.mark.parametrize(
    "url,platform,canonical",
    [
        (
            f"https://kick.com/coringa/videos/{UUID}?source=share",
            "kick",
            f"https://kick.com/coringa/videos/{UUID}",
        ),
        (
            f"https://www.kick.com/coringa/videos/{UUID}/",
            "kick",
            f"https://kick.com/coringa/videos/{UUID}",
        ),
        (
            "https://www.twitch.tv/videos/123456789?t=1h",
            "twitch",
            "https://twitch.tv/videos/123456789",
        ),
    ],
)
def test_recording_urls(url, platform, canonical):
    assert validate_url(url) == (platform, canonical)


@pytest.mark.parametrize(
    "url",
    [
        "https://kick.com/coringa/videos/0",
        "https://kick.com/coringa/videos/not-a-uuid",
        "https://twitch.tv/videos/abc",
        "https://kick.com.evil.org/coringa",
        "https://kick.com/coringa/videos/../../admin",
    ],
)
def test_invalid_recording_urls(url):
    with pytest.raises(ValueError):
        validate_url(url)


@pytest.mark.parametrize(
    "url",
    [f"https://kick.com/coringa/videos/{UUID}", "https://twitch.tv/videos/123456789"],
)
def test_capture_plugin_accepts_validated_recordings(url):
    from streamlink import Streamlink

    platform, canonical = validate_url(url)
    name, plugin, resolved = Streamlink().resolve_url(canonical)
    assert name == platform
    assert resolved == canonical
    assert plugin(Streamlink(), canonical).matches["vod"] is not None


def test_kick_recording_can_be_added_through_panel_api(tmp_path):
    from test_api import client, login
    from liveclip.version import VERSION

    with client(tmp_path) as c:
        login(c)
        result = c.post(
            "/api/sessions", json={"url": f"https://kick.com/coringa/videos/{UUID}"}
        )
        assert result.status_code == 201
        assert result.json()["platform"] == "kick"
        assert c.get("/api/health").json()["version"] == VERSION
