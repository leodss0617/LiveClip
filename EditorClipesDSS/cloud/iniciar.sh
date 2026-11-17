#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if ! command -v python3 >/dev/null; then echo "Python 3 ausente"; exit 1; fi
if ! command -v ffmpeg >/dev/null; then echo "FFmpeg ausente"; exit 1; fi
echo "EditorClipesDSS: iniciando serviço remoto privado..."
echo "Porta 18755; nunca torne essa porta Public no Codespaces."
exec python3 cloud/service.py
