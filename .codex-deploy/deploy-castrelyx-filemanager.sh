#!/usr/bin/env bash
set -euo pipefail

archive=/home/keinus/castrelyx-filemanager-source-latest.tgz
live=/home/keinus/castrelyx
stamp=$(date -u +%Y%m%dT%H%M%SZ)
backup=/home/keinus/castrelyx.backup-filemanager-$stamp
next=/home/keinus/castrelyx.next-filemanager-$stamp
agent_bin=/tmp/castrelyx-agent-filemanager-$stamp
completed=0

run_sudo() {
  if [ -n "${SUDO_PASSWORD:-}" ]; then
    printf '%s\n' "$SUDO_PASSWORD" | sudo -S -p '' "$@"
  else
    sudo -n "$@"
  fi
}

rollback() {
  code=$?
  if [ "$completed" -eq 0 ] && [ -d "$backup" ]; then
    echo "deployment failed; rolling source tree back to $backup" >&2
    if [ -d "$live" ]; then
      mv "$live" "$live.failed-filemanager-$stamp"
    fi
    mv "$backup" "$live"
  fi
  exit "$code"
}
trap rollback ERR

safe_extract() {
  python3 - "$archive" "$next" <<'PY'
import os
import sys
import tarfile

archive, target = sys.argv[1], sys.argv[2]
target_real = os.path.realpath(target)
with tarfile.open(archive, "r:gz") as tf:
    for member in tf.getmembers():
        dest = os.path.realpath(os.path.join(target, member.name))
        if dest != target_real and not dest.startswith(target_real + os.sep):
            raise SystemExit(f"unsafe archive member: {member.name}")
    tf.extractall(target)
PY
}

[ -f "$archive" ] || { echo "missing archive: $archive" >&2; exit 1; }
command -v python3 >/dev/null || { echo "python3 is required" >&2; exit 1; }
command -v go >/dev/null || { echo "go is required for agent verification/build" >&2; exit 1; }

run_sudo true
run_sudo docker version >/dev/null

rm -rf "$next"
mkdir -p "$next"
safe_extract

if [ -d "$live" ]; then
  [ -f "$live/.env" ] && cp "$live/.env" "$next/.env"
  [ -f "$live/docker-compose.override.yml" ] && cp "$live/docker-compose.override.yml" "$next/docker-compose.override.yml"
fi

chmod +x "$next/manager/gradlew" "$next/CastrelSign/gradlew" "$next/logparser/gradlew" 2>/dev/null || true
(cd "$next/agent" && go test ./... && CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath -ldflags='-s -w' -o "$agent_bin" ./cmd/castrelyx-agent)

if [ -d "$live" ]; then
  mv "$live" "$backup"
fi
mv "$next" "$live"

cd "$live"
run_sudo docker compose build castrelsign manager
run_sudo docker compose up -d castrelsign manager
run_sudo install -m 0755 "$agent_bin" /usr/local/bin/castrelyx-agent

if ! run_sudo grep -q '^file_manager_enabled:' /etc/castrelyx/agent.yaml 2>/dev/null; then
  cat <<'EOF' | run_sudo tee -a /etc/castrelyx/agent.yaml >/dev/null
file_manager_enabled: true
file_manager_allow_delete: true
file_manager_max_transfer_bytes: 256mb
file_manager_poll_interval: 5s
file_manager_roots:
  - /
EOF
fi

run_sudo systemctl restart castrelyx-agent
run_sudo systemctl is-active --quiet castrelyx-agent
run_sudo docker compose ps

manager_status=$(curl -k -s -o /dev/null -w '%{http_code}' https://127.0.0.1/ || true)
file_manager_status=$(curl -k -s -o /dev/null -w '%{http_code}' -X POST https://127.0.0.1:8443/api/agent/file-manager/check || true)
echo "manager_https_status=$manager_status"
echo "castrelsign_file_manager_check_status=$file_manager_status"
[ "$file_manager_status" != "404" ] || { echo "file-manager endpoint still returns 404" >&2; exit 1; }

completed=1
echo "deployment complete"
echo "backup=$backup"
