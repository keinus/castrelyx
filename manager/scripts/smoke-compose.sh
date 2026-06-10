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

step "inserting ClickHouse raw telemetry fixture"
docker compose exec -T clickhouse clickhouse-client --user "${MANAGER_CLICKHOUSE_USER:-default}" --password "${MANAGER_CLICKHOUSE_PASSWORD:-change-clickhouse-me}" \
  --query "CREATE DATABASE IF NOT EXISTS ${MANAGER_CLICKHOUSE_DATABASE:-castrelyx}"
docker compose exec -T clickhouse clickhouse-client --user "${MANAGER_CLICKHOUSE_USER:-default}" --password "${MANAGER_CLICKHOUSE_PASSWORD:-change-clickhouse-me}" --query "
CREATE TABLE IF NOT EXISTS ${MANAGER_CLICKHOUSE_DATABASE:-castrelyx}.${MANAGER_CLICKHOUSE_RAW_TABLE:-castrelyx_agent_events} (
  received_at DateTime64(3) DEFAULT now64(3),
  agent_id String,
  tenant_id Nullable(String),
  source_id String,
  item_kind Nullable(String),
  item_type Nullable(String),
  item_key Nullable(String),
  event_json String
)
ENGINE = MergeTree
PARTITION BY toYYYYMM(received_at)
ORDER BY (source_id, received_at)"

ts0="$(date -u -d '4 minutes ago' '+%Y-%m-%d %H:%M:%S.000')"
ts1="$(date -u -d '3 minutes ago' '+%Y-%m-%d %H:%M:%S.000')"
ts2="$(date -u -d '2 minutes ago' '+%Y-%m-%d %H:%M:%S.000')"
ts3="$(date -u -d '1 minutes ago' '+%Y-%m-%d %H:%M:%S.000')"
cat <<EOF | docker compose exec -T clickhouse clickhouse-client --user "${MANAGER_CLICKHOUSE_USER:-default}" --password "${MANAGER_CLICKHOUSE_PASSWORD:-change-clickhouse-me}" --query "INSERT INTO ${MANAGER_CLICKHOUSE_DATABASE:-castrelyx}.${MANAGER_CLICKHOUSE_RAW_TABLE:-castrelyx_agent_events} (received_at, agent_id, tenant_id, source_id, item_kind, item_type, item_key, event_json) FORMAT JSONEachRow"
{"received_at":"$ts0","agent_id":"agent-01","tenant_id":null,"source_id":"agent-01","item_kind":"asset","item_type":"identity","item_key":"identity","event_json":"{\"asset_uid\":\"agent-01\",\"hostname\":\"smoke-agent\",\"management_ip\":\"10.255.0.10\",\"asset_type\":\"LINUX_SERVER\"}"}
{"received_at":"$ts1","agent_id":"agent-01","tenant_id":null,"source_id":"agent-01","item_kind":"metric","item_type":"cpu","item_key":"cpu.total","event_json":"{\"asset_uid\":\"agent-01\",\"metric_name\":\"cpu.usage\",\"metric_value\":95.5,\"unit\":\"percent\"}"}
{"received_at":"$ts2","agent_id":"agent-01","tenant_id":null,"source_id":"agent-01","item_kind":"metric","item_type":"interface","item_key":"eth0.in","event_json":"{\"asset_uid\":\"agent-01\",\"metric_name\":\"interface.in.bps\",\"metric_value\":1200000,\"unit\":\"bps\",\"labels\":{\"interface\":\"eth0\"}}"}
{"received_at":"$ts3","agent_id":"agent-01","tenant_id":null,"source_id":"agent-01","item_kind":"metric","item_type":"interface","item_key":"eth0.out","event_json":"{\"asset_uid\":\"agent-01\",\"metric_name\":\"interface.out.bps\",\"metric_value\":900000,\"unit\":\"bps\",\"labels\":{\"interface\":\"eth0\"}}"}
EOF

step "syncing ClickHouse raw telemetry through manager"
curl -fsS -b "$COOKIE_JAR" -X POST "$BASE_URL/api/telemetry/sync" >/dev/null || fail "telemetry sync failed"

step "checking manager APIs"
assets="$(curl -fsS -b "$COOKIE_JAR" "$BASE_URL/api/assets")" || fail "assets endpoint failed"
traffic="$(curl -fsS -b "$COOKIE_JAR" "$BASE_URL/api/traffic/interfaces?range=1h")" || fail "traffic endpoint failed"
alerts="$(curl -fsS -b "$COOKIE_JAR" "$BASE_URL/api/alerts")" || fail "alerts endpoint failed"

printf '%s' "$assets" | grep -q 'smoke-edge-router' || fail "fixture asset not returned"
printf '%s' "$traffic" | grep -q '"assetUid":"agent-01"' || fail "traffic fixture not returned"
printf '%s' "$traffic" | grep -q '"interfaceName":"eth0"' || fail "traffic interface fixture not returned"
printf '%s' "$alerts" | grep -q 'CPU threshold exceeded' || fail "alert fixture not returned"

step "compose smoke passed"
