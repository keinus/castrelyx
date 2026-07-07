# CastrelVault 구현 사양

문서 기준: 현재 저장소 구현, 2026-06-27 KST

## 1. 범위

CastrelVault v1은 Castrelyx용 단일 노드 credential vault다. 서비스 자체 persistence는 embedded SQLite만 사용한다. 외부 DB, HA, SQLCipher, cloud KMS, OIDC/LDAP, multi-admin approval은 v1 범위 밖이다.

## 2. 아키텍처

```text
Admin Browser
  -> CastrelVault static UI
  -> CastrelVault Admin/Secret/Audit API
  -> SQLite vault.db

Application with CastrelSign client certificate
  -> mTLS request to CastrelVault /api/v1/secrets/resolve
  -> CastrelVault validates certificate and calls CastrelSign access decision
  -> CastrelVault decrypts current version and returns plaintext only in resolve response

Manager
  -> legacy encrypted fields
  -> VaultMigrationService
  -> CastrelVault Admin Secret API for migration writes
  -> stores vault_ref
  -> future reads use VaultClient resolve
```

## 3. 서비스 모듈

| 모듈 | 파일 | 책임 |
| --- | --- | --- |
| Boot app | `CastrelVaultApplication.java` | Spring Boot entrypoint |
| Configuration | `CastrelVaultProperties.java` | `castrelvault.*` config binding |
| DataSource | `DataSourceConfig.java` | `CASTRELVAULT_DATA_DIR/vault.db` SQLite datasource |
| Schema | `SchemaInitializer.java` | Vault tables 생성 |
| Master key | `MasterKeyRegistry.java` | master key parsing, active key validation |
| Crypto | `EnvelopeCrypto.java` | AES-GCM payload encryption and DEK wrapping |
| Admin auth | `AdminSessionService.java` | bootstrap admin, bcrypt hash, session token hash |
| Secret lifecycle | `SecretService.java` | metadata/version persistence, rotate, reveal/resolve decrypt |
| Application access | `ApplicationAccessService.java` | X509 client certificate, CN extraction, CastrelSign decision, cache |
| CastrelSign client | `HttpCastrelSignAccessClient.java` | principal permission decision lookup |
| REST | `AdminController.java`, `SecretController.java`, `ApplicationResolveController.java`, `AuditController.java` | API surface |
| UI | `static/index.html`, `static/app.js`, `static/styles.css` | Admin web UI |

## 4. Storage 사양

DB 파일:

```text
${CASTRELVAULT_DATA_DIR}/vault.db
```

기본 경로:

```text
/var/lib/castrelvault/vault.db
```

테이블:

| 테이블 | 내용 |
| --- | --- |
| `vault_users` | ADMIN username, password hash, role, password-change flag, enabled |
| `vault_sessions` | session token hash, user id, expiration |
| `vault_secrets` | secret metadata, enabled/deleted state, current version pointer |
| `vault_secret_versions` | key id, wrapped DEK, nonces, ciphertext, content hash |
| `vault_audit_events` | admin/application access events without plaintext |
| `vault_application_access_cache` | short-lived CastrelSign decision cache |

`vault_secrets`는 secret body를 저장하지 않는다. plaintext와 ciphertext는 분리되며, payload ciphertext는 `vault_secret_versions.ciphertext`에만 저장된다.

## 5. Configuration 사양

`application.yml`은 `CASTRELVAULT_` prefix를 사용한다.

| 환경 변수 | 구현 config | 설명 |
| --- | --- | --- |
| `CASTRELVAULT_PORT` | `server.port` | 기본 `8781` |
| `CASTRELVAULT_TLS_ENABLED` | `server.ssl.enabled` | 기본 `false` |
| `CASTRELVAULT_TLS_KEYSTORE_PATH` | `server.ssl.key-store` 및 property mirror | TLS server keystore |
| `CASTRELVAULT_TLS_KEYSTORE_PASSWORD` | `server.ssl.key-store-password` 및 property mirror | keystore password |
| `CASTRELVAULT_TLS_TRUSTSTORE_PATH` | `server.ssl.trust-store` 및 property mirror | mTLS truststore |
| `CASTRELVAULT_TLS_TRUSTSTORE_PASSWORD` | `server.ssl.trust-store-password` 및 property mirror | truststore password |
| `CASTRELVAULT_DATA_DIR` | `castrelvault.data-dir` | SQLite directory |
| `CASTRELVAULT_MASTER_KEYS` | `castrelvault.master-keys` | key id와 32 byte base64 key 목록 |
| `CASTRELVAULT_ACTIVE_MASTER_KEY_ID` | `castrelvault.active-master-key-id` | 신규 version key id |
| `CASTRELVAULT_BOOTSTRAP_ADMIN_USERNAME` | `castrelvault.bootstrap-admin-username` | 최초 ADMIN |
| `CASTRELVAULT_BOOTSTRAP_ADMIN_PASSWORD` | `castrelvault.bootstrap-admin-password` | 최초 password |
| `CASTRELVAULT_SESSION_TTL_SECONDS` | `castrelvault.session-ttl-seconds` | session TTL |
| `CASTRELVAULT_CASTRELSIGN_BASE_URL` | `castrelvault.castrelsign-base-url` | CastrelSign decision API |
| `CASTRELVAULT_CASTRELSIGN_CA_CERT_PATH` | `castrelvault.castrelsign-ca-cert-path` | optional PEM CA verification |

