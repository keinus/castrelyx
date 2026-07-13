# Castrelyx 수집 아키텍처 및 장비별 스키마 설계

## 1. 목표와 전제

Castrelyx의 수집 목표는 여러 종류의 호스트와 네트워크 장비를 하나의 관제 자산 모델로 정규화하여, 장애 징후, 보안 이벤트, 네트워크 상태, 변경 가능성을 빠르게 판단할 수 있게 하는 것이다.

현재 허용된 수집 방식은 다음 두 가지로 제한한다.

| 수집 방식 | 적용 대상 | 핵심 역할 |
|---|---|---|
| 설치형 Agent | Linux, Windows 서버, Windows host | OS 내부 상태, 프로세스, 서비스, 포트, 패키지, 로그, 보안 이벤트, 리소스 metric 수집 |
| SNMP Polling | 라우터, 방화벽, 네트워크 장비, SNMP가 활성화된 Linux/Windows | 표준 MIB 기반 장비 식별, 인터페이스, 트래픽, 라우팅, TCP/UDP 통계, BGP/OSPF 상태 수집 |

현재 범위에서 제외하는 방식은 다음과 같다.

| 제외 방식 | 제외 이유 |
|---|---|
| SSH 원격 명령 수집 | 운영 계정 관리, 권한 통제, 장비별 명령 차이가 큼 |
| 장비 벤더 API | v1에서 공통 표준 수집 모델을 먼저 만들기 위함 |
| 원격 Syslog 수집 | 현재 허용된 수집 방식이 agent와 SNMP뿐임 |
| 방화벽 정책 원문 수집 | 표준 SNMP MIB 범위를 벗어나고 민감도가 높음 |
| NAT 룰 원문 수집 | 표준 SNMP MIB 범위를 벗어나고 장비별 차이가 큼 |

단, Agent가 설치된 장비 내부의 로그는 agent log tailer가 수집할 수 있다. 예를 들어 Linux 서버의 journald(불가 시 `/var/log/auth.log` 폴백)나 Windows 서버의 Security Event Log는 agent 수집 범위에 포함한다.

## 2. 전체 수집 모델

Castrelyx는 수집 데이터를 다음 네 가지 형태로 나누어 저장한다.

| 데이터 형태 | 설명 | 예시 |
|---|---|---|
| Asset Snapshot | 장비의 비교적 정적인 식별 정보 | hostname, OS, firmware, vendor, model, serial |
| State Snapshot | 현재 상태를 나타내는 구조화 정보 | process list, service status, route table, BGP peer state |
| Metric | 시간에 따라 변하는 숫자형 지표 | CPU 사용률, memory 사용률, interface traffic, TCP reset count |
| Event | 특정 시점에 발생한 보안/운영 이벤트 | 로그인 실패, 서비스 중단, 인터페이스 down, BGP peer down |

수집 파이프라인은 다음 순서로 동작한다.

1. 자산 발견 또는 등록
2. 수집 채널 결정
3. capability discovery 수행
4. collector 실행
5. raw data 수집
6. 정규화 schema 변환
7. asset correlation 및 중복 제거
8. metric/event/state 저장
9. alert rule 및 dashboard에서 사용

## 3. 수집 채널별 설계

### 3.1 설치형 Agent

Agent는 Windows와 Linux에 설치되는 로컬 collector이다. Agent는 장비 내부에서 직접 정보를 읽기 때문에 SNMP보다 훨씬 깊은 정보를 수집할 수 있다.

Agent는 다음 collector 모듈로 구성한다.

| Collector | 수집 방식 | 주요 수집 정보 |
|---|---|---|
| Identity Collector | OS API, system file, registry | hostname, OS, kernel/build, architecture, boot time, machine id |
| Metric Collector | `/proc`, `df`, performance counter, WMI/CIM | CPU, memory, mount/drive별 disk 사용량, interface별 ingress/egress traffic bytes |
| Network Collector | `ip`, `/proc/net`, Windows networking API | IP, MAC, NIC, route, DNS, gateway |
| Process Collector | `/proc`, Windows process API | process name, PID, parent PID, executable path, resource usage, 연결된 socket key |
| Service Collector | systemd, Windows Service API | service name, status, startup type |
| Port/Socket Collector | `ss`, `/proc/net`, Windows TCP/UDP table API | listening socket, established remote connection, local/remote address와 port, owning process |
| Package Collector | dpkg/rpm/apk/pacman, registry/WMI | installed package/application, version, vendor |
| Firewall Collector | nftables/iptables/ufw, Windows Firewall API | host firewall enabled state, rule summary |
| Log Tailer | journald/file tailer, Windows Event Log record id | authentication, system, service, security event |
| Agent Health Collector | internal heartbeat | agent version, last collection time, queue size, error state |

v1 agent의 기본 collector set은 `identity`, `metric`, `network`, `process`, `service`, `port`, `package`, `firewall`, `log_tailer`, `agent_health`이다. `metric` collector는 disk와 network byte counter를 함께 내보내고, `port` collector는 단순 listening port가 아니라 socket state를 수집한다.

Agent는 원칙적으로 outbound 방식으로 Castrelyx 서버에 연결한다. 수집 결과는 전송 성공 여부와 무관하게 먼저 로컬 segmented spool queue에 durable enqueue하고, 독립 sender loop가 재연결 후 오래된 record부터 순서대로 전송한다.

#### 3.1.1 현재 Agent control loop

상시 실행 Agent는 한 개의 직렬 poll loop가 아니라 다음 control loop를 분리한다.

| Loop | 기본 cadence | 자원/장애 격리 방식 |
|---|---:|---|
| Collection scheduler | 30초, ±10% jitter | due collector만 실행한다. 수집 결과는 batch chunk로 나눠 spool에 enqueue하므로 전송 latency와 분리된다. |
| Sender | 2초, ±20% jitter | 한 번에 오래된 record 최대 25개를 보낸다. 일시 실패는 exponential backoff하며 최대 1분이다. |
| Remote task | 10초, ±20% jitter | 활성화된 경우 별도 goroutine에서 command를 poll한다. |
| Updater | 시작 후 약 1초, 이후 6시간 ±10% jitter | update artifact 확인/검증/적용과 post-update 상태 처리를 collection loop와 분리한다. |
| File manager | 30초 | 명시적으로 활성화한 경우에만 별도 goroutine에서 command를 poll한다. |

전송 장애가 host observation을 직접 멈추지는 않지만 spool이 byte/record 한도에 도달하면 새 collection chunk를 durable enqueue할 수 없으므로 해당 cycle은 실패한다. collector 자체는 한 collection cycle 안에서 현재 순차 실행되므로 느린 OS 명령 하나는 같은 cycle의 뒤 collector를 지연시킬 수 있다.

#### 3.1.2 Envelope, delta와 full snapshot

현재 Agent envelope schema는 `1.1`이다. collection cycle마다 새 `batch_id`를 만들고 각 item에 `<batch_id>:<sequence>` 형태의 `item_id`와 `sequence`를 부여한다. item 수나 JSON byte 한계를 넘으면 동일한 `batch_id`를 유지하고 `chunk_index`/`chunk_count`로 여러 chunk를 구분한다.

기본 크기 한계는 item 512 KiB, chunk 1,000 items/4 MiB, spool record 8 MiB이다. 한계를 넘는 item은 silent truncate하지 않고 collection enqueue 오류로 노출한다. 이 크기는 압축 전 JSON encoding 기준이고 `tcp_mtls`에서는 gzip 후 Agent frame 상한 8 MiB가 추가 적용된다.

