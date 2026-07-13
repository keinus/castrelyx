# Castrelyx Agent

Castrelyx Agent는 Linux와 Windows 호스트 내부에서 실행되는 경량 Go 수집기입니다. 에이전트는 호스트 식별 정보, 리소스 지표, 네트워크 인터페이스, 프로세스, 서비스, 패키지, 방화벽 상태, 최근 보안/시스템 로그를 수집한 뒤 Castrelyx 서버 측 수신 파이프라인으로 전송합니다.

이 문서는 현재 저장소의 코드 구현을 기준으로 작성되었습니다. `docs/collection-architecture.md`에는 장기 설계 범위가 더 넓게 적혀 있지만, 아래 내용은 실제 `agent`, `CastrelSign`, `logparser`, `manager` 코드에서 확인되는 동작을 설명합니다.

## 구성 요소

| 영역 | 코드 위치 | 역할 |
|---|---|---|
| Agent 실행 파일 | `agent/cmd/castrelyx-agent` | 설정 로드, 인증서 등록/갱신, collector 실행, 전송 반복 |
| Agent 런타임 | `agent/internal/agent` | collector별 cadence 적용, delta/full snapshot 생성, batch chunking, durable spool enqueue와 독립 전송 |
| Collector | `agent/internal/collectors` | Linux/Windows 로컬 데이터 수집 |
| Envelope | `agent/internal/envelope` | batch/item 스키마와 민감 key redaction |
| Spool | `agent/internal/spool` | batch chunk를 개별 durable record로 저장하고 성공 ACK/DLQ를 관리 |
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
9. 상시 실행 Agent는 수집, spool 전송, remote task, updater, file manager를 서로 독립된 control loop로 실행합니다.
10. HTTPS 모드에서는 gzip JSON batch를 `/api/agent/ingest`로 POST합니다.
11. TCP/mTLS 모드에서는 gzip JSON batch 앞에 4바이트 big-endian 길이 prefix를 붙여 Logparser TCP ingest 포트로 전송하고, newline JSON ack를 기다립니다.
12. 수집 결과는 전송 전에 항상 크기 제한에 맞는 chunk로 나뉘어 로컬 segmented spool에 먼저 durable enqueue됩니다.
13. sender loop는 오래된 spool record부터 최대 25개씩 전송하고, 서버가 성공 응답한 record만 ACK하여 제거합니다. 일시 오류는 재시도하고 영구 오류나 잘못된 record는 dead-letter queue로 옮깁니다.
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
tcp_ingest_max_idle: 15s
batch_interval: 30s
sender_flush_interval: 2s
spool_dir: /var/lib/castrelyx-agent/spool
max_spool_record_bytes: 8mb
max_spool_bytes: 256mb
max_spool_records: 10000
max_spool_age: 168h
max_batch_items: 1000
max_batch_bytes: 4mb
max_item_bytes: 512kb
log_cursor_path: /var/lib/castrelyx-agent/spool/log-cursors.json
log_message_max_bytes: 1024
file_manager_enabled: false
file_manager_allow_delete: false
file_manager_max_transfer_bytes: 256mb
file_manager_poll_interval: 30s
file_manager_roots:
  - /var/log
  - /etc/castrelyx
  - /var/lib/castrelyx-agent
