from liveclip.knowledge import Knowledge


def test_knowledge_survives_restart_and_does_not_confirm_own_scores(tmp_path):
    memory = Knowledge(tmp_path / "memory.db")
    memory.record(
        "clip1", "render", dict(title="Teste", rating=9, status="ready", duration=40)
    )
    context = Knowledge(tmp_path / "memory.db").context()
    assert context["rendered"] == 1
    assert context["examples"] == []
    memory.feedback("clip1", 2, "Ficou sem contexto")
    context = Knowledge(tmp_path / "memory.db").context()
    assert context["examples"][0]["user_rating"] == 2
    assert context["examples"][0]["note"] == "Ficou sem contexto"


def test_repeated_feedback_replaces_old_rating(tmp_path):
    memory = Knowledge(tmp_path / "memory.db")
    memory.record(
        "x", "render", dict(title="Teste", rating=8, status="ready", duration=30)
    )
    memory.feedback("x", 8, "Bom")
    memory.feedback("x", 1, "Revisei")
    assert len(memory.context()["examples"]) == 1
    assert memory.context()["examples"][0]["user_rating"] == 1


def test_memory_is_bounded_and_can_be_cleared(tmp_path):
    memory = Knowledge(tmp_path / "memory.db")
    for i in range(1005):
        memory.record(str(i), "analysis", dict(selected=0))
    assert memory.context()["records"] == 1000
    memory.clear()
    assert memory.context()["records"] == 0


def test_automatic_experience_is_available_but_not_human_confirmation(tmp_path):
    memory = Knowledge(tmp_path / "memory.db")
    memory.record(
        "analysis1",
        "analysis",
        dict(
            selected=0, reviews=[dict(accepted=False, rating=2, reason="Sem desfecho")]
        ),
    )
    memory.record(
        "clip1", "render", dict(status="ready", technical={"checks": {"camera": False}})
    )
    context = memory.context()
    assert (
        context["recent_rejections"][0]["source"] == "model_estimate_not_human_feedback"
    )
    assert context["technical_gaps"]["camera"] == 1
    assert context["examples"] == []
