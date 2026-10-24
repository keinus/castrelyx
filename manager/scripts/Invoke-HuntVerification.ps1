param(
  [Parameter(Mandatory)][ValidatePattern('^TH-R[0-9]{3}$')][string]$RunId,
  [Parameter(Mandatory)][string]$JavaHome,
  [Parameter(Mandatory)][string]$GoExecutable,
  [string]$HuntClickHouseUrl='http://localhost:18124',
  [string]$LegacyClickHouseUrl='http://localhost:18125',
  [string]$WslDistribution='Ubuntu-22.04',
  [string]$LinuxGoImage='golang:1.24'
)

$ErrorActionPreference='Stop'
$PSNativeCommandUseErrorActionPreference=$false
$repoRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$runRoot=Join-Path $repoRoot "output/hunt-acceptance/$RunId"
if(Test-Path -LiteralPath $runRoot){throw "A run must be immutable; choose a new RunId: $RunId"}
if(!(Test-Path -LiteralPath (Join-Path $JavaHome 'bin/java.exe'))){throw 'Java executable not found'}
if(!(Test-Path -LiteralPath $GoExecutable)){throw 'Native Windows Go executable not found'}
New-Item -ItemType Directory -Path $runRoot|Out-Null
$env:JAVA_HOME=$JavaHome
$env:PATH=(Join-Path $JavaHome 'bin')+[IO.Path]::PathSeparator+$env:PATH
$env:HUNT_TEST_CLICKHOUSE_URL=$HuntClickHouseUrl
$env:ROUTER_TEST_CLICKHOUSE_URL=$LegacyClickHouseUrl
$env:TELEMETRY_TEST_CLICKHOUSE_URL=$LegacyClickHouseUrl
$env:HUNT_ACCEPTANCE_RESULTS_DIR=Join-Path $runRoot 'scenarios'
Remove-Item Env:HUNT_ACCEPTANCE_SCENARIOS -ErrorAction SilentlyContinue
$stages=[Collections.Generic.List[object]]::new()
$manifest=[ordered]@{run=$RunId;scope='complete automated regression from fresh test state; full product requirements still need individual evidence';startedAt=[DateTimeOffset]::UtcNow.ToString('o');head='';sourceFiles=@();sourceSha256='';environment=@{};stages=$stages;productVerdict='IN_PROGRESS'}

Push-Location $repoRoot
try {
  $manifest.head=(& git rev-parse HEAD)
  $sourcePaths=@(& rg --files manager/src manager/frontend/src logparser/src agent/internal agent/cmd -g '*.java' -g '*.go' -g '*.ts' -g '*.tsx' -g '*.css' -g '*.sql' -g '*.yml' -g '*.json' -g '*.properties' -g '*.xml' -g '!**/static/**')+@('agent/go.mod','agent/go.sum','manager/build.gradle','logparser/build.gradle','manager/frontend/package.json','manager/frontend/package-lock.json','docs/threat-hunting-implementation.md','docs/threat-hunting-test-plan.md','manager/scripts/Invoke-HuntVerification.ps1')
  $manifest.sourceFiles=@($sourcePaths|Sort-Object -Unique|Where-Object {Test-Path -LiteralPath $_ -PathType Leaf}|ForEach-Object {[ordered]@{path=$_;sha256=(Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash}})
  $encoded=[Text.Encoding]::UTF8.GetBytes(($manifest.sourceFiles|ConvertTo-Json -Depth 4 -Compress))
  $manifest.sourceSha256=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($encoded))
  $manifest.environment=@{os=[Environment]::OSVersion.VersionString;processors=[Environment]::ProcessorCount;java=((& (Join-Path $JavaHome 'bin/java.exe') -version 2>&1)|Out-String).Trim();go=(& $GoExecutable version);node=(& node --version);huntClickHouse=$HuntClickHouseUrl;legacyClickHouse=$LegacyClickHouseUrl;linuxGoImage=$LinuxGoImage;wsl=$WslDistribution}
} finally {Pop-Location}

