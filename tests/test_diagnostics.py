from liveclip.capture import capture_message


def test_platform_restrictions_are_reported():
    assert "429" in capture_message("429 Client Error: Too Many Requests")
    assert "certificado" in capture_message("CERTIFICATE_VERIFY_FAILED")
    assert "não está entregando" in capture_message("No playable streams found")


def test_diagnostic_includes_model_runtime_and_saved_transcript(tmp_path, monkeypatch):
    import io
    import json

    from test_api import client, login

    from liveclip.store import Store

    store = Store(tmp_path / "liveclip.db")
    s = store.create_session("https://kick.com/test", "kick")
    work = tmp_path / "work" / s["id"]
    work.mkdir(parents=True)
    (work / "transcription.json").write_text(
        json.dumps(
            {
                "transcript": [{"start": 0, "end": 10, "text": "Fala do teste"}],
                "words": [{"word": "Fala"}],
            }
        )
    )
    monkeypatch.setattr(
        "urllib.request.urlopen",
        lambda request, **kw: io.BytesIO(
            json.dumps(
                {"models": []} if str(request).endswith("/ps") else {"version": "test"}
            ).encode()
        ),
    )
    with client(tmp_path) as c:
        login(c)
        result = c.get("/api/sessions/" + s["id"] + "/diagnostic")
        assert result.status_code == 200
        body = result.json()
        assert body["runtime"]["ollama"]["ps"] == {"models": []}
        assert body["runtime"]["ollama"]["version"]["version"] == "test"
        assert body["transcription"]["passages"][0]["text"] == "Fala do teste"
        assert body["transcription"]["words_count"] == 1