collector_interval_identity: 1h
collector_interval_metric: 30s
collector_interval_network: 5m
collector_interval_process: 2m
collector_interval_service: 5m
collector_interval_port: 2m
collector_interval_package: 12h
collector_interval_firewall: 10m
collector_interval_log_tailer: 30s
collector_interval_agent_health: 30s
collector_full_interval_identity: 24h
collector_full_interval_network: 1h
collector_full_interval_process: 15m
collector_full_interval_service: 1h
collector_full_interval_port: 15m
collector_full_interval_package: 24h
collector_full_interval_firewall: 1h
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
| `batch_interval` | 선택 | `30s` | collector scheduler의 기본 wake-up 주기입니다. 실제 collector 실행 여부는 collector별 cadence가 결정합니다. |
| `sender_flush_interval` | 선택 | `2s` | 독립 sender loop의 기본 spool flush 주기입니다. 실패 시 최대 1분까지 exponential backoff합니다. |
| `spool_dir` | 선택 | OS별 기본값 | 전송 전 batch chunk를 저장하는 segmented queue와 DLQ 디렉터리입니다. 상대 경로는 config 파일 위치 기준입니다. |
| `max_spool_record_bytes` | 선택 | `8mb` | spool record 하나의 최대 크기입니다. `kb`, `mb` suffix를 지원합니다. |
| `max_spool_bytes` | 선택 | `256mb` | pending queue와 DLQ record 파일을 합한 총 최대 크기입니다. 공간이 필요하면 가장 오래된 DLQ record부터 정리하며 pending record는 용량 확보 목적으로 버리지 않습니다. |
| `max_spool_records` | 선택 | `10000` | pending queue와 DLQ를 합한 최대 record 수입니다. |
| `max_spool_age` | 선택 | `168h` | pending record 보존 시간입니다. 초과 record는 DLQ로 이동하고, 이보다 오래된 DLQ record는 정리됩니다. |
| `max_batch_items` | 선택 | `1000` | batch chunk 하나에 넣는 최대 item 수입니다. |
| `max_batch_bytes` | 선택 | `4mb` | JSON encoding 후 batch chunk의 최대 크기입니다. `max_spool_record_bytes` 이하여야 합니다. |
| `max_item_bytes` | 선택 | `512kb` | JSON encoding 후 item 하나의 최대 크기입니다. `max_batch_bytes` 이하여야 합니다. |
| `log_cursor_path` | 선택 | `${spool_dir}/log-cursors.json` | log tailer cursor 저장 파일입니다. 상대 경로는 config 파일 위치 기준입니다. |
| `log_message_max_bytes` | 선택 | `1024` | log tailer가 전송하는 message 최대 byte 길이입니다. |
| `cert_dir` | 선택 | OS별 기본값 | client key/cert, CA cert, enrollment metadata 저장 디렉터리입니다. |
| `ca_cert_path` | 선택 | `${cert_dir}/ca.pem` | 서버 인증서 검증에 사용할 CA PEM 경로입니다. |
| `client_cert_path` | 선택 | `${cert_dir}/client.pem` | agent client certificate PEM 경로입니다. |
| `client_key_path` | 선택 | `${cert_dir}/client.key` | agent private key PEM 경로입니다. |
| `tls_server_name` | 선택 | 빈 값 | HTTPS enrollment/ingest에서 검증할 TLS ServerName입니다. |
| `ingest_transport` | 선택 | `https` | `https` 또는 `tcp_mtls`만 허용합니다. |
| `tcp_ingest_addr` | 조건부 | 없음 | `ingest_transport: tcp_mtls`일 때 필수입니다. `host:port` 형식입니다. |
| `tcp_ingest_server_name` | 선택 | `tls_server_name` | TCP/mTLS server certificate 검증용 ServerName입니다. |
| `tcp_ingest_max_idle` | 선택 | `15s` | 재사용 TLS 연결을 선제 교체하는 최대 유휴 시간입니다. Logparser input adapter의 `timeoutMs`보다 작고 30초 미만이어야 합니다. |
| `file_manager_enabled` | 선택 | `false` | CastrelSign mTLS command channel 기반 파일 관리자 polling을 켜거나 끕니다. 명시적으로 활성화해야 합니다. |
| `file_manager_roots` | 선택 | OS별 기본 root | 허용 root 목록입니다. Linux 기본은 `/var/log`, `/etc/castrelyx`, `/var/lib/castrelyx-agent`이고 Windows 기본은 `%ProgramData%\Castrelyx\files` 하나입니다. |
| `file_manager_allow_delete` | 선택 | `false` | 삭제뿐 아니라 move와 기존 대상 overwrite를 허용할지 결정합니다. 파일 관리자를 켜도 이 파괴적 동작은 별도로 활성화해야 합니다. |
| `file_manager_max_transfer_bytes` | 선택 | `256mb` | 업로드/다운로드 단일 파일 최대 크기입니다. |
| `file_manager_poll_interval` | 선택 | `30s` | 파일 관리자 command polling 주기입니다. |
| `collector_interval_<name>` | 선택 | 아래 cadence 표 | collector별 실행 주기입니다. 등록된 collector 이름만 허용합니다. |
| `collector_full_interval_<name>` | 선택 | 아래 cadence 표 | `identity`, `network`, `process`, `service`, `port`, `package`, `firewall`의 full snapshot 주기입니다. |
| `collectors` | 선택 | 기본 collector 전체 | 활성 collector 목록입니다. 알 수 없는 이름은 설정 오류입니다. |

`manager_url`과 enrollment 응답의 `ingest_url`은 모두 `https`만 허용됩니다. HTTP URL을 넣으면 설정 또는 sender 생성 단계에서 실패합니다.

현재 기본 cadence는 다음과 같습니다. scheduler는 `batch_interval`마다 깨어나지만, 아직 주기가 되지 않은 collector는 실행하지 않습니다.

| Collector | 실행 주기 | Full snapshot 주기 |
|---|---:|---:|
| `identity` | 1시간 | 24시간 |
| `metric` | 30초 | 해당 없음 |
| `network` | 5분 | 1시간 |
| `process` | 2분 | 15분 |
| `service` | 5분 | 1시간 |
| `port` | 2분 | 15분 |
| `package` | 12시간 | 24시간 |
| `firewall` | 10분 | 1시간 |
| `log_tailer` | 30초 | 해당 없음, cursor 증분 수집 |
| `agent_health` | 30초 | 해당 없음 |

