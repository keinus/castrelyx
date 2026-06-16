# Castrelyx Agent

Castrelyx Agent는 Linux와 Windows 호스트 내부에서 실행되는 경량 Go 수집기입니다. 에이전트는 호스트 식별 정보, 리소스 지표, 네트워크 인터페이스, 프로세스, 서비스, 패키지, 방화벽 상태, 최근 보안/시스템 로그를 수집한 뒤 Castrelyx 서버 측 수신 파이프라인으로 전송합니다.

이 문서는 현재 저장소의 코드 구현을 기준으로 작성되었습니다. `docs/collection-architecture.md`에는 장기 설계 범위가 더 넓게 적혀 있지만, 아래 내용은 실제 `agent`, `CastrelSign`, `logparser`, `manager` 코드에서 확인되는 동작을 설명합니다.

## 구성 요소

| 영역 | 코드 위치 | 역할 |
|---|---|---|
| Agent 실행 파일 | `agent/cmd/castrelyx-agent` | 설정 로드, 인증서 등록/갱신, collector 실행, 전송 반복 |
| Agent 런타임 | `agent/internal/agent` | pending spool flush, batch 생성, collector 결과 취합, 실패 시 spool 저장 |
| Collector | `agent/internal/collectors` | Linux/Windows 로컬 데이터 수집 |
| Envelope | `agent/internal/envelope` | batch/item 스키마와 민감 key redaction |
| Spool | `agent/internal/spool` | 전송 실패 batch를 로컬 NDJSON 큐에 저장 |
| Sender | `agent/internal/sender` | HTTPS gzip ingest 또는 TCP/mTLS gzip ingest 전송 |
| TLS identity | `agent/internal/tlsidentity` | ECDSA P-256 key, CSR, CA/client cert, TLS 설정 |
| Enrollment client | `agent/internal/enrollment` | `/api/agent/enroll`, `/api/agent/renew` 호출 |
| Agent API 서버 | `CastrelSign/src/main/java/org/castrelyx/castrelsign/api` | enrollment token 검증, 인증서 발급/갱신, HTTPS ingest |
| TCP ingest 서버 | `logparser/src/main/java/org/keinus/logparser/domain/input/model/TcpMtlsGzipInputAdapter.java` | TCP + mTLS + gzip framed batch 수신 |
| Raw 저장 | `logparser/src/main/java/org/keinus/logparser/domain/output/model/ClickHouseOutputAdapter.java` | ClickHouse raw table 저장 |
| Manager 동기화 | `manager/src/main/java/org/castrelyx/manager/telemetry` | raw telemetry를 metric/state/event 정규 테이블로 변환 |

## 전체 흐름

1. 운영자가 CastrelSign 관리자 API에서 enrollment token을 발급합니다.
2. Agent 설정 파일에 CastrelSign HTTPS base URL, enrollment token, agent id, tenant id, 인증서/스풀 경로, 수집 주기, collector 목록을 적습니다.
3. Agent가 처음 실행되면 로컬 ECDSA P-256 private key를 생성합니다.
4. Agent가 private key로 CSR을 만들고 `/api/agent/enroll`에 enrollment token과 함께 전송합니다.
5. CastrelSign은 token 유효성, 사용 횟수, 만료 시간, agent id 제한, block 상태, CSR 내용을 검증합니다.
6. CastrelSign은 CA 인증서와 agent client certificate를 발급하고, agent metadata와 인증서 발급 audit을 저장합니다.
7. Agent는 `ca.pem`, `client.pem`, `client.key`, `enrollment.json`을 로컬 인증서 디렉터리에 저장합니다.
8. 이후 batch 전송은 enrollment token이 아니라 client certificate 기반 mTLS로 인증합니다.
9. Agent는 실행 주기마다 먼저 실패 큐를 flush하고, 그 다음 collector를 실행해 새 batch를 만듭니다.
10. HTTPS 모드에서는 gzip JSON batch를 `/api/agent/ingest`로 POST합니다.
11. TCP/mTLS 모드에서는 gzip JSON batch 앞에 4바이트 big-endian 길이 prefix를 붙여 Logparser TCP ingest 포트로 전송하고, newline JSON ack를 기다립니다.
12. 전송이 실패하면 JSON batch가 로컬 spool queue에 append-only NDJSON record로 저장됩니다.
13. 다음 실행 주기에서 spool record를 먼저 재전송하고 성공한 record만 ack 처리합니다.
14. HTTPS 모드에서는 CastrelSign이 batch와 item을 자체 ingest table에 저장합니다.
15. TCP/mTLS 모드에서는 Logparser가 item을 `LogEvent`로 변환하고 ClickHouse `castrelyx_agent_events` raw table에 저장합니다.
16. Manager의 telemetry sync worker는 ClickHouse raw row를 canonical metric/state/event 테이블로 정규화하고, asset binding과 alert 평가에 사용합니다.

## 설정 파일

기본 설정 파일 경로는 OS에 따라 다릅니다.

| OS | 기본 경로 |
|---|---|
| Windows | `%ProgramData%\Castrelyx\agent.yaml` |
| Linux/Unix | `/etc/castrelyx/agent.yaml` |

`-config` 옵션으로 다른 경로를 지정할 수 있습니다.

```powershell
castrelyx-agent.exe -config C:\ProgramData\Castrelyx\agent.yaml
```

```bash
sudo /usr/local/bin/castrelyx-agent -config /etc/castrelyx/agent.yaml
```

예시:

