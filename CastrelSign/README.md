# CastrelSign

CastrelSign은 Castrelyx agent의 인증서 발급, mTLS 인증, agent ingest 수신을 담당하는 독립 Spring Boot 서브프로젝트입니다.

CastrelSign은 자체 self-signed root CA를 보유합니다. 이 root CA로 CastrelSign HTTPS server certificate를 발급하고, agent가 제출한 CSR을 서명해 agent client certificate를 발급합니다. agent private key는 agent 실행 호스트에서 생성되며, CastrelSign은 private key를 생성하거나 저장하거나 반환하지 않습니다.

## 핵심 개념

| 항목 | 설명 |
| --- | --- |
| Root CA | CastrelSign이 최초 실행 시 생성하는 self-signed CA입니다. agent에는 `ca.pem`만 배포합니다. |
| Server certificate | root CA가 서명한 CastrelSign HTTPS 인증서입니다. agent는 `ca.pem`으로 이 인증서를 검증합니다. |
| Client certificate | root CA가 agent CSR을 서명해서 만든 agent mTLS 인증서입니다. EKU는 clientAuth입니다. |
| Enrollment token | admin API로 생성하는 agent 최초 등록용 bootstrap secret입니다. DB에는 hash만 저장되고, ingest/renew에는 사용하지 않습니다. |
| Admin token | enrollment token 생성, 조회, 폐기에 사용하는 운영자용 API token입니다. |
| Agent private key | agent가 로컬에서 생성하는 ECDSA P-256 private key입니다. 서버로 전송하지 않습니다. |
| Ingest | agent가 gzip JSON batch를 `/api/agent/ingest`로 전송하는 수집 데이터 저장 경로입니다. |

## 개념 구조도

```mermaid
flowchart LR
  Operator["운영자 / 배포 도구"]
  Agent["Castrelyx Agent"]

  subgraph AgentHost["Agent Host"]
    AgentKey["client.key\nagent 로컬 생성\n외부 전송 금지"]
    AgentCert["client.pem\nauth client certificate"]
    AgentCA["ca.pem\nCastrelSign root CA"]
    AgentConfig["agent.yaml\nenrollment_token, agent_id"]
    AgentSpool["local spool\n장애 복구용 queue"]
  end

  subgraph CastrelSign["CastrelSign Manager"]
    AdminAPI["Admin API\n/api/admin/enrollment-tokens"]
    AgentAPI["Agent API\n/enroll, /renew, /ingest"]
    TLS["Spring Boot HTTPS\nserver cert + client-auth want"]
    CA["Root CA\nca.pem + ca.key"]
    CertIssuer["Certificate Issuer\nCSR 검증 + clientAuth 서명"]
    TokenService["Enrollment Token Service\nhash, TTL, max_uses, revoke"]
    CertVerifier["Client Cert Verifier\nchain, expiry, EKU, CN"]
    IngestStore["Ingest Service\ngzip JSON 검증 + 저장"]
    DB[("SQLite\nagents\nenrollment_tokens\nissued_certificates\ningest_batches\ningest_items\naudit_events")]
  end

  Operator -->|"admin token으로 token 생성/폐기"| AdminAPI
  AdminAPI --> TokenService
  TokenService --> DB
  AdminAPI -->|"enrollment token 원문 1회 반환"| Operator

  Operator -->|"ca.pem, agent.yaml 배포"| AgentConfig
  Operator --> AgentCA
  Agent -->|"client.key 생성"| AgentKey
  Agent -->|"CSR 생성"| AgentAPI

  AgentCA -->|"server certificate 검증"| TLS
  AgentAPI --> TokenService
  AgentAPI --> CertIssuer
  CA -->|"server/client cert 서명"| CertIssuer
  CertIssuer -->|"client.pem 발급"| AgentCert
  CertIssuer --> DB

  Agent -->|"mTLS /renew, /ingest"| AgentAPI
  AgentCert -->|"client certificate 제출"| TLS
  TLS --> CertVerifier
  CertVerifier -->|"CN = agent_id/source_id 검증"| IngestStore
  AgentSpool -->|"gzip batch"| IngestStore
  IngestStore --> DB
```

이 구조에서 `CASTRELSIGN_ADMIN_TOKEN`은 운영자와 admin API 사이에서만 사용됩니다. agent는 admin token을 알 필요가 없고, agent 배포에는 admin API가 생성한 개별 enrollment token만 포함됩니다.

## 프로젝트 구조

```text
CastrelSign/
  build.gradle
  settings.gradle
  README.md
  src/main/resources/application.yml
  src/main/java/org/castrelyx/castrelsign/
    api/
    bootstrap/
    config/
    crypto/
    persistence/
    security/
  src/test/java/org/castrelyx/castrelsign/
```