## 수집 데이터

Agent batch는 항상 다음 envelope로 전송됩니다.

```json
{
  "schema_version": "1.1",
  "batch_id": "5fb34990d93a47ac93fe346bf871d62d",
  "chunk_index": 0,
  "chunk_count": 1,
  "source": "agent",
  "source_id": "prod-web-01",
  "tenant_id": "default",
  "observed_at": "2026-06-16T05:00:00Z",
  "sent_at": "2026-06-16T05:00:01Z",
  "items": [
    {
      "item_id": "5fb34990d93a47ac93fe346bf871d62d:0",
      "sequence": 0,
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

`batch_id`는 collection cycle마다 생성되고, `item_id`는 기본적으로 `<batch_id>:<sequence>`입니다. item 수나 JSON byte 한계를 넘으면 동일한 `batch_id`를 유지한 채 `chunk_index`/`chunk_count`가 다른 여러 batch로 나뉩니다. 이 식별자는 수신 측 중복 억제와 장애 추적을 위한 것이며 exactly-once 전달을 의미하지 않습니다. 서버가 수신한 뒤 ACK가 유실되거나 Agent가 로컬 ACK 전에 종료되면 같은 chunk가 재전송될 수 있습니다.

### Delta와 full snapshot

`identity`, `network`, `process`, `service`, `port`, `package`, `firewall`의 `asset`/`state` item은 매 실행마다 모두 전송하지 않습니다.

- 첫 수집과 `collector_full_interval_<name>` 도래 시 현재 item 전체를 `snapshot_full: true`로 전송합니다. 같은 collector의 item은 `<batch_id>:<collector>` 형식의 동일한 `snapshot_id`와 전체 inventory 크기인 `snapshot_item_count`를 공유합니다.
- 그 사이에는 새로 생기거나 payload가 바뀐 item만 `snapshot_full: false`로 전송합니다. 이 delta에도 collector별 `snapshot_id`와 현재 inventory의 `snapshot_item_count`가 포함됩니다.
- 이전에 있던 key가 사라지면 delta/full 모두 같은 kind/type/key에 `deleted: true` tombstone을 전송합니다. 정상적으로 빈 집합이 될 수 있는 socket inventory도 마지막 socket이 닫히면 정확히 제거됩니다.
- 명령/권한 실패는 collector가 오류를 반환해 state filter를 건너뛰므로 마지막 cache를 보존합니다. 오류 없이 반환된 빈 inventory는 유효한 완전 집합으로 처리하고, full 주기에는 `snapshot_item_count: 0`, `snapshot_full: true` tombstone이 complete watermark를 만듭니다.
- 상태 hash와 collector별 마지막 full 시각은 `${spool_dir}/state-cache.json`에 `0600` atomic file로 저장됩니다. Linux에서는 rename 뒤 parent directory도 `fsync`하므로 정상 재시작 후에는 변경분만 이어서 보냅니다. 파일이 없거나 손상되면 health의 `state_cache_error`에 드러나며, cache가 없는 collector는 다음 수집을 full로 만들고 성공한 저장으로 손상 파일을 교체합니다.
- full snapshot은 현재 전체 집합과 이전 집합에서 사라진 key의 tombstone을 함께 보냅니다. Manager는 동일 `snapshot_id`의 고유 key 수가 선언된 `snapshot_item_count` 이상인 full snapshot만 해당 state type의 최신 full watermark로 채택하므로, 일부 chunk만 도착한 full이 이전 상태를 조기에 숨기지 않습니다.

`metric`, `event`, log tailer item은 위 state delta filter를 통과하지 않고 collector 결과 그대로 enqueue됩니다.

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
- `/sys/class/thermal`, `/sys/class/hwmon`
  - sensor별 `host.temperature.celsius`
  - `source`, `sensor`, `chip`, `path` label로 thermal zone/hwmon 원천 구분
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
| `source` | 온도 metric일 때 `thermal` 또는 `hwmon` |
| `sensor` | 온도 sensor 이름 |
| `chip` | hwmon chip 이름 |
| `path` | 온도 값을 읽은 sysfs path |

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

- `journald` 우선 수집
- `journalctl`이 없거나 실행/파싱에 실패하거나 최초 빈 결과로 journal coverage를 증명하지 못할 때 `/var/log/auth.log`, `/var/log/secure`, `/var/log/syslog`, `/var/log/messages`로 자동 폴백
- `/var/log/suricata/eve.json`
- `/var/log/suricata/fast.log`
- `/var/log/suricata/suricata.log`
- `/var/log/zeek/current/{conn,notice,dns,http,ssl,weird}.log`
- `/opt/zeek/logs/current/{conn,notice,dns,http,ssl,weird}.log`
- `/usr/local/zeek/logs/current/{conn,notice,dns,http,ssl,weird}.log`
- `/var/log/bro/current/{conn,notice,dns,http,ssl,weird}.log`

Linux file log는 cursor 파일에 file identity, offset, 128-byte checkpoint와 미완성 line fragment를 저장합니다. 첫 실행이나 rotation/truncation 감지 시에는 최대 64 KiB window에서 최근 50줄만 bounded resync하고, 이후에는 실행당 최대 64 KiB와 완성된 200줄을 오래된 순서대로 처리합니다. 줄이 여러 read에 걸치면 최대 64 KiB까지 cursor에 이어 붙이고, 그보다 긴 줄은 `line_truncated: true`로 명시합니다. 존재하지 않는 Suricata/Zeek 경로는 조용히 건너뜁니다. journald는 첫 실행 최근 50개, 이후 `--after-cursor` 기준 최대 200개를 오래된 순서대로 처리합니다. journald가 정상인 동안에는 rsyslog가 복제한 auth/system 파일 4종을 다시 읽지 않아 같은 이벤트의 이중 적재를 막고, Suricata/Zeek 전용 파일은 계속 수집합니다. journald 실패 주기는 journal cursor 변경을 폐기하고 bounded file tail로 폴백합니다. `collector_error` 진단은 fallback 전환 때 한 번만 남기고 정상 복구 후 다시 실패할 때까지 반복하지 않습니다.

Windows 수집:

- `Security` channel
- `System` channel
- `Application` channel
- `Windows PowerShell` channel
- `Microsoft-Windows-PowerShell/Operational` channel
- `Microsoft-Windows-TerminalServices-LocalSessionManager/Operational` channel
- `Microsoft-Windows-Windows Defender/Operational` channel

Windows Event Log는 channel별 `RecordId`를 cursor 파일에 저장합니다. 첫 실행은 channel별 최근 20개를 수집하고, 이후에는 `EventRecordID > cursor` 조건과 `-Oldest`로 실행당 최대 100개를 오래된 순서대로 전송합니다. incremental 결과가 없으면 같은 PowerShell 호출에서 최신 1건의 RecordId를 확인하고, channel clear로 최신 ID가 저장 cursor보다 작아졌을 때만 cursor를 초기화해 새 로그를 다시 따라갑니다.

file offset, journald cursor, Windows `RecordId`는 수집 직후 바로 저장하지 않습니다. collection cycle의 모든 batch chunk가 segmented spool에 성공적으로 enqueue된 뒤에만 pending cursor를 temp path와 rename을 거쳐 저장합니다. enqueue 또는 cursor 저장이 실패하면 다음 cycle은 마지막 committed cursor에서 재생하므로 누락보다 중복 가능성을 선택합니다. load/save/scanner 오류도 collector 오류로 노출됩니다.

관련 설정:

| 설정 | 기본값 | 설명 |
|---|---|---|
| `log_cursor_path` | `<spool_dir>/log-cursors.json` | Linux file offset, journald cursor, Windows RecordId 저장 파일 |
| `log_message_max_bytes` | `1024` | payload message 최대 byte 길이 |

전송 형태:

| 필드 | 설명 |
|---|---|
| `kind` | `event` |
| `type` | `log` |
| `key` | `source_name:dedup_hash_prefix` |
| `payload.event_type` | `pam.session.open`, `auth.login.failure`, `auth.login.success`, `auth.privilege.sudo`, `system.service.failure`, `system.kernel`, `suricata.alert`, `zeek.notice`, `zeek.conn` 등 |
| `payload.event_category` | `auth`, `system`, `security` 등 |
| `payload.platform` | `linux` 또는 `windows` |
| `payload.source` | `agent` |
| `payload.source_name` | file path, `journald`, Windows channel |
| `payload.channel` | auth/system/journald 또는 Windows channel |
| `payload.program` | Linux syslog identifier 또는 process name |
| `payload.provider` | Windows provider, `suricata`, `zeek` |
| `payload.pid` | Linux PID |
| `payload.event_id` | Windows Event ID |
| `payload.record_id` | Windows RecordId |
| `payload.event_time` | log 원천 timestamp |
| `payload.observed_at` | 수집 시각 |
| `payload.actor` | 추출 가능한 user/account |
| `payload.action` | `login`, `session.open`, `privilege.sudo`, `service.failure` 등 |
| `payload.outcome` | `success`, `failure`, `unknown` |
| `payload.severity` | `INFO`, `WARNING`, `ERROR` |
| `payload.message` | scrub 및 길이 제한이 적용된 짧은 message |
| `payload.raw_ref` | 현재 기본값 null |
| `payload.dedup_key` | platform/source/time/record/message 기반 SHA-256 |

Suricata `eve.json` alert는 `signature`, `signature_id`, `src_ip`, `src_port`, `dest_ip`, `dest_port`, `proto`, `flow_id` 같은 구조화 필드를 추가합니다. Zeek TSV log는 header line을 전송하지 않고, `conn`, `notice`, `dns`, `http`, `ssl`, `weird` log별로 `uid`, endpoint, protocol, query, status, note 등 핵심 필드를 추가합니다.

`payload.message`는 정규화 보조용 짧은 문자열입니다. `password`, `token`, `api_key`, `authorization`, `secret`, `credential`, Bearer token 형태의 값은 scrub한 뒤 `log_message_max_bytes`로 제한합니다. 전체 raw log 보존은 기본 동작에 포함하지 않습니다.

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
- 인증서·private key·CA 중 하나라도 없거나 서로 맞지 않으면 원격 갱신 실패를 무시하지 않고 identity 확보 단계에서 실패합니다.

원격 서버 장애나 서버 인증서 만료 때문에 갱신이 실패해도 기존 client certificate, private key, CA가 key pair·agent CN·명시적 clientAuth·단일 self-signed P-256 CA chain·root validity horizon 검증을 모두 통과하면 Agent는 서버 검증을 우회하지 않고 로컬 수집과 durable spooling을 계속합니다. 전송·remote task는 정상 TLS 검증에 실패한 채 backoff하고, identity loop는 1분부터 최대 15분까지 지수 backoff로 갱신을 재시도합니다. 평상시에도 매시간 만료 임박 여부를 검사합니다. 갱신 응답은 staging에서 private key 일치, configured agent id, clientAuth, 기존 CA chain, 실제 certificate/metadata 만료 시각을 검증하며 CA 추가·교체 응답은 명시적 trust migration 없이는 거부합니다. 검증된 certificate·CA·metadata는 restricted backup과 client-certificate target 기반 transaction marker를 남긴 뒤 원자 교체하고, 중단된 commit은 다음 시작에서 이전 bundle로 복원합니다. Process-local mutex와 canonical target별 OS file lock을 함께 사용하므로 service 실행 중 별도 `-once`나 같은 target을 공유하는 다른 config가 시작되어도 key 생성·recovery·commit이 겹치지 않습니다. Commit 완료 결과를 받은 뒤에만 sentinel 오류로 서비스를 종료해 systemd `Restart=always` 또는 Windows SCM failure action이 process를 다시 시작하고 모든 TLS client가 새 identity를 함께 로드하게 합니다. 저장된 metadata가 agent/HTTPS/expiry 검증에 실패하면 그 목적지는 사용하지 않고 configured manager ingest URL로 되돌아갑니다. `-once` 실행은 background retry를 시작하지 않습니다.

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
- Agent는 gzip에 CPU 비용이 낮은 `BestSpeed`를 사용하고 성공한 TLS connection을 다음 frame에도 재사용합니다. I/O 오류나 영구 NACK 뒤에는 connection을 닫고 다음 시도에서 다시 연결합니다.
- Agent의 compressed frame 상한은 8 MiB입니다. Logparser 기본 상한은 compressed frame 10 MiB, decompressed JSON 16 MiB, batch item 5,000개입니다.
- 서버는 frame 길이가 0 이하이거나 `maxFrameBytes`를 넘으면 NACK를 반환합니다.
- 서버는 client certificate CN과 batch `source_id`가 다르면 NACK를 반환합니다.
- schema `1.1`에서는 nonblank `batch_id`, 유효한 `chunk_index`/`chunk_count`, 배열 `items`, item별 nonblank `item_id`, chunk 안에서 중복되지 않는 0 이상의 정수 `sequence`를 검증합니다.
- 서버는 batch 전체가 input queue에 들어갈 여유가 있는지 확인한 뒤 한 번에 enqueue하고 `{"status":"accepted"}` newline ACK를 반환합니다. 여유가 없으면 `queue_full` NACK로 전체 chunk를 재시도하게 하여 부분 enqueue를 피합니다.
- `queue_full`, `busy`, `retry_later` NACK는 transient로 재시도하고 그 밖의 NACK와 oversized frame은 DLQ 대상으로 분류합니다.
- Logparser는 최근 50,000개의 `source_id:batch_id:chunk_index`를 process memory에서 기억해 같은 process 수명 안의 최근 재전송을 input queue 앞에서 억제합니다.
- ClickHouse output은 schema `1.1` item을 `source_id`/`batch_id`/`chunk_index` 단위로 모아 `chunk_item_count`개가 모두 도착하면 `item_sequence` 순서로 한 번에 insert합니다. raw와 canonical telemetry table 각각에 chunk identity 기반의 안정적인 dedup token을 사용하므로 재시도에서 `sent_at`이나 body가 달라져도 같은 완성 chunk의 token은 같습니다.
- 불완전 chunk group은 기본 30초 timeout, 최대 2,048 groups/50,000 items, adapter 종료 시 강제 flush됩니다. 불완전 group에는 완성 chunk용 안정 token을 사용하지 않습니다.

Agent의 로컬 spool 재시도 의미는 at-least-once이지만 TCP ACK는 Logparser의 메모리 input queue 수락을 뜻할 뿐 ClickHouse commit을 뜻하지 않습니다. 따라서 ACK 뒤 Logparser가 저장 전에 종료되면 데이터가 유실될 수 있고, ACK 유실·cache eviction·Logparser 재시작·ClickHouse dedup window 초과 때는 중복이 생길 수 있습니다. 안정 token은 이 범위를 줄이지만 end-to-end exactly-once나 무손실 전달을 보장하지 않습니다.

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
- 일반 payload 문자열 내부의 secret pattern은 redaction하지 않습니다.
- batch item의 top-level `key` 필드는 redaction 대상이 아니고, `payload` 내부 key만 대상입니다.
- log tailer의 `payload.message`는 별도 scrubber와 길이 제한을 적용합니다.

### 로컬 spool 보안

모든 새 batch chunk는 네트워크 전송 전에 `${spool_dir}/queue/<record-id>.json`에 개별 record로 저장됩니다. 기존 `${spool_dir}/queue.ndjson`이 있으면 시작 시 segmented record로 migration한 뒤 legacy 파일을 제거합니다.

특성:

- spool 디렉터리는 `0700`으로 생성됩니다.
- queue/dead-letter 디렉터리는 `0700`, record 파일은 `0600`으로 생성됩니다.
- 각 record는 시간순 정렬이 가능한 id, 생성 시각, JSON payload를 갖습니다. temp file에 쓴 뒤 file `Sync`와 rename으로 publish하고, 비-Windows에서는 parent directory도 `fsync`합니다.
- `max_spool_bytes`와 `max_spool_records`는 pending과 DLQ의 합계를 제한합니다. 새 enqueue 공간이 필요하면 가장 오래된 DLQ를 먼저 지우지만, 아직 ACK되지 않은 pending record를 용량 확보 목적으로 버리지는 않습니다. DLQ를 정리해도 한계를 맞출 수 없으면 collection enqueue가 실패합니다.
- `max_spool_age`를 넘은 pending record, spool envelope는 유효하지만 batch payload JSON decode가 불가능한 record, 영구 delivery 오류 record는 `${spool_dir}/deadletter`로 옮기고 reason을 기록합니다. age를 넘은 DLQ record와 총량 한계 때문에 밀려난 가장 오래된 DLQ record는 자동 정리됩니다.
- 손상된 queue record는 원본 파일명과 base64 원문을 담은 진단 record로 DLQ에 격리하고, `Peek`는 다음 정상 record를 계속 처리합니다.
- 독립 sender loop가 오래된 순서로 한 번에 최대 25개를 전송하고, 성공적으로 전송된 record id만 ACK하여 제거합니다.
- 일시 오류 record는 queue에 남아 exponential backoff로 재시도합니다.
- health snapshot은 pending/DLQ/합계 record와 byte, pending oldest age를 노출합니다.

현재 구현은 spool 파일을 별도로 암호화하지 않습니다. 보안은 filesystem permission과 실행 계정 분리로 보호합니다. 디스크 암호화가 필요한 환경에서는 OS 레벨 disk encryption 또는 별도 encrypted volume에 `spool_dir`를 두는 구성이 필요합니다. DLQ는 자동 재전송되지 않으며 총량/age 제한 때문에 오래된 진단 record가 제거될 수 있으므로 외부 보관이 필요하면 운영자가 먼저 추출해야 합니다. 여러 chunk를 넣다가 뒤 chunk 또는 state-cache 저장이 실패하면 이번 cycle에서 이미 넣은 record를 제거해 rollback하지만, 여러 파일을 아우르는 filesystem transaction은 아니므로 process/전원 장애 시점에 따라 일부 chunk가 남을 수 있습니다.

### File manager와 updater의 streaming I/O

File manager는 기본적으로 비활성화되어 있고(`file_manager_enabled: false`), 활성화해도 삭제는 기본적으로 거부합니다(`file_manager_allow_delete: false`). Linux 기본 root는 `/var/log`, `/etc/castrelyx`, `/var/lib/castrelyx-agent`이고 Windows 기본 root는 `%ProgramData%\Castrelyx\files` 하나입니다. 기존 경로와 새 경로의 parent symlink를 resolve한 뒤 허용 root 내부인지 확인합니다. configured root 자체는 rename/delete/move할 수 없고, directory COPY/MOVE는 source와 같은 경로 또는 그 하위 target을 거부합니다. recursive copy는 내부 symlink를 따라가지 않고 오류로 종료해 허용 root 밖 파일의 복제와 재귀 디스크 고갈을 막습니다. `file_manager_allow_delete: false`이면 delete뿐 아니라 move, copy/upload의 기존 대상 overwrite도 거부합니다.

production HTTP transport의 파일 업로드/다운로드는 전체 파일을 memory에 올리지 않습니다. 서버에서 Agent로 받는 파일은 같은 디렉터리의 temp file에 최대 `file_manager_max_transfer_bytes`까지 stream하고 size 확인, file `Sync`, rename 순서로 publish합니다. Agent에서 서버로 보내는 파일도 열린 파일에서 request body로 stream합니다. 기존 `Transport` interface만 구현한 외부 adapter/test double은 호환 경로에서 byte slice buffering을 사용할 수 있습니다.

Updater도 artifact를 최대 128 MiB까지 temp file로 stream하면서 SHA-256을 동시에 계산합니다. Ed25519 manifest signature, manifest/release의 OS·architecture·size와 artifact SHA-256을 검증한 뒤 file `Sync`와 durable replace로 stage하며, 검증 전에 실행 파일을 교체하지 않습니다. `APPLY_INTENT`/`APPLYING`과 `ROLLBACK_INTENT`/`ROLLBACKING`은 source-target hash로 실제 교체 여부를 확인하고 terminal 상태의 staging은 제거합니다.

새 바이너리는 전체 config, spool, enrollment, TLS 초기화 전에 `update_dir`만 관대하게 찾아 startup probation을 시작합니다. 첫 실행에는 1회를 허용하고 첫 collection이 성공하면 원격 상태 보고보다 먼저 `probation_passed`를 durable 저장합니다. 건강 표시 없이 다시 시작되면 이전 바이너리로 rollback하고 재시작하므로 config 호환성이나 초기화 회귀도 restart loop로 남지 않습니다. Windows 설치 스크립트는 SCM failure action을 함께 설정하고, replacement helper는 교체 성공 여부와 서비스 재시작 오류를 main update state에 반영합니다.

이 watchdog은 교체된 실행 파일의 `main`에 진입할 수 있어야 동작합니다. OS loader 실패, 지원하지 않는 CPU instruction, package `init` panic처럼 프로세스가 recovery 코드에 도달하지 못하는 artifact까지 자동 복구하려면 업데이트 대상과 분리된 이전 버전 기반 external launcher가 추가로 필요합니다. 현재 installer는 이 별도 launcher를 설치하지 않습니다.

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
- `batch_id`
- `chunk_index`
- `chunk_count`
- `chunk_item_count`
- `source`
- `source_id`
- `tenant_id`
- `observed_at`
- `sent_at`
- `item_kind`
- `item_type`
- `item_key`
- `item_id`
- `item_sequence`
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
  batch_id String DEFAULT '',
  chunk_index UInt32 DEFAULT 0,
  chunk_item_count UInt32 DEFAULT 0,
  item_sequence UInt32 DEFAULT 0,
  item_id String DEFAULT '',
  item_kind Nullable(String),
  item_type Nullable(String),
  item_key Nullable(String),
  event_json String
)
ENGINE = MergeTree
PARTITION BY toDate(received_at)
ORDER BY (received_at, source_id, ifNull(item_key, ''))
TTL toDateTime(received_at) + INTERVAL 7 DAY DELETE
```

