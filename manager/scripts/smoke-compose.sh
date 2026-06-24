#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BASE_URL="${BASE_URL:-http://127.0.0.1:${MANAGER_PORT:-8780}}"
ADMIN_USER="${ADMIN_USER:-admin}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:-correct-password}"
COOKIE_JAR="$(mktemp)"

cleanup() {
  rm -f "$COOKIE_JAR"
}
trap cleanup EXIT

step() {
  printf '[smoke] %s\n' "$1"
}

fail() {
  printf '[smoke] %s\n' "$1" >&2
  docker compose ps || true
  docker compose logs --tail=200 manager || true
  docker compose logs --tail=120 mariadb || true
  docker compose logs --tail=120 clickhouse || true
  exit 1
}

json_escape() {
  python3 -c 'import json,sys; print(json.dumps(sys.argv[1]))' "$1"
}

cd "$ROOT_DIR"

step "starting compose stack"
docker compose up -d --build

step "waiting for manager setup endpoint"
status=""
for _ in $(seq 1 60); do
  if status="$(curl -fsS --max-time 3 "$BASE_URL/api/setup/status" 2>/dev/null)"; then
    break
  fi
  sleep 2
done
if [[ -z "$status" ]]; then
  fail "manager did not become ready"
fi

if printf '%s' "$status" | grep -q '"required"[[:space:]]*:[[:space:]]*true'; then
  step "creating first admin"
  curl -fsS -X POST "$BASE_URL/api/setup/admin" \
    -H 'Content-Type: application/json' \
    -d "{\"username\":$(json_escape "$ADMIN_USER"),\"password\":$(json_escape "$ADMIN_PASSWORD"),\"displayName\":\"Administrator\"}" \
    >/dev/null || fail "admin setup failed"
fi

step "logging in"
curl -fsS -c "$COOKIE_JAR" -X POST "$BASE_URL/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"username\":$(json_escape "$ADMIN_USER"),\"password\":$(json_escape "$ADMIN_PASSWORD")}" \
  >/dev/null || fail "login failed"

step "creating fixture asset"
curl -fsS -b "$COOKIE_JAR" -X POST "$BASE_URL/api/assets" \
  -H 'Content-Type: application/json' \
  -d '{"name":"smoke-edge-router","assetType":"ROUTER","managementIp":"10.255.0.1","description":"compose smoke fixture"}' \
  >/dev/null || fail "fixture asset create failed"

step "inserting ClickHouse transformed telemetry fixture"
docker compose exec -T clickhouse clickhouse-client --user "${MANAGER_CLICKHOUSE_USER:-default}" --password "${MANAGER_CLICKHOUSE_PASSWORD:-change-clickhouse-me}" \
  --query "CREATE DATABASE IF NOT EXISTS ${MANAGER_CLICKHOUSE_DATABASE:-castrelyx}"
docker compose exec -T clickhouse clickhouse-client --user "${MANAGER_CLICKHOUSE_USER:-default}" --password "${MANAGER_CLICKHOUSE_PASSWORD:-change-clickhouse-me}" --query "
CREATE TABLE IF NOT EXISTS ${MANAGER_CLICKHOUSE_DATABASE:-castrelyx}.manager_metric_samples (
  observed_at DateTime64(3),
  asset_uid String,
  source_type String,
  source_id String,
  metric_name String,
  metric_value Float64,
  unit Nullable(String),
  labels_json String
)
ENGINE = MergeTree
PARTITION BY toDate(observed_at)
ORDER BY (asset_uid, metric_name, observed_at)
TTL toDateTime(observed_at) + INTERVAL 30 DAY DELETE"
docker compose exec -T clickhouse clickhouse-client --user "${MANAGER_CLICKHOUSE_USER:-default}" --password "${MANAGER_CLICKHOUSE_PASSWORD:-change-clickhouse-me}" --query "
CREATE TABLE IF NOT EXISTS ${MANAGER_CLICKHOUSE_DATABASE:-castrelyx}.manager_state_snapshots (
  observed_at DateTime64(3),
  asset_uid String,
  source_type String,
  source_id String,
  state_type String,
  state_key String,
  state_json String
)
ENGINE = ReplacingMergeTree(observed_at)
PARTITION BY toDate(observed_at)
ORDER BY (asset_uid, state_type, state_key)
TTL toDateTime(observed_at) + INTERVAL 30 DAY DELETE"