## 실행 전 준비

필수 조건은 Java 21 이상과 Gradle입니다.

운영 실행에는 최소한 `CASTRELSIGN_PUBLIC_BASE_URL`과 `CASTRELSIGN_ADMIN_TOKEN`이 필요합니다. `CASTRELSIGN_PUBLIC_BASE_URL`은 agent가 접근할 수 있는 HTTPS 주소여야 합니다.

```powershell
cd D:\Study\castrelyx\CastrelSign

$env:CASTRELSIGN_PUBLIC_BASE_URL = 'https://manager.example.com:8443'
$env:CASTRELSIGN_ADMIN_TOKEN = 'replace-with-long-random-admin-token'
$env:CASTRELSIGN_TLS_SERVER_NAME = 'manager.example.com'

gradle bootRun
```

JAR로 실행할 때는 다음과 같이 사용할 수 있습니다.

```powershell
gradle bootJar

$env:CASTRELSIGN_PUBLIC_BASE_URL = 'https://manager.example.com:8443'
$env:CASTRELSIGN_ADMIN_TOKEN = 'replace-with-long-random-admin-token'
$env:CASTRELSIGN_TLS_SERVER_NAME = 'manager.example.com'

java -jar .\build\libs\CastrelSign-0.1.0.jar
```

## 설정

| 환경 변수 | 기본값 | 설명 |
| --- | --- | --- |
| `CASTRELSIGN_PORT` | `8443` | CastrelSign HTTPS listen port |
| `CASTRELSIGN_DATA_DIR` | `${user.home}/castrelsign` | CA, keystore, truststore, SQLite DB 저장 경로 |
| `CASTRELSIGN_PUBLIC_BASE_URL` | 없음 | enroll/renew 응답의 `ingest_url` 생성에 사용합니다. 운영 필수입니다. |
| `CASTRELSIGN_ADMIN_TOKEN` | 없음 | enrollment token을 관리하는 admin API 인증 token입니다. 운영 필수입니다. |
| `CASTRELSIGN_ENROLLMENT_TOKEN` | 없음 | 이전 버전 호환용 설정입니다. production enrollment에는 사용하지 않습니다. |
| `CASTRELSIGN_CERT_VALID_DAYS` | `30` | agent client certificate 유효 기간 |
| `CASTRELSIGN_ROOT_VALID_DAYS` | `3650` | root CA 유효 기간 |
| `CASTRELSIGN_TLS_SERVER_NAME` | `localhost` | server certificate CN/SAN DNS 이름 |
| `CASTRELSIGN_KEYSTORE_PASSWORD` | `changeit` | Spring Boot HTTPS keystore/truststore 비밀번호 |

`CASTRELSIGN_TLS_SERVER_NAME`은 agent 설정의 `tls_server_name`과 일치해야 합니다. 예를 들어 agent가 `manager.example.com`으로 접속한다면 CastrelSign server certificate에도 `manager.example.com`이 SAN으로 들어가야 합니다.

## 최초 실행 시 생성되는 파일

CastrelSign은 최초 실행 시 `CASTRELSIGN_DATA_DIR` 아래에 다음 파일을 생성합니다.

| 경로 | 설명 | 배포 대상 |
| --- | --- | --- |
| `certs/ca.pem` | CastrelSign root CA certificate | agent에 배포 |
| `certs/ca.key` | CastrelSign root CA private key | 외부 배포 금지 |
| `certs/server.pem` | CastrelSign HTTPS server certificate | CastrelSign 내부 사용 |
| `certs/server.key` | CastrelSign HTTPS server private key | 외부 배포 금지 |
| `certs/server.p12` | Spring Boot HTTPS keystore | CastrelSign 내부 사용 |
| `certs/truststore.p12` | client certificate 검증용 truststore | CastrelSign 내부 사용 |
| `castrelsign.sqlite` | SQLite 저장소 | CastrelSign 내부 사용 |

`ca.key`와 `server.key`는 CastrelSign 호스트 밖으로 내보내면 안 됩니다. agent 배포에 필요한 파일은 `ca.pem`입니다.

## 인증 모델

CastrelSign API는 두 단계 인증 모델을 사용합니다.

1. 최초 등록: enrollment token
2. 등록 이후: TLS client certificate

`/api/admin/enrollment-tokens`는 운영자가 enrollment token을 생성, 조회, 폐기하는 endpoint입니다. 이 endpoint는 `Authorization: Bearer <admin_token>`으로 보호됩니다.

