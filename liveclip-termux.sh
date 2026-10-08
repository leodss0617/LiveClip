#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
trap 'echo "Não foi possível concluir. Leia o erro acima; seus vídeos não foram apagados." >&2' ERR
if ! command -v pkg >/dev/null; then
  echo 'Execute no Termux (prompt ~ $). Se estiver no Ubuntu, digite exit primeiro.'
  exit 1
fi
for task_tool in python proot-distro tmux flock; do
  if ! command -v "$task_tool" >/dev/null; then
    pkg install -y python proot-distro tmux util-linux
    break
  fi
done
mkdir -p "$HOME/.local/share/liveclip" "$HOME/.local/bin"
exec 9>"$HOME/.local/share/liveclip/update.lock"
if ! flock -n 9; then echo 'Outro comando liveclip já está atualizando. Aguarde.'; exit 1; fi
if [ ! -d "$HOME/storage/downloads" ]; then
  termux-setup-storage
  echo 'Autorize o armazenamento no Android e execute este comando novamente.'
  exit 1
fi
# Install the short command once; it can also be called by its absolute path.
task_command="${PREFIX:-$HOME/.local}/bin/liveclip"
if [ "$(readlink -f "$0")" != "$task_command" ]; then
  cp -- "$0" "$task_command"
  chmod +x "$task_command"
fi
command -v termux-wake-lock >/dev/null && termux-wake-lock || true
if ! proot-distro login ubuntu -- /bin/true >/dev/null 2>&1; then
  echo 'Instalando Ubuntu. O primeiro preparo precisa de internet…'
  proot-distro install ubuntu
fi
shopt -s nullglob
task_zip=''
for task_candidate in "$HOME/storage/downloads"/LiveClip*.zip; do
  if [ -z "$task_zip" ] || [ "$task_candidate" -nt "$task_zip" ]; then task_zip="$task_candidate"; fi
done
task_marker="$HOME/.local/share/liveclip/package.sha256"
if [ -n "$task_zip" ]; then
  task_hash=$(sha256sum "$task_zip" | cut -d ' ' -f 1)
  if [ ! -f "$task_marker" ] || [ "$(cat "$task_marker")" != "$task_hash" ]; then
    echo "Verificando atualização: $(basename "$task_zip")"
    task_stage=$(mktemp -d "$HOME/.local/share/liveclip/update.XXXXXX")
    trap 'rm -rf -- "$task_stage"' EXIT
    python - "$task_zip" "$task_stage" <<'PY'
import stat, sys, zipfile
from pathlib import Path, PurePosixPath
reserved = {'native.json','data','data-native','logs-native','.venv312','.local-ollama','.native.lock'}
with zipfile.ZipFile(sys.argv[1]) as z:
    total = 0
    for i in z.infolist():
        p = PurePosixPath(i.filename)
        if p.is_absolute() or '..' in p.parts or '\\' in i.filename or not p.parts or p.parts[0] != 'LiveClip':
            raise SystemExit('ZIP recusado: caminho inválido.')
        if len(p.parts)>1 and (p.parts[1] in reserved or p.parts[1].startswith('.')):
            raise SystemExit('ZIP recusado: contém dados ou configuração privada.')
        if stat.S_ISLNK(i.external_attr >> 16):
            raise SystemExit('ZIP recusado: contém link simbólico.')
        total += i.file_size
        if total > 30*1024*1024:
            raise SystemExit('ZIP recusado: tamanho inesperado.')
    required={'LiveClip/atualizar-nativo.py','LiveClip/iniciar-ubuntu-termux.sh','LiveClip/liveclip/version.py','LiveClip/requirements.txt','LiveClip/liveclip-termux.sh'}
    if not required.issubset(z.namelist()):
        raise SystemExit('Baixe o ZIP atualizado com o iniciador automático.')
    if z.testzip() is not None: raise SystemExit('ZIP danificado. Baixe novamente.')
    z.extractall(sys.argv[2])
PY
    proot-distro login --bind "$task_stage/LiveClip:/mnt/liveclip-update" ubuntu -- bash -lc '
if ! command -v python3 >/dev/null; then apt-get update && apt-get install -y python3; fi
exec python3 /mnt/liveclip-update/atualizar-nativo.py /mnt/liveclip-update
' 
    cp -- "$task_stage/LiveClip/liveclip-termux.sh" "$task_command.new"
    chmod +x "$task_command.new"
    mv -- "$task_command.new" "$task_command"
    printf '%s\n' "$task_hash" > "$task_marker"
    rm -rf -- "$task_stage"
    trap - EXIT
  else
    echo 'O ZIP já está instalado.'
  fi
else
  echo 'Sem novo ZIP no Download. Tentando abrir a instalação existente.'
fi
# Do not keep the update lock in the tmux server/session.
flock -u 9
exec 9>&-
# A failed service leaves an interactive diagnostic shell. Reopen the service,
# rather than merely attaching to that shell on the next invocation.
if tmux has-session -t liveclip-auto 2>/dev/null &&
   proot-distro login ubuntu -- test -f /root/LiveClip/.liveclip-exit; then
  tmux kill-session -t liveclip-auto
fi
if tmux has-session -t liveclip-auto 2>/dev/null; then
  echo 'Abrindo sessão do LiveClip. Para sair sem parar: Ctrl+B, depois D.'
else
  echo 'Iniciando o LiveClip. O painel abrirá em http://127.0.0.1:8080'
  tmux new-session -d -s liveclip-auto proot-distro login ubuntu -- bash -lc '
set -e
cd /root/LiveClip
rm -f .liveclip-exit
if [ -f .install-required ] || [ ! -x .venv312/bin/python ]; then
  bash instalar-ubuntu-termux.sh
  rm -f .install-required
fi
set +e
bash iniciar-ubuntu-termux.sh
task_exit=$?
printf "%s\n" "$task_exit" > .liveclip-exit
echo "LiveClip encerrou com código $task_exit. Esta sessão permanece aberta."
echo "Confira: tail -n 60 logs-native/model-test.log logs-native/ollama.log logs-native/studio.log"
echo "Para tentar novamente: bash iniciar-ubuntu-termux.sh"
exec bash
'
fi
if command -v termux-open-url >/dev/null; then termux-open-url http://127.0.0.1:8080 || true; fi
exec tmux attach-session -t liveclip-auto
