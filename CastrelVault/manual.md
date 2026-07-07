# CastrelVault 운영 매뉴얼

문서 기준: 현재 저장소 구현, 2026-06-27 KST

## 1. 서비스 역할

CastrelVault는 Castrelyx 운영 secret을 한 곳에서 저장, 회전, reveal, application resolve, audit하기 위한 독립 서비스다. Manager와 다른 애플리케이션은 secret body 대신 `vault://...` reference를 저장하는 방향으로 이동한다.

## 2. 구성 파일과 코드 위치

- 서비스 루트: `CastrelVault/`
- 애플리케이션: `src/main/java/org/castrelyx/castrelvault/CastrelVaultApplication.java`
- 설정: `src/main/resources/application.yml`
- SQLite datasource: `config/DataSourceConfig.java`
- schema 초기화: `persistence/SchemaInitializer.java`
- 암호화: `crypto/MasterKeyRegistry.java`, `crypto/EnvelopeCrypto.java`
- Admin session: `auth/AdminSessionService.java`
- Secret lifecycle: `secret/SecretService.java`
- REST API: `web/AdminController.java`, `web/SecretController.java`, `web/ApplicationResolveController.java`, `web/AuditController.java`
- Application certificate access: `application/ApplicationAccessService.java`
- UI: `src/main/resources/static/index.html`, `app.js`, `styles.css`

## 3. 필수 환경 변수

| 변수 | 목적 | 필수 여부 |
| --- | --- | --- |
| `CASTRELVAULT_DATA_DIR` | SQLite `vault.db` 위치. 기본 `/var/lib/castrelvault` | 운영 필수 |
| `CASTRELVAULT_MASTER_KEYS` | `key-id:base64-encoded-32-byte-key` 목록 | 필수 |
| `CASTRELVAULT_ACTIVE_MASTER_KEY_ID` | 신규 secret version에 사용할 active key id | 필수 |
| `CASTRELVAULT_BOOTSTRAP_ADMIN_USERNAME` | 최초 ADMIN 계정 | 최초 boot 필수 |
| `CASTRELVAULT_BOOTSTRAP_ADMIN_PASSWORD` | 최초 ADMIN password | 최초 boot 필수 |
| `CASTRELVAULT_SESSION_TTL_SECONDS` | Admin session TTL | 선택 |
| `CASTRELVAULT_CASTRELSIGN_BASE_URL` | CastrelSign decision API base URL | application resolve 운영 필수 |
| `CASTRELVAULT_CASTRELSIGN_CA_CERT_PATH` | client certificate CA 검증용 PEM | mTLS 운영 권장 |
| `CASTRELVAULT_TLS_KEYSTORE_PATH` | server TLS keystore | mTLS 운영 필수 |
| `CASTRELVAULT_TLS_KEYSTORE_PASSWORD` | server TLS keystore password | mTLS 운영 필수 |
| `CASTRELVAULT_TLS_TRUSTSTORE_PATH` | client cert truststore | mTLS 운영 필수 |
| `CASTRELVAULT_TLS_TRUSTSTORE_PASSWORD` | truststore password | mTLS 운영 필수 |

PowerShell에서 테스트용 32 byte key를 생성하는 예:

```powershell
$bytes = New-Object byte[] 32
[Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
[Convert]::ToBase64String($bytes)
```

`CASTRELVAULT_MASTER_KEYS` 값 예:

```text
key-2026:<base64-32-byte-key>
```

## 4. 로컬 실행 절차

```powershell
cd D:\study\castrelyx\CastrelVault
$env:JAVA_HOME = "$env:TEMP\temurin-jdk21"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$env:CASTRELVAULT_DATA_DIR = "$env:TEMP\castrelvault-data"
$env:CASTRELVAULT_MASTER_KEYS = "key-1:MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
$env:CASTRELVAULT_ACTIVE_MASTER_KEY_ID = "key-1"
$env:CASTRELVAULT_BOOTSTRAP_ADMIN_USERNAME = "admin"
$env:CASTRELVAULT_BOOTSTRAP_ADMIN_PASSWORD = "bootstrap-password-123"
.\gradlew.bat bootRun
```

브라우저에서 `http://127.0.0.1:8781/`로 접속한다.

## 5. 최초 ADMIN bootstrap

1. `CASTRELVAULT_BOOTSTRAP_ADMIN_USERNAME`과 `CASTRELVAULT_BOOTSTRAP_ADMIN_PASSWORD`를 설정한다.
2. Vault를 시작한다.
3. `vault_users`에 ADMIN이 없으면 bootstrap ADMIN이 생성된다.
4. 첫 로그인 응답은 `requiresPasswordChange=true`다.
5. UI 또는 `POST /api/admin/change-password`로 password를 변경한다.
6. 변경 후 secret inventory와 API 접근이 가능하다.

세션 token은 cookie `CASTRELVAULT_SESSION`으로 전달되며 DB에는 SHA-256 hash만 저장된다.

## 6. Secret lifecycle 운영

### 생성

UI의 Create secret form 또는 API를 사용한다.

```http
POST /api/secrets
Cookie: CASTRELVAULT_SESSION=...
Content-Type: application/json

{
  "path": "/integrations/logparser/admin-token",
  "displayName": "Logparser admin token",
  "type": "API_TOKEN",
  "tags": ["integration"],
  "payload": {"value": "secret-value"}
}
```

정상 응답은 payload plaintext를 반환하지 않고 masked metadata만 반환한다.

### 목록 및 상세

- `GET /api/secrets`
- `GET /api/secrets/{id}`