schema `1.1` chunk는 `batchSize`가 아니라 선언된 `chunk_item_count`가 찰 때 flush됩니다. `batchSize: 100`과 `flushIntervalMs: 5000`은 chunk metadata가 없는 legacy event buffer에 적용됩니다. Chunk group은 기본 `incompleteGroupTimeoutMs: 30000`, `maxPendingGroups: 2048`, `maxPendingItems: 50000`, `maxPendingBytes: 67108864`의 전역 상한을 함께 적용합니다. 시간·개수·byte 상한이나 adapter 종료로 불완전하게 남은 chunk는 canonical table에 쓰지 않고 기본 128 MiB/1,000 records의 durable DLQ로 격리합니다. 인증은 `CLICKHOUSE_USER`, `CLICKHOUSE_PASSWORD` 환경변수에서 읽어 Basic Auth header를 구성합니다.

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
log_cursor_path: /var/lib/castrelyx-agent/spool/log-cursors.json
log_message_max_bytes: 1024
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
log_cursor_path: C:\ProgramData\Castrelyx\spool\log-cursors.json
log_message_max_bytes: 1024
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

sc.exe failure CastrelyxAgent reset= 86400 actions= restart/5000/restart/5000/restart/30000
sc.exe failureflag CastrelyxAgent 1
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

