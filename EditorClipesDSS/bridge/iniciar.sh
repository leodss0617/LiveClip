#!/data/data/com.termux/files/usr/bin/bash
set -eu
project=$(cd "$(dirname "$(realpath "$0")")" && pwd)
native=()
if [ -d "$HOME/ferramentas/whisper.cpp" ]; then
    native=(--bind "$HOME/ferramentas/whisper.cpp:/opt/editor-whisper")
fi
exec proot-distro login "${native[@]}" --bind "$HOME/.maquina-de-cortes:/root/.maquina-de-cortes" --bind "$HOME/maquina-de-cortes-android/maquina-de-cortes:/opt/maquina-de-cortes" --bind "/storage/emulated/0/Download:/mnt/downloads" --bind "$project:/opt/editor-bridge" maquina-cortes -- bash -lc '
    set -eu
    umask 077
    export EDITOR_TERMUX_HOME="/data/data/com.termux/files/home"
    export PATH=/opt/maquina-venv/bin:$PATH HF_HUB_OFFLINE=1
    if [ ! -s /root/.editorclipes-token ]; then
        python -c "import secrets; print(secrets.token_urlsafe(24))" > /root/.editorclipes-token
    fi
    if [ "${1:-}" = codigo ]; then cat /root/.editorclipes-token; exit; fi
    exec python /opt/editor-bridge/bridge.py
' editor "$@"
