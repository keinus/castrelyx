# CastrelVault PRD

문서 상태: v1 구현 기준 갱신, 2026-06-27 KST

이 PRD는 CastrelVault의 제품 요구사항과 현재 저장소 구현 상태를 함께 추적한다. 제품 요구사항은 아래 1-19장에 유지하고, 구현 완료 여부는 이 상단 섹션과 `verification.md`에서 코드 및 테스트 증거로 검증한다.

## 0. Current Implementation Alignment

### 구현 완료로 확인된 범위

- 독립 서비스 루트: `CastrelVault/`
- Spring Boot 애플리케이션명과 Java 패키지: `CastrelVault`, `org.castrelyx.castrelvault`
- embedded SQLite 전용 저장소: `CASTRELVAULT_DATA_DIR` 아래 `vault.db`
- 필수 Vault 테이블: `vault_users`, `vault_sessions`, `vault_secrets`, `vault_secret_versions`, `vault_audit_events`, `vault_application_access_cache`
- envelope encryption: secret payload는 AES-256-GCM data key로 암호화하고, data key는 active master key로 AES-256-GCM wrapping
- ADMIN bootstrap login, 최초 비밀번호 변경, bcrypt password hash, session token hash 저장
- secret CRUD, metadata update, rotate, enable/disable, soft delete, reveal
- reveal 정책: ADMIN session, 현재 password 재인증, non-empty reason, enabled/decryptable secret 필요
- normal list/detail API masking, reveal/resolve `no-store` cache header
- CastrelSign APPLICATION principal, one-use application enrollment token, client certificate 발급, Vault permission decision API
- CastrelVault application resolve: request X509 client certificate, CN principal extraction, CastrelSign access decision, short-lived decision cache
- Manager migration seam: legacy integration/SNMP encrypted values를 Vault secret으로 생성하고 `vault_ref` 저장, 이후 reads는 Vault reference resolve
- Docker Compose 서비스: `castrelvault`, volume `castrelvault-data`
- 정적 Admin UI: login, password change, inventory, create, rotate, reveal modal, audit list

### 부분 구현 또는 운영 보강 필요 범위

- CastrelVault TLS/mTLS는 Spring SSL 설정과 servlet client certificate 처리 경로가 구현되어 있으나, compose 기본값은 HTTP port `8781` 노출이다. 운영 mTLS는 keystore/truststore 환경 변수를 설정해야 한다.
- Manager migration은 `VaultMigrationService`와 테스트로 검증된 코드 seam이며, 운영자가 호출할 별도 REST/CLI endpoint는 아직 없다.
- Manager의 application resolve용 mTLS client material 설정은 `MANAGER_VAULT_KEYSTORE_*`, `MANAGER_VAULT_TRUSTSTORE_*`로 구현되어 있으나, certificate enrollment/package 자동화는 별도 운영 절차가 필요하다.
- master key 자동 rewrap, HA/cluster, SQLCipher, cloud KMS, LDAP/OIDC, multi-admin approval은 PRD Non-Goals 또는 v1 이후 작업이다.
- Admin user lifecycle은 bootstrap admin과 password change 중심이다. 다중 admin 생성/권한 관리 UI는 없다.

### 구현 검증 문서

- 운영/사용 절차: `manual.md`
- 구현 상세 사양: `spec.md`
- 증거 기반 검증 결과: `verification.md`
- 빠른 시작 및 문서 인덱스: `README.md`

## 1. Product Overview

CastrelVault is a dedicated credential vault for the Castrelyx platform. It stores server credentials, integration secrets, SNMP parameters, API tokens, database passwords, certificate private-key material, and other sensitive operational secrets in one controlled security subsystem.

The product goal is to remove plaintext credential handling from Manager, operational scripts, and future Castrelyx applications. CastrelVault becomes the single place where secrets are stored, rotated, revealed, resolved for application use, and audited.

CastrelVault is a standalone service in the repository under `CastrelVault/`, but its storage is intentionally self-contained. It must not depend on any external database service.

## 2. Goals