```yaml
manager_url: https://castrelsign.example.com
enrollment_token: cse_replace_with_one_time_token
agent_id: prod-web-01
tenant_id: default
cert_dir: /var/lib/castrelyx-agent/certs
ca_cert_path: /var/lib/castrelyx-agent/certs/ca.pem
tls_server_name: castrelsign.example.com
ingest_transport: tcp_mtls
tcp_ingest_addr: logparser.example.com:9443
tcp_ingest_server_name: logparser.example.com
batch_interval: 30s
spool_dir: /var/lib/castrelyx-agent/spool
max_spool_record_bytes: 8mb
collectors:
  - identity
  - metric
  - network
  - process
  - service
  - port
  - package
  - firewall
  - log_tailer
  - agent_health
```

현재 코드에서 설정 key 이름은 `manager_url`이지만, agent enrollment API는 CastrelSign 서비스에 구현되어 있습니다. 따라서 실제 배포에서는 CastrelSign의 HTTPS base URL을 넣어야 합니다. CastrelSign이 반환하는 `ingest_url`도 같은 base URL의 `/api/agent/ingest`입니다. TCP/mTLS 전송을 사용할 때도 enrollment와 인증서 갱신은 `manager_url`로 수행하고, telemetry batch만 `tcp_ingest_addr`로 보냅니다.

| 설정 | 필수 여부 | 기본값 | 설명 |
|---|---:|---|---|
| `manager_url` | 필수 | 없음 | CastrelSign agent API base URL. 반드시 `https`여야 합니다. |
| `enrollment_token` | 조건부 | 없음 | client cert가 없거나 만료되어 token enrollment가 필요할 때 필수입니다. |
| `agent_id` | 선택 | hostname | batch `source_id`, CSR Common Name, 서버 인증 identity로 사용됩니다. |
| `tenant_id` | 선택 | `default` | batch에 포함되는 tenant 식별자입니다. |
| `batch_interval` | 선택 | `30s` | 상시 실행 시 collector 실행 주기입니다. Go duration 형식을 사용합니다. |
| `spool_dir` | 선택 | OS별 기본값 | 실패 batch를 저장하는 로컬 queue 디렉터리입니다. 상대 경로는 config 파일 위치 기준입니다. |
| `max_spool_record_bytes` | 선택 | `8mb` | spool record 하나의 최대 크기입니다. `kb`, `mb` suffix를 지원합니다. |
| `cert_dir` | 선택 | OS별 기본값 | client key/cert, CA cert, enrollment metadata 저장 디렉터리입니다. |
| `ca_cert_path` | 선택 | `${cert_dir}/ca.pem` | 서버 인증서 검증에 사용할 CA PEM 경로입니다. |
| `client_cert_path` | 선택 | `${cert_dir}/client.pem` | agent client certificate PEM 경로입니다. |
| `client_key_path` | 선택 | `${cert_dir}/client.key` | agent private key PEM 경로입니다. |
| `tls_server_name` | 선택 | 빈 값 | HTTPS enrollment/ingest에서 검증할 TLS ServerName입니다. |
| `ingest_transport` | 선택 | `https` | `https` 또는 `tcp_mtls`만 허용합니다. |
| `tcp_ingest_addr` | 조건부 | 없음 | `ingest_transport: tcp_mtls`일 때 필수입니다. `host:port` 형식입니다. |
| `tcp_ingest_server_name` | 선택 | `tls_server_name` | TCP/mTLS server certificate 검증용 ServerName입니다. |
| `collectors` | 선택 | 기본 collector 전체 | 활성 collector 목록입니다. 알 수 없는 이름은 설정 오류입니다. |

`manager_url`과 enrollment 응답의 `ingest_url`은 모두 `https`만 허용됩니다. HTTP URL을 넣으면 설정 또는 sender 생성 단계에서 실패합니다.

## 수집 데이터

Agent batch는 항상 다음 envelope로 전송됩니다.

```json
{
  "schema_version": "1.0",
  "source": "agent",
  "source_id": "prod-web-01",
  "tenant_id": "default",
  "observed_at": "2026-06-16T05:00:00Z",
  "sent_at": "2026-06-16T05:00:01Z",
  "items": [
    {
      "kind": "metric",
      "type": "host",
      "key": "cpu.usage",
      "payload": {
        "metric_name": "cpu.usage",
        "value": 12.3,
        "unit": "percent"
      }
    }
  ]
}
```

`kind`는 `asset`, `metric`, `state`, `event` 중 하나입니다. 서버 측 Manager는 `metric`을 metric sample로, `state`와 `asset`을 state snapshot으로, `event`를 event row와 alert 평가 입력으로 사용합니다.

### Identity collector

Collector 이름은 `identity`입니다.

수집 방식:

- `os.Hostname()`으로 hostname을 읽습니다.
- Go runtime의 `runtime.GOOS`, `runtime.GOARCH`로 OS family와 architecture를 기록합니다.
- 현재 UTC 시간을 `observed_at`으로 기록합니다.

전송 형태:

| 필드 | 값 |
|---|---|
| `kind` | `asset` |
| `type` | `identity` |
| `key` | hostname |
| `payload.hostname` | 호스트 이름 |
| `payload.os` | `linux`, `windows` 등 Go OS 값 |
| `payload.architecture` | `amd64`, `arm64` 등 |
| `payload.collector` | `identity` |
| `payload.schema_target` | `assets` |

### Metric collector

Collector 이름은 `metric`입니다.

공통 수집:

- Agent 프로세스의 goroutine 수: `agent.runtime.goroutines`
- Agent 프로세스 heap allocation bytes: `agent.runtime.heap_alloc_bytes`
- 호스트 CPU 논리 코어 수: `agent.host.cpu_count`, `host.cpu.count`

Linux 수집:

- `/proc/meminfo`
  - `host.memory.total_bytes`
  - `host.memory.available_bytes`
  - `memory.usage`
- `/proc/stat`
  - 100ms 간격으로 두 번 읽고 delta를 계산해 `cpu.usage`, `host.cpu.usage_percent` 생성
- `/proc/loadavg`
  - `host.load.1`
  - `host.load.5`
  - `host.load.15`
  - CPU 수로 나눈 `host.load.normalized_1`
- `/proc/net/dev`
  - interface별 `host.network.rx_bytes`
  - interface별 `host.network.tx_bytes`
- `df -P -B1`
  - mount별 `host.disk.total_bytes`
  - mount별 `host.disk.used_bytes`
  - mount별 `host.disk.available_bytes`
  - mount별 `host.disk.used_percent`
  - 전체 mount 중 최대 사용률을 `disk.usage`로 추가

Windows 수집:

- PowerShell `Get-CimInstance Win32_Processor`
  - 평균 CPU load percentage를 `cpu.usage`, `host.cpu.usage_percent`로 기록
- PowerShell `Get-CimInstance Win32_OperatingSystem`
  - `TotalVisibleMemorySize`, `FreePhysicalMemory` 기반 memory total/available/usage 기록
- PowerShell `Get-NetAdapterStatistics`
  - interface별 received/sent bytes 기록
- PowerShell `Get-CimInstance Win32_LogicalDisk`
  - removable/fixed drive의 size/free 기반 disk usage 기록

Metric payload 공통 필드:

| 필드 | 설명 |
|---|---|
| `metric_name` | metric 이름 |
| `value` | 숫자 값 |
| `unit` | `bytes`, `percent`, `count` 등 |
| `interface` | 네트워크 metric일 때 interface 이름 |
| `direction` | `ingress` 또는 `egress` |
| `filesystem` | disk metric일 때 filesystem 또는 provider |
| `mount_point` | Linux mount point 또는 Windows drive letter |

### Network collector

Collector 이름은 `network`입니다.

수집 방식:

- Go `net.Interfaces()`로 network interface 목록을 읽습니다.
- 각 interface의 주소 목록은 `iface.Addrs()`로 읽습니다.

전송 형태:

| 필드 | 설명 |
|---|---|
| `kind` | `state` |
| `type` | `interface` |
| `key` | interface 이름 |
| `payload.name` | interface 이름 |
| `payload.mac_address` | MAC address |
| `payload.mtu` | MTU |
| `payload.flags` | Go interface flag 문자열 |
| `payload.oper_status` | flag 기준 `up` 또는 `down` |
| `payload.addresses` | IP/CIDR 주소 배열 |

### Port/socket collector

Collector 이름은 `port`입니다.

Linux 수집:

- `/proc/net/tcp`
- `/proc/net/tcp6`
- `/proc/net/udp`
- `/proc/net/udp6`

Windows 수집:

- PowerShell `Get-NetTCPConnection`
- PowerShell `Get-NetUDPEndpoint`

현재 구현은 listening socket뿐 아니라 established/syn/time_wait 일부 상태를 함께 구조화합니다. TCP/UDP socket은 local/remote address와 port, state, direction을 갖습니다. Linux에서는 socket inode를 통해 process와 연결하고, Windows에서는 owning process PID를 사용합니다.

전송 형태:

| 필드 | 설명 |
|---|---|
| `kind` | `state` |
| `type` | `socket` |
| `key` | protocol/local/remote/state 조합 |
| `payload.protocol` | `tcp`, `tcp6`, `udp`, `udp6` 등 |
| `payload.local_address` | local IP |
| `payload.local_port` | local port |
| `payload.remote_address` | remote IP |
| `payload.remote_port` | remote port |
| `payload.state` | `listen`, `established`, `syn_sent`, `syn_recv`, `time_wait`, `close` 등 |
| `payload.direction` | `listening`, `connected`, `bound` |
| `payload.process_id` | 연결된 process PID 또는 null |
| `payload.process_name` | 연결된 process name 또는 null |
| `payload.socket_inode` | Linux socket inode |

### Process collector

Collector 이름은 `process`입니다.

Linux 수집:

- `/proc/<pid>/stat`에서 process name과 parent PID를 파싱합니다.
- `/proc/<pid>/exe` symlink에서 executable path를 읽습니다.
- `/proc/<pid>/status`에서 UID와 `VmRSS` memory를 읽습니다.
- `/proc/<pid>/fd` symlink에서 socket inode를 수집합니다.
- socket inode를 socket collector 결과와 연결해 listening/connected socket count를 계산합니다.

Windows 수집:

- PowerShell `Get-CimInstance Win32_Process`
- `ProcessId`, `ParentProcessId`, `Name`, `ExecutablePath`, `WorkingSetSize`를 읽습니다.

전송 형태:

| 필드 | 설명 |
|---|---|
| `kind` | `state` |
| `type` | `process` |
| `key` | PID |
| `payload.pid` | PID |
| `payload.parent_pid` | parent PID 또는 null |
| `payload.name` | process name |
| `payload.executable_path` | executable path 또는 null |
| `payload.command_line` | 현재 구현에서는 항상 null |
| `payload.user` | Linux UID 또는 null |
| `payload.cpu_percent` | 현재 구현에서는 null |
| `payload.memory_bytes` | RSS/working set bytes 또는 null |
| `payload.started_at` | 현재 구현에서는 null |
| `payload.socket_keys` | 연결된 socket key 배열 |
| `payload.listening_socket_count` | listening socket 수 |
| `payload.connected_socket_count` | remote established socket 수 |

