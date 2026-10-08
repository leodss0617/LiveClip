#!/usr/bin/env bash
set -euo pipefail
task_project=$(cd -- "$(dirname -- "$0")" && pwd -P)
task_bin="$HOME/.local/bin"
mkdir -p "$task_bin"
printf '#!/usr/bin/env bash\nexec bash %q "$@"\n' "$task_project/liveclip-desktop.sh" > "$task_bin/liveclip"
chmod 755 "$task_bin/liveclip"
task_line='export PATH="$HOME/.local/bin:$PATH"'
for task_profile in "$HOME/.profile" "$HOME/.bashrc"; do
  touch "$task_profile"
  if ! grep -Fqx "$task_line" "$task_profile"; then printf '\n%s\n' "$task_line" >> "$task_profile"; fi
done
echo 'Comando instalado. Mantenha esta pasta no lugar. Abra outro terminal e digite: liveclip'
echo 'No terminal atual: export PATH="$HOME/.local/bin:$PATH"; liveclip'
