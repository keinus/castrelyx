# Castrelyx (카스트렐릭스)

Castrelyx는 라틴어 `Castra`와 데이터 기반 관제를 뜻하는 `Analytics`의 세련된 연결 어미 `-lyx`를 합성한 고유한 신조어입니다.

## 이름의 의미

- `Castra`: 군사 요새, 진영, 주둔지를 뜻하는 라틴어
- `Analytics`: 분석과 관제를 상징하는 데이터 기반 운영 개념
- `-lyx`: 현대적인 인프라, 보안, 관제 제품에 어울리는 날카로운 기술적 어감

## 조어 배경

군대가 주둔하는 단단한 성벽과 요새를 뜻하는 `Castra`에, 데이터 기반 관제를 뜻하는 `Analytics`의 연결 어미인 `-lyx`를 합성했습니다. Castrelyx는 인프라를 단순히 관찰하는 도구가 아니라, 서비스 전체를 하나의 요새처럼 방어하고 운영하는 관제 시스템을 지향합니다.

## 관제 의미

Castrelyx는 우리 서비스의 인프라 요새 전체를 실시간으로 분석하고 모니터링하여, 단 하나의 위협이나 장애도 용납하지 않는 철벽 관제 시스템을 뜻합니다. 보안 이벤트, 장애 징후, 원격 상태를 한곳에서 추적하고 판단하는 강력한 운영 기반을 목표로 합니다.

## 개발 감성

Castrelyx는 보안과 원격 관제가 강력하게 결합된 느낌을 주는 이름입니다. 단단한 방어성, 실시간 분석, 인프라 중심 관제라는 인상을 담고 있으며, CNCF 오픈소스 프로젝트들이 가진 현대적이고 기술적인 톤앤매너와 잘 맞는 프로젝트 정체성을 가집니다.

## Agent 상세 설명

Castrelyx Agent는 Linux/Windows 호스트에 설치되는 Go 기반 수집기입니다. 호스트 내부의 식별 정보, CPU/memory/disk/network metric, interface 상태, process/socket 연결, service 상태, package/application 목록, firewall 상태, 최근 system/security log를 수집하고 Castrelyx 서버로 전송합니다.

현재 구현 기준의 상세 운영 문서는 [`agent/README.md`](agent/README.md)에 있습니다. 해당 문서에는 다음 내용이 포함되어 있습니다.

- Agent, CastrelSign, Logparser, Manager, ClickHouse가 어떻게 연결되는지
- 어떤 collector가 어떤 OS API, `/proc` 파일, PowerShell/CIM 명령, system command를 사용해 어떤 데이터를 수집하는지
- batch envelope의 `schema_version`, `source_id`, `tenant_id`, `items[]`, `kind/type/key/payload` 구조
- enrollment token 발급, ECDSA P-256 private key 생성, CSR 제출, client certificate 발급과 갱신 방식
- HTTPS gzip ingest와 TCP/mTLS gzip ingest의 전송 형식
- 민감 key redaction, TLS 1.2 이상, client certificate CN과 `source_id` 일치 검증, spool 파일 권한 등 보안 처리
- HTTPS 수신 시 CastrelSign ingest table, TCP/mTLS 수신 시 Logparser와 raw ClickHouse table, 이후 Manager canonical metric/state/event table과 asset binding으로 이어지는 저장 흐름
- Docker Compose 기반 서버 설치, enrollment token 생성, Linux/Windows agent 설치, systemd/Windows Service 등록 방법
- 현재 구현상 제한과 운영 문제 해결 절차

핵심 보안 모델은 "최초 등록은 1회용 enrollment token, 이후 전송은 client certificate 기반 mTLS"입니다. Agent의 private key는 로컬에서 생성되고 서버로 전송되지 않으며, batch payload는 전송 전 민감 key 기준으로 redaction됩니다. 단, 현재 구현은 spool 파일 자체를 암호화하지 않고 자유 텍스트 로그 메시지 내부 secret을 정규식으로 scrub하지 않으므로, 운영 환경에서는 파일 권한, 실행 계정 분리, 디스크 암호화, 로그 원천의 secret 기록 금지 정책을 함께 적용해야 합니다.
