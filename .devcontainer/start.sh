#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "$0")/.."
task_stage='verificando ambiente'
trap 'printf "Falha na etapa: %s. Execute liveclip diagnostico e copie a mensagem de erro acima.\n" "$task_stage" >&2' ERR
case "${1:-}" in
  diagnostico)
    printf 'Diagnóstico do LiveClip\n'
    if command -v docker >/dev/null; then
      echo 'Docker: instalado'
      if docker info >/dev/null 2>&1; then
        echo 'Docker: respondendo'
        docker compose ps || true
      else
        echo 'Docker: não responde. Confira os logs de criação do Codespace.'
      fi
    else echo 'Docker: ausente. Este comando requer um Codespace no ambiente padrão do GitHub.'; fi
    if command -v python3 >/dev/null; then python3 --version; else echo 'Python: ausente'; fi
    if command -v gh >/dev/null && [ -n "${CODESPACE_NAME:-}" ]; then
      echo 'Para consultar os logs de criação: gh codespace logs -c "$CODESPACE_NAME"'
    fi
    echo 'Se o terminal nem abriu, consulte a página github.com/codespaces e os logs de criação. Não envie senhas, tokens ou o arquivo .env.'
    exit 0 ;;
  parar) docker compose stop; exit 0 ;;
  logs) docker compose logs -f --tail=60 model-setup studio; exit 0 ;;
  atualizar) git pull --ff-only ;;
  '') ;;
  *) echo 'Uso: liveclip [parar|logs|atualizar|diagnostico]'; exit 2 ;;
esac
if ! command -v docker >/dev/null || ! docker compose version >/dev/null 2>&1; then
  echo 'Docker com Compose não está disponível. Execute liveclip diagnostico.' >&2
  exit 1
fi
if ! docker info >/dev/null 2>&1; then
  echo 'Docker ainda não responde. Execute liveclip diagnostico; não inicie downloads de modelos enquanto isso.' >&2
  exit 1
fi
task_stage='verificando modelo de áudio'
python3 .devcontainer/model.py
if [ ! -f .env ]; then
  umask 077
  python3 - <<'PY'
import secrets
from pathlib import Path
Path('.env').write_text('LIVECLIP_PASSWORD='+secrets.token_urlsafe(24)+'\nBIND_ADDRESS=127.0.0.1\nCPU_THREADS=2\nMAX_DATA_GB=8\n')
PY
fi
task_stage='construindo serviços e preparando modelos'
echo 'Iniciando serviços. Esta etapa mostra a construção e o download; o primeiro uso pode demorar.'
docker compose up -d --build
if [ -n "${CODESPACE_NAME:-}" ]; then
  if command -v gh >/dev/null; then
    if ! gh codespace ports visibility 8080:private -c "$CODESPACE_NAME"; then
      echo 'Não foi possível ajustar a porta automaticamente. Confira na aba PORTS que 8080 está Private. Os serviços já foram iniciados.' >&2
    fi
  fi
  printf 'Painel: https://%s-8080.app.github.dev\n' "$CODESPACE_NAME"
fi
sed -n 's/^LIVECLIP_PASSWORD=/Senha: /p' .env
echo 'Os modelos podem levar vários minutos. Progresso: liveclip logs'
echo 'Depois de usar, pare o Codespace em https://github.com/codespaces'
