import json

import pytest

from liveclip import job
from liveclip.intelligence import SelectionError


def test_failed_selection_retries_without_transcribing_again(tmp_path, monkeypatch):
    payload = dict(
        segments=[],
        source=str(tmp_path / "source.mkv"),
        data=str(tmp_path),
        whisper_model="tiny",
        cpu_threads=2,
        ollama_model="test",
        ollama_url="http://localhost",
    )
    request = tmp_path / "job.json"
    request.write_text(json.dumps(payload))
    result = tmp_path / "result.json"
    monkeypatch.setattr("sys.argv", ["job", "analysis", str(request), str(result)])
    monkeypatch.setattr(job, "concat_segments", lambda *a: None)
    monkeypatch.setattr(job, "probe", lambda *a: {"format": {"duration": 90}})
    monkeypatch.setattr(job, "classify_music", lambda *a: [])
    calls = []
    monkeypatch.setattr(
        job.Intelligence,
        "transcribe",
        lambda *a, **kw: (
            calls.append("transcribe") or ([dict(start=0, end=20, text="Teste")], [])
        ),
    )

    def fail(*a, **kw):
        raise SelectionError("Ollama ficou sem memória")

    monkeypatch.setattr(job.Intelligence, "select", fail)
    with pytest.raises(SelectionError):
        job.main()
    assert "sem memória" in json.loads((tmp_path / "error.json").read_text())["detail"]
    monkeypatch.setattr(job.Intelligence, "select", lambda *a, **kw: [])
    job.main()
    assert calls == ["transcribe"]
    assert json.loads(result.read_text())["candidates"] == []