`identity`, `network`, `process`, `service`, `port`, `package`, `firewall`의 `asset`/`state` item은 `${spool_dir}/state-cache.json`에 지속한 이전 payload hash와 비교한다. 이 파일에는 collector별 마지막 full 시각도 함께 저장하며 temp write, file `Sync`, rename과 비-Windows parent-directory `fsync`를 사용한다.

- cache가 없는 첫 수집 또는 full interval 도래 시 현재 전체 집합을 `snapshot_full: true`로 보낸다. 같은 collector의 item은 `<batch_id>:<collector>` 형식의 `snapshot_id`와 현재 전체 집합 크기인 `snapshot_item_count`를 공유한다.
- 중간 수집은 신규/변경 item만 `snapshot_full: false`로 보내되, 해당 collector의 새 `snapshot_id`와 현재 inventory의 `snapshot_item_count`를 함께 싣는다.
- 이전 집합에서 사라진 key는 delta/full 모두 `deleted: true` tombstone으로 보낸다. 정상 empty가 가능한 socket inventory도 마지막 socket 종료를 stale state 없이 반영한다.
- 명령/권한 실패는 collector error로 state filter 진입 전 차단해 cache를 보존한다. 오류 없는 empty inventory는 유효한 집합으로 처리하며, full empty에는 `snapshot_item_count: 0`, `snapshot_full: true` tombstone을 남겨 complete watermark를 만든다.
- 정상 재시작은 persisted cache와 full 시각을 복원하므로 무조건 full로 되돌아가지 않는다. cache 파일이 없거나 손상되면 health의 `state_cache_error`에 노출하고 cache가 없는 collector의 다음 수집을 full로 만든 뒤 성공한 저장으로 교체한다.
- Manager dashboard 조회는 동일 `snapshot_id`의 고유 `state_key` 수가 선언된 `snapshot_item_count` 이상인 full만 해당 asset/source/state type의 최신 full watermark로 채택한다. 따라서 일부 chunk만 저장된 full은 이전 상태를 조기에 잘라내지 않는다.

`batch_id`/`item_id`는 receiver 중복 억제와 추적에 사용하지만 exactly-once 보장은 아니다. 서버 enqueue 후 ACK 유실, Agent ACK 전 종료, receiver restart/cache eviction이 발생하면 같은 chunk/item이 다시 나타날 수 있다.

#### 3.1.3 Segmented spool과 전달 의미

Spool은 `${spool_dir}/queue/<record-id>.json`의 file-per-record 구조이다. temp file write, file `Sync`, rename 후에만 enqueue 성공으로 처리하고, timestamp prefix id를 정렬해 FIFO로 읽는다. 기존 단일 `queue.ndjson`은 시작 시 segmented record로 migration한다.

기본 한계는 record 8 MiB, pending+DLQ 합계 256 MiB/10,000 records, 최대 age 7일이다. age 초과, 유효한 spool record 안의 잘못된 batch JSON, 영구 delivery 오류는 `${spool_dir}/deadletter`로 옮겨 reason을 남긴다. transient 오류는 pending에 유지한다. 새 enqueue를 위한 공간이 부족하면 가장 오래된 DLQ부터 제거하지만 ACK되지 않은 pending record는 용량 확보 목적으로 버리지 않는다. age를 넘은 DLQ도 정리되며, 그래도 합계 한계를 맞출 수 없으면 enqueue가 실패한다.

손상된 queue record는 원본 파일명과 base64 원문, decode reason을 담은 진단 record로 DLQ에 격리하고 다음 정상 record를 계속 읽는다. record publish/remove마다 비-Windows parent directory도 `fsync`한다. 여러 chunk 중 뒤 enqueue 또는 state-cache 저장이 실패하면 이번 cycle에서 앞서 넣은 queue record를 ACK-remove하여 rollback한다. 다만 여러 파일을 하나로 묶는 filesystem transaction은 아니므로 process/전원 장애 경계에는 일부 chunk가 남을 수 있다. spool 자체는 암호화하지 않고 DLQ는 자동 재전송되지 않으며 오래된 진단 record가 총량/age 정책으로 제거될 수 있다.

#### 3.1.4 Streaming 및 secure file-manager 기본값

File manager 기본값은 disabled, delete disabled, 최대 transfer 256 MiB, poll 30초이다. Linux 기본 root는 `/var/log`, `/etc/castrelyx`, `/var/lib/castrelyx-agent`이며 Windows는 `%ProgramData%\Castrelyx\files` 하나만 기본 허용한다. 기존 path와 새 path의 parent symlink를 resolve한 뒤 configured root 내부인지 확인하고, configured root 자체의 rename/delete/move는 항상 거부한다. directory COPY/MOVE는 source와 동일하거나 source 하위인 target을 거부하고 recursive copy는 내부 symlink를 따라가지 않는다. delete disabled이면 delete뿐 아니라 move와 copy/upload의 기존 대상 overwrite도 거부한다.

Production HTTP file transport는 전체 파일을 memory에 materialize하지 않는다. 서버에서 Agent로 오는 파일은 같은 디렉터리 temp file에 limit를 적용해 stream한 뒤 size 확인, file `Sync`, rename으로 publish한다. Agent에서 서버로 보내는 파일도 열린 파일에서 request body로 stream한다. 단, legacy `Transport` interface만 구현한 외부 adapter/test double은 compatibility path에서 buffering할 수 있다.

Updater artifact도 기본 최대 128 MiB까지 temp file로 stream하면서 SHA-256을 계산한다. Ed25519 manifest signature와 OS/architecture/size/hash를 확인한 뒤 file `Sync`와 durable replace로 stage한다. 적용/rollback은 intent를 먼저 기록하고 source-target hash로 helper/replace 완료를 확인하며 terminal staging은 제거한다.

업데이트 직후에는 전체 config parser보다 먼저 `update_dir`만 관대하게 읽는 startup watchdog이 실행된다. 새 target과 staged artifact가 일치하는 첫 boot는 probation 1회를 기록하고 진행하며, 첫 collection 성공 시 원격 API보다 먼저 local health를 durable 기록한다. health 기록 없이 두 번째 boot가 발생하면 `ROLLBACK_INTENT`를 기록한 뒤 이전 바이너리를 복구한다. Windows installer는 SCM restart failure action을 설정해 이 두 번째 boot와 helper 복구가 실제로 실행되도록 한다.

이 probation은 replacement binary의 `main` 진입 이후에 동작한다. loader/CPU instruction/package-init 단계 실패까지 덮는 완전한 watchdog은 교체 대상 밖에 고정된 old-version launcher가 rollback marker와 health mark를 소유해야 하며, 현재 구조의 잔여 hardening 범위로 남는다.