`/api/agent/enroll`은 아직 client certificate가 없는 agent를 위한 endpoint입니다. 이 endpoint는 admin API로 생성된 `Authorization: Bearer <enrollment_token>`을 받습니다. CastrelSign은 token 원문을 저장하지 않고 SHA-256 hash, 만료 시각, 최대 사용 횟수, agent 제한, 폐기 상태를 DB에 저장합니다.

`/api/agent/renew`와 `/api/agent/ingest`는 Bearer token을 사용하지 않습니다. TLS handshake에서 제출된 client certificate가 있어야 하고, CastrelSign root CA로 검증되어야 합니다. 컨트롤러에서도 인증서 만료, root CA 서명, clientAuth EKU, subject CN을 확인합니다.

## 인증 흐름

```mermaid
sequenceDiagram
  participant Operator as 운영자
  participant Manager as CastrelSign
  participant Agent as Castrelyx Agent

  Operator->>Manager: CastrelSign 최초 실행
  Manager->>Manager: root CA, server cert, truststore 생성
  Operator->>Manager: POST /api/admin/enrollment-tokens + admin token
  Manager-->>Operator: enrollment token 원문 1회 반환
  Operator->>Agent: ca.pem, agent.yaml 배포
  Agent->>Agent: ECDSA P-256 client.key 생성
  Agent->>Agent: agent_id CN으로 CSR 생성
  Agent->>Manager: POST /api/agent/enroll + Bearer token + CSR
  Manager->>Manager: token, CSR 서명, CN=agent_id 검증
  Manager-->>Agent: ca_cert_pem, client_cert_pem, ingest_url
  Agent->>Agent: client.pem, enrollment.json 저장
  Agent->>Manager: POST /api/agent/ingest over mTLS
  Manager->>Manager: client cert CN과 batch source_id 매칭
  Manager-->>Agent: 202 Accepted
```

## Agent 배포 절차

### 1. CastrelSign 실행

CastrelSign을 먼저 실행해 root CA를 생성합니다.

```powershell
$env:CASTRELSIGN_PUBLIC_BASE_URL = 'https://manager.example.com:8443'
$env:CASTRELSIGN_ADMIN_TOKEN = 'replace-with-long-random-admin-token'
$env:CASTRELSIGN_TLS_SERVER_NAME = 'manager.example.com'
gradle bootRun
```

생성된 `ca.pem` 위치는 기본값 기준으로 다음과 같습니다.

```text
%USERPROFILE%\castrelsign\certs\ca.pem
```

### 2. Agent enrollment token 생성

agent별로 만료 시간이 짧고 사용 횟수가 제한된 enrollment token을 생성합니다.

```powershell
$body = @{
  name = 'host-001 initial enrollment'
  agent_id = 'host-001'
  ttl_seconds = 3600
  max_uses = 1
} | ConvertTo-Json

curl.exe --cacert .\certs\ca.pem `
  -H "Authorization: Bearer $env:CASTRELSIGN_ADMIN_TOKEN" `
  -H "Content-Type: application/json" `
  -d $body `
  https://manager.example.com:8443/api/admin/enrollment-tokens
```

응답의 `token` 값은 이때 한 번만 표시됩니다. 이 값을 agent 설정의 `enrollment_token`에 넣습니다.

### 3. Agent 설정 파일 준비

agent 설정 예시는 다음과 같습니다.

```yaml
manager_url: https://manager.example.com:8443
enrollment_token: cse_generated-token-from-admin-api
agent_id: host-001
tenant_id: default
cert_dir: ./certs
ca_cert_path: ./certs/ca.pem
tls_server_name: manager.example.com
batch_interval: 30s
spool_dir: ./spool
collectors:
  - identity
  - metric
  - network
  - process
  - service
  - port
```

`agent_id`는 인증서 subject CN이 되므로 안정적인 장비 식별자로 정해야 합니다. 이후 ingest batch의 `source_id`도 이 값과 같아야 합니다.

### 4. Agent 호스트에 파일 배포

agent 호스트에는 다음을 배포합니다.

| 파일 | 설명 |
| --- | --- |
| `agent.yaml` | agent 설정 |
| `certs/ca.pem` | CastrelSign root CA |

다음 파일은 최초 실행 후 agent가 로컬에서 생성하거나 저장합니다.

| 파일 | 생성 주체 | 설명 |
| --- | --- | --- |
| `certs/client.key` | agent | agent private key. 서버로 전송하지 않습니다. |
| `certs/client.pem` | CastrelSign 응답 저장 | agent client certificate |
| `certs/enrollment.json` | agent | `agent_id`, `ingest_url`, `expires_at` 메타데이터 |

### 5. Agent 최초 실행

agent는 `client.pem`이 없거나 만료된 경우 enrollment를 수행합니다.