Fail-fast 조건:

- `CASTRELVAULT_DATA_DIR` missing
- `CASTRELVAULT_MASTER_KEYS` missing/invalid
- key entry가 `key-id:base64` 형식이 아님
- decoded key가 32 byte가 아님
- active key id가 configured key 목록에 없음
- 최초 ADMIN이 없는데 bootstrap username/password가 missing

## 6. Encryption 사양

새 secret version 생성:

1. request payload JSON을 byte array로 serialize한다.
2. 32 byte random data encryption key를 생성한다.
3. 12 byte payload nonce로 payload를 AES/GCM/NoPadding encrypt한다.
4. 12 byte wrapping nonce로 data encryption key를 active master key로 AES/GCM/NoPadding encrypt한다.
5. `key_id`, `wrapped_dek_nonce`, `encrypted_dek`, `encryption_nonce`, `ciphertext`, `payload_content_hash`를 저장한다.

Decrypt:

1. version row의 `key_id`로 configured master key를 찾는다.
2. `encrypted_dek`를 unwrap한다.
3. unwrapped DEK로 payload ciphertext를 decrypt한다.
4. reveal/resolve response에만 plaintext JSON payload를 반환한다.

Key rotation:

- active key id 변경은 새 version에만 적용된다.
- 기존 version은 해당 old key id와 key material이 config에 남아 있으면 decrypt 가능하다.
- automatic background rewrap은 구현하지 않는다.

## 7. Admin API 사양

| API | 설명 | plaintext 반환 |
| --- | --- | --- |
| `POST /api/admin/login` | ADMIN login, session cookie 발급 | 없음 |
| `POST /api/admin/change-password` | 최초 또는 일반 password change | 없음 |
| `POST /api/admin/logout` | session 삭제 | 없음 |
| `GET /api/admin/session` | session 상태 | 없음 |

Password:

- `BCryptPasswordEncoder(12)` 사용
- bootstrap admin은 `require_password_change=1`

Session:

- token prefix는 `csv_`
- cookie name은 `CASTRELVAULT_SESSION`
- DB에는 raw token이 아니라 SHA-256 hash 저장
- cookie는 HttpOnly, SameSite Strict

## 8. Secret API 사양

| API | 설명 |
| --- | --- |
| `GET /api/secrets` | enabled/deleted가 아닌 secret metadata list |
| `POST /api/secrets` | secret metadata와 encrypted version 생성 |
| `GET /api/secrets/{id}` | secret metadata detail |
| `PUT /api/secrets/{id}` | displayName, description, tags update |
| `POST /api/secrets/{id}/rotate` | 새 version 생성 |
| `POST /api/secrets/{id}/disable` | reveal/resolve 차단 |
| `POST /api/secrets/{id}/enable` | enable |
| `DELETE /api/secrets/{id}` | soft delete |
| `POST /api/secrets/{id}/reveal` | ADMIN plaintext reveal |

Normal response payload:

```json
{"payload": {"configured": true, "masked": "********"}}
```

Reveal response:

```json
{"id": "...", "path": "/...", "version": 1, "payload": {...}}
```

Reveal은 `Cache-Control: no-store`를 설정한다.

## 9. Application resolve 사양

API:

```text
POST /api/v1/secrets/resolve
```

Request:

```json
{"reference": "vault:///path/to/secret"}
```

또는:

```json
{"paths": ["/path/one", "/path/two"]}
```

Access validation:

1. servlet request attribute `jakarta.servlet.request.X509Certificate`에서 client certificate를 읽는다.
2. certificate validity date를 확인한다.
3. `CASTRELVAULT_CASTRELSIGN_CA_CERT_PATH`가 있으면 해당 CA public key로 certificate verify를 수행한다.
4. subject common name을 application principal id로 추출한다.
5. CastrelSign `/api/applications/{principalId}/vault-access`에 permission `vault:resolve`와 serial number를 질의한다.
6. allowed decision이면 short-lived cache에 저장한다.
7. denied decision 또는 missing/expired certificate면 resolve를 거부하고 audit한다.