Client certificate 갱신은 collection/sender와 별도인 identity loop가 담당한다. 시작 시 원격 renewal/enrollment가 실패하더라도 기존 certificate/private key/CA의 key pair, configured agent CN, 명시적 clientAuth, 단일 self-signed P-256 CA chain과 root validity horizon을 모두 검증할 수 있을 때만 degraded startup을 허용한다. 따라서 peer 검증이나 client identity를 우회하지 않으며, 전송은 실패한 채 durable spool에 남고 host observation은 계속된다. Identity loop는 장애 시 1분부터 최대 15분까지 지수 backoff, 정상 시 1시간 cadence로 재검사한다. 새 응답은 staging에서 key/agent/usage/chain/expiry를 검증하고 CA 추가·교체는 명시적 trust migration 없이는 거부한다. CA, client certificate, metadata 각각의 restricted backup과 client-target 기반 transaction marker를 durable 기록한 뒤 원자 교체하며, commit 도중 중단되면 다음 시작에서 이전 bundle 전체를 복원한다. Process mutex와 canonical target별 OS file lock으로 service, 별도 `-once`, 동일 target을 공유하는 config의 key 생성/recovery/commit도 직렬화한다. 저장된 metadata의 agent/HTTPS/expiry가 certificate와 맞지 않으면 configured manager ingest URL을 사용한다. 검증·commit 성공 결과 뒤에만 sentinel 오류를 반환해 systemd/SCM failure policy로 재시작하며 sender·remote task·updater의 정적 TLS config를 새 identity로 일괄 교체한다.

#### 3.1.5 `tcp_mtls` ingest

`tcp_mtls`는 enrollment/certificate renewal은 CastrelSign HTTPS에 유지하고 telemetry만 Logparser의 TCP/mTLS endpoint로 보낸다. frame은 4-byte big-endian compressed length와 gzip JSON payload이며, newline JSON ACK/NACK를 받는다. Agent는 gzip `BestSpeed`를 사용하고 성공한 TLS connection을 재사용한다.

Agent compressed frame 상한은 8 MiB이고 Logparser 기본 상한은 compressed 10 MiB, decompressed 16 MiB, batch 5,000 items이다. Logparser는 client certificate CN과 `source_id`를 비교하고 동시 TLS client를 기본 32개로 제한한다. schema `1.1`은 128자 이하의 nonblank `batch_id`, 유효한 chunk 범위, array `items`, item별 256자 이하의 nonblank `item_id`와 chunk 안에서 고유한 0 이상의 정수 `sequence`를 요구한다. batch 전체가 input queue에 들어갈 수 있을 때만 한 번에 enqueue하고, 부족하면 transient `queue_full` NACK를 반환한다. CastrelSign이 PKCS12를 갱신하면 Logparser는 기본 5초 안에 digest 변화를 확인하고 새 SSLContext를 검증한 뒤 listener를 재바인딩한다.

Logparser input은 최근 50,000개의 chunk identity를 고정 길이 SHA-256 key로 process memory에서 억제한다. ClickHouse output은 같은 chunk의 `chunk_item_count`개 item을 memory에서 모아 sequence 순서로 한 번에 insert하고, raw/canonical table별로 chunk identity 기반의 안정적인 dedup token을 사용한다. 이 token은 재시도에서 `sent_at`이나 body가 달라져도 동일하다. Pending group은 기본 30초, 2,048 groups, 50,000 items와 실제 serialized JSON 64 MiB의 전역 상한을 모두 적용한다. 불완전 group은 canonical table에 쓰지 않고 기본 128 MiB/1,000 records의 bounded durable DLQ로 격리하며 raw insert만 best-effort로 남긴다.

이 경로의 end-to-end 의미는 exactly-once가 아니다. Agent spool은 로컬 at-least-once 재시도를 제공하지만 TCP ACK는 Logparser memory queue 수락 시점이며 ClickHouse commit 시점이 아니다. ACK 뒤 저장 전 process 종료는 유실을 만들 수 있고, ACK 유실, input cache eviction/restart, ClickHouse non-replicated dedup window 초과는 raw 중복을 만들 수 있다. 완성 chunk의 canonical insert는 안정 token으로 보호되고, 불완전 chunk는 canonical table에서 차단된다.

### 3.2 Agent metric 수집

Agent metric은 짧은 주기로 반복 수집되는 숫자형 지표이다.

| Metric | Linux 수집 방식 | Windows 수집 방식 | 권장 주기 |
|---|---|---|---|
| CPU 사용률 | `/proc/stat` delta 계산 | PDH/performance counter | 30~60초 |
| Load average | `/proc/loadavg` | 해당 없음 | 30~60초 |
| Memory 사용률 | `/proc/meminfo` | performance counter/WMI | 30~60초 |
| Disk 사용률 | `df -P -B1`, mount point 기준 | logical disk/volume API/WMI | 1~5분 |
| Disk I/O | `/proc/diskstats` | performance counter | 1분 |
| Network ingress/egress bytes | `/proc/net/dev` | interface counter API | 30~60초 |
| Process resource | `/proc/<pid>` | process API | 1~5분 |

Metric은 절대값과 delta 계산 결과를 구분한다. 예를 들어 network byte counter는 원천값을 저장하되, 서버 측에서 초당 rate를 계산한다.

### 3.3 Agent log tailer

Log tailer는 장비 내부의 로그를 구조화 event로 변환한다.

Linux에서는 다음 원천을 우선 지원한다.

| 로그 원천 | 수집 방식 | 주요 이벤트 |
|---|---|---|
| journald | journal cursor 기반 우선 tail | service start/stop, failed unit, kernel/auth message |
| `/var/log/auth.log` | journald 불가 시 identity + offset 기반 폴백 | SSH login, sudo, user change |
| `/var/log/secure` | journald 불가 시 identity + offset 기반 폴백 | RHEL 계열 auth event |
| `/var/log/syslog` | journald 불가 시 identity + offset 기반 폴백 | system event |
| `/var/log/messages` | journald 불가 시 identity + offset 기반 폴백 | system/kernel event |
| auditd log | file tail 또는 audit parser | privileged action, file access event |

Windows에서는 다음 Event Log channel을 우선 지원한다.

| 로그 원천 | 수집 방식 | 주요 이벤트 |
|---|---|---|
| Security | Event Log channel별 record id | login success/failure, account lockout, privilege use |
| System | Event Log API | service failure, driver failure, reboot |
| Application | Event Log API | application error |
| Windows PowerShell | Event Log API | PowerShell execution event |
| Microsoft-Windows-PowerShell/Operational | Event Log API | script block, command event |
| Microsoft-Windows-TerminalServices-* | Event Log API | RDP login/session event |
| Microsoft-Windows-Windows Defender/Operational | Event Log API | Defender detection/status event |

Log tailer는 다음 규칙을 따른다.

| 규칙 | 내용 |
|---|---|
| Cursor 저장 | Linux file은 file identity+offset+128-byte checkpoint+미완성 line fragment, journald는 journal cursor, Windows는 channel별 record id 저장 |
| Commit 시점 | 수집한 모든 batch chunk가 segmented spool에 durable enqueue된 뒤 pending cursor를 저장. 실패 시 마지막 committed cursor에서 replay |
| 중복 억제 | journald가 정상일 때 rsyslog mirror 4종을 건너뛰고, 나머지는 안전하게 동일하다고 판단되는 source/time/record/message 조합만 cross-source deduplication. 폴백·replay 중복 가능성은 허용 |
| 민감정보 보호 | password, token, API key, Authorization header 형태의 문자열은 redaction |
| 원문 제한 | raw log 전체 저장은 선택 기능으로 두고, 기본은 normalized field 중심 저장 |
| 장애 복구 | file identity/checkpoint로 rotation·truncation·same-inode regrowth를 감지하고 cursor 손상/scan 오류는 collector 오류로 노출 |