두 API 모두 plaintext payload를 반환하지 않는다.

### 회전

```http
POST /api/secrets/{id}/rotate

{"payload": {"value": "new-secret-value"}}
```

새 `vault_secret_versions` row가 생성되고 `vault_secrets.current_version_id`가 새 버전으로 이동한다.

### 비활성화, 재활성화, 삭제

- `POST /api/secrets/{id}/disable`
- `POST /api/secrets/{id}/enable`
- `DELETE /api/secrets/{id}`

삭제는 soft delete이며 disabled/deleted secret은 reveal/resolve할 수 없다.

## 7. Plaintext reveal 절차

Reveal은 ADMIN 전용이며 다음 조건을 모두 요구한다.

- active ADMIN session
- 현재 ADMIN password 재인증
- non-empty reason
- secret enabled
- current version decryptable

API:

```http
POST /api/secrets/{id}/reveal
Cache-Control: no-store

{
  "currentPassword": "admin-current-password",
  "reason": "incident response"
}
```

성공과 실패 모두 `vault_audit_events`에 남는다. audit에는 plaintext secret value를 저장하지 않는다.

## 8. Application resolve 절차

Application resolve는 `POST /api/v1/secrets/resolve`를 사용한다.

운영 절차:

1. CastrelSign ADMIN이 application principal을 생성한다.
2. principal에 Vault permission 예: `vault:resolve`를 grant한다.
3. one-use application enrollment token을 만든다.
4. 애플리케이션이 ECDSA P-256 key와 CSR을 로컬 생성한다.
5. CSR common name은 application principal id와 같아야 한다.
6. 애플리케이션이 token과 CSR을 CastrelSign `/api/applications/enroll`에 제출한다.
7. CastrelSign이 client certificate와 CA certificate를 반환한다.
8. 애플리케이션은 private key와 certificate를 owner-only 권한으로 저장한다.
9. 애플리케이션은 mTLS로 CastrelVault `POST /api/v1/secrets/resolve`를 호출한다.

Resolve request 예:

```json
{"reference": "vault:///integrations/logparser/admin-token"}
```

응답은 `Cache-Control: no-store`를 사용한다.

## 9. Manager migration 운영

Manager 쪽 구현은 다음 파일에 있다.

- DB migration: `manager/src/main/resources/db/migration/V3__Add_vault_references.sql`
- Vault client: `manager/src/main/java/org/castrelyx/manager/vault/HttpVaultClient.java`
- migration runner: `manager/src/main/java/org/castrelyx/manager/vault/VaultMigrationService.java`
- integration read/update path: `manager/src/main/java/org/castrelyx/manager/integration/IntegrationService.java`

설정:

```yaml
manager:
  vault:
    enabled: true
    base-url: https://castrelvault:8781
    admin-session-token: <temporary-admin-session-for-migration-writes>
    key-store-path: <application-client-keystore.p12>
    key-store-password: <password>
    trust-store-path: <castrelsign-truststore.p12>
    trust-store-password: <password>
```

동작:

- legacy `integration_configs.encrypted_secret`를 decrypt한다.
- Vault secret을 생성한다.
- `integration_configs.vault_ref`를 저장하고 legacy encrypted value를 null로 만든다.
- `snmp_credentials.encrypted_params`도 Vault secret으로 옮기고 `snmp_credentials.vault_ref`를 저장한다.
- 이후 integration secret read는 `vault_ref`가 있으면 Vault resolve를 사용한다.

현재 제한:

- 운영 호출용 REST/CLI endpoint는 아직 없다.
- migration은 코드 seam 및 테스트로 검증되어 있으며, 배포 자동화에서 호출하는 작업이 별도로 필요하다.

## 10. Audit 확인

```http
GET /api/audit-events?limit=100
```

audit action 예:

- `LOGIN`
- `LOGOUT`
- `CHANGE_PASSWORD`
- `CREATE_SECRET`
- `UPDATE_SECRET_METADATA`
- `ROTATE_SECRET`
- `DISABLE_SECRET`
- `ENABLE_SECRET`
- `DELETE_SECRET`
- `REVEAL_SECRET`
- `APPLICATION_CERT_ACCESS`
- `RESOLVE_SECRET`

## 11. 장애 대응

### master key 오류

증상: startup 실패

확인:

- `CASTRELVAULT_MASTER_KEYS`가 비어 있지 않은지
- 각 key가 base64 decode 후 정확히 32 byte인지
- `CASTRELVAULT_ACTIVE_MASTER_KEY_ID`가 key 목록에 존재하는지

### secret decrypt 실패

증상: reveal/resolve가 conflict

확인:

- 해당 version의 `key_id`가 현재 `CASTRELVAULT_MASTER_KEYS`에 남아 있는지
- master key 값이 rotate 전과 동일한지

### application resolve 실패

확인:

- client certificate가 요청에 전달되는지
- certificate CN이 application principal id와 같은지
- CastrelSign principal 상태가 `ACTIVE`인지
- principal에 `vault:resolve` permission이 있는지
- certificate serial이 CastrelSign `application_certificates`에 active로 등록되어 있는지

## 12. 검증 명령

```powershell
cd D:\study\castrelyx\CastrelVault
$env:JAVA_HOME = "$env:TEMP\temurin-jdk21"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
.\gradlew.bat test --no-daemon
.\gradlew.bat bootJar --no-daemon

cd D:\study\castrelyx\CastrelSign
.\gradlew.bat test --no-daemon

cd D:\study\castrelyx\manager
.\gradlew.bat test --no-daemon -PskipFrontend=true
```
