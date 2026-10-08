"""Linux launcher without Docker/systemd, including Ubuntu inside Termux."""

import argparse
import fcntl
import importlib.util
import json
import os
import secrets
import shutil
import signal
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

from .version import VERSION


def load_config(path):
    path = Path(path)
    if path.exists():
        config = json.loads(path.read_text())
    else:
        config = {
            "LIVECLIP_PASSWORD": secrets.token_urlsafe(24),
            "WHISPER_MODEL": "tiny",
            "OLLAMA_MODEL": "qwen3:4b-instruct-2507-q4_K_M",
            "CPU_THREADS": "2",
            "MIN_FREE_GB": "2",
            "MAX_DATA_GB": "8",
        }
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "w") as out:
            json.dump(config, out, indent=2)
    if (
        not isinstance(config, dict)
        or not isinstance(config.get("LIVECLIP_PASSWORD"), str)
        or len(config["LIVECLIP_PASSWORD"]) < 12
    ):
        raise ValueError(
            "Senha inválida no arquivo native.json. Corrija sem apagar seus dados."
        )
    allowed = {
        "LIVECLIP_PASSWORD",
        "WHISPER_MODEL",
        "OLLAMA_MODEL",
        "CPU_THREADS",
        "MIN_FREE_GB",
        "MAX_DATA_GB",
    }
    if any(k not in allowed or not isinstance(v, str) for k, v in config.items()):
        raise ValueError("Configuração native.json inválida.")
    # Migrate the bundled defaults only; preserve passwords, recordings and custom models.
    if config.get("OLLAMA_MODEL") in {"qwen2.5:1.5b", "qwen2.5:3b", "qwen3:0.6b"}:
        config["OLLAMA_MODEL"] = "qwen3:4b-instruct-2507-q4_K_M"
        temporary = path.with_name(path.name + ".updating")
        fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w") as out:
            os.fchmod(out.fileno(), 0o600)
            json.dump(config, out, indent=2)
            out.flush()
            os.fsync(out.fileno())
        os.replace(temporary, path)
    return config


def dependencies():
    missing = [
        name for name in ("ffmpeg", "ffprobe", "ollama") if not shutil.which(name)
    ]
    missing += [
        name
        for name in ("uvicorn", "fastapi", "cv2", "faster_whisper", "streamlink")
        if importlib.util.find_spec(name) is None
    ]
    if missing:
        raise RuntimeError(
            "Dependências ausentes: "
            + ", ".join(missing)
            + ". Execute bash instalar-ubuntu-termux.sh dentro do Ubuntu."
        )


def available(url):
    try:
        with urllib.request.urlopen(url, timeout=2) as response:
            return response.status == 200
    except Exception:
        return False