Linux file tail은 첫 관찰/rotation/truncation 때 최대 64 KiB에서 최근 50줄을 읽고, 이후 실행마다 최대 64 KiB와 완성된 200줄을 오래된 순서대로 처리한다. 최대 64 KiB의 미완성 줄을 cursor에 보관하며 더 긴 줄에는 `line_truncated: true`를 붙인다. journald는 첫 50개, 이후 cursor 다음 최대 200개를 streaming scan한다. command 성공과 기존/신규 journal cursor가 함께 있어야 journald coverage로 인정하므로, 최초 결과가 비어 cursor가 없으면 mirror file을 폴백 수집한다. journalctl 부재·오류·내부 timeout 때는 실패 중 변경된 journal cursor를 버리고 bounded file tail로 폴백한다. 명시적인 stale-cursor seek 오류만 cursor를 비우고 최근 50개로 한 번 재동기화하며, decode/scanner/I/O 오류에는 기존 cursor를 유지해 backlog를 건너뛰지 않는다. Windows는 첫 20개, 이후 `EventRecordID > cursor`인 최대 100개를 `-Oldest` 순으로 처리한다. incremental 결과가 없으면 같은 PowerShell 호출에서 최신 RecordId 1건을 확인해 channel clear로 ID가 되감긴 경우에만 bounded initial tail로 재동기화한다.

이 commit-after-enqueue 정책은 누락보다 중복을 선택하는 at-least-once 방식이다. exactly-once 또는 중복 없는 event stream을 의미하지 않는다.

### 3.4 SNMP Poller

SNMP Poller는 네트워크 장비를 대상으로 표준 MIB를 polling한다. SNMP는 read-only 조회만 수행하며, `SET` 동작은 지원하지 않는다.

SNMP 버전은 다음 우선순위를 따른다.

| 우선순위 | 버전 | 정책 |
|---:|---|---|
| 1 | SNMPv3 authPriv | 기본 권장. 인증과 암호화를 모두 사용 |
| 2 | SNMPv3 authNoPriv | 암호화가 어려운 환경의 차선책 |
| 3 | SNMPv2c | 레거시 장비 호환용. read-only community만 허용 |

SNMP Poller는 다음 discovery 흐름을 따른다.

1. target IP와 credential로 SNMP 연결 확인
2. `SNMPv2-MIB`의 `sysObjectID`, `sysDescr`, `sysName`, `sysUpTime` 조회
3. `IF-MIB` walk로 인터페이스 capability 확인
4. `IP-MIB`, `IP-FORWARD-MIB`, `TCP-MIB`, `UDP-MIB` 지원 여부 확인
5. 라우팅 장비는 `BGP4-MIB`, `OSPF-MIB` 지원 여부 확인
6. L2 장비는 `BRIDGE-MIB`, `Q-BRIDGE-MIB`, `LLDP-MIB` 지원 여부 확인
7. 상용 장비는 `ENTITY-MIB`, `ENTITY-SENSOR-MIB` 지원 여부 확인
8. 지원되는 MIB 목록을 asset capability로 저장
9. 지원되는 MIB만 주기 polling

### 3.5 SNMP 표준 MIB 수집 범위

| MIB | 수집 정보 | 사용 대상 | 수집 우선순위 |
|---|---|---|---|
| `SNMPv2-MIB` | sysName, sysDescr, sysObjectID, sysUpTime, sysContact, sysLocation | 전체 SNMP 장비 | 필수 |
| `IF-MIB` | ifIndex, ifName, ifDescr, ifAlias, ifType, ifMtu, ifSpeed, ifHighSpeed, ifAdminStatus, ifOperStatus, ifLastChange, ifHCInOctets, ifHCOutOctets, errors, discards | 전체 SNMP 장비 | 필수 |
| `IP-MIB` | IP address, forwarding state, IP/ICMP statistics, reassembly/drop counters | 라우터, 방화벽, 서버 | 필수 |
| `IP-FORWARD-MIB` | route destination, next hop, route type, route protocol, metric, route age | 라우터, L3 네트워크 장비 | 필수 |
| `TCP-MIB` | active/passive opens, attempt fails, resets, current established, retransmitted segments, connection table | 서버, 방화벽, 라우터 | 권장 |
| `UDP-MIB` | in/out datagrams, no port, receive errors, endpoint table | 서버, 방화벽, 라우터 | 권장 |
| `BGP4-MIB` | BGP peer, peer state, remote AS, remote address, update count, route refresh 관련 counter | BGP 라우터 | 조건부 필수 |
| `OSPF-MIB` | OSPF router id, area, interface, neighbor, neighbor state | OSPF 라우터 | 조건부 필수 |
| `HOST-RESOURCES-MIB` | CPU, memory, storage, running software, installed software | SNMP가 활성화된 host/OpenWrt 일부 | 조건부 |
| `ENTITY-MIB` | chassis, module, slot, power supply, fan, physical inventory | 상용 장비 | 조건부 |
| `ENTITY-SENSOR-MIB` | temperature, voltage, fan speed, sensor status | 상용 장비 | 조건부 |
| `BRIDGE-MIB` | bridge port, forwarding database, MAC table | L2 장비 | 조건부 |
| `Q-BRIDGE-MIB` | VLAN, VLAN별 forwarding table | VLAN 지원 L2 장비 | 조건부 |
| `LLDP-MIB` | neighbor device, neighbor port, chassis id, port id | LLDP 지원 장비 | 조건부 |

SNMP counter 처리 규칙은 다음과 같다.

| 규칙 | 내용 |
|---|---|
| 64-bit counter 우선 | traffic은 `ifHCInOctets`, `ifHCOutOctets`를 우선 사용 |
| 32-bit fallback | 64-bit counter가 없으면 32-bit counter를 사용하되 wrap 처리를 수행 |
| reboot 감지 | `sysUpTime`이 이전보다 작아지면 장비 reboot로 판단하고 counter delta를 reset |
| interval 기록 | rate 계산을 위해 poll interval과 observed timestamp를 반드시 저장 |
| partial failure 허용 | 일부 OID 실패가 전체 장비 수집 실패가 되지 않게 한다 |

## 4. 공통 스키마

모든 장비는 먼저 공통 asset schema로 정규화한다. 장비별 상세 정보는 type-specific schema에 저장한다.

### 4.1 Asset 공통 스키마

```json
{
  "asset_id": "string",
  "tenant_id": "string",
  "device_type": "linux | windows_server | windows_host | router | firewall | network_device",
  "display_name": "string",
  "hostname": "string | null",
  "management_ips": ["string"],
  "mac_addresses": ["string"],
  "vendor": "string | null",
  "model": "string | null",
  "serial_number": "string | null",
  "os_family": "linux | windows | network_os | unknown",
  "os_name": "string | null",
  "os_version": "string | null",
  "firmware_version": "string | null",
  "architecture": "string | null",
  "first_seen_at": "datetime",
  "last_seen_at": "datetime",
  "collection_sources": ["agent | snmp"],
  "tags": ["string"],
  "location": {
    "site": "string | null",
    "rack": "string | null",
    "zone": "string | null"
  },
  "owner": {
    "team": "string | null",
    "contact": "string | null"
  },
  "capabilities": {
    "agent": "AgentCapability | null",
    "snmp": "SnmpCapability | null"
  }
}
```

### 4.2 Agent capability 스키마

```json
{
  "agent_id": "string",
  "agent_version": "string",
  "install_mode": "service | daemon",
  "last_heartbeat_at": "datetime",
  "enabled_collectors": [
    "identity",
    "metrics",
    "network",
    "process",
    "service",
    "port",
    "package",
    "firewall",
    "log_tailer"
  ],
  "permission_level": "standard | elevated | administrator | root",
  "log_sources": ["string"],
  "last_error": "string | null"
}
```

