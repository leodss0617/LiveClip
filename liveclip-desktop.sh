#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "$(readlink -f -- "$0")")"
if ! command -v python3 >/dev/null; then
  sudo apt-get update
  sudo apt-get install -y python3
fi
task_downloads=${LIVECLIP_DOWNLOADS:-}
if [ -z "$task_downloads" ]; then
  task_downloads=$(xdg-user-dir DOWNLOAD 2>/dev/null || printf '%s/Downloads' "$HOME")
fi
python3 desktop-update.py "$PWD" "$task_downloads"
if ! command -v docker >/dev/null || ! docker compose version >/dev/null 2>&1; then
  exec bash instalar-zorin.sh "$@"
fi
if ! docker info >/dev/null 2>&1; then
  sudo systemctl start docker
fi
exec bash iniciar.sh "$@"
