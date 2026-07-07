#!/usr/bin/env bash
set -euo pipefail
cd /home/keinus/castrelyx
printf '[1/4] building manager image\n'
docker compose build manager
printf '[2/4] recreating manager container\n'
docker compose up -d --no-deps manager
printf '[3/4] manager container status\n'
docker compose ps manager
printf '[4/4] checking local manager HTML bundle\n'
curl -fsS --max-time 10 http://127.0.0.1:8780/ | grep -aoE 'assets/index-[^"'"'"' ]+' | head -n 5