command line과 환경변수는 secret 노출 가능성이 높기 때문에 현재 구현에서 수집하지 않습니다.

### Service collector

Collector 이름은 `service`입니다.

Linux 수집:

- `systemctl list-units --type=service --all --no-pager --no-legend`
- unit name, active state, sub state, display name을 파싱합니다.

Windows 수집:

- PowerShell `Get-Service`
- `Name`, `DisplayName`, `Status`, `StartType`을 읽습니다.

전송 형태:

| 필드 | 설명 |
|---|---|
| `kind` | `state` |
| `type` | `service` |
| `key` | service/unit 이름 |
| `payload.name` | service/unit 이름 |
| `payload.display_name` | 표시 이름 |
| `payload.status` | `running`, `stopped`, `failed`, 기타 상태 |
| `payload.startup_type` | Windows start type 또는 Linux `unknown` |
| `payload.binary_path` | 현재 구현에서는 대부분 null |
| `payload.user` | 현재 구현에서는 대부분 null |
| `payload.last_state_change_at` | 현재 구현에서는 null |

### Package collector

Collector 이름은 `package`입니다.

Linux 수집:

아래 명령을 순서대로 시도하고, 가장 먼저 유효한 결과를 반환한 package manager 결과를 사용합니다.

- `dpkg-query -W -f=${binary:Package}\t${Version}\t${Architecture}\n`
- `rpm -qa --qf %{NAME}\t%{VERSION}-%{RELEASE}\t%{ARCH}\n`
- `pacman -Q`
- `apk info -vv`

Windows 수집:

- Registry uninstall path를 PowerShell `Get-ItemProperty`로 조회합니다.
- `DisplayName`, `DisplayVersion`, `Publisher`, `InstallDate`를 읽습니다.

전송 형태:

| 필드 | 설명 |
|---|---|
| `kind` | `state` |
| `type` | `package` |
| `key` | package/application 이름 |
| `payload.name` | package/application 이름 |
| `payload.version` | version |
| `payload.vendor` | vendor/publisher |
| `payload.architecture` | architecture |
| `payload.install_time` | install date |
| `payload.source` | `dpkg`, `rpm`, `pacman`, `apk`, `registry` |

### Firewall collector

Collector 이름은 `firewall`입니다.

Linux 수집:

- `ufw status`
- `firewall-cmd --state`
- `nft list ruleset`
- `iptables -S`

Windows 수집:

- PowerShell `Get-NetFirewallProfile`

전송 형태:

| 필드 | 설명 |
|---|---|
| `kind` | `state` |
| `type` | `firewall` |
| `key` | backend 또는 backend/profile 조합 |
| `payload.backend` | `ufw`, `firewalld`, `nftables`, `iptables`, `windows_firewall` |
| `payload.profile` | Windows firewall profile 또는 null |
| `payload.enabled` | 활성 여부 또는 null |
| `payload.rule_count` | rule count 또는 null |

현재 구현은 rule 원문 전체를 전송하지 않고 활성 여부와 rule count 중심으로 전송합니다.

### Log tailer collector

Collector 이름은 `log_tailer`입니다.

Linux 수집:

- `/var/log/auth.log` 마지막 50줄
- `/var/log/secure` 마지막 50줄
- `/var/log/syslog` 마지막 50줄
- `/var/log/messages` 마지막 50줄
- `journalctl -n 50 --no-pager --output short-iso`

Windows 수집:

- `Security` channel 최근 20개
- `System` channel 최근 20개
- `Application` channel 최근 20개
- `Windows PowerShell` channel 최근 20개
- `Microsoft-Windows-PowerShell/Operational` channel 최근 20개
- `Microsoft-Windows-TerminalServices-LocalSessionManager/Operational` channel 최근 20개
- `Microsoft-Windows-Windows Defender/Operational` channel 최근 20개

전송 형태:

| 필드 | 설명 |
|---|---|
| `kind` | `event` |
| `type` | `log` |
| `key` | `source_name:dedup_hash_prefix` |
| `payload.event_type` | 현재 기본값 `system` |
| `payload.platform` | `linux` 또는 `windows` |
| `payload.source` | `agent` |
| `payload.source_name` | file path, `journald`, Windows channel |
| `payload.observed_at` | 수집 시각 |
| `payload.actor` | 현재 기본값 null |
| `payload.action` | 현재 기본값 null |
| `payload.outcome` | 현재 기본값 `unknown` |
| `payload.message` | 원본 log message 또는 event message |
| `payload.raw_ref` | 현재 기본값 null |
| `payload.dedup_key` | platform/source/message의 SHA-256 |
| `payload.event_id` | Windows Event ID |
| `payload.provider` | Windows provider |
| `payload.level` | Windows level |
| `payload.time_created` | Windows event time |

현재 log tailer는 cursor 파일을 유지하지 않습니다. 실행 주기마다 최근 줄/최근 이벤트를 다시 읽고, message 기반 `dedup_key`를 생성합니다. 따라서 downstream에서 dedup key를 활용하지 않으면 같은 최근 로그가 여러 번 보일 수 있습니다.

