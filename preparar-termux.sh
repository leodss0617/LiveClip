#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "$0")"
if ! command -v pkg >/dev/null; then echo 'Execute este arquivo no Termux, antes de entrar no Ubuntu.'; exit 1; fi
pkg install -y proot-distro tmux
termux-wake-lock
if ! proot-distro login ubuntu -- /bin/true >/dev/null 2>&1; then
  if proot-distro install --help 2>&1 | grep -q -- '--name'; then
    proot-distro install ubuntu:24.04 --name ubuntu
  else
    proot-distro install ubuntu
  fi
fi
if tmux has-session -t liveclip 2>/dev/null; then
  echo 'Retomando sessão existente.'
  exec tmux attach-session -t liveclip
fi
# Keep the proot session alive inside Termux's tmux; do not daemonize inside proot.
exec tmux new-session -s liveclip proot-distro login --bind "$PWD:/mnt/liveclip-package" ubuntu -- bash -lc '
mkdir -p /root/LiveClip
cp -R /mnt/liveclip-package/. /root/LiveClip/
cd /root/LiveClip
bash instalar-ubuntu-termux.sh && bash iniciar-ubuntu-termux.sh
printf "\nPara tentar novamente: bash iniciar-ubuntu-termux.sh\n"
exec bash
'