function Save-RunManifest {
  $manifest|ConvertTo-Json -Depth 12|Set-Content -LiteralPath (Join-Path $runRoot 'manifest.json') -Encoding utf8
}
function Invoke-TestStage([string]$Name,[string]$Directory,[string]$Executable,[string[]]$Arguments) {
  $entry=[ordered]@{name=$Name;startedAt=[DateTimeOffset]::UtcNow.ToString('o');workingDirectory=$Directory;executable=$Executable;arguments=$Arguments;status='RUNNING';exitCode=$null;log="$Name.log.txt"}
  $stages.Add($entry);Save-RunManifest
  Push-Location $Directory
  try {
    & $Executable @Arguments 2>&1|Tee-Object -LiteralPath (Join-Path $runRoot $entry.log)|Out-Null
    $entry.exitCode=$LASTEXITCODE
    $entry.status=if($entry.exitCode -eq 0){'PASS'}else{'FAIL'}
  } catch {
    $entry.status='BLOCKED';$entry.error=$_.Exception.Message
    $_|Out-String|Add-Content -LiteralPath (Join-Path $runRoot $entry.log)
  } finally {
    Pop-Location;$entry.finishedAt=[DateTimeOffset]::UtcNow.ToString('o');Save-RunManifest
    Write-Output "$RunId $Name $($entry.status) exit=$($entry.exitCode)"
  }
}
function Save-Junit([string]$Project) {
  $source=Join-Path $repoRoot "$Project/build/test-results/test"
  if(Test-Path -LiteralPath $source){$destination=Join-Path $runRoot "$Project-junit";New-Item -ItemType Directory -Path $destination|Out-Null;Get-ChildItem -LiteralPath $source -Filter 'TEST-*.xml'|Copy-Item -Destination $destination}
}

Save-RunManifest
Invoke-TestStage 'manager-tests' (Join-Path $repoRoot 'manager') '.\gradlew.bat' @('test','-PskipFrontend=true','--rerun-tasks','--console=plain')
Save-Junit 'manager'
Invoke-TestStage 'logparser-tests' (Join-Path $repoRoot 'logparser') '.\gradlew.bat' @('test','-PskipFrontend=true','--rerun-tasks','--console=plain')
Save-Junit 'logparser'
Invoke-TestStage 'frontend-tests' (Join-Path $repoRoot 'manager/frontend') 'npm.cmd' @('test','--','--run','--maxWorkers=4')
Invoke-TestStage 'frontend-build' (Join-Path $repoRoot 'manager/frontend') 'npm.cmd' @('run','build')
Invoke-TestStage 'agent-windows-tests' (Join-Path $repoRoot 'agent') $GoExecutable @('test','-count=1','-json','./...')
Invoke-TestStage 'agent-windows-build' (Join-Path $repoRoot 'agent') $GoExecutable @('build','./...')
$agentRoot=Join-Path $repoRoot 'agent'
if($agentRoot -notmatch '^([A-Za-z]):\\(.+)$'){throw 'The WSL test runner requires an absolute drive path'}
$wslAgentRoot='/mnt/'+$Matches[1].ToLowerInvariant()+'/'+$Matches[2].Replace('\','/')
Invoke-TestStage 'agent-linux-tests' $repoRoot 'wsl.exe' @('-d',$WslDistribution,'-u','root','--','docker','run','--rm','--label','codex.task=threat-hunting-acceptance','--memory=2g','--cpus=2','-v',"${wslAgentRoot}:/src:ro",$LinuxGoImage,'bash','-c','cp -a /src /tmp/agent; cd /tmp/agent; /usr/local/go/bin/go test -count=1 -json ./...')

# Preserve the full acceptance scope. Green component suites never imply that absent UI,
# host isolation, load, installation or restore evidence exists.
$requirementRows=Get-Content -LiteralPath (Join-Path $repoRoot 'docs/threat-hunting-test-plan.md')|Where-Object {$_ -match '^\| (TH-M[0-6]-[0-9]{2}) \|'}
$requirements=@($requirementRows|ForEach-Object {$_ -match '^\| (TH-M[0-6]-[0-9]{2}) \|'|Out-Null;[ordered]@{id=$Matches[1];status='NOT_RUN';evidence=@();notes='Requires requirement-by-requirement audit; component test counts alone do not prove completion.'}})
$requirements|ConvertTo-Json -Depth 5|Set-Content -LiteralPath (Join-Path $runRoot 'requirements.json') -Encoding utf8
$manifest.finishedAt=[DateTimeOffset]::UtcNow.ToString('o')
$manifest.productVerdict=if(@($stages|Where-Object {$_.status -eq 'FAIL'}).Count){'FAIL'}else{'NOT_VERIFIED'}
Save-RunManifest
$rows=@($stages|ForEach-Object {"| $($_.name) | $($_.status) | $($_.exitCode) | $($_.log) |"})
$report=@("# $RunId 자동 시험 결과",'',"전체 제품 판정: **$($manifest.productVerdict)**. 전체 요구사항은 requirements.json에서 별도 증거로 심사한다.",'',"소스 SHA-256: $($manifest.sourceSha256)",'','| 단계 | 결과 | 종료 코드 | 증거 |','| --- | --- | --- | --- |')+$rows+@('','미구현·미시험·Chrome/실장비/관리자 권한/장시간 부하 등의 외부 제약을 PASS로 처리하지 않는다. 실패 회차는 보존하고 구현을 수정한 뒤 새 RunId로 환경 준비부터 다시 실행한다.')
$report|Set-Content -LiteralPath (Join-Path $runRoot 'report.md') -Encoding utf8
if($manifest.productVerdict -eq 'FAIL'){exit 1}
exit 2 # Component results require the full product completion audit; never auto-certify.
