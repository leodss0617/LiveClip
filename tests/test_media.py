import subprocess

import pytest


def test_candidates_reject_hallucination_and_incomplete_context():
    from liveclip.intelligence import validate_candidates

    transcript = [
        {"start": 0, "end": 22, "text": "Uma história completa."},
        {"start": 25, "end": 48, "text": "Outro momento completo."},
    ]
    raw = {
        "clips": [
            {
                "start": 0,
                "end": 22,
                "title": "História",
                "reason": "Completa",
                "score": 0.9,
                "complete": True,
            },
            {
                "start": 25,
                "end": 90,
                "title": "Fora",
                "reason": "",
                "score": 0.9,
                "complete": True,
            },
            {
                "start": 25,
                "end": 48,
                "title": "Aberta",
                "reason": "",
                "score": 0.9,
                "complete": False,
            },
        ]
    }
    result = validate_candidates(raw, transcript, 50)
    assert len(result) == 1 and result[0]["end"] == 22
    assert validate_candidates({"clips": []}, transcript, 50) == []
    with pytest.raises(ValueError):
        validate_candidates({"clips": "invalid"}, transcript, 50)


def test_ass_cannot_inject_drawing_commands(tmp_path):
    from liveclip.media import write_ass

    p = tmp_path / "captions.ass"
    write_ass([{"start": 0, "end": 1, "word": "{\\p1}Olá\\N mundo"}], 0, 2, p)
    content = p.read_text()
    assert "{\\p1}" not in content
    assert "Olá" in content
    assert "PlayResX: 1080" in content


@pytest.fixture
def source(tmp_path):
    p = tmp_path / "source.mp4"
    subprocess.run(
        [
            "ffmpeg",
            "-v",
            "error",
            "-f",
            "lavfi",
            "-i",
            "testsrc2=size=640x360:rate=15",
            "-f",
            "lavfi",
            "-i",
            "sine=frequency=440:sample_rate=48000",
            "-t",
            "3",
            "-c:v",
            "libx264",
            "-pix_fmt",
            "yuv420p",
            "-c:a",
            "aac",
            str(p),
        ],
        check=True,
    )
    return p


def test_render_actual_vertical_mp4_and_audio(source, tmp_path):
    from liveclip.media import probe, render

    out = tmp_path / "clip.mp4"
    result = render(source, 0, 2, [{"start": 0, "end": 1, "word": "Teste"}], out)
    info = probe(out)
    video = next(x for x in info["streams"] if x["codec_type"] == "video")
    assert (video["width"], video["height"]) == (1080, 1920)
    assert any(x["codec_type"] == "audio" for x in info["streams"])
    assert 1.8 <= float(info["format"]["duration"]) <= 2.2
    assert result in ("integral", "rosto", "reacao")


def test_render_without_audio_is_valid(source, tmp_path):
    from liveclip.media import probe, render

    silent = tmp_path / "silent.mp4"
    subprocess.run(
        [
            "ffmpeg",
            "-v",
            "error",
            "-i",
            str(source),
            "-an",
            "-c:v",
            "copy",
            str(silent),
        ],
        check=True,
    )
    out = tmp_path / "silent-clip.mp4"
    render(silent, 0, 1, [], out)
    assert float(probe(out)["format"]["duration"]) > 0.9
