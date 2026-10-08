import pytest
from liveclip.editorial import grade_candidates


def test_low_interest_music_is_not_exported():
    candidate = dict(start=5, end=80, title="Música", reason="Áudio", score=0.95)
    call = lambda *a: dict(
        ratings=[
            dict(index=0, interest=2, context=9, ending=9, reason="Sem relevância")
        ]
    )
    accepted, reviews = grade_candidates(call, [candidate], [], {})
    assert accepted == [] and reviews[0]["rating"] < 7


def test_ending_below_seven_is_rejected_even_with_high_average():
    call = lambda *a: dict(
        ratings=[dict(index=0, interest=10, context=10, ending=6, reason="Fim incerto")]
    )
    assert (
        grade_candidates(
            call, [dict(start=0, end=40, title="Reação", score=0.9)], [], {}
        )[0]
        == []
    )


def test_grade_cannot_be_nan_or_missing_or_boolean():
    for value in [float("nan"), True, -1, 11]:
        call = lambda *a: dict(
            ratings=[dict(index=0, interest=value, context=9, ending=9, reason="Teste")]
        )
        with pytest.raises(ValueError):
            grade_candidates(
                call, [dict(start=0, end=40, title="Evento", score=0.9)], [], {}
            )


def test_high_quality_candidate_has_explicit_ten_point_rating():
    call = lambda *a: dict(
        ratings=[
            dict(
                index=0,
                interest=9,
                context=9,
                ending=9,
                reason="Preparação e desfecho claros",
            )
        ]
    )
    approved, reviews = grade_candidates(
        call, [dict(start=0, end=40, title="Evento", score=0.9)], [], {}
    )
    assert approved[0]["score"] == 0.9 and approved[0]["rating"] == 9
    assert reviews[0]["accepted"] is True


def test_caption_check_does_not_credit_speech_outside_clip():
    from liveclip.editorial import technical_rating

    info = dict(
        format={"duration": 30},
        streams=[
            dict(codec_type="video", width=1080, height=1920),
            dict(codec_type="audio"),
        ],
    )
    quality = technical_rating(
        info, 40, 70, [dict(start=0, end=5, word="fora")], "reacao"
    )
    assert quality["checks"]["subtitles"] is False
    assert quality["rating"] < 10
    quality = technical_rating(
        info, 40, 70, [dict(start=50, end=55, word="dentro")], "reacao"
    )
    assert quality["rating"] == 10


def test_selector_receives_saved_human_preferences():
    from types import SimpleNamespace
    from liveclip.intelligence import Intelligence

    ai = Intelligence(SimpleNamespace())
    ai.knowledge = {"examples": [{"user_rating": 2, "note": "Começou sem contexto"}]}
    seen = []
    ai._chat = lambda system, data: seen.append(data) or {"clips": []}
    ai.select([dict(start=0, end=20, text="Evento")], 30)
    assert seen[0]["knowledge"]["examples"][0]["note"] == "Começou sem contexto"


def test_wrong_format_cannot_be_called_ready_to_post():
    from liveclip.editorial import technical_rating

    info = dict(
        format={"duration": 30},
        streams=[
            dict(codec_type="video", width=1280, height=720),
            dict(codec_type="audio"),
        ],
    )
    assert (
        technical_rating(info, 0, 30, [dict(start=1, end=2, word="teste")], "reacao")[
            "rating"
        ]
        == 0
    )