1. `certs/client.key`가 없으면 ECDSA P-256 private key를 생성합니다.
2. `agent_id`를 CSR subject CN으로 넣어 CSR을 생성합니다.
3. `ca.pem`으로 CastrelSign HTTPS server certificate를 검증합니다.
4. `/api/agent/enroll`에 Bearer token과 CSR을 전송합니다.
5. 응답의 `client_cert_pem`을 `client.pem`으로 저장합니다.
6. 이후 ingest는 mTLS로 전송합니다.

## Agent 배포 후 인증서 인증 절차

agent 배포 후 정상 통신은 다음 절차로 인증됩니다.

1. agent가 CastrelSign에 TLS 연결을 시작합니다.
2. agent는 `ca.pem`으로 CastrelSign server certificate를 검증합니다.
3. `/api/agent/ingest` 또는 `/api/agent/renew` 호출 시 agent가 `client.pem`과 `client.key`를 사용해 client certificate를 제출합니다.
4. CastrelSign TLS layer가 `truststore.p12`에 들어 있는 root CA로 client certificate chain을 검증합니다.
5. 컨트롤러가 client certificate 존재 여부를 확인합니다.
6. 컨트롤러가 인증서 유효 기간과 root CA 서명을 다시 확인합니다.
7. 컨트롤러가 clientAuth EKU를 확인합니다.
8. 컨트롤러가 subject CN을 agent identity로 사용합니다.
9. ingest에서는 batch `source_id`가 subject CN과 같은지 확인합니다.
10. 검증이 끝나면 batch를 SQLite에 저장하고 `202 Accepted`를 반환합니다.

## API 명세

### 공통

| 항목 | 값 |
| --- | --- |
| Base URL | `https://<manager-host>:<port>` |
| 기본 포트 | `8443` |
| TLS | 필수 |
| Server trust | CastrelSign `ca.pem` |
| 데이터 형식 | JSON |

오류 응답은 Spring 기본 오류 응답 또는 다음 형태의 JSON 오류 응답일 수 있습니다.

```json
{
  "error": "request validation failed"
}
```

### `POST /api/admin/enrollment-tokens`

운영자가 agent 최초 등록용 enrollment token을 생성하는 endpoint입니다.

#### 인증

| 항목 | 값 |
| --- | --- |
| 방식 | Bearer token |
| 헤더 | `Authorization: Bearer <admin_token>` |
| mTLS client cert | 필요 없음 |

#### 요청 body

```json
{
  "name": "host-001 initial enrollment",
  "agent_id": "host-001",
  "ttl_seconds": 3600,
  "max_uses": 1
}
```

| 필드 | 필수 | 설명 |
| --- | --- | --- |
| `name` | 예 | 운영자가 식별할 token 이름 |
| `agent_id` | 아니오 | 지정하면 해당 agent만 이 token으로 enroll할 수 있습니다. production에서는 지정 권장 |
| `ttl_seconds` | 아니오 | token 만료 시간. 기본값은 86400초, 최소 60초입니다. |
| `max_uses` | 아니오 | 사용 가능 횟수. 기본값은 1입니다. |

#### 응답

```json
{
  "id": 1,
  "name": "host-001 initial enrollment",
  "token": "cse_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
  "agent_id": "host-001",
  "max_uses": 1,
  "used_count": 0,
  "expires_at": "2026-06-07T12:00:00Z",
  "created_at": "2026-06-07T11:00:00Z"
}
```

`token` 원문은 생성 응답에서만 반환됩니다. DB에는 `token_hash`만 저장되고 list API에도 원문은 나오지 않습니다.

### `GET /api/admin/enrollment-tokens`

enrollment token 목록을 조회합니다. 응답에는 token 원문과 hash가 포함되지 않습니다.

#### 인증

| 항목 | 값 |
| --- | --- |
| 방식 | Bearer token |
| 헤더 | `Authorization: Bearer <admin_token>` |

#### 응답

```json
[
  {
    "id": 1,
    "name": "host-001 initial enrollment",
    "agent_id": "host-001",
    "max_uses": 1,
    "used_count": 1,
    "expires_at": "2026-06-07T12:00:00Z",
    "created_at": "2026-06-07T11:00:00Z",
    "last_used_at": "2026-06-07T11:05:00Z",
    "last_used_agent_id": "host-001"
  }
]
```

### `POST /api/admin/enrollment-tokens/{id}/revoke`

enrollment token을 폐기합니다. 폐기된 token은 남은 사용 횟수나 만료 시간과 관계없이 enroll에 사용할 수 없습니다.

#### 인증

| 항목 | 값 |
| --- | --- |
| 방식 | Bearer token |
| 헤더 | `Authorization: Bearer <admin_token>` |