def run(host):
    print(f"LiveClip {VERSION}", flush=True)
    dependencies()
    root = Path(__file__).resolve().parents[1]
    os.chdir(root)
    # Held open for the whole lifetime: prevent two workers sharing the database.
    with (root / ".native.lock").open("a+") as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise RuntimeError(
                "LiveClip já está aberto. Para carregar a atualização, encerre a sessão anterior com Ctrl+C e execute este comando novamente."
            )
        config = load_config(root / "native.json")
        data = root / "data-native"
        logs = root / "logs-native"
        logs.mkdir(exist_ok=True)
        env = dict(
            os.environ,
            **config,
            LIVECLIP_DATA=str(data),
            OLLAMA_URL="http://127.0.0.1:11434",
            OLLAMA_HOST="127.0.0.1:11434",
            OLLAMA_NO_CLOUD="1",
            OLLAMA_NUM_PARALLEL="1",
            OLLAMA_MAX_LOADED_MODELS="1",
            SECURE_COOKIE="false",
            HF_HUB_DISABLE_TELEMETRY="1",
        )
        processes = []
        stopping = False

        def stop_handler(*_):
            nonlocal stopping
            stopping = True

        signal.signal(signal.SIGTERM, stop_handler)
        signal.signal(signal.SIGINT, stop_handler)

        def start(command, log):
            with (logs / log).open("a") as output:
                process = subprocess.Popen(
                    command,
                    env=env,
                    stdout=output,
                    stderr=subprocess.STDOUT,
                    start_new_session=True,
                )
            processes.append(process)
            return process

        def wait_ready(url, process, seconds):
            for _ in range(seconds):
                if stopping:
                    raise RuntimeError("Inicialização cancelada.")
                if process.poll() is not None:
                    raise RuntimeError("Serviço parou. Confira logs-native.")
                if available(url):
                    return
                time.sleep(1)
            raise RuntimeError("Serviço não ficou disponível. Confira logs-native.")

        try:
            if available("http://127.0.0.1:8080/api/health"):
                raise RuntimeError(
                    "A porta 8080 já tem um LiveClip ativo; encerre-o antes de iniciar outro."
                )
            if not available(env["OLLAMA_URL"] + "/api/tags"):
                ollama = start(["ollama", "serve"], "ollama.log")
                wait_ready(env["OLLAMA_URL"] + "/api/tags", ollama, 90)
            print(
                "Preparando modelo local. Primeiro download pode demorar.", flush=True
            )
            pull = start(["ollama", "pull", env["OLLAMA_MODEL"]], "model-download.log")
            while pull.poll() is None and not stopping:
                time.sleep(1)
            if stopping or pull.returncode != 0:
                raise RuntimeError(
                    "Download cancelado ou falhou. Consulte logs-native/model-download.log."
                )
            print(
                "Testando resposta real da IA (até 5 minutos). Acompanhe logs-native/model-test.log.",
                flush=True,
            )
            check = start(
                [sys.executable, "-m", "liveclip.model_check"], "model-test.log"
            )
            check_started = time.monotonic()
            check_deadline = check_started + 300
            next_notice = check_started + 15
            while check.poll() is None and not stopping:
                if time.monotonic() >= next_notice:
                    elapsed = int(time.monotonic() - check_started)
                    print(
                        f"Teste da IA: aguardando resposta há {elapsed}s (limite: 300s). O painel ainda não iniciou. Se o terminal encerrar, confira logs-native/model-test.log e ollama.log.",
                        flush=True,
                    )
                    next_notice = time.monotonic() + 15
                if time.monotonic() >= check_deadline:
                    os.killpg(check.pid, signal.SIGTERM)
                    try:
                        check.wait(timeout=2)
                    except subprocess.TimeoutExpired:
                        os.killpg(check.pid, signal.SIGKILL)
                        check.wait(timeout=5)
                    break
                time.sleep(0.5)
            if stopping:
                raise RuntimeError("Inicialização cancelada.")
            if check.returncode != 0:
                env["LIVECLIP_PROCESSING_ENABLED"] = "false"
                env["LIVECLIP_PROCESSING_ERROR"] = (
                    "A IA falhou no teste inicial. Processamento pausado; seus cortes continuam disponíveis. Confira logs-native/model-test.log e execute liveclip para tentar novamente."
                )
                print(env["LIVECLIP_PROCESSING_ERROR"], flush=True)
            api = start(
                [
                    sys.executable,
                    "-m",
                    "uvicorn",
                    "liveclip.app:create_app",
                    "--factory",
                    "--host",
                    host,
                    "--port",
                    "8080",
                    "--workers",
                    "1",
                    "--timeout-graceful-shutdown",
                    "30",
                    "--no-access-log",
                ],
                "studio.log",
            )
            wait_ready("http://127.0.0.1:8080/api/health", api, 90)
            print(
                "Painel: http://127.0.0.1:8080\nSenha: " + config["LIVECLIP_PASSWORD"],
                flush=True,
            )
            print(
                "Mantenha esta sessão aberta no tmux. Logs: logs-native/studio.log",
                flush=True,
            )
            while not stopping:
                if api.poll() is not None or any(
                    p.poll() is not None for p in processes if p not in (pull, check)
                ):
                    raise RuntimeError(
                        "Um serviço encerrou. Confira logs-native e reinicie com iniciar-ubuntu-termux.sh."
                    )
                time.sleep(1)
        finally:
            for process in reversed(processes):
                if process.poll() is None:
                    os.killpg(process.pid, signal.SIGTERM)
                    try:
                        process.wait(timeout=45)
                    except subprocess.TimeoutExpired:
                        os.killpg(process.pid, signal.SIGKILL)
                        process.wait()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    parser.add_argument(
        "--rede", action="store_true", help="Permitir acesso pelo Wi-Fi local"
    )
    args = parser.parse_args()
    try:
        if args.check:
            print(f"LiveClip {VERSION}", flush=True)
            dependencies()
            print("Dependências locais disponíveis.")
        else:
            run("0.0.0.0" if args.rede else "127.0.0.1")
    except (RuntimeError, ValueError, OSError) as error:
        print(str(error), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