Resolve response는 plaintext payload를 포함하므로 `Cache-Control: no-store`를 설정한다.

## 10. CastrelSign 확장 사양

추가 테이블:

- `application_principals`
- `application_enrollment_tokens`
- `application_certificates`

Admin API:

- `GET /api/admin/applications`
- `POST /api/admin/applications`
- `POST /api/admin/applications/{principalId}/permissions`
- `POST /api/admin/applications/{principalId}/block`
- `POST /api/admin/applications/{principalId}/reactivate`
- `GET /api/admin/application-enrollment-tokens`
- `POST /api/admin/application-enrollment-tokens`
- `POST /api/admin/application-enrollment-tokens/{id}/revoke`

Application API:

- `POST /api/applications/enroll`
- `GET /api/applications/{principalId}/vault-access?permission=...&serial_number=...`

Security:

- enrollment token은 raw token을 저장하지 않고 hash만 저장한다.
- application enrollment token은 one-use다.
- CSR common name은 `principal_id`와 일치해야 한다.
- principal status가 `ACTIVE`여야 certificate 발급 및 access decision이 허용된다.
- Vault permission이 없거나 certificate serial이 active가 아니면 access decision은 denied다.

## 11. Manager 연동 사양

DB migration:

```sql
alter table integration_configs add column vault_ref varchar(500);
alter table snmp_credentials add column vault_ref varchar(500);
```

Manager config:

- `MANAGER_VAULT_ENABLED`
- `MANAGER_VAULT_BASE_URL`
- `MANAGER_VAULT_ADMIN_SESSION_TOKEN`
- `MANAGER_VAULT_KEYSTORE_PATH`
- `MANAGER_VAULT_KEYSTORE_PASSWORD`
- `MANAGER_VAULT_TRUSTSTORE_PATH`
- `MANAGER_VAULT_TRUSTSTORE_PASSWORD`

`IntegrationService` 동작:

- `vault_ref`가 있으면 `VaultClient.resolveString`을 사용한다.
- 새 secret이 들어오고 Vault client가 enabled이면 Vault secret을 만들고 `vault_ref`를 저장한다.
- Vault client가 disabled이면 기존 local `SecretCrypto` encryption 경로를 유지한다.

`VaultMigrationService` 동작:

- `integration_configs.encrypted_secret`가 있고 `vault_ref`가 비어 있으면 Vault secret으로 migration한다.
- migration 후 `encrypted_secret=null`, `vault_ref='vault:///...'`로 갱신한다.
- `snmp_credentials.encrypted_params`도 Vault secret으로 migration하고 `vault_ref`를 저장한다.

## 12. Security invariants

- `vault_secrets`는 plaintext payload를 저장하지 않는다.
- `vault_secret_versions`는 encrypted blobs만 저장한다.
- list/detail API는 plaintext를 반환하지 않는다.
- reveal/resolve API만 plaintext를 반환할 수 있다.
- reveal/resolve response는 `no-store`다.
- audit event에는 plaintext secret value를 저장하지 않는다.
- session token은 raw value가 아니라 hash만 저장한다.
- enrollment token은 raw value가 아니라 hash만 저장한다.
- disabled/deleted secret은 reveal/resolve할 수 없다.

## 13. Test coverage 사양

CastrelVault:

- `EnvelopeCryptoTest`: 암호화/복호화, nonce randomization, wrong key failure, old key decrypt, config fail-fast
- `CastrelVaultApiIntegrationTest`: SQLite file creation, bootstrap/password change, session hash, lifecycle masking, DB/audit/static leakage scan, reveal policy, application resolve, missing/expired/denied cert rejection, disabled/deleted resolve rejection

CastrelSign:

- `ApplicationPrincipalControllerTest`: application principal, permission grant, one-use token, certificate issue, vault access decision, blocked/missing permission/unknown serial denial

Manager:

- `VaultMigrationServiceTest`: legacy integration/SNMP migration, `vault_ref` storage, encrypted legacy clear, post-migration resolve, new integration secret write to Vault

## 14. 현재 제한과 후속 작업

- CastrelVault compose 기본은 HTTP다. 운영 mTLS는 TLS env 설정 후 배포해야 한다.
- Manager migration runner 호출용 운영 endpoint/CLI는 없다.
- Manager application certificate enrollment 자동화는 없다.
- key rewrap maintenance API는 없다.
- Admin user management UI는 없다.
- log plaintext scan은 테스트가 API/DB/audit/static leakage를 검증하지만, 외부 log aggregation까지 자동 검증하지는 않는다.
