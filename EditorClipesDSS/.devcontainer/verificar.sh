#!/usr/bin/env bash
set -euo pipefail
printf 'EditorClipesDSS - verificacao do terminal\n'
python3 --version
ffmpeg -version | head -n 1
git --version
mkdir -p "$HOME/editorclipes-trabalhos"
ffmpeg -hide_banner -loglevel error -f lavfi -i 'testsrc2=size=360x640:rate=15' -t 2 -c:v libx264 -preset ultrafast -pix_fmt yuv420p "$HOME/editorclipes-trabalhos/teste-9x16.mp4" -y
ffprobe -v error -show_entries stream=width,height -of csv=p=0 "$HOME/editorclipes-trabalhos/teste-9x16.mp4"
echo 'Teste basico concluido; trabalhos e integracao remota requerem configuracao adicional.'
