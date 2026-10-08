from liveclip.intelligence import validate_candidates


def test_complete_boolean_alone_does_not_prove_story():
    t = [
        dict(start=0, end=20, text="Ele entrou e fez isso."),
        dict(start=20, end=40, text="Aí todo mundo ficou olhando."),
        dict(start=40, end=60, text="No fim ele devolveu e pediu desculpas."),
    ]
    item = dict(
        start=0,
        end=60,
        title="Um pedido de desculpas",
        reason="História",
        score=0.9,
        complete=True,
    )
    assert validate_candidates({"clips": [item]}, t, 60, require_arc=True) == []


def test_arc_requires_opening_development_and_ending_in_order():
    t = [
        dict(start=0, end=20, text="Ontem meu amigo levou minha moto."),
        dict(start=20, end=40, text="Ele esqueceu a chave na loja e precisou voltar."),
        dict(start=40, end=60, text="No final achou a chave e voltou para casa."),
    ]
    item = dict(
        start=0,
        end=60,
        title="A chave esquecida",
        reason="História completa",
        score=0.9,
        complete=True,
        arc=dict(
            opening_index=0,
            development_index=1,
            ending_index=2,
            starts_mid_story=False,
            ends_mid_story=False,
        ),
    )
    assert len(validate_candidates({"clips": [item]}, t, 60, require_arc=True)) == 1
    item["arc"]["starts_mid_story"] = True
    assert not validate_candidates({"clips": [item]}, t, 60, require_arc=True)
    item["arc"]["starts_mid_story"] = False
    item["arc"]["ending_index"] = 0
    assert not validate_candidates({"clips": [item]}, t, 60, require_arc=True)


def test_camera_face_is_not_combined_with_game_character():
    from liveclip.media import choose_camera_face

    assert choose_camera_face([(30, 20, 70, 75), (700, 300, 140, 150)], 1280, 720) == (
        30,
        20,
        70,
        75,
    )


def test_camera_track_does_not_jump_to_distant_character():
    from liveclip.media import choose_camera_face

    assert (
        choose_camera_face([(700, 300, 140, 150)], 1280, 720, previous=(30, 20, 70, 75))
        is None
    )


def test_story_reviewer_vetoes_mid_story_despite_selection(monkeypatch):
    from types import SimpleNamespace

    from liveclip.intelligence import Intelligence

    transcript = [
        dict(start=0, end=20, text="Ele fez aquilo."),
        dict(start=20, end=40, text="Depois eles voltaram."),
        dict(start=40, end=60, text="Então terminou."),
    ]
    ai = Intelligence(
        SimpleNamespace(ollama_model="local", ollama_url="http://localhost:11434")
    )
    calls = []

    def reply(system, data):
        calls.append(data)
        if len(calls) == 1:
            return {
                "clips": [
                    dict(
                        title="Um retorno",
                        reason="Uma história",
                        score=0.9,
                        complete=True,
                        arc=dict(
                            opening_index=0,
                            development_index=1,
                            ending_index=2,
                            starts_mid_story=False,
                            ends_mid_story=False,
                        ),
                    )
                ]
            }
        return dict(approved=False, starts_mid_story=True, ends_mid_story=False)

    monkeypatch.setattr(ai, "_chat", reply)
    assert ai.select(transcript, 60) == []
    assert len(calls) == 2


def test_actual_render_places_camera_above_full_content(tmp_path, monkeypatch):
    import subprocess

    import cv2

    from liveclip import media

    source = tmp_path / "reaction.mp4"
    subprocess.run(
        [
            "ffmpeg",
            "-v",
            "error",
            "-f",
            "lavfi",
            "-i",
            "color=c=green:s=640x360:r=10,drawbox=x=20:y=20:w=130:h=90:color=red:t=fill",
            "-t",
            "1",
            "-c:v",
            "libx264",
            "-pix_fmt",
            "yuv420p",
            str(source),
        ],
        check=True,
    )
    # Isolate the rendering contract from detector uncertainty.
    monkeypatch.setattr(
        media, "face_track", lambda *_: (640, 360, [(0, 40, 35, 35, 35)])
    )
    out = tmp_path / "edited.mp4"
    assert media.render(source, 0, 1, [], out) == "reacao"
    cap = cv2.VideoCapture(str(out))
    ok, frame = cap.read()
    cap.release()
    assert ok
    upper = frame[340, 540]
    lower = frame[1300, 540]
    assert int(upper[2]) > int(upper[1]) + 80, "Camera must be in upper panel"
    assert int(lower[1]) > int(lower[2]) + 40, "Watched video must be below camera"