### 4.3 SNMP capability 스키마

```json
{
  "snmp_version": "v2c | v3",
  "security_level": "noAuthNoPriv | authNoPriv | authPriv | community",
  "sys_object_id": "string | null",
  "sys_descr": "string | null",
  "supported_mibs": [
    "SNMPv2-MIB",
    "IF-MIB",
    "IP-MIB",
    "IP-FORWARD-MIB",
    "TCP-MIB",
    "UDP-MIB",
    "BGP4-MIB",
    "OSPF-MIB"
  ],
  "last_poll_at": "datetime",
  "last_success_at": "datetime | null",
  "last_error": "string | null"
}
```

### 4.4 Interface 공통 스키마

```json
{
  "asset_id": "string",
  "interface_id": "string",
  "source": "agent | snmp",
  "if_index": "number | null",
  "name": "string",
  "description": "string | null",
  "alias": "string | null",
  "type": "string | null",
  "mac_address": "string | null",
  "mtu": "number | null",
  "speed_bps": "number | null",
  "admin_status": "up | down | testing | unknown",
  "oper_status": "up | down | testing | dormant | not_present | lower_layer_down | unknown",
  "ip_addresses": [
    {
      "address": "string",
      "prefix_length": "number | null",
      "family": "ipv4 | ipv6"
    }
  ],
  "counters": {
    "in_octets": "number | null",
    "out_octets": "number | null",
    "in_packets": "number | null",
    "out_packets": "number | null",
    "in_errors": "number | null",
    "out_errors": "number | null",
    "in_discards": "number | null",
    "out_discards": "number | null"
  },
  "observed_at": "datetime"
}
```

### 4.5 Metric 공통 스키마

```json
{
  "asset_id": "string",
  "metric_name": "string",
  "value": "number",
  "unit": "percent | bytes | count | bps | pps | celsius | seconds | status",
  "labels": {
    "source": "agent | snmp",
    "collector": "string",
    "interface": "string | null",
    "protocol": "string | null"
  },
  "observed_at": "datetime",
  "interval_sec": "number | null"
}
```

### 4.6 Event 공통 스키마

```json
{
  "event_id": "string",
  "asset_id": "string",
  "event_type": "auth | service | process | network | routing | firewall | system | security | collector",
  "severity": "info | low | medium | high | critical",
  "source": "agent | snmp",
  "source_name": "string",
  "observed_at": "datetime",
  "actor": "string | null",
  "action": "string | null",
  "outcome": "success | failure | unknown",
  "src_ip": "string | null",
  "dst_ip": "string | null",
  "protocol": "string | null",
  "process": "string | null",
  "service": "string | null",
  "message": "string",
  "raw_ref": "string | null",
  "dedup_key": "string"
}
```

### 4.7 Route 공통 스키마

```json
{
  "asset_id": "string",
  "source": "agent | snmp",
  "destination": "string",
  "prefix_length": "number",
  "next_hop": "string | null",
  "interface_id": "string | null",
  "route_protocol": "local | static | ospf | bgp | rip | icmp | other | unknown",
  "route_type": "unicast | blackhole | unreachable | prohibit | other | unknown",
  "metric": "number | null",
  "age_sec": "number | null",
  "observed_at": "datetime"
}
```

## 5. 장비 타입별 스키마와 수집 범위

### 5.1 Linux

Linux는 agent를 기본 수집 방식으로 사용한다. SNMP가 활성화되어 있으면 보조 채널로 사용할 수 있지만, Castrelyx의 Linux 정밀 관제는 agent 기준으로 설계한다.

| 수집 영역 | 수집 방식 | 주요 정보 |
|---|---|---|
| Identity | Agent | hostname, distro, kernel, architecture, boot time, machine id |
| Resource Metric | Agent | CPU, load average, memory, swap, mount point별 disk 사용량, disk I/O, interface별 ingress/egress bytes |
| Network | Agent | interface, IP, MAC, route, DNS, gateway |
| Process | Agent | PID, process name, executable path, parent PID, CPU/memory, process별 socket key/count |
| Service | Agent | systemd unit, status, startup state, failed unit |
| Package | Agent | dpkg/rpm/apk/pacman package name/version |
| Port/Socket | Agent | listening TCP/UDP socket, established connection, local/remote address와 port, owning process |
| Firewall | Agent | nftables/iptables/ufw enabled state, rule summary |
| Log | Agent log tailer | SSH login, sudo, user change, service failure, kernel/system event |
| SNMP 보조 | SNMP | IF-MIB, HOST-RESOURCES-MIB, TCP-MIB, UDP-MIB |

Linux type-specific schema는 다음 필드를 가진다.

```json
{
  "linux": {
    "distro_name": "string",
    "distro_version": "string",
    "kernel_version": "string",
    "init_system": "systemd | sysvinit | openrc | unknown",
    "selinux_status": "enforcing | permissive | disabled | unknown",
    "apparmor_status": "enabled | disabled | unknown",
    "package_managers": ["dpkg | rpm | apk | pacman | unknown"],
    "services": ["ServiceState"],
    "processes": ["ProcessState"],
    "packages": ["PackageState"],
    "listening_ports": ["PortState"],
    "firewall": {
      "backend": "nftables | iptables | ufw | firewalld | unknown",
      "enabled": "boolean | null",
      "rule_count": "number | null"
    },
    "log_sources": [
      "journald",
      "/var/log/auth.log",
      "/var/log/secure",
      "/var/log/syslog",
      "/var/log/messages",
      "auditd"
    ]
  }
}
```

Linux에서 기본적으로 alert 후보가 되는 event는 다음과 같다.

| 이벤트 | 원천 |
|---|---|
| SSH 로그인 실패 반복 | auth log, journald |
| sudo 실패 또는 권한 상승 | auth log |
| 신규 사용자 생성/삭제 | auth log, auditd |
| systemd service failed | journald |
| listening port 신규 생성 | port collector diff |
| CPU/memory/disk 임계치 초과 | metric collector |
| default route 변경 | network collector diff |

### 5.2 Windows 서버

Windows 서버는 agent를 기본 수집 방식으로 사용한다. Windows 서버는 인증 이벤트, 서비스 장애, RDP, PowerShell 실행 이벤트의 관제 가치가 높다.

| 수집 영역 | 수집 방식 | 주요 정보 |
|---|---|---|
| Identity | Agent | hostname, Windows edition, build, domain membership, boot time |
| Resource Metric | Agent | CPU, memory, drive별 disk 사용량, disk I/O, interface별 ingress/egress bytes |
| Network | Agent | NIC, IP, MAC, route, DNS, gateway |
| Process | Agent | process name, PID, executable path, resource usage, process별 socket key/count |
| Service | Agent | Windows service status, startup type |
| Package/Application | Agent | installed application, patch/update hints |
| Port/Socket | Agent | listening socket, established connection, local/remote address와 port, owning process |
| Firewall | Agent | Windows Firewall profile status, rule summary |
| Event Log | Agent log tailer | Security, System, Application, PowerShell, RDP, Defender |
| Server Role | Agent | IIS, DNS, DHCP, AD DS, Hyper-V 등 role 감지 |
| SNMP 보조 | SNMP | IF-MIB, TCP-MIB, UDP-MIB, HOST-RESOURCES-MIB 일부 |

Windows 서버 type-specific schema는 다음 필드를 가진다.