- Centralize secret storage for Castrelyx-managed infrastructure and integrations.
- Use embedded SQLite only for Vault persistence.
- Encrypt secret payloads before they are written to SQLite.
- Keep plaintext out of list APIs, normal read APIs, logs, audit records, and frontend bundles.
- Support controlled administrator reveal with password reauthentication and a mandatory reason.
- Support application-to-Vault access through CastrelSign-issued client certificates and mTLS.
- Migrate existing Manager-managed integration and SNMP secrets to Vault references.
- Keep deployment simple with a single `castrelvault` service and a persistent `castrelvault-data` volume.

## 3. Non-Goals

- No external database support.
- No database backend abstraction for future swap-out.
- No high-availability or clustered Vault mode.
- No SQLCipher requirement.
- No cloud KMS integration.
- No OIDC, LDAP, or SSO login.
- No multi-ADMIN approval workflow for reveal.
- No requirement that Vault secrets map to Manager assets.

## 4. Core Principles

- **Single authority for secrets:** CastrelVault owns app-managed secret values. Other Castrelyx services store references, not secret bodies.
- **Embedded persistence only:** CastrelVault stores all Vault data in its own SQLite file.
- **Defense in depth:** SQLite file permissions protect the file, and application-level encryption protects secret payloads inside the file.
- **Minimal plaintext exposure:** Plaintext appears only in reveal or application resolve flows.
- **Auditable access:** Secret creation, update, rotation, reveal, resolve, delete, login, logout, failed access, and application certificate access decisions are audit events.
- **Bootstrap separation:** Values required before Vault starts, such as the Vault master key, remain outside Vault and are supplied by deployment configuration.

## 5. Naming

- Product name: `CastrelVault`
- Repository directory: `CastrelVault/`
- Docker service name: `castrelvault`
- Docker volume name: `castrelvault-data`
- Spring application name: `CastrelVault`
- Java package: `org.castrelyx.castrelvault`
- Environment variable prefix: `CASTRELVAULT_`
- Default data directory: `/var/lib/castrelvault`
- SQLite database file: `/var/lib/castrelvault/vault.db`

## 6. Storage Design

CastrelVault uses only embedded SQLite.

The SQLite database is stored at:

```text
/var/lib/castrelvault/vault.db
```

The deployment must mount `/var/lib/castrelvault` from the `castrelvault-data` volume.

Required tables:

- `vault_users`
- `vault_sessions`
- `vault_secrets`
- `vault_secret_versions`
- `vault_audit_events`
- `vault_application_access_cache`

`vault_secrets` stores metadata only:

- stable secret id
- path
- display name
- type
- tags
- description
- enabled/deleted state
- created/updated timestamps
- current version id

`vault_secret_versions` stores encrypted payloads:

- secret id
- version number
- key id
- encrypted data encryption key
- encryption nonce
- ciphertext
- payload content hash
- created timestamp
- creator principal

No secret plaintext is stored in SQLite.

## 7. Configuration

CastrelVault v1 supports these environment variables:

- `CASTRELVAULT_DATA_DIR`
- `CASTRELVAULT_MASTER_KEYS`
- `CASTRELVAULT_ACTIVE_MASTER_KEY_ID`
- `CASTRELVAULT_BOOTSTRAP_ADMIN_USERNAME`
- `CASTRELVAULT_BOOTSTRAP_ADMIN_PASSWORD`
- `CASTRELVAULT_SESSION_TTL_SECONDS`
- `CASTRELVAULT_CASTRELSIGN_BASE_URL`
- `CASTRELVAULT_CASTRELSIGN_CA_CERT_PATH`
- `CASTRELVAULT_TLS_KEYSTORE_PATH`
- `CASTRELVAULT_TLS_KEYSTORE_PASSWORD`
- `CASTRELVAULT_TLS_TRUSTSTORE_PATH`
- `CASTRELVAULT_TLS_TRUSTSTORE_PASSWORD`

`CASTRELVAULT_MASTER_KEYS` contains one or more key entries in this form:

```text
key-id:base64-encoded-32-byte-key,key-id-2:base64-encoded-32-byte-key
```

`CASTRELVAULT_ACTIVE_MASTER_KEY_ID` must match one configured key id. New secret versions use only the active key id.

If required configuration is missing, CastrelVault must fail fast at startup.

## 8. Encryption Design

CastrelVault uses envelope encryption.

For every new secret version:

1. Generate a random data encryption key.
2. Serialize and validate the secret payload.
3. Encrypt the payload with AES-256-GCM using the data encryption key.
4. Wrap the data encryption key with the active master key using AES-256-GCM.
5. Store only the key id, nonces, wrapped key, ciphertext, and metadata.

