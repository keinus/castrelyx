param(
  [string]$BaseUrl = "http://localhost:8780",
  [string]$AdminUser = "admin",
  [string]$AdminPassword = "correct-password",
  [switch]$UseWsl
)

$ErrorActionPreference = "Stop"

function Escape-WslSingleQuote([string]$Value) {
  return $Value.Replace("'", "'\''")
}

function ConvertTo-WslPath([string]$Path) {
  if ($Path -match '^([A-Za-z]):\\(.*)$') {
    $drive = $Matches[1].ToLowerInvariant()
    $rest = $Matches[2].Replace('\', '/')
    return "/mnt/$drive/$rest"
  }
  return $Path.Replace('\', '/')
}

function Invoke-WslSmokeIfNeeded {
  $nativeDocker = Get-Command docker -ErrorAction SilentlyContinue
  if ($nativeDocker -and -not $UseWsl) {
    return
  }
  if (-not (Get-Command wsl.exe -ErrorAction SilentlyContinue)) {
    return
  }
  wsl.exe -u root sh -lc "command -v docker >/dev/null 2>&1 && docker ps >/dev/null 2>&1"
  if ($LASTEXITCODE -ne 0) {
    return
  }
  $repoPath = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
  $wslRepoPath = ConvertTo-WslPath $repoPath
  $escapedRepo = Escape-WslSingleQuote $wslRepoPath
  $escapedBaseUrl = Escape-WslSingleQuote ($BaseUrl -replace "localhost", "127.0.0.1")
  $escapedAdminUser = Escape-WslSingleQuote $AdminUser
  $escapedAdminPassword = Escape-WslSingleQuote $AdminPassword
  Write-Host "[smoke] using WSL root Docker"
  wsl.exe -u root bash -lc "cd '$escapedRepo' && BASE_URL='$escapedBaseUrl' ADMIN_USER='$escapedAdminUser' ADMIN_PASSWORD='$escapedAdminPassword' bash ./manager/scripts/smoke-compose.sh"
  exit $LASTEXITCODE
}

Invoke-WslSmokeIfNeeded

function Write-Step([string]$Message) {
  Write-Host "[smoke] $Message"
}

function Show-LogsAndFail([string]$Message) {
  Write-Host "[smoke] $Message" -ForegroundColor Red
  docker compose ps
  docker compose logs --tail=200 manager
  docker compose logs --tail=120 mariadb
  docker compose logs --tail=120 clickhouse
  exit 1
}

Write-Step "starting compose stack"
docker compose up -d --build

Write-Step "waiting for manager setup endpoint"
$ready = $false
for ($i = 0; $i -lt 60; $i++) {
  try {
    $status = Invoke-RestMethod -Uri "$BaseUrl/api/setup/status" -Method Get -TimeoutSec 3
    $ready = $true
    break
  } catch {
    Start-Sleep -Seconds 2
  }
}
if (-not $ready) {
  Show-LogsAndFail "manager did not become ready"
}

$session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
if ($status.required) {
  Write-Step "creating first admin"
  $body = @{
    username = $AdminUser
    password = $AdminPassword
    displayName = "Administrator"
  } | ConvertTo-Json
  Invoke-RestMethod -Uri "$BaseUrl/api/setup/admin" -Method Post -ContentType "application/json" -Body $body | Out-Null
}

Write-Step "logging in"
$loginBody = @{
  username = $AdminUser
  password = $AdminPassword
} | ConvertTo-Json
Invoke-WebRequest -Uri "$BaseUrl/api/auth/login" -Method Post -ContentType "application/json" -Body $loginBody -WebSession $session | Out-Null

Write-Step "creating fixture asset"
$assetBody = @{
  name = "smoke-edge-router"
  assetType = "ROUTER"
  managementIp = "10.255.0.1"
  description = "compose smoke fixture"
} | ConvertTo-Json
Invoke-RestMethod -Uri "$BaseUrl/api/assets" -Method Post -ContentType "application/json" -Body $assetBody -WebSession $session | Out-Null

Write-Step "inserting ClickHouse raw telemetry fixture"
docker compose exec -T clickhouse clickhouse-client --query "CREATE DATABASE IF NOT EXISTS castrelyx"
docker compose exec -T clickhouse clickhouse-client --query @"
CREATE TABLE IF NOT EXISTS castrelyx.castrelyx_agent_events (
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
ORDER BY (source_id, received_at)
"@

$baseTime = (Get-Date).ToUniversalTime().AddMinutes(-4)
$ts0 = $baseTime.ToString("yyyy-MM-dd HH:mm:ss.fff", [Globalization.CultureInfo]::InvariantCulture)
$ts1 = $baseTime.AddMinutes(1).ToString("yyyy-MM-dd HH:mm:ss.fff", [Globalization.CultureInfo]::InvariantCulture)
$ts2 = $baseTime.AddMinutes(2).ToString("yyyy-MM-dd HH:mm:ss.fff", [Globalization.CultureInfo]::InvariantCulture)
$ts3 = $baseTime.AddMinutes(3).ToString("yyyy-MM-dd HH:mm:ss.fff", [Globalization.CultureInfo]::InvariantCulture)
$rawRows = @"
{"received_at":"$ts0","agent_id":"agent-01","tenant_id":null,"source_id":"agent-01","item_kind":"asset","item_type":"identity","item_key":"identity","event_json":"{\"asset_uid\":\"agent-01\",\"hostname\":\"smoke-agent\",\"management_ip\":\"10.255.0.10\",\"asset_type\":\"LINUX_SERVER\"}"}
{"received_at":"$ts1","agent_id":"agent-01","tenant_id":null,"source_id":"agent-01","item_kind":"metric","item_type":"cpu","item_key":"cpu.total","event_json":"{\"asset_uid\":\"agent-01\",\"metric_name\":\"cpu.usage\",\"metric_value\":95.5,\"unit\":\"percent\"}"}
{"received_at":"$ts2","agent_id":"agent-01","tenant_id":null,"source_id":"agent-01","item_kind":"metric","item_type":"interface","item_key":"eth0.in","event_json":"{\"asset_uid\":\"agent-01\",\"metric_name\":\"interface.in.bps\",\"metric_value\":1200000,\"unit\":\"bps\",\"labels\":{\"interface\":\"eth0\"}}"}
{"received_at":"$ts3","agent_id":"agent-01","tenant_id":null,"source_id":"agent-01","item_kind":"metric","item_type":"interface","item_key":"eth0.out","event_json":"{\"asset_uid\":\"agent-01\",\"metric_name\":\"interface.out.bps\",\"metric_value\":900000,\"unit\":\"bps\",\"labels\":{\"interface\":\"eth0\"}}"}
"@
$rawRows | docker compose exec -T clickhouse clickhouse-client --query "INSERT INTO castrelyx.castrelyx_agent_events (received_at, agent_id, tenant_id, source_id, item_kind, item_type, item_key, event_json) FORMAT JSONEachRow"

Write-Step "syncing ClickHouse raw telemetry through manager"
try {
  Invoke-RestMethod -Uri "$BaseUrl/api/telemetry/sync" -Method Post -WebSession $session | Out-Null
} catch {
  Show-LogsAndFail "telemetry sync failed"
}

Write-Step "checking manager APIs"
$assets = Invoke-RestMethod -Uri "$BaseUrl/api/assets" -Method Get -WebSession $session
$traffic = Invoke-RestMethod -Uri "$BaseUrl/api/traffic/interfaces?range=1h" -Method Get -WebSession $session
$alerts = Invoke-RestMethod -Uri "$BaseUrl/api/alerts" -Method Get -WebSession $session

if (-not ($assets | Where-Object { $_.name -eq "smoke-edge-router" })) {
  Show-LogsAndFail "fixture asset not returned"
}
if ($null -eq $traffic) {
  Show-LogsAndFail "traffic endpoint returned null"
}
if (-not ($traffic | Where-Object { $_.assetUid -eq "agent-01" -and $_.interfaceName -eq "eth0" })) {
  Show-LogsAndFail "traffic fixture not returned"
}
if ($null -eq $alerts) {
  Show-LogsAndFail "alerts endpoint returned null"
}
if (-not ($alerts | Where-Object { $_.title -eq "CPU threshold exceeded" })) {
  Show-LogsAndFail "alert fixture not returned"
}

Write-Step "compose smoke passed"
