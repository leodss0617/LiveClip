"""Update only application files, retaining user data and models."""

import fcntl
import os
import shutil
import signal
import sys
import time
from pathlib import Path

RUNTIME = {
    "native.json",
    "data",
    "data-native",
    "logs-native",
    ".venv312",
    ".local-ollama",
    ".native.lock",
}


def atomic_copy(source, target):
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = target.with_name(target.name + ".updating")
    try:
        shutil.copy2(source, temporary)
        os.replace(temporary, target)
    finally:
        temporary.unlink(missing_ok=True)


def copy_update(source, target, backup):
    files = sorted(p for p in source.rglob("*") if p.is_file())
    for path in files:
        rel = path.relative_to(source)
        if path.is_symlink() or rel.parts[0] in RUNTIME or rel.parts[0].startswith("."):
            raise ValueError(
                "O pacote contém arquivos de dados/configuração; atualização recusada."
            )
        dest = target / rel
        if any(p.is_symlink() for p in [dest, *dest.parents]):
            raise ValueError("Destino com link simbólico; atualização recusada.")
    requirements = source / "requirements.txt"
    old = target / "requirements.txt"
    needs_install = not old.exists() or (
        requirements.exists() and requirements.read_bytes() != old.read_bytes()
    )
    target.mkdir(parents=True, exist_ok=True)
    if (
        shutil.disk_usage(target).free
        < sum(p.stat().st_size for p in files) * 3 + 1048576
    ):
        raise OSError("Espaço insuficiente para atualização e backup.")
    existed = set()
    for path in files:
        rel = path.relative_to(source)
        dest = target / rel
        if dest.exists():
            saved = backup / rel
            saved.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(dest, saved)
            existed.add(rel)
    touched = []
    try:
        for path in files:
            rel = path.relative_to(source)
            touched.append(rel)
            atomic_copy(path, target / rel)
    except BaseException:
        for rel in reversed(touched):
            if rel in existed:
                shutil.copy2(backup / rel, target / rel)
            else:
                (target / rel).unlink(missing_ok=True)
        raise
    return needs_install


def native_pids(target):
    for folder in Path("/proc").iterdir():
        if not folder.name.isdigit() or int(folder.name) == os.getpid():
            continue
        try:
            argv = (folder / "cmdline").read_bytes().split(b"\0")
            if any(
                argv[i : i + 2] == [b"-m", b"liveclip.native"]
                for i in range(len(argv) - 1)
            ) and os.path.samestat((folder / "cwd").stat(), target.stat()):
                yield int(folder.name)
        except (OSError, RuntimeError):
            continue


def main():
    source = Path(sys.argv[1])
    target = Path("/root/LiveClip")
    target.mkdir(exist_ok=True)
    with (target / ".native.lock").open("a+") as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            print("Encerrando o LiveClip anterior e preservando os vídeos…", flush=True)
            for pid in native_pids(target):
                try:
                    os.kill(pid, signal.SIGTERM)
                except ProcessLookupError:
                    pass
            deadline = time.monotonic() + 90
            while True:
                try:
                    fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
                    break
                except BlockingIOError:
                    if time.monotonic() >= deadline:
                        raise RuntimeError(
                            "O LiveClip anterior não encerrou. Arquivos não foram alterados. Tente novamente após Ctrl+C na sessão antiga."
                        )
                    time.sleep(0.5)
        backup = Path("/root/.liveclip-backups") / str(time.time_ns())
        needs_install = copy_update(source, target, backup)
        if needs_install or not (target / ".venv312/bin/python").is_file():
            (target / ".install-required").touch()
        print("Arquivos atualizados. Vídeos, senha e modelos preservados.", flush=True)


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, RuntimeError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