Decryption:

1. Load the secret version.
2. Select the configured master key by stored key id.
3. Unwrap the data encryption key.
4. Decrypt the payload with AES-256-GCM.
5. Return plaintext only to reveal or application resolve callers.

Key rotation:

- Adding a new master key and changing `CASTRELVAULT_ACTIVE_MASTER_KEY_ID` affects new versions only.
- Existing versions remain decryptable while their old key id remains configured.
- Re-encryption may be implemented as an explicit administrative maintenance task, but v1 does not require automatic background rewrapping.

## 9. Secret Types

CastrelVault v1 supports these secret types:

- `SERVER_LOGIN`
- `SSH_KEY`
- `WINDOWS_LOGIN`
- `SNMP_V2C`
- `SNMP_V3`
- `API_TOKEN`
- `DB_PASSWORD`
- `CERTIFICATE_KEY`
- `GENERIC`

All secret types share the same lifecycle:

- create
- update metadata
- rotate value
- disable
- delete or soft-delete
- reveal
- application resolve
- audit

## 10. Admin Authentication

CastrelVault has its own ADMIN login.

Bootstrap behavior:

1. On first startup, if no admin exists, create the bootstrap admin from `CASTRELVAULT_BOOTSTRAP_ADMIN_USERNAME` and `CASTRELVAULT_BOOTSTRAP_ADMIN_PASSWORD`.
2. Mark the account as requiring password change.
3. On first login, allow only the password-change flow.
4. After password change, allow normal Vault UI/API access.

Admin passwords are stored with a strong one-way password hash.

Session behavior:

- Session tokens are random.
- Only token hashes are stored.
- Cookies are `HttpOnly`.
- Session TTL is controlled by `CASTRELVAULT_SESSION_TTL_SECONDS`.

## 11. Plaintext Reveal Policy

Secret plaintext reveal is allowed only for ADMIN users.

Reveal requires:

- active ADMIN session
- current ADMIN password reauthentication
- non-empty reason
- target secret is enabled
- target version is decryptable

Every reveal attempt creates an audit event.

Audit event contents:

- timestamp
- actor type
- actor id
- secret path
- secret version
- action
- result
- reason
- request source metadata

Audit events must never store plaintext secret values.

## 12. CastrelSign Application Authentication

Application access uses CastrelSign-issued client certificates and mTLS.

CastrelSign must support `APPLICATION` principals in addition to existing agent principals.

Application enrollment process:

1. CastrelSign ADMIN creates an application principal.
2. CastrelSign ADMIN grants the application principal a Vault permission such as `vault:admin`.
3. CastrelSign ADMIN creates a one-use application enrollment token.
4. The application generates an ECDSA P-256 private key locally.
5. The application creates a CSR with the application principal id as the certificate common name.
6. The application posts the CSR and token to CastrelSign.
7. CastrelSign validates the token, CSR common name, principal state, and requested certificate profile.
8. CastrelSign signs and returns a client certificate and CA certificate.
9. The application stores the private key and certificate locally with owner-only permissions.
10. The application calls CastrelVault using mTLS.

CastrelVault validates application requests by:

- requiring a client certificate
- verifying the certificate chain against the CastrelSign CA
- checking certificate validity dates
- extracting the common name as the application principal id
- confirming the principal is active in CastrelSign
- confirming the principal has a Vault permission accepted by CastrelVault
- recording the access decision in audit events

If CastrelSign is temporarily unavailable, CastrelVault may use a short-lived access cache from `vault_application_access_cache`. The cache must not allow blocked or expired certificates beyond its configured TTL.

## 13. API Scope

Admin/session API:

- `POST /api/admin/login`
- `POST /api/admin/change-password`
- `POST /api/admin/logout`
- `GET /api/admin/session`

Secret management API:

- `GET /api/secrets`
- `POST /api/secrets`
- `GET /api/secrets/{id}`
- `PUT /api/secrets/{id}`
- `POST /api/secrets/{id}/rotate`
- `POST /api/secrets/{id}/disable`
- `POST /api/secrets/{id}/enable`
- `DELETE /api/secrets/{id}`

Plaintext reveal API:

- `POST /api/secrets/{id}/reveal`

Application resolve API:

