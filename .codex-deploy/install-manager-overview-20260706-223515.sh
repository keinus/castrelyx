#!/usr/bin/env bash
set -euo pipefail
pkg="${1:?package path required}"
stamp="${2:?stamp required}"
root="/home/keinus/castrelyx"
backup="$root/.codex-backups/manager-overview-$stamp"
list="/tmp/manager-overview-$stamp.files"
mkdir -p "$backup"
cd "$root"
tar -tzf "$pkg" > "$list"
while IFS= read -r f; do
  case "$f" in
    manager/*) ;;
    *) echo "refusing unexpected path: $f" >&2; exit 2 ;;
  esac
  if [ -e "$f" ]; then
    mkdir -p "$backup/$(dirname "$f")"
    cp -a "$f" "$backup/$f"
  fi
done < "$list"
for f in manager/src/main/resources/static/assets/index-D1xgt42W.js manager/src/main/resources/static/assets/index-DYld9rK8.css; do
  if [ -e "$f" ]; then
    mkdir -p "$backup/$(dirname "$f")"
    cp -a "$f" "$backup/$f"
    rm -f "$f"
  fi
done
tar -xzf "$pkg" -C "$root"
echo "source_sync_ok"
echo "backup=$backup"
echo "files=$(wc -l < "$list")"