#### 응답

정상 처리 시 body 없이 `204 No Content`를 반환합니다.

### `POST /api/agent/enroll`

client certificate가 아직 없는 agent의 최초 등록 endpoint입니다. 이 endpoint에서 사용하는 Bearer token은 admin API로 미리 생성한 enrollment token입니다.

#### 인증

| 항목 | 값 |
| --- | --- |
| 방식 | Bearer token |
| 헤더 | `Authorization: Bearer <enrollment_token>` |
| mTLS client cert | 필요 없음 |

#### 요청 헤더

| 헤더 | 필수 | 설명 |
| --- | --- | --- |
| `Authorization` | 예 | `Bearer <admin API로 생성한 enrollment token>` |
| `Content-Type` | 예 | `application/json` |

#### 요청 body

```json
{
  "agent_id": "host-001",
  "hostname": "host-001.example.com",
  "version": "0.1.0",
  "csr_pem": "-----BEGIN CERTIFICATE REQUEST-----\n...\n-----END CERTIFICATE REQUEST-----\n"
}
```

| 필드 | 필수 | 설명 |
| --- | --- | --- |
| `agent_id` | 예 | agent 고유 ID. CSR subject CN과 같아야 합니다. |
| `hostname` | 아니오 | agent 호스트명 |
| `version` | 아니오 | agent 버전 |
| `csr_pem` | 예 | agent private key로 서명한 PKCS#10 CSR PEM |

#### 검증

| 조건 | 실패 시 |
| --- | --- |
| Bearer token 누락 또는 hash 불일치 | `401 Unauthorized` |
| Bearer token 만료, 폐기, 사용 횟수 초과 | `401 Unauthorized` |
| Bearer token의 `agent_id` 제한과 요청 `agent_id` 불일치 | `403 Forbidden` |
| `agent_id` 누락 | `400 Bad Request` |
| `csr_pem` 누락 | `400 Bad Request` |
| CSR PEM 파싱 실패 | `400 Bad Request` |
| CSR subject CN 누락 | `400 Bad Request` |
| CSR subject CN과 `agent_id` 불일치 | `400 Bad Request` |
| CSR 서명 검증 실패 | `400 Bad Request` |
| CSR public key가 EC가 아님 | `400 Bad Request` |

#### 응답

```json
{
  "agent_id": "host-001",
  "ca_cert_pem": "-----BEGIN CERTIFICATE-----\n...\n-----END CERTIFICATE-----\n",
  "client_cert_pem": "-----BEGIN CERTIFICATE-----\n...\n-----END CERTIFICATE-----\n",
  "expires_at": "2026-07-07T00:00:00Z",
  "ingest_url": "https://manager.example.com:8443/api/agent/ingest"
}
```

| 필드 | 설명 |
| --- | --- |
| `agent_id` | 발급 대상 agent ID |
| `ca_cert_pem` | CastrelSign root CA PEM |
| `client_cert_pem` | agent client certificate PEM |
| `expires_at` | client certificate 만료 시각 |
| `ingest_url` | agent ingest endpoint |

#### curl 예시

```powershell
curl.exe --cacert .\certs\ca.pem `
  -H "Authorization: Bearer cse_generated-token-from-admin-api" `
  -H "Content-Type: application/json" `
  -d "@enroll-request.json" `
  https://manager.example.com:8443/api/agent/enroll
```

### `POST /api/agent/renew`

기존 client certificate로 인증한 agent가 새 CSR을 제출해 인증서를 갱신하는 endpoint입니다.

#### 인증

| 항목 | 값 |
| --- | --- |
| 방식 | mTLS client certificate |
| Bearer token | 사용하지 않음 |
| client certificate | 필수 |

#### 요청 헤더

| 헤더 | 필수 | 설명 |
| --- | --- | --- |
| `Content-Type` | 예 | `application/json` |

#### 요청 body

`/api/agent/enroll`과 동일합니다.

```json
{
  "agent_id": "host-001",
  "hostname": "host-001.example.com",
  "version": "0.1.0",
  "csr_pem": "-----BEGIN CERTIFICATE REQUEST-----\n...\n-----END CERTIFICATE REQUEST-----\n"
}
```

#### 검증

| 조건 | 실패 시 |
| --- | --- |
| client certificate 누락 | `401 Unauthorized` |
| client certificate 만료 또는 서명 검증 실패 | `401 Unauthorized` |
| client certificate에 clientAuth EKU 없음 | `401 Unauthorized` |
| client certificate subject CN 누락 | `401 Unauthorized` |
| client certificate subject CN과 요청 `agent_id` 불일치 | `403 Forbidden` |
| CSR subject CN과 요청 `agent_id` 불일치 | `400 Bad Request` |

