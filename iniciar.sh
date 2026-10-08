#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "$0")"
bash instalar-comando-linux.sh
if ! command -v docker >/dev/null || ! docker compose version >/dev/null 2>&1; then
  echo 'Instale e inicie o Docker com Compose: https://docs.docker.com/get-started/get-docker/'
  exit 1
fi
task_docker=(docker)
if ! docker info >/dev/null 2>&1; then
  if command -v sudo >/dev/null && sudo docker info >/dev/null; then
    task_docker=(sudo docker)
  else
    echo 'Docker não está disponível. No Zorin, execute: bash instalar-zorin.sh'
    exit 1
  fi
fi
if [ ! -f .env ]; then
  umask 077
  task_password=$(od -An -N24 -tx1 /dev/urandom | tr -d ' \n')
  printf 'LIVECLIP_PASSWORD=%s\nBIND_ADDRESS=0.0.0.0\n' "$task_password" > .env
fi
if [ "${1:-}" = '--nuvem' ]; then
  if [ -z "${2:-}" ] || [[ ! "$2" =~ ^[a-zA-Z0-9.-]+$ ]]; then
    echo 'Uso: bash iniciar.sh --nuvem seu-dominio.com (domínio apontado para este servidor)'; exit 1
  fi
  sed -i '/^LIVECLIP_DOMAIN=/d; /^SECURE_COOKIE=/d; /^BIND_ADDRESS=/d' .env
  printf 'LIVECLIP_DOMAIN=%s\nSECURE_COOKIE=true\nBIND_ADDRESS=127.0.0.1\n' "$2" >> .env
  "${task_docker[@]}" compose --profile cloud up -d --build
  printf 'Painel: https://%s\n' "$2"
else
  "${task_docker[@]}" compose up -d --build
  echo 'Painel no PC: http://localhost:8080'
  for task_ip in $(hostname -I); do
    if [[ "$task_ip" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
      printf 'Endereço possível no celular (mesmo Wi-Fi): http://%s:8080\n' "$task_ip"
    fi
  done
  echo 'Mantenha o notebook ligado, sem suspensão. Não encaminhe a porta 8080 no roteador.'
fi
echo 'Sua senha está na linha LIVECLIP_PASSWORD do arquivo .env (não compartilhe esse arquivo).'
if [ -t 1 ]; then
  sed -n 's/^LIVECLIP_PASSWORD=/Senha do painel: /p' .env
fi
echo 'O primeiro início baixa os modelos e pode demorar. Acompanhe: docker compose logs -f model-setup studio'