중요한 보안 제한도 있습니다. Agent의 redaction은 구조화 payload의 key 이름을 기준으로 수행됩니다. `payload.message` 같은 자유 텍스트 안에 password나 token이 포함되어 있으면 현재 구현은 문자열 내부를 정규식으로 scrub하지 않습니다. 운영 환경에서는 log source 자체에서 secret이 기록되지 않도록 관리하거나, 서버 측 추가 scrubber를 두는 것이 안전합니다.

### Agent health collector

Collector 이름은 `agent_health`입니다.

전송 형태:

| 필드 | 설명 |
|---|---|
| `kind` | `event` |
| `type` | `health` |
| `key` | `heartbeat` |
| `payload.status` | `ok` |
| `payload.collector` | `agent_health` |
| `payload.go_version` | Agent 빌드 런타임의 Go version |
| `payload.os` | OS |
| `payload.architecture` | architecture |
| `payload.process_id` | agent process id |
| `payload.observed_at` | 수집 시각 |

Collector가 모두 빈 결과를 반환하면 런타임도 별도 heartbeat event를 추가합니다.

## 보안 처리

### Enrollment token

Enrollment token은 최초 등록 또는 만료된 인증서 복구에만 사용됩니다.

서버 측 token 정책:

- token은 `cse_` prefix와 32바이트 random 값을 base64url로 인코딩해 생성합니다.
- token 원문은 생성 응답에서만 반환됩니다.
- 저장소에는 SHA-256 hash가 저장됩니다.
- 기본 TTL은 24시간입니다.
- 기본 최대 사용 횟수는 1회입니다.
- token마다 특정 `agent_id`를 제한할 수 있습니다.
- revoke된 token, 만료된 token, 사용 횟수를 초과한 token은 거부됩니다.
- token 생성/list/revoke는 `CASTRELSIGN_ADMIN_TOKEN` 기반 관리자 Bearer 인증을 요구합니다.

token 발급 예시:

```bash
curl -X POST https://castrelsign.example.com/api/admin/enrollment-tokens \
  -H "Authorization: Bearer ${CASTRELSIGN_ADMIN_TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{"name":"prod-web-01 initial enrollment","agent_id":"prod-web-01","ttl_seconds":86400,"max_uses":1}'
```

반환되는 `token` 값을 agent 설정 파일의 `enrollment_token`에 넣습니다. token 원문은 다시 조회할 수 없으므로 생성 직후 안전하게 전달해야 합니다.

### Local private key와 인증서

Agent는 local private key를 서버로 전송하지 않습니다.

로컬 파일:

| 파일 | 내용 | 권한 |
|---|---|---|
| `client.key` | ECDSA P-256 private key PEM | `0600` |
| `client.pem` | CastrelSign이 발급한 client certificate PEM | `0600` |
| `ca.pem` | CastrelSign root CA PEM | `0600` |
| `enrollment.json` | agent id, ingest URL, 인증서 만료 시각 | `0600` |

`cert_dir` 디렉터리는 `0700`으로 생성됩니다. Windows에서도 Go의 mode 값은 전달되지만 실제 적용은 NTFS ACL과 실행 계정 권한에 따라 달라질 수 있습니다. 운영 환경에서는 agent 실행 계정만 읽을 수 있도록 ACL을 별도로 확인해야 합니다.

CSR 내용:

- Subject Common Name: `agent_id`
- Organization: `Castrelyx`
- Organizational Unit: `agent`, OS, architecture, agent version
- DNS SAN: hostname이 있으면 hostname을 넣습니다.

CastrelSign은 CSR이 요청한 `agent_id`와 맞는지 검증한 뒤 client certificate를 발급합니다. 기본 인증서 유효 기간은 `CastrelSignProperties.certValidDays`의 기본값인 30일입니다.

### 인증서 갱신

Agent는 client certificate 만료 7일 전부터 갱신 대상으로 판단합니다.

- 인증서가 존재하고 아직 만료되지 않았으면 기존 client certificate로 mTLS를 구성해 `/api/agent/renew`를 호출합니다.
- `/renew` 서버는 요청의 `agent_id`가 client certificate CN과 같아야만 허용합니다.
- 인증서가 없거나 이미 만료되어 mTLS 갱신을 할 수 없으면 enrollment token이 필요합니다.
- `enrollment_token` 없이 인증서 파일도 없으면 설정 로드 단계에서 실패합니다.
- 인증서 파일은 있지만 만료된 상태이고 token도 없으면 identity 확보 단계에서 실패합니다.

### Transport 보안

HTTPS 전송:

- enrollment endpoint는 `https`만 허용합니다.
- ingest endpoint도 `https`만 허용합니다.
- TLS 최소 버전은 TLS 1.2입니다.
- agent는 `ca.pem`으로 서버 인증서를 검증합니다.
- agent는 `client.pem`과 `client.key`로 mTLS client authentication을 수행합니다.
- `/api/agent/ingest` 서버는 client certificate에서 agent identity를 추출합니다.
- batch의 `source_id`가 client certificate Common Name과 다르면 403으로 거부됩니다.
- payload는 gzip 압축 JSON이며 `Content-Encoding: gzip`이 필수입니다.

TCP/mTLS 전송:

