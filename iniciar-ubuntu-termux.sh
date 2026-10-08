#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "$0")"
if [ ! -x .venv312/bin/python ]; then echo 'Execute primeiro: bash instalar-ubuntu-termux.sh'; exit 1; fi
export PATH="$PWD/.local-ollama/bin:$PATH"
exec .venv312/bin/python -m liveclip.native "$@"