#### 응답

`/api/agent/enroll`과 동일합니다. 새 `client_cert_pem`과 `expires_at`이 반환됩니다.

#### curl 예시

```powershell
curl.exe --cacert .\certs\ca.pem `
  --cert .\certs\client.pem `
  --key .\certs\client.key `
  -H "Content-Type: application/json" `
  -d "@renew-request.json" `
  https://manager.example.com:8443/api/agent/renew
```

### `POST /api/agent/ingest`

agent가 수집 batch를 전송하는 endpoint입니다.

#### 인증

| 항목 | 값 |
| --- | --- |
| 방식 | mTLS client certificate |
| Bearer token | 사용하지 않음 |
| client certificate | 필수 |

#### 요청 헤더

| 헤더 | 필수 | 설명 |
| --- | --- | --- |
| `Content-Type` | 예 | `application/json` |
| `Content-Encoding` | 예 | `gzip` |

현재 구현은 `Content-Encoding: gzip`을 강제합니다. `Content-Type`은 클라이언트 계약상 `application/json`으로 보내야 합니다.

#### gzip 해제 후 body

```json
{
  "schema_version": "1.0",
  "source": "agent",
  "source_id": "host-001",
  "tenant_id": "default",
  "observed_at": "2026-06-07T00:00:00Z",
  "sent_at": "2026-06-07T00:00:01Z",
  "items": [
    {
      "kind": "metric",
      "type": "cpu",
      "key": "cpu.usage",
      "payload": {
        "value": 12.5,
        "unit": "percent"
      }
    }
  ]
}
```

| 필드 | 필수 | 설명 |
| --- | --- | --- |
| `schema_version` | 아니오 | batch schema version |
| `source` | 아니오 | 보통 `agent` |
| `source_id` | 예 | client certificate subject CN과 같아야 합니다. |
| `tenant_id` | 아니오 | tenant 식별자 |
| `observed_at` | 아니오 | agent 관측 시각 |
| `sent_at` | 아니오 | agent 전송 시각 |
| `items` | 아니오 | 수집 item 배열. 있으면 배열이어야 합니다. |

`items[]`는 다음 필드를 저장합니다.

| 필드 | 설명 |
| --- | --- |
| `kind` | `metric`, `event`, `state`, `log` 같은 상위 분류 |
| `type` | 세부 타입 |
| `key` | item key |
| `payload` | 원문 payload JSON |

#### 검증

| 조건 | 실패 시 |
| --- | --- |
| client certificate 누락 | `401 Unauthorized` |
| client certificate 만료 또는 서명 검증 실패 | `401 Unauthorized` |
| client certificate에 clientAuth EKU 없음 | `401 Unauthorized` |
| `Content-Encoding`이 `gzip`이 아님 | `415 Unsupported Media Type` |
| gzip 해제 실패 | `400 Bad Request` |
| JSON 파싱 실패 | `400 Bad Request` |
| body가 JSON object가 아님 | `400 Bad Request` |
| `items`가 배열이 아님 | `400 Bad Request` |
| `source_id` 누락 | `400 Bad Request` |
| `source_id`와 client certificate subject CN 불일치 | `403 Forbidden` |

#### 응답

정상 수신 시 body 없이 `202 Accepted`를 반환합니다.

#### curl 예시

```powershell
# Compress-Archive는 gzip이 아니므로 사용하지 마세요.

gzip.exe -c .\batch.json > .\batch.json.gz

curl.exe --cacert .\certs\ca.pem `
  --cert .\certs\client.pem `
  --key .\certs\client.key `
  -H "Content-Type: application/json" `
  -H "Content-Encoding: gzip" `
  --data-binary "@batch.json.gz" `
  https://manager.example.com:8443/api/agent/ingest