```json
{
  "windows_server": {
    "edition": "string",
    "build_number": "string",
    "domain_joined": "boolean",
    "domain_name": "string | null",
    "server_roles": ["iis | dns | dhcp | ad_ds | file_server | hyper_v | unknown"],
    "services": ["ServiceState"],
    "processes": ["ProcessState"],
    "installed_applications": ["PackageState"],
    "listening_ports": ["PortState"],
    "firewall": {
      "domain_profile_enabled": "boolean | null",
      "private_profile_enabled": "boolean | null",
      "public_profile_enabled": "boolean | null",
      "rule_count": "number | null"
    },
    "security_products": {
      "defender_enabled": "boolean | null",
      "edr_detected": "boolean | null",
      "last_signature_update": "datetime | null"
    },
    "event_log_sources": [
      "Security",
      "System",
      "Application",
      "Windows PowerShell",
      "PowerShell Operational",
      "Terminal Services",
      "Windows Defender"
    ]
  }
}
```

Windows 서버에서 기본적으로 alert 후보가 되는 event는 다음과 같다.

| 이벤트 | 원천 |
|---|---|
| 로그인 실패 반복 | Security Event Log |
| 관리자 권한 사용 또는 권한 변경 | Security Event Log |
| 계정 잠금 | Security Event Log |
| RDP 로그인 | Terminal Services Event Log |
| PowerShell 실행 이벤트 | PowerShell Operational Log |
| 서비스 장애 | System Event Log |
| Defender 탐지 | Defender Operational Log |
| 신규 listening port | port collector diff |

### 5.3 Windows host

Windows host는 일반 사용자 단말 또는 업무용 PC를 의미한다. 서버보다 사용자 세션, 보안 제품 상태, 로컬 방화벽, 의심스러운 실행 프로세스 관제가 중요하다.

| 수집 영역 | 수집 방식 | 주요 정보 |
|---|---|---|
| Identity | Agent | hostname, Windows edition/build, device id |
| Resource Metric | Agent | CPU, memory, drive별 disk 사용량, interface별 ingress/egress bytes |
| User Session | Agent | logged-on user, session state |
| Process | Agent | process name, executable path, resource usage, process별 socket key/count |
| Service | Agent | Windows service status |
| Application | Agent | installed application |
| Port/Socket | Agent | listening socket, established connection, local/remote address와 port |
| Firewall | Agent | Windows Firewall profile status |
| Security Product | Agent | Defender/AV/EDR detected state |
| Event Log | Agent log tailer | login failure, Defender, PowerShell, system error |

Windows host type-specific schema는 다음 필드를 가진다.

```json
{
  "windows_host": {
    "edition": "string",
    "build_number": "string",
    "domain_joined": "boolean",
    "logged_on_users": ["string"],
    "interactive_sessions": [
      {
        "username": "string",
        "session_id": "string | null",
        "state": "active | disconnected | unknown"
      }
    ],
    "services": ["ServiceState"],
    "processes": ["ProcessState"],
    "installed_applications": ["PackageState"],
    "listening_ports": ["PortState"],
    "firewall": {
      "domain_profile_enabled": "boolean | null",
      "private_profile_enabled": "boolean | null",
      "public_profile_enabled": "boolean | null"
    },
    "security_products": {
      "defender_enabled": "boolean | null",
      "edr_detected": "boolean | null",
      "last_signature_update": "datetime | null"
    }
  }
}
```

Windows host에서는 privacy와 민감정보 노출을 조심해야 한다. 기본 정책은 다음과 같다.

| 항목 | 기본 정책 |
|---|---|
| process command line | 기본 비수집. 필요 시 redaction 후 선택 수집 |
| browser history | 수집 제외 |
| user document path | 수집 제외 |
| clipboard | 수집 제외 |
| credential/token | 수집 제외 |

### 5.4 라우터

라우터는 SNMP를 기본 수집 방식으로 사용한다. Linux 기반 라우터에 agent를 설치할 수 있는 경우가 있더라도, v1에서는 라우터 type의 표준 수집 모델을 SNMP 중심으로 둔다.

| 수집 영역 | 수집 방식 | 주요 정보 |
|---|---|---|
| Identity | SNMPv2-MIB | sysName, sysDescr, sysObjectID, sysUpTime |
| Interface | IF-MIB | interface status, speed, traffic, errors, discards |
| IP Layer | IP-MIB | IP forwarding, IP/ICMP statistics |
| Route | IP-FORWARD-MIB | route table, next hop, protocol, metric |
| TCP/UDP | TCP-MIB, UDP-MIB | transport statistics |
| BGP | BGP4-MIB | peer, state, remote AS, update counters |
| OSPF | OSPF-MIB | area, interface, neighbor, neighbor state |
| Hardware | ENTITY-MIB, ENTITY-SENSOR-MIB | chassis, module, sensor |

라우터 type-specific schema는 다음 필드를 가진다.

```json
{
  "router": {
    "routing_enabled": "boolean | null",
    "routes": ["RouteState"],
    "routing_protocols": {
      "static": "boolean",
      "ospf": "boolean",
      "bgp": "boolean",
      "rip": "boolean | null"
    },
    "bgp_peers": [
      {
        "peer_address": "string",
        "remote_as": "number | null",
        "state": "idle | connect | active | opensent | openconfirm | established | unknown",
        "in_updates": "number | null",
        "out_updates": "number | null",
        "observed_at": "datetime"
      }
    ],
    "ospf_neighbors": [
      {
        "neighbor_id": "string",
        "neighbor_address": "string | null",
        "area_id": "string | null",
        "state": "down | attempt | init | two_way | exchange_start | exchange | loading | full | unknown",
        "observed_at": "datetime"
      }
    ],
    "transport_stats": {
      "tcp_curr_estab": "number | null",
      "tcp_retrans_segs": "number | null",
      "tcp_estab_resets": "number | null",
      "udp_in_errors": "number | null",
      "udp_no_ports": "number | null"
    }
  }
}
```

라우터에서 기본적으로 alert 후보가 되는 event는 다음과 같다.

| 이벤트 | 원천 |
|---|---|
| interface oper down | IF-MIB |
| interface error/discard 급증 | IF-MIB |
| route table 변화 | IP-FORWARD-MIB diff |
| BGP peer established 이탈 | BGP4-MIB |
| OSPF neighbor full 이탈 | OSPF-MIB |
| sysUpTime reset | SNMPv2-MIB |
| TCP reset/retransmission 급증 | TCP-MIB |

### 5.5 방화벽

방화벽은 SNMP를 기본 수집 방식으로 사용한다. 현재 수집 방식이 SNMP뿐이므로, 방화벽 정책 원문, NAT 룰 원문, threat log 상세는 v1 기본 범위에 포함하지 않는다. 대신 표준 MIB 기반으로 인터페이스, IP, routing, TCP/UDP 통계, 장비 상태를 수집한다.

| 수집 영역 | 수집 방식 | 주요 정보 |
|---|---|---|
| Identity | SNMPv2-MIB | sysName, sysDescr, sysObjectID, sysUpTime |
| Interface | IF-MIB | interface status, traffic, errors, discards |
| IP Layer | IP-MIB | IP/ICMP statistics |
| Route | IP-FORWARD-MIB | route table, next hop |
| TCP/UDP | TCP-MIB, UDP-MIB | session-like transport counters, resets, errors |
| Hardware | ENTITY-MIB, ENTITY-SENSOR-MIB | module, fan, PSU, temperature |
| Vendor Extension | 벤더 MIB | session count, HA status 등은 향후 확장 |

방화벽 type-specific schema는 다음 필드를 가진다.

