#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "$0")"
trap 'echo "Falha na instalação. Copie o erro acima; seus vídeos foram preservados." >&2' ERR
if [ ! -f /etc/os-release ]; then echo 'Entre no Ubuntu primeiro. Não execute no Termux puro.'; exit 1; fi
. /etc/os-release
if [ "${ID:-}" != ubuntu ]; then echo 'Este instalador requer Ubuntu dentro do Termux.'; exit 1; fi
case "$(uname -m)" in aarch64|arm64) task_arch=arm64;; x86_64) task_arch=amd64;; *) echo 'Requer Android/Ubuntu de 64 bits (ARM64 ou x86_64).'; exit 1;; esac
if [ "$(id -u)" -eq 0 ]; then task_apt=(apt-get); else task_apt=(sudo apt-get); fi
"${task_apt[@]}" update
"${task_apt[@]}" install -y python3 python3-venv python3-pip ffmpeg fonts-dejavu-core curl ca-certificates zstd libgomp1
bash preparar-python.sh
.venv312/bin/python -m pip install --upgrade pip
# Require published wheels for the native extensions: stop instead of an enormous phone build.
.venv312/bin/python -m pip install --only-binary=:all: av==16.1.0 opencv-python-headless==4.13.0.92 ctranslate2==4.8.2
.venv312/bin/python -m pip install -r requirements.txt
mkdir -p .local-ollama
if [ ! -x .local-ollama/bin/ollama ]; then
  task_archive=$(mktemp)
  trap 'rm -f "$task_archive"' EXIT
  curl --fail --location --retry 3 "https://ollama.com/download/ollama-linux-${task_arch}.tar.zst" -o "$task_archive"
  tar --zstd -xf "$task_archive" -C .local-ollama
fi
export PATH="$PWD/.local-ollama/bin:$PATH"
.venv312/bin/python -m liveclip.native --check
echo 'Instalado. Agora execute: bash iniciar-ubuntu-termux.sh'
