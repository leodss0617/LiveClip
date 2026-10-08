import hashlib
import json
import sys
from pathlib import Path
from types import SimpleNamespace

from .intelligence import Intelligence, SelectionError
from .editorial import grade_candidates, technical_rating
from .audio_events import (
    classify_music,
    music_candidates,
    protect_candidates,
    merge_candidates,
)
from .media import concat_segments, probe, render


def main():
    kind, request, result = sys.argv[1:]
    request, result = Path(request), Path(result)
    p = json.loads(request.read_text())
    activity = request.parent / "activity.json"
    stage = "preparing"

    def report(name, detail, progress=None):
        nonlocal stage
        stage = name
        print(
            json.dumps(
                {"stage": name, "detail": detail, "progress": progress},
                ensure_ascii=False,
            ),
            flush=True,
        )
        partial = activity.with_suffix(".partial")
        partial.write_text(
            json.dumps(
                dict(stage=name, detail=detail, progress=progress), ensure_ascii=False
            )
        )
        partial.replace(activity)

    try:
        report(
            "preparing",
            "Preparando o trecho gravado para análise."
            if kind == "analysis"
            else "Preparando o vídeo selecionado.",
        )
        concat_segments(p["segments"], p["source"])
        if kind == "analysis":
            s = SimpleNamespace(
                **{
                    k: p[k]
                    for k in (
                        "whisper_model",
                        "cpu_threads",
                        "ollama_model",
                        "ollama_url",
                    )
                },
                data=Path(p["data"]),
            )
            duration = float(probe(p["source"])["format"]["duration"])
            spans = classify_music(p["source"], duration, s.cpu_threads, report)
            musical = music_candidates(spans, duration)
            ai = Intelligence(s)
            ai.knowledge = p.get("knowledge", {})
            report(
                "loading_model",
                "Carregando transcrição local. O primeiro uso pode baixar o modelo.",
            )
            cache = request.parent / "transcription.json"
            key = hashlib.sha256(
                json.dumps(
                    {"segments": p["segments"], "whisper_model": p["whisper_model"]},
                    sort_keys=True,
                ).encode()
            ).hexdigest()
            cached = None
            try:
                candidate = json.loads(cache.read_text())
                if (
                    candidate.get("key") == key
                    and isinstance(candidate.get("transcript"), list)
                    and isinstance(candidate.get("words"), list)
                ):
                    cached = candidate
            except (OSError, ValueError, TypeError):
                pass
            if cached is not None:
                transcript, words = cached["transcript"], cached["words"]
                report(
                    "transcribing",
                    "Transcrição já concluída: reutilizando o trecho salvo, sem transcrever novamente.",
                    100,
                )
            else:
                transcript, words = ai.transcribe(p["source"], report=report)
                partial_cache = cache.with_suffix(".partial")
                partial_cache.write_text(
                    json.dumps({"key": key, "transcript": transcript, "words": words})
                )
                partial_cache.replace(cache)
            report(
                "selecting",
                f"IA lendo {len(transcript)} falas para procurar histórias completas.",
            )
            candidates = ai.select(transcript, duration, report=report)
            candidates, pending = protect_candidates(
                candidates, spans, duration, final=p.get("final", False)
            )
            ai.chat_stage = "grading"
            report(
                "grading",
                "Atribuindo notas de interesse, contexto e desfecho de 0 a 10.",
            )
            graded, reviews = grade_candidates(
                ai._chat,
                merge_candidates(candidates + musical),
                transcript,
                ai.knowledge,
            )
            output = {
                "words": words,
                "candidates": graded,
                "reviews": reviews,
                "pending_music_start": pending,
                "music_events": len(spans),
            }

        elif kind == "render":
            report(
                "rendering",
                "Detectando a câmera, compondo o vídeo vertical e aplicando legendas.",
            )
            output = {
                "layout": render(
                    p["source"], p["start"], p["end"], p["words"], p["out"]
                )
            }
            output["technical"] = technical_rating(
                probe(p["out"]), p["start"], p["end"], p["words"], output["layout"]
            )
            if output["technical"]["rating"] == 0:
                Path(p["out"]).unlink(missing_ok=True)
                raise ValueError(
                    "Edição reprovada: formato, duração ou áudio inválidos."
                )
        else:
            raise ValueError("Tipo de trabalho inválido.")
        partial = result.with_suffix(".partial.json")
        partial.write_text(json.dumps(output, ensure_ascii=False))
        partial.replace(result)
    except Exception as error:
        messages = {
            "preparing": "Preparação do vídeo falhou. Verifique se a gravação existe e se o FFmpeg está instalado.",
            "listening": "Análise de música falhou. Verifique o modelo de áudio do ZIP e o FFmpeg.",
            "loading_model": "Não foi possível carregar o modelo de transcrição. Verifique internet no primeiro download, memória livre e espaço.",
            "transcribing": "Transcrição falhou. Verifique memória livre e o áudio da gravação.",
            "selecting": "Seleção pela IA falhou. Verifique se o Ollama e o modelo estão disponíveis; no celular a resposta pode exceder o tempo limite.",
            "grading": "Avaliação de qualidade falhou. O trecho será repetido sem descartar a gravação.",
            "reviewing": "Revisão da história falhou. Verifique se o modelo local está respondendo e se há memória livre.",
            "rendering": "Edição falhou. Verifique memória, espaço livre e FFmpeg.",
        }
        (request.parent / "error.json").write_text(
            json.dumps(
                {
                    "detail": str(error)
                    if isinstance(error, SelectionError)
                    else messages.get(
                        stage, "Processamento falhou. Consulte o diagnóstico."
                    )
                }
            )
        )
        raise


if __name__ == "__main__":
    main()
