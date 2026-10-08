#!/usr/bin/env bash
# Nested Docker can reset legacy forwarding rules when a Codespace resumes.
# Restore only the existing LiveClip bridge; never change the global policy.
set -euo pipefail
[[ ${CODESPACES:-false} == true ]] || exit 0
network_id=$(docker network inspect liveclip_default --format '{{.Id}}' 2>/dev/null) || exit 0
[[ $network_id =~ ^[a-f0-9]{64}$ ]] || exit 1
bridge="br-${network_id:0:12}"
outbound=$(ip -4 route show default | awk '{for(i=1;i<=NF;i++)if($i=="dev"){print $(i+1);exit}}')
[[ $outbound =~ ^[a-zA-Z0-9_.:-]+$ ]] || exit 1
sudo -n iptables-legacy -S FORWARD | head -n 1 | grep -qx -- '-P FORWARD DROP' || exit 0
ensure_rule(){
 if ! sudo -n iptables-legacy -C FORWARD "$@" 2>/dev/null; then
  sudo -n iptables-legacy -I FORWARD 1 "$@"
 fi
}
ensure_rule -i "$bridge" -o "$bridge" -j ACCEPT
ensure_rule -i "$bridge" -o "$outbound" -j ACCEPT
ensure_rule -i "$outbound" -o "$bridge" -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT
