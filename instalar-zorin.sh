#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "$0")"
trap 'echo "Instalação interrompida. Copie o erro acima para diagnóstico; seus vídeos não foram apagados." >&2' ERR
if ! command -v docker >/dev/null || ! docker compose version >/dev/null 2>&1; then
  . /etc/os-release
  task_codename=${UBUNTU_CODENAME:-${VERSION_CODENAME:-}}
  case "$task_codename" in jammy|noble|resolute) ;; *)
    echo "Base Ubuntu não suportada por este instalador: $task_codename. Nada foi instalado."; exit 1;; esac
  task_conflicts=()
  for task_package in docker.io docker-compose docker-compose-v2 podman-docker containerd runc; do
    if dpkg-query -W -f='${Status}' "$task_package" 2>/dev/null | grep -q 'install ok installed'; then
      task_conflicts+=("$task_package")
    fi
  done
  if [ "${#task_conflicts[@]}" -gt 0 ]; then
    printf 'Já existem pacotes que precisam de revisão antes de instalar Docker: %s\n' "${task_conflicts[*]}"
    exit 1
  fi
  echo 'Instalando Docker Engine e Compose gratuitos. O sudo solicita a senha do Linux.'
  sudo apt-get update
  sudo apt-get install -y ca-certificates curl
  sudo install -m 0755 -d /etc/apt/keyrings
  sudo curl --fail --show-error --silent --location https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
  sudo chmod a+r /etc/apt/keyrings/docker.asc
  task_arch=$(dpkg --print-architecture)
  sudo tee /etc/apt/sources.list.d/docker.sources >/dev/null <<EOF
Types: deb
URIs: https://download.docker.com/linux/ubuntu
Suites: $task_codename
Components: stable
Architectures: $task_arch
Signed-By: /etc/apt/keyrings/docker.asc
EOF
  sudo apt-get update
  sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
fi
sudo systemctl enable --now docker
echo 'Iniciando o LiveClip local. Não usa conta de nuvem nem chave de API.'
bash iniciar.sh