- Logparser의 `TcpMtlsGzipInputAdapter`는 TLS 1.2 또는 TLS 1.3만 활성화합니다.
- 서버 socket은 `setNeedClientAuth(true)`로 client certificate를 요구합니다.
- truststore는 CastrelSign이 관리하는 CA/truststore를 사용합니다.
- agent는 TLS handshake 후 gzip JSON batch를 전송합니다.
- frame 형식은 `4-byte big-endian length` + `gzip JSON payload`입니다.
- 서버는 frame 길이가 0 이하이거나 `maxFrameBytes`를 넘으면 NACK를 반환합니다.
- 서버는 client certificate CN과 batch `source_id`가 다르면 NACK를 반환합니다.
- 서버는 queue에 이벤트를 넣은 뒤 `{"status":"accepted"}` newline ack를 반환합니다.

### Payload redaction

Agent는 batch item을 추가할 때 `payload`를 재귀적으로 redaction합니다.

redaction 대상 key:

- 정확히 `key`
- 정확히 `api_key`
- 정확히 `apikey`
- `token`을 포함하는 key
- `password`를 포함하는 key
- `secret`을 포함하는 key
- `authorization`을 포함하는 key
- `credential`을 포함하는 key

key 비교 전에는 소문자화하고 `-`를 `_`로 바꿉니다. 예를 들어 `Authorization`, `api-key`, `refresh_token`, `dbPassword`, `client_secret`, `credential_id`는 모두 redaction 대상입니다.

redaction 값은 `[REDACTED]`입니다.

주의 사항:

- redaction은 map key 기반입니다.
- 문자열 내부의 secret pattern은 현재 scrub하지 않습니다.
- batch item의 top-level `key` 필드는 redaction 대상이 아니고, `payload` 내부 key만 대상입니다.
- 자유 텍스트 log message는 현재 그대로 보낼 수 있습니다.

### 로컬 spool 보안

전송 실패 batch는 `${spool_dir}/queue.ndjson`에 저장됩니다.

특성:

- spool 디렉터리는 `0700`으로 생성됩니다.
- queue 파일은 `0600`으로 생성됩니다.
- record는 append-only NDJSON 형식입니다.
- 각 record는 random 16바이트가 포함된 id와 생성 시각, JSON payload를 갖습니다.
- `max_spool_record_bytes`보다 큰 batch는 spool에 저장하지 않고 오류 처리됩니다.
- 다음 주기에서 최대 25개 record를 먼저 재전송합니다.
- 성공적으로 전송된 record id만 ack되어 queue 파일에서 제거됩니다.
- 깨진 JSON record는 ack 대상으로 처리되어 재전송 루프를 막습니다.

현재 구현은 spool 파일을 별도로 암호화하지 않습니다. 보안은 filesystem permission과 실행 계정 분리로 보호합니다. 디스크 암호화가 필요한 환경에서는 OS 레벨 disk encryption 또는 별도 encrypted volume에 `spool_dir`를 두는 구성이 필요합니다.

## 서버 수신과 저장

### CastrelSign HTTPS ingest

`/api/agent/ingest`는 다음을 요구합니다.

- mTLS client certificate
- `Content-Encoding: gzip`
- gzip 해제 후 JSON object batch
- batch `source_id`
- batch `source_id`와 client certificate CN 일치

수신에 성공하면 raw JSON batch를 CastrelSign의 `ingest_batches`, `ingest_items` table에 저장하고 `INGEST_ACCEPTED` audit을 남깁니다. 이 HTTPS ingest path는 agent batch를 안전하게 수신하고 보존하는 경로입니다. 현재 Manager dashboard가 조회하는 ClickHouse raw pipeline은 아래 TCP/mTLS Logparser path를 기준으로 구성되어 있습니다.

### Logparser TCP/mTLS ingest

Docker Compose 기본 seed는 Logparser에 다음 adapter를 생성합니다.

- input: `TcpMtlsGzipInputAdapter`
- message type: `castrelyx-agent-item`
- listen port: `9443`
- key store: `/var/lib/castrelsign/certs/server.p12`
- trust store: `/var/lib/castrelsign/certs/truststore.p12`
- max frame bytes: `10485760`

TCP ingest는 batch 안의 각 `items[]`를 개별 `LogEvent`로 변환합니다. 이때 batch metadata와 item metadata를 event field로 펼칩니다.

추가되는 field 예:

- `schema_version`
- `source`
- `source_id`
- `tenant_id`
- `observed_at`
- `sent_at`
- `item_kind`
- `item_type`
- `item_key`
- `payload`
- `payload_*`

### ClickHouse raw table

Logparser 기본 output adapter는 ClickHouse raw table을 자동 생성합니다.

기본 테이블:

```sql
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
```

ClickHouse output adapter는 기본 `batchSize: 100`, `flushIntervalMs: 5000`으로 buffer를 flush합니다. 인증은 `CLICKHOUSE_USER`, `CLICKHOUSE_PASSWORD` 환경변수에서 읽어 Basic Auth header를 구성합니다.

### Manager canonical 동기화

Manager `TelemetrySyncWorker`는 ClickHouse raw table에서 cursor 이후 row를 읽어 canonical table로 변환합니다.

처리 내용:

- raw table cursor는 MariaDB `sync_cursors`에 저장됩니다.
- 한 번에 최대 1000 rows, 최대 100 batches를 처리합니다.
- `metric` item은 `manager_metric_samples`로 변환됩니다.
- `state` item은 `manager_state_snapshots`로 변환됩니다.
- `event` item은 `manager_events`로 변환됩니다.
- `asset` 또는 `identity` item은 identity state로 변환됩니다.
- agent source를 asset source binding으로 연결합니다.
- metric/event 기반 alert rule 평가를 수행합니다.

## 설치

### 1. 서버 스택 준비

루트 디렉터리에서 환경 파일을 만들고 secret을 바꿉니다.

```bash
cp .env.example .env
```

최소한 다음 값은 운영용 strong secret으로 변경해야 합니다.

- `MANAGER_DB_PASSWORD`
- `MARIADB_ROOT_PASSWORD`
- `MANAGER_CRYPTO_KEY`
- `MANAGER_CLICKHOUSE_PASSWORD`
- `CASTRELSIGN_ADMIN_TOKEN`
- `CASTRELSIGN_KEYSTORE_PASSWORD`
- `LOGPARSER_CRYPTO_KEY`
- `LOGPARSER_CRYPTO_SALT`
- `LOGPARSER_KEYSTORE_PASSWORD`
- `LOGPARSER_TRUSTSTORE_PASSWORD`

서버를 시작합니다.

```bash
docker compose up -d --build
```

기본 포트:

| 서비스 | 포트 |
|---|---:|
| CastrelSign HTTPS | `8443` |
| Logparser HTTP/UI | `8765` |
| Logparser TCP/mTLS ingest | `9443` |
| Manager UI/API | `8780` |
| ClickHouse HTTP | `8123` |

### 2. Enrollment token 발급

```bash
curl -X POST https://castrelsign.example.com/api/admin/enrollment-tokens \
  -H "Authorization: Bearer ${CASTRELSIGN_ADMIN_TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{"name":"prod-web-01","agent_id":"prod-web-01","ttl_seconds":86400,"max_uses":1}'
```

개발용 self-signed CA 환경에서는 `curl --cacert <ca.pem>` 또는 임시 `-k`가 필요할 수 있습니다. 운영 문서나 자동화에서는 `-k` 대신 CA 배포를 권장합니다.

### 3. Agent 빌드

Linux/macOS:

```bash
cd agent
go mod download
go test ./...
go build -o dist/castrelyx-agent ./cmd/castrelyx-agent
```

Windows PowerShell:

```powershell
cd agent
go mod download
go test ./...
go build -o dist\castrelyx-agent.exe .\cmd\castrelyx-agent
```

Linux amd64용 교차 빌드 예시:

```bash
cd agent
GOOS=linux GOARCH=amd64 go build -o dist/castrelyx-agent-linux-amd64 ./cmd/castrelyx-agent
```

Windows amd64용 교차 빌드 예시:

```bash
cd agent
GOOS=windows GOARCH=amd64 go build -o dist/castrelyx-agent-windows-amd64.exe ./cmd/castrelyx-agent
```

### 4. Linux 설치

```bash
sudo install -m 0755 agent/dist/castrelyx-agent /usr/local/bin/castrelyx-agent
sudo install -d -m 0755 /etc/castrelyx
sudo install -d -m 0700 /var/lib/castrelyx-agent/certs
sudo install -d -m 0700 /var/lib/castrelyx-agent/spool
sudo tee /etc/castrelyx/agent.yaml >/dev/null <<'YAML'
manager_url: https://castrelsign.example.com
enrollment_token: cse_replace_with_one_time_token
agent_id: prod-web-01
tenant_id: default
cert_dir: /var/lib/castrelyx-agent/certs
ca_cert_path: /var/lib/castrelyx-agent/certs/ca.pem
tls_server_name: castrelsign.example.com
ingest_transport: tcp_mtls
tcp_ingest_addr: logparser.example.com:9443
tcp_ingest_server_name: logparser.example.com
batch_interval: 30s
spool_dir: /var/lib/castrelyx-agent/spool
max_spool_record_bytes: 8mb
collectors:
  - identity
  - metric
  - network
  - process
  - service
  - port
  - package
  - firewall
  - log_tailer
  - agent_health
YAML
```

단발 실행으로 enrollment와 수집을 검증합니다.

```bash
sudo /usr/local/bin/castrelyx-agent -config /etc/castrelyx/agent.yaml -once
```

systemd 서비스 파일을 만든 뒤 활성화합니다.

```bash
sudo tee /etc/systemd/system/castrelyx-agent.service >/dev/null <<'UNIT'
[Unit]
Description=Castrelyx Agent
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
ExecStart=/usr/local/bin/castrelyx-agent -config /etc/castrelyx/agent.yaml
Restart=always
RestartSec=10
User=root
Group=root

[Install]
WantedBy=multi-user.target
UNIT

sudo systemctl daemon-reload
sudo systemctl enable --now castrelyx-agent
sudo systemctl status castrelyx-agent
```

root가 아닌 사용자로 실행하려면 다음 권한을 별도로 부여해야 합니다.

- `/proc` 기반 process/socket 정보 읽기 권한
- `/var/log/*` 또는 journald 읽기 권한
- package manager 명령 실행 권한
- firewall 상태 명령 실행 권한
- `cert_dir`, `spool_dir` read/write 권한

### 5. Windows 설치

디렉터리를 준비합니다.

```powershell
New-Item -ItemType Directory -Force C:\ProgramData\Castrelyx | Out-Null
New-Item -ItemType Directory -Force C:\ProgramData\Castrelyx\certs | Out-Null
New-Item -ItemType Directory -Force C:\ProgramData\Castrelyx\spool | Out-Null
Copy-Item .\agent\dist\castrelyx-agent.exe C:\ProgramData\Castrelyx\castrelyx-agent.exe
```

`C:\ProgramData\Castrelyx\agent.yaml`:

```yaml
manager_url: https://castrelsign.example.com
enrollment_token: cse_replace_with_one_time_token
agent_id: win-host-01
tenant_id: default
cert_dir: C:\ProgramData\Castrelyx\certs
ca_cert_path: C:\ProgramData\Castrelyx\certs\ca.pem
tls_server_name: castrelsign.example.com
ingest_transport: tcp_mtls
tcp_ingest_addr: logparser.example.com:9443
tcp_ingest_server_name: logparser.example.com
batch_interval: 30s
spool_dir: C:\ProgramData\Castrelyx\spool
max_spool_record_bytes: 8mb
collectors:
  - identity
  - metric
  - network
  - process
  - service
  - port
  - package
  - firewall
  - log_tailer
  - agent_health
```

단발 실행:

```powershell
C:\ProgramData\Castrelyx\castrelyx-agent.exe -config C:\ProgramData\Castrelyx\agent.yaml -once
```

Windows 서비스 등록 예시:

```powershell
New-Service `
  -Name CastrelyxAgent `
  -DisplayName "Castrelyx Agent" `
  -BinaryPathName '"C:\ProgramData\Castrelyx\castrelyx-agent.exe" -config "C:\ProgramData\Castrelyx\agent.yaml"' `
  -StartupType Automatic

Start-Service CastrelyxAgent
Get-Service CastrelyxAgent
```

코드에는 Windows Service Control Manager에서 실행 중인지 감지하는 로직이 들어 있습니다. 서비스로 실행되면 stop/shutdown 요청을 받아 context를 취소하고 최대 30초 동안 종료를 기다립니다.

## 실행 모드

단발 실행:

```bash
castrelyx-agent -config /etc/castrelyx/agent.yaml -once
```

상시 실행:

```bash
castrelyx-agent -config /etc/castrelyx/agent.yaml
```

상시 실행은 다음 루프를 반복합니다.

1. spool에 남아 있는 pending batch를 최대 25개까지 먼저 전송합니다.
2. collector들을 순서대로 실행합니다.
3. collector 오류는 전체 실행 실패가 아니라 `collector_error` event item으로 batch에 포함합니다.
4. item이 하나도 없으면 heartbeat event를 추가합니다.
5. batch를 전송합니다.
6. 전송 실패 시 batch를 spool에 저장합니다.
7. `batch_interval`만큼 기다립니다.

## 운영 확인

Agent 로컬 확인:

```bash
castrelyx-agent -config /etc/castrelyx/agent.yaml -once
ls -l /var/lib/castrelyx-agent/certs
ls -l /var/lib/castrelyx-agent/spool
```

정상 enrollment 후 인증서 디렉터리에는 다음 파일이 있어야 합니다.

- `ca.pem`
- `client.pem`
- `client.key`
- `enrollment.json`

ClickHouse raw row 확인:

```bash
curl 'http://clickhouse.example.com:8123/?query=SELECT%20source_id,item_kind,item_type,item_key,received_at%20FROM%20castrelyx.castrelyx_agent_events%20ORDER%20BY%20received_at%20DESC%20LIMIT%2010'
```

Manager 쪽에서는 agent dashboard, traffic view, asset list가 raw/canonical telemetry를 조회합니다. Manager sync가 돌면 asset source binding이 `AGENT` source로 갱신됩니다.

## 문제 해결

| 증상 | 가능한 원인 | 확인/조치 |
|---|---|---|
| `manager_url must use https` | 설정 URL이 HTTP | CastrelSign HTTPS URL로 변경 |
| `enrollment_token is required` | 인증서 파일이 없고 token도 없음 | 새 enrollment token 발급 후 설정 |
| `tls authentication failed` | CA, server name, client cert 불일치 | `ca.pem`, `tls_server_name`, 인증서 CN 확인 |
| `/renew` 403 | 요청 `agent_id`와 client cert CN 불일치 | 설정의 `agent_id` 변경 여부 확인 |
| `/ingest` 403 | batch `source_id`와 client cert CN 불일치 | `agent_id`와 기존 인증서 CN 확인 |
| TCP NACK `bad_frame` | frame 길이/압축/JSON 오류 또는 source id 불일치 | `ingest_transport`, server name, agent id 확인 |
| spool 파일 증가 | 서버 연결 실패 또는 ingest 거부 | agent 로그, CastrelSign/Logparser 로그, 네트워크 확인 |
| 로그 중복 | 현재 log tailer가 cursor 없이 최근 로그를 반복 수집 | downstream dedup 또는 collector 비활성화 검토 |
| package/service/firewall 결과 없음 | OS 명령이 없거나 권한 부족 | `systemctl`, `dpkg-query`, `rpm`, `ufw` 등 설치/권한 확인 |

## 현재 구현상 제한

- Agent 설정 parser는 완전한 YAML parser가 아니라 단순 key/value와 `collectors` list만 처리합니다.
- `manager_url` 이름은 Manager처럼 보이지만 실제 agent API 구현은 CastrelSign에 있습니다.
- Log tailer는 cursor/bookmark를 파일로 저장하지 않습니다.
- 자유 텍스트 log message 내부 secret은 redaction하지 않습니다.
- Spool은 암호화하지 않습니다.
- Process command line과 환경변수는 수집하지 않습니다.
- Linux service startup type, binary path, user, last state change는 대부분 null입니다.
- Windows 일부 collector는 PowerShell과 CIM cmdlet 사용 가능 여부에 의존합니다.
- `tcp_mtls` 모드에서 enrollment는 여전히 HTTPS CastrelSign API가 필요합니다.
