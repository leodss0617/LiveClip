"""Verify a local model really answers; this is not an editorial quality score."""

import json
import os
import subprocess
import sys
import time

from .intelligence import Intelligence
from .settings import Settings


def verify_model(settings):
    stamp = settings.data / "model-check.json"
    stamp.unlink(missing_ok=True)
    started = time.monotonic()
    ai = Intelligence(settings)
    ai.report = lambda stage, detail, *_: print(detail, flush=True)
    answer = ai._chat(
        'Responda somente este objeto JSON: {"liveclip_check":"ok"}.',
        {"task": "Teste de resposta JSON do modelo local."},
        context_tokens=1024,
        max_tokens=32,
    )
    if answer.get("liveclip_check") != "ok":
        raise RuntimeError(
            "A IA respondeu, mas falhou no teste JSON. Confira logs-native/model-test.log."
        )
    result = dict(
        ok=True,
        model=settings.ollama_model,
        checked_at=time.time(),
        seconds=round(time.monotonic() - started, 2),
    )
    partial = stamp.with_suffix(".partial")
    partial.write_text(json.dumps(result))
    partial.replace(stamp)
    print(
        "Modelo respondeu ao teste JSON. A qualidade dos cortes depende da análise do vídeo.",
        flush=True,
    )
    return result


def main():
    if "--serve" in sys.argv:
        try:
            result = subprocess.run(
                [sys.executable, "-m", "liveclip.model_check"], timeout=300
            )
        except subprocess.TimeoutExpired:
            print(
                "Teste da IA excedeu 5 minutos. Verifique memória e logs.",
                file=sys.stderr,
            )
            result = None
        if result is None or result.returncode:
            os.environ["LIVECLIP_PROCESSING_ENABLED"] = "false"
            os.environ["LIVECLIP_PROCESSING_ERROR"] = (
                "A IA falhou no teste inicial. Processamento pausado; seus cortes continuam disponíveis. Confira os logs e reinicie o sistema para tentar novamente."
            )
        args = [
            sys.executable,
            "-m",
            "uvicorn",
            "liveclip.app:create_app",
            "--factory",
            "--host",
            "0.0.0.0",
            "--port",
            "8080",
            "--workers",
            "1",
            "--timeout-graceful-shutdown",
            "30",
            "--no-access-log",
        ]
        os.execv(sys.executable, args)
    try:
        verify_model(Settings())
    except (RuntimeError, ValueError, OSError) as error:
        print(str(error), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