상시 실행은 역할별 control loop를 독립적으로 실행합니다.

| Loop | 기본 cadence | 동작 |
|---|---:|---|
| Collection | `batch_interval: 30s` ±10% jitter | due collector만 순서대로 실행하고, delta/full filter와 chunking 후 모든 chunk를 spool에 enqueue합니다. collector 오류는 `collector_error` event로 포함하고 item이 없으면 idle heartbeat를 만듭니다. |
| Sender | `sender_flush_interval: 2s` ±20% jitter | 오래된 pending record부터 최대 25개씩 보냅니다. 실패 시 exponential backoff를 적용하며 최대 1분입니다. |
| Remote task | `remote_task_interval: 10s` ±20% jitter | 활성화된 경우 command를 poll하고 실행합니다. |
| Updater | 초기 probation, 이후 시작 약 1초/`update_check_interval: 6h` ±10% jitter | config/TLS 이전 probation과 rollback 복구를 먼저 수행하고, 이후 artifact 확인/검증/적용 및 원격 상태 보고를 collection loop와 분리합니다. |
| File manager | `file_manager_poll_interval: 30s` | 명시적으로 활성화된 경우 별도 goroutine에서 command를 poll합니다. |
| Identity | 정상 시 `1h`, 갱신 장애 시 `1m`→`15m` ±10% jitter | 만료 7일 전부터 인증서를 갱신합니다. 기존 strict TLS identity가 완전하면 원격 장애 중에도 수집/spool을 유지하고, 갱신 자료를 모두 저장한 뒤 오류 종료로 service restart를 유도합니다. |

