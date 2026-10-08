#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "$0")"
if [ -x .local-uv/uv ]; then
  task_uv="$PWD/.local-uv/uv"
elif command -v uv >/dev/null; then
  task_uv=$(command -v uv)
else
  task_installer=$(mktemp)
  trap 'rm -f "$task_installer"' EXIT
  curl --fail --show-error --silent --location --retry 3 https://astral.sh/uv/install.sh -o "$task_installer"
  UV_UNMANAGED_INSTALL="$PWD/.local-uv" sh "$task_installer"
  task_uv="$PWD/.local-uv/uv"
fi
if [ ! -x .venv312/bin/python ]; then
  "$task_uv" venv --python 3.12 --seed .venv312
fi
.venv312/bin/python -c 'import sys; sys.exit(0 if sys.version_info[:2] == (3,12) else "Ambiente .venv312 inválido: precisa de Python 3.12.")'
echo 'Python 3.12 separado pronto; Python do Ubuntu preservado.'
