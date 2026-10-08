#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "$0")/.."
case "${1:-}" in
  parar) docker compose stop; exit 0 ;;
  logs) docker compose logs -f --tail=60 model-setup studio; exit 0 ;;
  atualizar) git pull --ff-only ;;
  '') ;;
  *) echo 'Uso: liveclip [parar|logs|atualizar]'; exit 2 ;;
esac
python3 .devcontainer/model.py
if [ ! -f .env ]; then
  umask 077
  python3 - <<'PY'
import secrets
from pathlib import Path
Path('.env').write_text('LIVECLIP_PASSWORD='+secrets.token_urlsafe(24)+'\nBIND_ADDRESS=127.0.0.1\nCPU_THREADS=2\nMAX_DATA_GB=8\n')
PY
fi
docker compose up -d --build
if [ -n "${CODESPACE_NAME:-}" ]; then
  gh codespace ports visibility 8080:private -c "$CODESPACE_NAME"
  printf 'Painel: https://%s-8080.app.github.dev\n' "$CODESPACE_NAME"
fi
sed -n 's/^LIVECLIP_PASSWORD=/Senha: /p' .env
echo 'Os modelos podem levar vários minutos. Progresso: liveclip logs'
echo 'Depois de usar, pare o Codespace em https://github.com/codespaces'