따라서 서버 전송 지연은 host observation을 직접 멈추지 않습니다. 다만 pending spool이 byte/record 한계에 도달하면 새 collection chunk를 durable enqueue할 수 없어 해당 collection cycle은 실패합니다. `-once`는 호환용 단발 경로로 한 번 수집/enqueue한 뒤 pending record를 최대 25개 flush합니다.

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
| 로그 중복 | cursor 파일 삭제, log rotation 후 bounded resync, journal cursor invalidation | `log_cursor_path` 파일 권한/상태와 `dedup_key` 확인 |
| package/service/firewall 결과 없음 | OS 명령이 없거나 권한 부족 | `systemctl`, `dpkg-query`, `rpm`, `ufw` 등 설치/권한 확인 |

## 현재 구현상 제한

- Agent 설정 parser는 완전한 YAML parser가 아니라 단순 key/value와 `collectors`, `file_manager_roots` list만 처리합니다.
- `manager_url` 이름은 Manager처럼 보이지만 실제 agent API 구현은 CastrelSign에 있습니다.
- Log tailer cursor는 local JSON 파일 기반이며, Windows는 Event Log bookmark XML이 아니라 channel별 `RecordId`를 저장합니다.
- Log cursor는 spool enqueue 후 commit되어 누락보다 재생을 선택하지만, cursor commit 실패나 ACK 유실 시 중복 event가 생길 수 있습니다.
- 일반 payload 자유 텍스트 문자열은 key 기반 redaction만 적용합니다. 단, log tailer message는 pattern scrub과 길이 제한을 적용합니다.
- Spool은 암호화하지 않습니다.
- 여러 chunk enqueue와 state-cache 저장은 실패 시 이번 cycle의 이미 쓴 queue record를 제거하지만 하나의 filesystem transaction은 아닙니다. process/전원 장애 경계에서는 일부 chunk가 남아 재전송될 수 있습니다.
- Agent spool, Logparser 최근 batch cache, ClickHouse dedup token은 중복 가능성을 줄이지만 end-to-end exactly-once를 보장하지 않습니다. 특히 TCP ACK는 메모리 queue 수락 시점이고 ClickHouse commit 시점이 아닙니다.
- DLQ는 pending과 함께 총량/age 제한을 적용하며 자동 재처리하지 않습니다. 공간 확보 과정에서 오래된 DLQ부터 삭제되므로 장기 증적 보관소로 사용할 수 없습니다.
- Process command line과 환경변수는 수집하지 않습니다.
- Linux service startup type, binary path, user, last state change는 대부분 null입니다.
- Windows 일부 collector는 PowerShell과 CIM cmdlet 사용 가능 여부에 의존합니다.
- `tcp_mtls` 모드에서 enrollment는 여전히 HTTPS CastrelSign API가 필요합니다.