ts0="$(date -u -d '4 minutes ago' '+%Y-%m-%d %H:%M:%S.000')"
ts1="$(date -u -d '3 minutes ago' '+%Y-%m-%d %H:%M:%S.000')"
ts2="$(date -u -d '2 minutes ago' '+%Y-%m-%d %H:%M:%S.000')"
ts3="$(date -u -d '1 minutes ago' '+%Y-%m-%d %H:%M:%S.000')"
cat <<EOF | docker compose exec -T clickhouse clickhouse-client --user "${MANAGER_CLICKHOUSE_USER:-default}" --password "${MANAGER_CLICKHOUSE_PASSWORD:-change-clickhouse-me}" --query "INSERT INTO ${MANAGER_CLICKHOUSE_DATABASE:-castrelyx}.manager_state_snapshots (observed_at, asset_uid, source_type, source_id, state_type, state_key, state_json) FORMAT JSONEachRow"
{"observed_at":"$ts0","asset_uid":"agent-01","source_type":"AGENT","source_id":"agent-01","state_type":"identity","state_key":"agent-01","state_json":"{\"asset_uid\":\"agent-01\",\"hostname\":\"smoke-agent\",\"management_ip\":\"10.255.0.10\",\"asset_type\":\"LINUX_SERVER\"}"}
EOF
cat <<EOF | docker compose exec -T clickhouse clickhouse-client --user "${MANAGER_CLICKHOUSE_USER:-default}" --password "${MANAGER_CLICKHOUSE_PASSWORD:-change-clickhouse-me}" --query "INSERT INTO ${MANAGER_CLICKHOUSE_DATABASE:-castrelyx}.manager_metric_samples (observed_at, asset_uid, source_type, source_id, metric_name, metric_value, unit, labels_json) FORMAT JSONEachRow"
{"observed_at":"$ts1","asset_uid":"agent-01","source_type":"AGENT","source_id":"agent-01","metric_name":"cpu.usage","metric_value":95.5,"unit":"percent","labels_json":"{}"}
{"observed_at":"$ts2","asset_uid":"agent-01","source_type":"AGENT","source_id":"agent-01","metric_name":"interface.in.bps","metric_value":1200000,"unit":"bps","labels_json":"{\"interface\":\"eth0\"}"}
{"observed_at":"$ts3","asset_uid":"agent-01","source_type":"AGENT","source_id":"agent-01","metric_name":"interface.out.bps","metric_value":900000,"unit":"bps","labels_json":"{\"interface\":\"eth0\"}"}
EOF

step "syncing observed telemetry assets through manager"
curl -fsS -b "$COOKIE_JAR" -X POST "$BASE_URL/api/telemetry/sync" >/dev/null || fail "telemetry sync failed"

step "checking manager APIs"
assets="$(curl -fsS -b "$COOKIE_JAR" "$BASE_URL/api/assets")" || fail "assets endpoint failed"
traffic="$(curl -fsS -b "$COOKIE_JAR" "$BASE_URL/api/traffic/interfaces?range=1h")" || fail "traffic endpoint failed"
alerts="$(curl -fsS -b "$COOKIE_JAR" "$BASE_URL/api/alerts")" || fail "alerts endpoint failed"

printf '%s' "$assets" | grep -q 'smoke-edge-router' || fail "fixture asset not returned"
printf '%s' "$traffic" | grep -q '"assetUid":"agent-01"' || fail "traffic fixture not returned"
printf '%s' "$traffic" | grep -q '"interfaceName":"eth0"' || fail "traffic interface fixture not returned"

step "compose smoke passed"
