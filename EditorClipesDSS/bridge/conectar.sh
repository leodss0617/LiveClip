#!/data/data/com.termux/files/usr/bin/bash
set -eu
bridge=$(cd "$(dirname "$(realpath "$0")")" && pwd)
code=$(bash "$bridge/iniciar.sh" codigo)
health() { curl -fsS --max-time 2 -H "Authorization: Bearer $code" http://127.0.0.1:18754/health >/dev/null 2>&1; }
if ! health; then
    exec 9>"$bridge/.start.lock"
    flock -w 18 9
fi
if ! health; then
    nohup bash "$bridge/iniciar.sh" >> "$bridge/servico.log" 2>&1 < /dev/null 9>&- &
    for attempt in $(seq 1 12); do
        health && break
        sleep 1
    done
fi
if ! health; then
    printf 'Não consegui iniciar a conexão no Termux. Veja %s/servico.log\n' "$bridge"
    exit 1
fi
termux-wake-lock >/dev/null 2>&1 || true
if [ "${1:-}" = silencioso ]; then exit; fi
printf 'Copie este código e cole no EditorClipesDSS → Máquina de Cortes:\n%s\n' "$code"
