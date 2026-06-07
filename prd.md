# Castrelyx Agent - Product Requirements Document (PRD)

## 1. Overview
Castrelyx Agent는 Linux/Windows 환경에서 실행되는 경량화된 모니터링 및 수집 에이전트입니다. 호스트 시스템의 상태, 메트릭, 네트워크 정보를 수집하여 중앙 관리 서버(Manager)로 안전하게 전송합니다.

## 2. Functional Requirements

### 2.1 Configuration Management
- 에이전트는 커스텀 YAML과 유사한 설정 파일을 사용합니다.
- 필수 설정: `manager_url` (HTTPS만 허용)
- 선택적 설정: `enrollment_token`, `agent_id`, `tenant_id`, `batch_interval`, `spool_dir`, `max_spool_record_bytes`, `cert_dir`, `tls_server_name`
- 설정 파일에서 누락된 값은 환경별 디폴트 값으로 대체됩니다.

### 2.2 Secure Enrollment & Renewal
- 첫 실행 시 `enrollment_token`을 사용하여 Manager와 보안 연결을 수립합니다.
- ECDSA P-256 기반의 키 페어를 생성하고 CSR을 Manager로 전송합니다.
- Manager로부터 CA 인증서 및 클라이언트 인증서를 받아 로컬에 저장합니다.
- 인증서 만료 임박 시 자동 갱신 메커니즘을 지원합니다.

### 2.3 Data Collection
- **Identity**: 호스트 이름, OS, 아키텍처 정보 수집
- **Metrics**: Go 런타임 메트릭(고루틴 수, 힙 할당), 시스템 메트릭(Linux의 경우 메모리 정보)
- **Network**: 네트워크 인터페이스 상태, IP 주소, MTU 정보
- **Port**: 리스닝 중인 TCP/UDP 포트 정보 (`/proc/net` 파싱)
- 향후 확장 예정: Process, Service, Package, Firewall, Log Tailer

### 2.4 Reliable Data Transmission
- 수집된 데이터는 gzip 압축 후 HTTPS를 통해 Manager의 `/api/agent/ingest` 엔드포인트로 전송됩니다.
- 전송 실패 시 로컬 디스크에 NDJSON 형식으로 스풀링(Spooling)하여 데이터 손실을 방지합니다.
- 스풀 디렉토리의 최대 크기는 `max_spool_record_bytes` 설정으로 제한됩니다.
- 성공적으로 전송된 레코드는 큐에서 제거(Ack)됩니다.

### 2.5 Health & Error Reporting
- 수집기 오류는 별도 이벤트로 기록되어 Manager로 보고됩니다.
- 데이터가 없을 경우 주기적으로 `heartbeat` 이벤트 전송을 통해 에이전트 생존 상태를 알립니다.

## 3. Non-Functional Requirements
- **Security**: 모든 통신은 TLS 1.2 이상을 사용하며, 클라이언트 인증서 기반双向 인증(mTLS)을 지원합니다.
- **Reliability**: 네트워크 단절 시에도 로컬 스풀링을 통해 데이터를 보존하고, 재연결 시 자동으로 복구합니다.
- **Performance**: 경량화된 구조로 시스템 리소스 최소화를 목표로 합니다.

## 4. Data Schema
- 데이터는 `Batch`와 `Item` 구조로 구성됩니다.
- 각 `Item`은 `Kind`(asset, metric, state, event), `Type`, `Key`, `Payload`를 포함합니다.
- 스풀 디렉토리 파일: `queue.ndjson`
- 인증서 디렉토리 파일: `ca.pem`, `client.pem`, `client.key`, `enrollment.json`
