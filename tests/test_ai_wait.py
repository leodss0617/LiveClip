import pytest

from liveclip.worker import AIWaitBudget


def test_heartbeat_does_not_reset_no_output_budget():
    budget = AIWaitBudget()
    budget.check("selecting", "IA lendo 29 falas", 0)
    budget.check("selecting", "IA lendo 29 falas", 299)
    with pytest.raises(RuntimeError, match="sem entregar"):
        budget.check("selecting", "IA lendo 29 falas", 300)


def test_new_output_resets_stall_budget_but_not_total_budget():
    budget = AIWaitBudget()
    budget.check("selecting", "IA lendo", 0)
    for t in [250, 500, 750]:
        budget.check(
            "selecting", f"IA gerando a resposta: {t} partes recebidas do modelo.", t
        )
    with pytest.raises(RuntimeError, match="15 minutos"):
        budget.check(
            "selecting", "IA gerando a resposta: 999 partes recebidas do modelo.", 900
        )


def test_new_review_has_its_own_budget():
    budget = AIWaitBudget()
    budget.check("selecting", "IA lendo", 0)
    budget.check("reviewing", "Revisando candidato", 290)
    budget.check("reviewing", "Revisando candidato", 589)
    with pytest.raises(RuntimeError):
        budget.check("reviewing", "Revisando candidato", 590)