```json
{
  "firewall": {
    "routes": ["RouteState"],
    "interfaces": ["InterfaceState"],
    "transport_stats": {
      "tcp_curr_estab": "number | null",
      "tcp_attempt_fails": "number | null",
      "tcp_estab_resets": "number | null",
      "tcp_retrans_segs": "number | null",
      "udp_in_errors": "number | null",
      "udp_no_ports": "number | null"
    },
    "policy_collection": {
      "supported": false,
      "reason": "standard_snmp_mib_does_not_expose_firewall_policy"
    },
    "nat_collection": {
      "supported": false,
      "reason": "standard_snmp_mib_does_not_expose_nat_rules"
    },
    "vendor_extensions": {
      "session_count": "number | null",
      "ha_state": "string | null",
      "license_status": "string | null"
    }
  }
}
```

방화벽에서 기본적으로 alert 후보가 되는 event는 다음과 같다.

| 이벤트 | 원천 |
|---|---|
| outside/inside interface down | IF-MIB |
| interface error/discard 급증 | IF-MIB |
| TCP reset 급증 | TCP-MIB |
| UDP no port/error 급증 | UDP-MIB |
| route table 변화 | IP-FORWARD-MIB |
| 장비 reboot | sysUpTime |
| fan/temperature 이상 | ENTITY-SENSOR-MIB |

### 5.6 네트워크 장비

네트워크 장비는 라우터와 방화벽을 제외한 스위치, L2/L3 장비, 무선 장비, 기타 SNMP 지원 장비를 포괄한다.

| 수집 영역 | 수집 방식 | 주요 정보 |
|---|---|---|
| Identity | SNMPv2-MIB | sysName, sysDescr, sysObjectID, sysUpTime |
| Interface | IF-MIB | port status, speed, traffic, errors, discards |
| L2 Bridge | BRIDGE-MIB | MAC forwarding table |
| VLAN | Q-BRIDGE-MIB | VLAN, VLAN별 forwarding table |
| Topology | LLDP-MIB | neighbor device, neighbor port |
| Hardware | ENTITY-MIB, ENTITY-SENSOR-MIB | chassis, module, PSU, fan, temperature |
| IP Layer | IP-MIB | management IP, IP statistics |

네트워크 장비 type-specific schema는 다음 필드를 가진다.

```json
{
  "network_device": {
    "network_roles": ["switch | l2 | l3 | wireless | appliance | unknown"],
    "vlans": [
      {
        "vlan_id": "number",
        "name": "string | null",
        "status": "active | suspended | unknown"
      }
    ],
    "mac_table": [
      {
        "mac_address": "string",
        "interface_id": "string | null",
        "vlan_id": "number | null",
        "entry_type": "dynamic | static | unknown",
        "observed_at": "datetime"
      }
    ],
    "lldp_neighbors": [
      {
        "local_interface_id": "string",
        "remote_chassis_id": "string | null",
        "remote_port_id": "string | null",
        "remote_system_name": "string | null",
        "remote_system_description": "string | null",
        "observed_at": "datetime"
      }
    ],
    "hardware_inventory": ["EntityState"],
    "sensors": ["SensorState"]
  }
}
```

네트워크 장비에서 기본적으로 alert 후보가 되는 event는 다음과 같다.

| 이벤트 | 원천 |
|---|---|
| uplink interface down | IF-MIB |
| port error/discard 급증 | IF-MIB |
| trunk port traffic 급감/급증 | IF-MIB |
| LLDP neighbor 변화 | LLDP-MIB diff |
| MAC table 급격한 변화 | BRIDGE-MIB/Q-BRIDGE-MIB diff |
| fan/temperature/power 이상 | ENTITY-SENSOR-MIB |
| 장비 reboot | sysUpTime |

## 6. 보조 상태 스키마

### 6.1 ProcessState

```json
{
  "pid": "number",
  "parent_pid": "number | null",
  "name": "string",
  "executable_path": "string | null",
  "command_line": "string | null",
  "user": "string | null",
  "cpu_percent": "number | null",
  "memory_bytes": "number | null",
  "started_at": "datetime | null",
  "socket_keys": ["string"],
  "listening_socket_count": "number",
  "connected_socket_count": "number"
}
```

`command_line`은 secret 노출 위험이 있어 기본 비수집으로 둔다. 수집을 활성화하더라도 token, password, key, secret, authorization 형태의 값을 redaction한다.

### 6.2 ServiceState

```json
{
  "name": "string",
  "display_name": "string | null",
  "status": "running | stopped | failed | paused | unknown",
  "startup_type": "automatic | manual | disabled | unknown",
  "binary_path": "string | null",
  "user": "string | null",
  "last_state_change_at": "datetime | null"
}
```

### 6.3 PackageState

```json
{
  "name": "string",
  "version": "string | null",
  "vendor": "string | null",
  "install_time": "datetime | null",
  "source": "dpkg | rpm | apk | pacman | registry | wmi | unknown"
}
```

### 6.4 PortState

```json
{
  "protocol": "tcp | udp",
  "local_address": "string",
  "local_port": "number",
  "remote_address": "string | null",
  "remote_port": "number | null",
  "state": "listen | established | bound | unknown",
  "direction": "listening | connected | bound",
  "process_id": "number | null",
  "process_name": "string | null",
  "socket_inode": "string | null",
  "observed_at": "datetime"
}
```

### 6.5 EntityState

```json
{
  "entity_index": "number | null",
  "name": "string",
  "description": "string | null",
  "class": "chassis | module | port | power_supply | fan | sensor | other | unknown",
  "serial_number": "string | null",
  "model": "string | null",
  "parent_entity_index": "number | null"
}
```

### 6.6 SensorState

```json
{
  "entity_index": "number | null",
  "name": "string",
  "sensor_type": "temperature | voltage | fan | power | other | unknown",
  "value": "number | null",
  "unit": "celsius | volts | rpm | watts | unknown",
  "status": "ok | warning | critical | unavailable | unknown",
  "observed_at": "datetime"
}
```

## 7. 수집 주기

### 7.1 Agent 구현 기본 수집 주기

다음 값은 현재 설정 parser와 runtime에 구현된 기본값이다. `batch_interval` 30초는 scheduler wake-up 기준이며, 각 collector는 `collector_interval_<name>`이 지난 경우에만 실행된다. TCP/mTLS sender는 짧은 backlog burst에서는 연결을 재사용하되 `tcp_ingest_max_idle`(기본 15초)이 지나면 선제 재연결한다. 이 값은 Logparser input adapter의 `timeoutMs`보다 작게 유지해야 하며, 이미 서버가 닫은 재사용 연결은 동일 schema 1.1 batch를 새 연결로 한 번만 즉시 재시도한다.

| Collector | 실행 주기 | Full snapshot 주기 | 비고 |
|---|---:|---:|---|
| `agent_health` | 30초 | 없음 | agent alive와 내부 상태 |
| `identity` | 1시간 | 24시간 | cache가 없거나 full 주기가 지난 실행은 full |
| `metric` | 30초 | 없음 | CPU/memory/disk/network metric |
| `network` | 5분 | 1시간 | state delta + periodic full |
| `process` | 2분 | 15분 | state delta + periodic full |
| `service` | 5분 | 1시간 | state delta + periodic full |
| `port` | 2분 | 15분 | state delta + periodic full |
| `package` | 12시간 | 24시간 | state delta + periodic full |
| `firewall` | 10분 | 1시간 | state delta + periodic full |
| `log_tailer` | 30초 | 없음 | bounded cursor incremental 수집 |

