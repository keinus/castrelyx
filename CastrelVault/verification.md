# CastrelVault 구현-문서 검증 보고서

검증 기준일: 2026-06-27 KST

이 문서는 `README.md`, `manual.md`, `CastrelVault_PRD.md`, `spec.md`가 현재 구현과 어긋나지 않는지 확인한 결과다. 검증은 코드 경로, 설정 파일, schema, 테스트 파일, 실행 명령을 기준으로 했다.

## 1. 문서 산출물 확인

| 문서 | 목적 | 상태 |
| --- | --- | --- |
| `README.md` | 빠른 시작, 문서 인덱스, 테스트/배포 요약 | 작성 |
| `manual.md` | 운영 절차와 troubleshooting | 작성 |
| `CastrelVault_PRD.md` | 제품 요구사항과 현재 구현 alignment | 갱신 |
| `spec.md` | 구현 상세 사양 | 작성 |
| `verification.md` | 구현과 문서 대조 증거 | 작성 |

## 2. 구현 증거 대조

| 요구사항 | 구현 증거 | 검증 판단 |
| --- | --- | --- |
| `CastrelVault/` 서비스 루트 | `CastrelVault/build.gradle`, `CastrelVault/Dockerfile`, `CastrelVault/src/main/java/.../CastrelVaultApplication.java` | 충족 |
| embedded SQLite만 사용 | `CastrelVault/src/main/java/.../config/DataSourceConfig.java`가 `jdbc:sqlite:${dataDir}/vault.db` 생성 | 충족 |
| `/var/lib/castrelvault/vault.db` 기본 | `CastrelVault/src/main/resources/application.yml`, `CastrelVaultProperties.dataDir` 기본값 | 충족 |
| Vault required tables | `CastrelVault/src/main/java/.../persistence/SchemaInitializer.java` | 충족 |
| payload encryption before storage | `EnvelopeCrypto.java`, `SecretService.createVersion` | 충족 |
| active master key validation | `MasterKeyRegistry.java` | 충족 |
| ADMIN bootstrap and forced password change | `AdminSessionService.afterPropertiesSet`, `AdminController.changePassword` | 충족 |
| session raw token 미저장 | `AdminSessionService.hash`, `vault_sessions.token_hash` | 충족 |
| normal API masking | `SecretService.response`의 `payload.configured/masked` | 충족 |
| reveal password reauth and reason | `SecretController.reveal` | 충족 |
| reveal/resolve no-store | `SecretController.reveal`, `ApplicationResolveController.resolve` | 충족 |
| audit event without plaintext | `AuditService.record`, leakage integration test | 충족 |
| application client certificate required | `ApplicationAccessService.certificate` | 충족 |
| CastrelSign APPLICATION principal | `ApplicationPrincipalRepository`, `AdminApplicationController`, `ApplicationController` | 충족 |
| one-use application enrollment token | `ApplicationEnrollmentTokenService.consumeValid` | 충족 |
| CastrelSign Vault permission decision | `ApplicationController.vaultAccess` | 충족 |
| Manager vault reference migration | `V3__Add_vault_references.sql`, `VaultMigrationService` | 충족 |
| Manager future reads resolve through Vault | `IntegrationService.decryptedSecret`, `HttpVaultClient.resolveString` | 충족 |
| Docker Compose service and volume | root `docker-compose.yml` service `castrelvault`, volume `castrelvault-data` | 충족 |
| UI login, secret inventory, reveal, audit | `CastrelVault/src/main/resources/static/index.html`, `app.js` | 충족 |

## 3. 부분 구현으로 문서에 명시한 항목

| 항목 | 현재 구현 상태 | 문서 반영 |
| --- | --- | --- |
| 운영 mTLS | Spring SSL 설정과 certificate request 처리 구현. compose 기본은 HTTP | `README.md`, `manual.md`, `spec.md`, PRD alignment에 명시 |
| Manager migration 운영 호출 | `VaultMigrationService`와 테스트 seam 구현. 별도 REST/CLI 없음 | `manual.md`, `spec.md`, PRD alignment에 제한으로 명시 |
| key rewrap | 자동 rewrap 없음 | PRD Non-Goal 및 `spec.md` 제한에 명시 |
| admin user lifecycle | bootstrap admin/password change 중심 | `manual.md`, `spec.md` 제한에 명시 |

## 4. 테스트 증거

### CastrelVault

테스트 파일:

- `CastrelVault/src/test/java/org/castrelyx/castrelvault/EnvelopeCryptoTest.java`
- `CastrelVault/src/test/java/org/castrelyx/castrelvault/CastrelVaultApiIntegrationTest.java`

검증 항목:

- SQLite file creation under configured data dir
- missing/invalid master key fail-fast
- encryption/decryption
- random nonce로 동일 plaintext의 ciphertext 차이
- wrong master key decrypt 실패
- old key configured 상태에서 old version decrypt
- first login password change required
- session token hash stored
- normal list/detail API plaintext 미노출
- DB ciphertext/audit/static asset plaintext 미노출
- reveal password/reason/enabled 조건
- failed reveal audit
- application certificate resolve
- missing/expired/denied certificate rejection
- disabled/deleted secret resolve rejection

### CastrelSign

테스트 파일:

- `CastrelSign/src/test/java/org/castrelyx/castrelsign/ApplicationPrincipalControllerTest.java`

검증 항목:

- admin application principal 생성
- Vault permission grant
- one-use application enrollment token
- token hash 미노출
- CSR common name 기반 certificate issue
- Vault access decision allowed/denied
- blocked principal denial
- missing permission denial
- unknown certificate serial denial

### Manager

테스트 파일:

- `manager/src/test/java/org/castrelyx/manager/vault/VaultMigrationServiceTest.java`

검증 항목:

- legacy integration secret migration
- legacy SNMP credential migration
- `vault_ref` 저장
- migrated integration encrypted value clear
- future read path Vault resolve
- existing `vault_ref`가 있으면 update에 secret body 불필요
- Vault enabled 상태에서 새 integration secret이 Vault write path를 사용

## 5. 실행 검증 명령

검증 완료 후 이 표에 결과를 남긴다.

| 명령 | 기대 결과 | 결과 |
| --- | --- | --- |
| `cd CastrelVault; .\gradlew.bat test --no-daemon` | CastrelVault unit/integration tests pass | PASS |
| `cd CastrelVault; .\gradlew.bat bootJar --no-daemon` | service jar build pass | PASS |
| `cd CastrelSign; .\gradlew.bat test --no-daemon` | CastrelSign tests pass | PASS |
| `cd manager; .\gradlew.bat test --no-daemon -PskipFrontend=true` | Manager backend tests pass | PASS |
| `git diff --check` | whitespace errors 없음 | PASS |

## 6. 문서 검증 결론

현재 문서들은 구현된 기능을 과장하지 않도록 다음 원칙으로 작성되었다.

- 코드로 확인되는 기능은 `구현 완료`로 표현했다.
- 운영 endpoint나 자동화가 없는 기능은 `코드 seam 구현`, `운영 보강 필요`로 표현했다.
- PRD Non-Goals 또는 v1 이후 범위는 구현 완료로 쓰지 않았다.
- 모든 보안 요구사항은 해당 구현 파일 또는 테스트 파일에 연결했다.
