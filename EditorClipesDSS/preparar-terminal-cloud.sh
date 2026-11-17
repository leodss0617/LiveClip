#!/usr/bin/env bash
# Preparacao segura do terminal Linux do EditorClipesDSS no GitHub Codespaces.
set -Eeuo pipefail
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
cd "$ROOT"
echo "[EditorClipesDSS] Configurando ambiente Linux..."
if ! command -v apt-get >/dev/null 2>&1; then
  echo "Este instalador espera Linux Debian/Ubuntu. Ambiente nao suportado." >&2
  exit 2
fi
packages=()
command -v python3 >/dev/null || packages+=(python3)
command -v ffmpeg >/dev/null || packages+=(ffmpeg)
command -v ffprobe >/dev/null || packages+=(ffmpeg)
command -v git >/dev/null || packages+=(git)
if (("${#packages[@]}" > 0)); then
  if command -v sudo >/dev/null 2>&1; then
    sudo apt-get update
    sudo DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends "${packages[@]}"
  else
    echo "Faltam programas e sudo nao esta disponivel: ${packages[*]}" >&2
    exit 3
  fi
fi
mkdir -p "$ROOT/.workspace-tests"
python3 --version
ffmpeg -version | head -n 1
git --version
OUT="$ROOT/.workspace-tests/teste-vertical.mp4"
ffmpeg -hide_banner -loglevel error -nostdin -f lavfi -i 'testsrc2=size=360x640:rate=15' -t 2 -c:v libx264 -preset ultrafast -pix_fmt yuv420p -y "$OUT"
echo "Resolucao de teste: $(ffprobe -v error -select_streams v:0 -show_entries stream=width,height -of csv=p=0 "$OUT")"
printf '\n[EditorClipesDSS] Rodando testes do motor Python...\n'
python3 -m unittest discover -s bridge -p 'test_*.py' -q
printf '\n[EditorClipesDSS] Concluido: terminal pronto para codigo, testes e FFmpeg basico.\n'
printf 'IMPORTANTE: ainda nao existe vinculacao automatica do APK com este terminal.\n'