운영 환경에서는 자산 규모, process/socket 수, log 유입률과 spool 사용량을 함께 보고 이 값을 조정한다. 실행 주기를 줄여도 file log는 cycle당 64 KiB/200 lines, journald 200 events, Windows channel당 100 events로 제한되므로 burst backlog 해소율도 별도로 계산해야 한다.

### 7.2 SNMP 권장 수집 주기

| MIB/수집 영역 | 권장 주기 | 비고 |
|---|---:|---|
| SNMPv2-MIB identity | 5분~1시간 | sysUpTime은 더 자주 수집 가능 |
| IF-MIB status | 30~60초 | 장애 감지 핵심 |
| IF-MIB traffic counter | 30~60초 | rate 계산 |
| IF-MIB errors/discards | 30~60초 | 장애 징후 |
| IP-MIB statistics | 1~5분 | IP/ICMP 이상 |
| IP-FORWARD-MIB route table | 1~5분 | 라우터는 더 짧게 |
| TCP-MIB/UDP-MIB | 1~5분 | 통계성 지표 |
| BGP4-MIB peer state | 30~60초 | BGP 장비는 중요 |
| OSPF-MIB neighbor state | 30~60초 | OSPF 장비는 중요 |
| ENTITY-MIB inventory | 1~6시간 | 변경 빈도 낮음 |
| ENTITY-SENSOR-MIB | 1~5분 | 온도/팬/전원 |
| LLDP-MIB | 5~15분 | 토폴로지 변화 감지 |
| BRIDGE/Q-BRIDGE-MIB | 5~15분 | 대형 스위치는 부하 고려 |

## 8. 자산 식별과 중복 제거

같은 장비가 agent와 SNMP 양쪽에서 보일 수 있으므로 asset correlation이 필요하다.

자산 식별 우선순위는 다음과 같다.

| 우선순위 | 식별자 | 설명 |
|---:|---|---|
| 1 | agent_id | agent 설치 장비의 가장 강한 식별자 |
| 2 | serial_number | 서버/상용 장비에서 강한 식별자 |
| 3 | sysObjectID + sysName + management IP | SNMP 장비 식별 |
| 4 | hostname + primary MAC | host 식별 |
| 5 | management IP | 마지막 fallback. IP 변경 가능성이 있어 단독 사용은 지양 |

중복 제거 규칙은 다음과 같다.

| 상황 | 처리 |
|---|---|
| Agent와 SNMP가 같은 hostname/MAC를 보고 | 하나의 asset에 source만 병합 |
| SNMP sysName이 hostname과 다름 | alias로 보관하고 관리자가 확인 |
| management IP가 바뀜 | 이전 IP를 historical identifier로 유지 |
| sysUpTime reset | 새 asset이 아니라 reboot event로 처리 |
| serial이 다른데 hostname이 같음 | 다른 asset으로 분리 |

## 9. 보안 및 개인정보 원칙

수집기는 관제를 위해 필요한 정보만 수집하고, 민감정보 원문은 저장하지 않는다.

| 항목 | 기본 정책 |
|---|---|
| Password/API key/token | 수집 제외 또는 redaction |
| Process command line | 기본 비수집. 선택 활성화 시 redaction |
| Environment variable | 기본 비수집 |
| Browser history | 수집 제외 |
| Clipboard | 수집 제외 |
| User document content | 수집 제외 |
| SNMP community | 서버 secret store에 저장, 로그 출력 금지 |
| SNMP SET | 미지원 |
| SNMPv2c | read-only community만 허용 |
| Agent 권한 | collector별 최소 권한 원칙 |

## 10. v1 구현 우선순위

v1은 수집 범위를 넓히기보다, 정규화 모델과 기본 collector의 신뢰성을 먼저 확보한다.

| 단계 | 구현 내용 | 이유 |
|---:|---|---|
| 1 | Asset 공통 schema, capability schema | 모든 수집 데이터의 기준 |
| 2 | Linux/Windows agent identity, metric, network, process, service, socket, package, firewall collector | 가장 안정적인 host telemetry와 process/socket 연계 |
| 3 | Linux/Windows log tailer | 플랫폼별 보안 이벤트와 장애 이벤트 수집 |
| 4 | SNMPv2-MIB, IF-MIB | 모든 SNMP 장비의 최소 공통 수집 |
| 5 | IP-MIB, IP-FORWARD-MIB, TCP-MIB, UDP-MIB | L3/transport 관제 확장 |
| 6 | BGP4-MIB, OSPF-MIB | 라우터 관제 핵심 |
| 7 | ENTITY-MIB, ENTITY-SENSOR-MIB | 상용 장비 하드웨어 상태 |
| 8 | BRIDGE-MIB, Q-BRIDGE-MIB, LLDP-MIB | 네트워크 토폴로지와 L2 관제 |

## 11. 장비 타입별 최종 수집 요약

| 장비 타입 | 기본 수집 방식 | 핵심 수집 정보 | 제한 사항 |
|---|---|---|---|
| Linux | Agent | OS, metric, network traffic bytes, mount별 disk usage, process/socket 연계, service, package, firewall, log | command line과 환경변수는 기본 비수집 |
| Windows 서버 | Agent | OS, metric, network traffic bytes, drive별 disk usage, process/socket 연계, service, app, firewall, Event Log, server role | 일부 보안 제품 상태는 제품별 편차 |
| Windows host | Agent | OS, metric, drive별 disk usage, user session, process/socket 연계, service, app, firewall, Defender/Event Log | 개인정보성 데이터는 수집 제외 |
| 라우터 | SNMP | interface, traffic, route, IP, TCP/UDP, BGP, OSPF | config 원문/CLI 상태는 제외 |
| 방화벽 | SNMP | interface, traffic, route, TCP/UDP, hardware sensor | policy/NAT/threat log 상세는 표준 SNMP로 불가 |
| 네트워크 장비 | SNMP | interface, MAC table, VLAN, LLDP, hardware sensor | 장비별 MIB 지원 편차 큼 |

## 12. 결론

Castrelyx의 수집 구조는 `Agent Deep Telemetry`와 `SNMP Infra Telemetry`를 결합하는 방식이 가장 현실적이다.

Agent는 Windows/Linux 내부 상태를 깊게 수집한다. Metric collector는 리소스와 네트워크 사용량을 수집하고, log tailer는 인증/보안/서비스 이벤트를 near real-time으로 수집한다. Process, service, package, port collector는 공격면과 운영 상태를 파악하는 데 사용한다.

SNMP는 네트워크 장비의 표준 상태 수집에 집중한다. `SNMPv2-MIB`, `IF-MIB`, `IP-MIB`, `IP-FORWARD-MIB`, `TCP-MIB`, `UDP-MIB`, `BGP4-MIB`, `OSPF-MIB`를 v1 핵심 MIB로 두고, 장비 capability에 따라 `ENTITY-MIB`, `ENTITY-SENSOR-MIB`, `BRIDGE-MIB`, `Q-BRIDGE-MIB`, `LLDP-MIB`를 추가 수집한다.

모든 수집 결과는 공통 asset schema에 먼저 매핑하고, Linux, Windows 서버, Windows host, 라우터, 방화벽, 네트워크 장비별 extension schema로 상세 정보를 분리한다. 이 구조를 사용하면 수집 방식이 달라도 Castrelyx UI와 분석 엔진은 동일한 자산 모델을 기준으로 동작할 수 있다.