- `POST /api/v1/secrets/resolve`

Audit API:

- `GET /api/audit-events`

Normal list/detail APIs return masked data only. Only reveal and resolve APIs may return plaintext.

## 14. Manager Migration

Manager must stop owning secret bodies.

Existing Manager-managed secrets to migrate:

- CastrelSign admin token from integration configuration
- Logparser API/admin token from integration configuration
- SNMP credential parameters

Migration behavior:

1. Manager starts with access to legacy encrypted fields and Vault client configuration.
2. A migration runner reads each legacy encrypted secret.
3. Manager decrypts it using the existing legacy crypto path.
4. Manager creates a CastrelVault secret.
5. Manager stores the Vault reference in Manager-owned configuration.
6. Manager clears or ignores the legacy encrypted value.
7. Future reads resolve the secret through CastrelVault.

Manager must not return migrated plaintext in API responses.

Manager must not require a secret body for future updates if an existing Vault reference is present.

## 15. Deployment

Repository layout:

```text
CastrelVault/
  build.gradle
  Dockerfile
  src/
```

Docker Compose adds:

```text
castrelvault
castrelvault-data
```

The service mounts:

```text
/var/lib/castrelvault
```

The service exposes the Vault UI/API on a dedicated port.

CastrelVault must start without any database service dependency.

## 16. Security Requirements

- Secret plaintext must not appear in list responses.
- Secret plaintext must not appear in detail responses.
- Secret plaintext must not appear in audit rows.
- Secret plaintext must not be logged by server code.
- Secret plaintext must not be embedded in frontend static assets.
- Failed decryptions must not leak ciphertext, key ids beyond necessary diagnostics, or plaintext fragments.
- Reveal and resolve responses must set no-store cache headers.
- Application resolve requests must require mTLS.
- Disabled/deleted secrets cannot be revealed or resolved.
- Blocked or revoked application certificates cannot resolve secrets.
- All admin and application access decisions must be auditable.

## 17. UI Requirements

The first screen is the CastrelVault login screen.

After bootstrap login, the admin must change the password before accessing secrets.

Main UI views:

- Secret inventory
- Secret detail
- Secret create/edit
- Secret rotate
- Reveal modal
- Audit events

The reveal modal must require:

- admin password
- reason
- explicit reveal action

The UI must never show plaintext in tables, cards, logs, audit event lists, or toasts.

## 18. Test and Verification Plan

Storage tests:

- CastrelVault starts with embedded SQLite only.
- Database file is created under `CASTRELVAULT_DATA_DIR`.
- Missing master keys fail startup.
- Invalid active key id fails startup.

Crypto tests:

- Secret encryption/decryption works.
- Same plaintext produces different ciphertext due to random nonces.
- Wrong master key fails decryption.
- Old versions remain decryptable while old key ids remain configured.
- New versions use the active key id.

Admin API tests:

- Bootstrap admin is created once.
- First login requires password change.
- Session token hashes are stored, not raw tokens.
- Reveal requires current password and reason.
- Failed reveal attempts are audited.

Application API tests:

- CastrelSign application certificate can resolve a permitted secret.
- Missing client certificate is rejected.
- Expired certificate is rejected.
- Blocked application principal is rejected.
- Missing Vault permission is rejected.

Manager migration tests:

- Legacy integration secret migrates to a Vault reference.
- Legacy SNMP credential migrates to a Vault reference.
- Manager resolves secrets through Vault after migration.
- Legacy encrypted fields are not used after migration.

Leakage tests:

- Search server logs for known test secret values.
- Search audit rows for known test secret values.
- Search API list/detail responses for known test secret values.
- Search frontend bundle output for known test secret values.

## 19. Acceptance Criteria

- `CastrelVault/` exists as the future service root.
- The product name is consistently `CastrelVault`.
- Environment variables use the `CASTRELVAULT_` prefix.
- CastrelVault uses `/var/lib/castrelvault/vault.db` as its embedded SQLite database.
- CastrelVault has no external database configuration surface.
- Secret payloads are encrypted before storage.
- ADMIN reveal requires password reauthentication and a reason.
- Application resolve uses CastrelSign-issued client certificates and mTLS.
- Manager stores Vault references instead of app-managed secret bodies.
- Plaintext secrets do not appear in normal APIs, logs, audit events, or frontend bundles.
