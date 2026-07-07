# CastrelVault

CastrelVault는 Castrelyx 플랫폼용 credential vault 서비스다. Manager, 운영 스크립트, 향후 Castrelyx 애플리케이션이 secret body를 직접 보관하지 않고 Vault reference를 저장하도록 만드는 것이 목적이다.

현재 구현은 `CastrelVault/` 아래 독립 Spring Boot 서비스로 제공된다. 저장소는 외부 DB 없이 embedded SQLite만 사용하며, secret payload는 SQLite에 기록되기 전에 envelope encryption으로 암호화된다.

## 문서

- `CastrelVault_PRD.md`: 제품 요구사항과 현재 구현 alignment
- `spec.md`: 코드 기준 구현 사양
- `manual.md`: 로컬 실행, 운영 설정, secret 관리, CastrelSign/Manager 연동 절차
- `verification.md`: 요구사항과 구현/테스트 증거 대조 결과

## 구현 요약

- 데이터 경로: `CASTRELVAULT_DATA_DIR`, 기본 `/var/lib/castrelvault`
- DB 파일: `/var/lib/castrelvault/vault.db`
- 주요 테이블: `vault_users`, `vault_sessions`, `vault_secrets`, `vault_secret_versions`, `vault_audit_events`, `vault_application_access_cache`
- 암호화: AES-256-GCM envelope encryption
- Admin 인증: bootstrap ADMIN, 최초 비밀번호 변경, bcrypt password hash, hashed session token
- Secret API: create/list/detail/update/rotate/enable/disable/delete/reveal
- Application API: `POST /api/v1/secrets/resolve`
- CastrelSign 연동: APPLICATION principal, one-use enrollment token, client certificate, Vault permission decision
- Manager 연동: `vault_ref` 컬럼, `VaultMigrationService`, `VaultClient`
- UI: 정적 Admin UI, 첫 화면 login, reveal modal, audit list

## 빠른 로컬 실행

PowerShell 예시:

```powershell
cd D:\study\castrelyx\CastrelVault
$env:JAVA_HOME = "$env:TEMP\temurin-jdk21"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$env:CASTRELVAULT_DATA_DIR = "$env:TEMP\castrelvault-data"
$env:CASTRELVAULT_MASTER_KEYS = "key-1:MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
$env:CASTRELVAULT_ACTIVE_MASTER_KEY_ID = "key-1"
$env:CASTRELVAULT_BOOTSTRAP_ADMIN_USERNAME = "admin"
$env:CASTRELVAULT_BOOTSTRAP_ADMIN_PASSWORD = "change-this-bootstrap-password"
.\gradlew.bat bootRun
```

접속:

```text
http://127.0.0.1:8781/
```

첫 로그인 후에는 반드시 비밀번호를 변경해야 secret inventory/API 접근이 가능하다.

## 테스트

```powershell
cd D:\study\castrelyx\CastrelVault
$env:JAVA_HOME = "$env:TEMP\temurin-jdk21"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
.\gradlew.bat test --no-daemon
.\gradlew.bat bootJar --no-daemon
```

연동 회귀까지 보려면 repository root에서 다음도 실행한다.

```powershell
cd D:\study\castrelyx\CastrelSign
$env:JAVA_HOME = "$env:TEMP\temurin-jdk21"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
.\gradlew.bat test --no-daemon

cd D:\study\castrelyx\manager
$env:JAVA_HOME = "$env:TEMP\temurin-jdk21"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
.\gradlew.bat test --no-daemon -PskipFrontend=true
```

## 배포

루트 `docker-compose.yml`에 `castrelvault` 서비스와 `castrelvault-data` volume이 추가되어 있다. 서비스는 `/var/lib/castrelvault`를 persistent volume으로 마운트하고 기본 port `8781`을 노출한다.

운영에서는 다음 값을 반드시 안전한 값으로 교체한다.

- `CASTRELVAULT_MASTER_KEYS`
- `CASTRELVAULT_ACTIVE_MASTER_KEY_ID`
- `CASTRELVAULT_BOOTSTRAP_ADMIN_USERNAME`
- `CASTRELVAULT_BOOTSTRAP_ADMIN_PASSWORD`
- `CASTRELVAULT_TLS_KEYSTORE_PATH`
- `CASTRELVAULT_TLS_KEYSTORE_PASSWORD`
- `CASTRELVAULT_TLS_TRUSTSTORE_PATH`
- `CASTRELVAULT_TLS_TRUSTSTORE_PASSWORD`

## 현재 제한

- compose 기본값은 HTTP이다. application resolve를 실제 mTLS로 운영하려면 TLS keystore/truststore를 설정해야 한다.
- Manager legacy secret migration은 `VaultMigrationService` 코드 seam으로 구현되어 있으며 별도 운영 endpoint는 없다.
- automatic key rewrap, HA, SQLCipher, cloud KMS, OIDC/LDAP, multi-admin approval은 v1 범위 밖이다.