```

## Use Cases

### Use Case 1. CastrelSign 신규 설치

목표는 root CA와 HTTPS server certificate를 생성하고 manager를 실행하는 것입니다.

1. Java와 Gradle을 준비합니다.
2. `CASTRELSIGN_PUBLIC_BASE_URL`을 agent가 접근 가능한 HTTPS 주소로 설정합니다.
3. `CASTRELSIGN_ADMIN_TOKEN`을 충분히 긴 랜덤 값으로 설정합니다.
4. `CASTRELSIGN_TLS_SERVER_NAME`을 agent가 접속할 DNS 이름으로 설정합니다.
5. `gradle bootRun` 또는 `java -jar`로 실행합니다.
6. `CASTRELSIGN_DATA_DIR/certs/ca.pem`이 생성되었는지 확인합니다.

결과적으로 CastrelSign은 HTTPS로만 API를 제공합니다.

### Use Case 2. Agent 온라인 최초 등록

가장 권장하는 방식입니다.

1. 운영자가 agent 호스트에 `agent.yaml`과 `ca.pem`을 배포합니다.
2. 운영자가 admin API로 해당 `agent_id`에 제한된 enrollment token을 생성합니다.
3. agent 설정에 생성된 `enrollment_token`을 넣습니다.
4. agent를 최초 실행합니다.
5. agent가 `client.key`를 로컬 생성합니다.
6. agent가 CSR을 만들고 `/api/agent/enroll`에 제출합니다.
7. CastrelSign이 token 만료, 사용 횟수, agent 제한을 확인하고 token을 소비합니다.
8. CastrelSign이 CSR을 서명하고 `client.pem`을 반환합니다.
9. agent가 `client.pem`과 `enrollment.json`을 저장합니다.
10. 이후 agent는 mTLS로 ingest를 보냅니다.

이 방식에서는 private key가 agent 호스트 밖으로 나가지 않습니다.

### Use Case 3. Agent 사전 프로비저닝

네트워크가 제한된 환경에서는 agent 실행 호스트 또는 해당 호스트를 대표하는 안전한 프로비저닝 환경에서 미리 enrollment를 수행할 수 있습니다.

1. 대상 agent ID를 정합니다.
2. 대상 호스트에서 agent 또는 프로비저닝 도구가 private key와 CSR을 생성합니다.
3. 운영자가 admin API로 짧은 TTL의 enrollment token을 생성합니다.
4. CSR만 CastrelSign `/api/agent/enroll`로 제출합니다.
5. 응답으로 받은 `client.pem`, `ca.pem`, `enrollment.json`을 대상 host의 `cert_dir`에 둡니다.
6. agent 설정에서 `enrollment_token`은 제거할 수 있습니다.
7. agent는 시작 후 기존 `client.pem`과 `client.key`로 바로 mTLS ingest를 사용합니다.

주의할 점은 private key 생성 위치입니다. private key를 중앙 서버에서 만들어 여러 경로로 복사하는 방식은 권장하지 않습니다.

### Use Case 4. 정상 ingest

agent가 등록된 뒤 반복적으로 수행되는 일반 수집 흐름입니다.

1. agent가 collector를 실행해 수집 item을 만듭니다.
2. agent가 batch envelope를 JSON으로 구성합니다.
3. agent가 JSON을 gzip으로 압축합니다.
4. agent가 `client.pem`과 `client.key`로 mTLS 연결을 맺습니다.
5. agent가 `/api/agent/ingest`로 batch를 전송합니다.
6. CastrelSign이 client certificate CN과 batch `source_id`를 비교합니다.
7. CastrelSign이 batch 원문과 item을 SQLite에 저장합니다.
8. CastrelSign이 `202 Accepted`를 반환합니다.

### Use Case 5. 인증서 갱신

agent certificate 만료가 가까워지면 agent는 renew를 수행합니다.

1. agent가 기존 `client.pem`과 `client.key`로 mTLS 연결을 맺습니다.
2. agent가 같은 `agent_id`로 새 CSR을 생성합니다.
3. agent가 `/api/agent/renew`에 새 CSR을 제출합니다.
4. CastrelSign이 기존 client certificate CN과 요청 `agent_id`가 같은지 확인합니다.
5. CastrelSign이 새 CSR CN도 `agent_id`와 같은지 확인합니다.
6. CastrelSign이 새 client certificate를 발급합니다.
7. agent가 `client.pem`과 `enrollment.json`을 갱신합니다.

기존 인증서가 이미 만료된 뒤에는 `/api/agent/renew`가 실패합니다. 이 경우 운영자가 enrollment token을 사용해 재등록 절차를 수행해야 합니다.

### Use Case 6. Enrollment token 생성, 만료, 폐기

enrollment token은 개별 agent 등록용 bootstrap secret입니다.

1. 운영자가 `/api/admin/enrollment-tokens`로 agent별 token을 생성합니다.
2. production 기본값은 `agent_id` 지정, `max_uses=1`, 짧은 `ttl_seconds`입니다.
3. 생성 응답의 `token` 원문을 agent 설정에 넣습니다.
4. 등록이 끝나면 token은 사용 횟수가 소진되어 재사용되지 않습니다.
5. 등록 전 취소가 필요하면 `/api/admin/enrollment-tokens/{id}/revoke`로 폐기합니다.
6. 이미 등록된 agent는 enrollment token 없이 mTLS ingest/renew를 계속 사용합니다.

enrollment token을 폐기해도 기존 client certificate는 폐기되지 않습니다. 인증서 폐기나 blocklist가 필요하면 별도 revocation 기능이 추가되어야 합니다.

### Use Case 7. Agent 재설치

agent 호스트를 재설치했지만 같은 `agent_id`를 유지하려는 경우입니다.

1. 기존 `client.key`와 `client.pem`이 백업되어 있고 안전하다면 그대로 복원합니다.
2. 키를 복원하지 못했다면 새 `client.key`를 생성하고 새 enrollment token으로 다시 `/api/agent/enroll`을 수행합니다.
3. 같은 `agent_id`로 새 인증서가 발급되면 `issued_certificates`에는 새 이력이 추가됩니다.

현재 v1 구현은 같은 `agent_id`의 이전 인증서를 자동 폐기하지 않습니다.

### Use Case 8. 인증 실패 대응

| 증상 | 가능한 원인 | 조치 |
| --- | --- | --- |
| admin API가 `401` 반환 | admin token 누락 또는 불일치 | `CASTRELSIGN_ADMIN_TOKEN`과 요청 Authorization 헤더를 확인합니다. |
| enroll이 `401` 반환 | token 누락, 불일치, 만료, 폐기, 사용 횟수 초과 | agent 설정의 `enrollment_token`과 admin token 목록을 확인합니다. |
| enroll이 `403` 반환 | token이 다른 `agent_id`에 제한됨 | token 생성 시 지정한 `agent_id`와 agent 설정을 확인합니다. |
| enroll이 TLS 오류 | agent가 `ca.pem`을 신뢰하지 않음 | agent `ca_cert_path`와 `tls_server_name`을 확인합니다. |
| renew/ingest가 `401` 반환 | client cert 누락, 만료, 서명 불일치, EKU 누락 | `client.pem`, `client.key`, `ca.pem`을 확인합니다. |
| renew가 `403` 반환 | client cert CN과 요청 `agent_id` 불일치 | agent 설정의 `agent_id`를 인증서 CN과 맞춥니다. |
| ingest가 `415` 반환 | `Content-Encoding: gzip` 누락 | gzip 압축과 헤더를 확인합니다. |
| ingest가 `403` 반환 | batch `source_id`와 인증서 CN 불일치 | batch `source_id`를 agent ID와 맞춥니다. |

## SQLite 저장 구조

| 테이블 | 설명 |
| --- | --- |
| `enrollment_tokens` | enrollment token hash, agent 제한, 사용 횟수, 만료, 폐기 상태 |
| `agents` | agent identity, hostname, version, status, first/last seen |
| `issued_certificates` | agent별 발급 인증서 이력 |
| `ingest_batches` | batch envelope 필드와 raw JSON |
| `ingest_items` | batch `items[]`의 `kind`, `type`, `key`, `payload_json` |
| `audit_events` | enroll, renew, ingest 감사 이벤트 |

CastrelSign v1은 SQLite 단일 저장소를 사용합니다. 원문 batch는 `ingest_batches.raw_json`에 저장하고, item 단위 조회를 위해 `ingest_items`에 주요 필드를 분리 저장합니다.

## 보안 운영 기준

- `ca.key`와 `server.key`는 CastrelSign 호스트 밖으로 내보내지 않습니다.
- agent에는 `ca.pem`만 배포합니다.
- agent private key는 agent 호스트에서 생성합니다.
- admin token은 enrollment token 관리용이며 agent에 배포하지 않습니다.
- enrollment token은 최초 등록용이며 ingest에는 사용하지 않습니다.
- enrollment token은 agent별로 제한하고, 짧은 TTL과 `max_uses=1`을 기본으로 사용합니다.
- enrollment token 원문은 생성 응답에서만 확인하고 DB에는 hash만 저장합니다.
- agent별 `agent_id`는 재사용과 충돌을 신중히 관리합니다.
- 인증서 폐기, CRL, OCSP, agent blocklist는 현재 v1 범위에 포함되어 있지 않습니다.
- `CASTRELSIGN_KEYSTORE_PASSWORD` 기본값 `changeit`은 운영에서 변경해야 합니다.

## 검증

```powershell
cd D:\Study\castrelyx\CastrelSign
gradle test
gradle bootJar
```

테스트에는 다음 흐름이 포함됩니다.

- CSR 파싱과 CN/agent_id 검증
- root CA, server certificate, clientAuth certificate 발급
- keystore/truststore 생성과 재로드
- `/api/agent/enroll` 성공/실패
- `/api/agent/renew` client certificate 검증
- `/api/agent/ingest` gzip, source_id, 저장 검증
- 실제 HTTPS 서버에서 enroll 후 mTLS ingest 수행
