import importlib.util
from pathlib import Path

import pytest


def updater():
    path = Path(__file__).parents[1] / "atualizar-nativo.py"
    spec = importlib.util.spec_from_file_location("native_update", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def test_update_preserves_data_and_password(tmp_path):
    source, target = tmp_path / "source", tmp_path / "target"
    source.mkdir()
    target.mkdir()
    (source / "requirements.txt").write_text("same")
    (source / "liveclip").mkdir()
    (source / "liveclip" / "version.py").write_text("new")
    (target / "liveclip").mkdir()
    (target / "liveclip" / "version.py").write_text("old")
    (target / "native.json").write_text("secret")
    (target / "data-native").mkdir()
    (target / "data-native" / "clip.mp4").write_bytes(b"video")
    assert updater().copy_update(source, target, tmp_path / "backup") is True
    assert (target / "native.json").read_text() == "secret"
    assert (target / "data-native" / "clip.mp4").read_bytes() == b"video"
    assert (target / "liveclip" / "version.py").read_text() == "new"
    assert (tmp_path / "backup" / "liveclip" / "version.py").read_text() == "old"


def test_runtime_paths_in_update_rejected(tmp_path):
    source = tmp_path / "source"
    source.mkdir()
    (source / "native.json").write_text("overwrite")
    with pytest.raises(ValueError):
        updater().copy_update(source, tmp_path / "target", tmp_path / "backup")


def test_failed_copy_restores_old_files(tmp_path, monkeypatch):
    module = updater()
    source, target = tmp_path / "source", tmp_path / "target"
    source.mkdir()
    target.mkdir()
    (source / "requirements.txt").write_text("new")
    (target / "requirements.txt").write_text("old")

    def fail(*args):
        raise OSError("disk full")

    monkeypatch.setattr(module, "atomic_copy", fail)
    with pytest.raises(OSError):
        module.copy_update(source, target, tmp_path / "backup")
    assert (target / "requirements.txt").read_text() == "old"


def test_termux_one_command_updates_once_then_reopens(tmp_path):
    import os
    import subprocess
    import sys
    from zipfile import ZipFile

    home = tmp_path / "phone"
    downloads = home / "storage/downloads"
    downloads.mkdir(parents=True)
    binary = tmp_path / "bin"
    binary.mkdir()
    prefix = tmp_path / "prefix"
    (prefix / "bin").mkdir(parents=True)
    calls = tmp_path / "calls"
    (binary / "python").symlink_to(sys.executable)
    for name in ["pkg", "proot-distro", "tmux", "termux-open-url", "termux-wake-lock"]:
        body = '#!/bin/bash\nprintf "%s\\n" "$0 $*" >> "$TASK_CALLS"\n'
        if name == "tmux":
            body += 'if [ "$1" = has-session ]; then test -f "$TASK_SESSION"; exit $?; fi\n'
            body += 'if [ "$1" = new-session ]; then touch "$TASK_SESSION"; fi\n'
            body += 'if [ "$1" = kill-session ]; then rm -f "$TASK_SESSION"; fi\n'
            body += 'if [ "$1" = new-session ]; then printf \"%s\" \"${@: -1}\" > \"$TASK_START\"; fi\n'
        if name == "proot-distro":
            body += 'case "$*" in *"-- test -f "*) test -f "$TASK_RUNTIME/.liveclip-exit"; exit $?;; esac\n'
        (binary / name).write_text(body)
        (binary / name).chmod(0o755)
    launcher = Path(__file__).parents[1] / "liveclip-termux.sh"
    zip_path = downloads / "LiveClip-v0.1.0.zip"
    with ZipFile(zip_path, "w") as z:
        for name in [
            "atualizar-nativo.py",
            "iniciar-ubuntu-termux.sh",
            "liveclip/version.py",
            "requirements.txt",
        ]:
            z.writestr("LiveClip/" + name, "test")
        z.writestr("LiveClip/liveclip-termux.sh", launcher.read_text())
    environment = dict(
        os.environ,
        HOME=str(home),
        PREFIX=str(prefix),
        PATH=str(binary) + ":" + os.environ["PATH"],
        TASK_CALLS=str(calls),
        TASK_START=str(tmp_path / "start-command"),
        TASK_SESSION=str(tmp_path / "session"),
        TASK_RUNTIME=str(tmp_path / "runtime"),
    )
    launcher = Path(__file__).parents[1] / "liveclip-termux.sh"
    first = subprocess.run(
        ["bash", str(launcher)], env=environment, capture_output=True, text=True
    )
    assert first.returncode == 0, first.stdout + first.stderr
    assert (prefix / "bin/liveclip").exists()
    assert "atualizar-nativo.py" in calls.read_text()
    runtime = tmp_path / "runtime"
    (runtime / ".venv312/bin").mkdir(parents=True)
    (runtime / ".venv312/bin/python").symlink_to(sys.executable)
    (runtime / "iniciar-ubuntu-termux.sh").write_text("#!/bin/bash\nexit 7\n")
    start_command = (tmp_path / "start-command").read_text().replace("/root/LiveClip", str(runtime))
    retained = subprocess.run(
        ["bash", "-lc", start_command], input="echo SESSION_STILL_OPEN\nexit\n",
        capture_output=True, text=True, timeout=5,
    )
    assert "SESSION_STILL_OPEN" in retained.stdout
    assert "7" in retained.stdout

    calls.write_text("")
    second = subprocess.run(
        ["bash", str(prefix / "bin/liveclip")],
        env=environment,
        capture_output=True,
        text=True,
    )
    assert second.returncode == 0, second.stdout + second.stderr
    assert "já está instalado" in second.stdout
    assert "atualizar-nativo.py" not in calls.read_text()
    assert "kill-session" in calls.read_text()
    assert "new-session" in calls.read_text()
    with ZipFile(zip_path, "w") as z:
        z.writestr("../outside", "bad")
    third = subprocess.run(
        ["bash", str(prefix / "bin/liveclip")],
        env=environment,
        capture_output=True,
        text=True,
    )
    assert third.returncode != 0
    assert "ZIP recusado" in third.stderr
    assert not (home / ".local/share/outside").exists()
