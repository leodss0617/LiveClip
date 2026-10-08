#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "$0")/.."
task_project=$(pwd -P)
mkdir -p "$HOME/.local/bin"
printf '#!/usr/bin/env bash\nexec bash %q "$@"\n' "$task_project/.devcontainer/start.sh" > "$HOME/.local/bin/liveclip"
chmod +x "$HOME/.local/bin/liveclip"
touch "$HOME/.bashrc"
if ! grep -q 'export PATH="$HOME/.local/bin:$PATH"' "$HOME/.bashrc"; then
  printf '\nexport PATH="$HOME/.local/bin:$PATH"\n' >> "$HOME/.bashrc"
fi
echo 'Instalação concluída. Abra um novo terminal e execute: liveclip'
