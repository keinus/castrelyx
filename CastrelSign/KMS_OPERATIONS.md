# CastrelSign KMS 운영 가이드

## 범위와 기본 설정

CA·업데이트 서명 키는 `KeyManagementService`를 통해서만 서명하며 키 ID·버전·상태·서명 기한·서비스별 권한을 검사한다. 서버 TLS 개인키는 HTTPS 엔진에서 사용하므로 별도 암호화 PKCS#8·PKCS12 파일로 유지한다. Agent/Application의 개인키는 서버로 받지 않는다.

기본값은 **development**다. 로컬 실행 호환성을 위해 공용 관리자 token과 자동 생성 wrapping key를 허용한다. 이름은 `legacy-admin`이며 단독으로 2인 승인을 완료할 수 없다. 운영은 **production**을 명시해야 한다. 기존 development 저장소의 environment를 바꾸는 방식으로 운영 키를 재사용할 수 없다.

| 환경 변수 | 기본값 | 동작 |
| --- | --- | --- |
| `CASTRELSIGN_KMS_MODE` | `development` | `production`은 외부 wrapping key·관리자 파일·운영 environment·16자 이상 TLS 비밀번호 요구 |
| `CASTRELSIGN_KMS_ENVIRONMENT` | `development` | 최초 생성 후 고정. 예: `production` |
| `CASTRELSIGN_KMS_TENANT` | `castrelyx` | 인스턴스당 한 tenant. 최초 생성 후 고정 |
| `CASTRELSIGN_KMS_MASTER_KEY_FILE` | 개발: `<data-dir>/secrets/kms-master.key` | 32바이트 난수를 Base64로 저장한 파일. 운영은 data-dir 외부의 기존 파일 필요 |
| `CASTRELSIGN_KMS_ADMINISTRATORS_FILE` | 없음 | 아래 관리자 JSON. 운영은 data-dir 외부에 보관 |
| `CASTRELSIGN_KMS_SIGNS_PER_MINUTE` | `600` | 키 ID당 프로세스 내 분당 서명 시도 한도. 초과 시 429 |
| `CASTRELSIGN_KMS_DELETION_GRACE_DAYS` | `7` | 삭제 유예일. 7일 미만 설정 거부 |
| `CASTRELSIGN_KMS_SIGNING_LIFETIME_DAYS` | `365` | 새 키의 서명 사용 기한. 인증서 만료보다 길 수 없음 |
| `CASTRELSIGN_KMS_PKCS11_CONFIG` | 없음 | 선택: SunPKCS11 설정 파일 경로 |
| `CASTRELSIGN_KMS_PKCS11_PIN_FILE` | 없음 | 선택: 토큰 PIN 파일. data-dir 외부 필요 |

wrapping key는 AES-256-GCM 키 저장과 무결성 MAC에 사용한다. key ID·버전·알고리즘·tenant·environment를 AAD로 결합한다. DB와 wrapping key·checkpoint를 함께 잃으면 자동 복구할 수 없다. 운영 계정만 비밀 파일을 읽을 수 있도록 OS ACL을 설정하고, DB 백업과 wrapping key 복구 매체는 별도 보관한다. 소프트웨어 모드에서는 복호화된 키가 JVM 안에 잠시 존재한다. HSM 비반출 보장과 동일하지 않다.

인스턴스마다 독립적인 wrapping key 파일을 사용한다. 이번 교체 API는 CA·업데이트 서명 키를 대상으로 하며 wrapping key의 재래핑·교체 API는 없다. 기존 wrapping key 파일을 새 난수로 덮어쓰면 저장소를 열 수 없다.

## 개인별 관리자와 MFA

아래 JSON의 예시 문자열은 실제 자격 증명이 아니다. token은 충분한 길이의 난수를 만들고 SHA-256의 소문자 64자리 hex만 저장한다. TOTP secret은 관리자별 독립적인 20바이트 이상 난수를 Base64로 저장한다. 인증 앱에는 같은 바이트를 Base32로 등록한다. TOTP 규격은 SHA-1, 6자리, 30초이며 서버 시각 ±1 step을 허용한다.

```json
[
  {
    "id": "key-admin@example.com",
    "tokenSha256": "<64자리 SHA-256 hex>",
    "roles": ["KEY_ADMIN", "AUDITOR"],
    "keys": ["ca-root", "update-signing"],
    "totpSecretBase64": "<개인별 난수 secret Base64>"
  },
  {
    "id": "security-officer@example.com",
    "tokenSha256": "<다른 token의 64자리 SHA-256 hex>",
    "roles": ["SECURITY_OFFICER", "AUDITOR"],
    "keys": ["ca-root", "update-signing"],
    "totpSecretBase64": "<다른 개인의 난수 secret Base64>"
  }
]
```

| 역할 | 권한 |
| --- | --- |
| `KEY_ADMIN` | 허용된 keys의 교체 준비, 긴급 중지·침해 선언, 변경 요청, 삭제 취소 |
| `SECURITY_OFFICER` | 허용된 keys에 대해 다른 사람이 요청한 작업 승인 |
| `AUDITOR` | 허용된 키 인벤토리·공개키·승인 목록 조회. 감사·health는 인스턴스 전체 정보 |
| `OPERATOR` | 기존 Agent·Application·enrollment token·업데이트 업무 API 변경 |
| `VERIFIER` | 인증서 상태 조회, 운영 모드 Application Vault 접근 판단 조회 |

관리자 파일이 설정되어 있으면 공용 `CASTRELSIGN_ADMIN_TOKEN` 인증은 사용하지 않는다. 파일을 요청마다 읽으므로 token·역할 회수는 다음 요청부터 적용된다. 승인 시 요청자의 현재 역할과 키 범위를 다시 확인한다. 파일 변경 권한은 애플리케이션 API보다 강한 OS 관리 권한이다.

관리 API의 GET은 `Authorization: Bearer <개인 token>`, 변경 요청은 여기에 `X-KMS-TOTP: <현재 코드>`를 추가한다. 코드 재사용 방지를 DB에 저장하므로 **한 관리자의 동일 30초 코드로 변경 요청을 반복할 수 없다.** 다음 코드로 진행한다. 관리자당 MFA 시도는 분당 10회로 제한한다. 요청자와 승인자는 반드시 달라야 한다. 토큰이나 TOTP secret을 승인 reason에 적지 않는다.

## 관리 API

모든 경로의 접두사는 `/api/admin/kms`다. 응답은 `Cache-Control: no-store`, 요청 식별자는 `X-Request-ID`다. 개인키 조회·범용 개인키 반출 API는 없다.

| 메서드·경로 | 동작 |
| --- | --- |
| `GET /keys` | 허용된 CA·업데이트 키의 ID·버전·별칭·소유자·purpose·경계·상태·기한·backend·공개 정보 |
| `GET /keys/{keyId}/versions/{version}` | 해당 버전, 서비스 grant, 인증서·릴리스·백업 의존성 |
| `GET /tls` | 서버 TLS 공개 인증서·키 fingerprint·버전·활성/이전 상태·발급 CA 참조 |
| `GET /trust-bundle.pem` | PRE_ACTIVE·ACTIVE·VERIFY_ONLY CA 인증서 묶음 |
| `GET /update-public-keys` | 상태와 버전이 포함된 업데이트 공개키 목록 |
| `GET /update-trust-bundle.pem` | PRE_ACTIVE·ACTIVE·VERIFY_ONLY 업데이트 공개키만 포함한 배포용 PEM 묶음 |
| `POST /keys/{alias}/rotate` | `{"requestId":"rotation-20260924"}`. 동일 관리자·requestId는 동일 준비 버전 반환 |
| `POST /keys/{keyId}/versions/{version}/suspend` | `{"reason":"incident-42","compromised":false}`. true면 침해 상태, 자동 복귀 불가 |
| `POST /keys/{keyId}/versions/{version}/requests` | 승인할 action·reason·payload를 고정하여 요청 ID 발급 |
| `GET /requests` | 승인 요청 조회 |
| `POST /requests/{requestId}/approve` | 다른 SECURITY_OFFICER가 승인하면서 한 번 실행. 요청 유효기간 24시간 |
| `POST /keys/{keyId}/versions/{version}/cancel-deletion` | 삭제 예약 취소. SUSPENDED로 전환하고 서명·신뢰를 자동 재활성화하지 않음 |
| `GET /certificates/status?serial=<hex>` | VERIFIED/UNKNOWN 추정 대신 `active` boolean과 ACTIVE/INACTIVE/UNKNOWN 반환 |
| `GET /audit?after=0&limit=100` | sequence 기준 페이지 조회. 최대 1,000건 |
| `GET /health` | 무결성 검사, 비활성 현재 키 수·30일 내 만료 키 수·감사 이벤트 수 |

승인 action과 payload:

| action | payload | 제약 |
| --- | --- | --- |
| `ACTIVATE` | `{"trustAcknowledgement":"배포 확인 증적 ID"}` | PRE_ACTIVE만 가능. 기존 ACTIVE는 VERIFY_ONLY로 유지 |
| `RESUME` | `{}` | SUSPENDED만 가능. 현재 별칭 버전은 ACTIVE, 이전 버전은 VERIFY_ONLY |
| `GRANT` / `REVOKE` | `{"subject":"service:certificate-issuer","operation":"SIGN"}` | CA는 certificate-issuer/tls, 업데이트는 update-signer만 허용 |
| `SCHEDULE_DELETION` | `{}` | 현재 별칭 버전 삭제 불가. 살아 있는 의존성 없어야 함 |
| `ACK_BACKUP_PURGE` | `{"backupId":"...","evidence":"외부 사본 폐기 증적"}` | 해당 키 버전의 백업 의존성을 해제하는 2인 확인 |
| `DESTROY` | `{"backupPurgeEvidence":"최종 사본 처리 증적"}` | 대기기간 경과·의존성 재검사 후 소프트웨어 암호문 삭제 및 tombstone 유지 |

예: 새 CA 활성화 요청. `{keyId}`와 `{version}`은 rotate 응답 값으로 치환한다.

```http
POST /api/admin/kms/keys/{keyId}/versions/{version}/requests
Authorization: Bearer <requester-token>
X-KMS-TOTP: <current-code>
Content-Type: application/json

{"action":"ACTIVATE","reason":"정기 CA 교체","payload":{"trustAcknowledgement":"change-2026-042"}}
```

## 교체·침해·폐기 절차

1. `rotate`로 준비 버전을 만든다. CA·업데이트 키 모두 자동 활성화하지 않는다.
2. CA는 신구 root 묶음을 Agent·Application·Logparser 등 모든 검증자에 배포한다. 업데이트는 `/update-trust-bundle.pem`의 신뢰 가능한 버전만 `update_public_key_path` 파일에 배포한다. 관리용 전체 목록에는 침해·파기 버전도 있으므로 목록 전체를 그대로 신뢰 파일로 합치면 안 된다. 이번 Agent 변경은 최대 32개 로컬 고정 Ed25519 키를 지원한다. 응답에 들어 있는 임의 공개키를 자동 신뢰하지 않는다.
3. 검증자별 설치·reload 결과를 확인해 증적 ID를 기록하고 ACTIVATE를 요청한다. 다른 관리자가 승인한다. 이 증적 문자열은 실제 배포 확인을 자동 측정하는 기능이 아니다.
4. CA 활성화 후 서버 TLS 자료를 새 CA로 갱신한다. 기존 client 인증서는 이전 root가 VERIFY_ONLY인 동안 계속 검증할 수 있다. 기존 `ca.pem`을 읽어 메모리에 저장한 외부 프로세스는 별도 reload/restart가 필요하다.
5. 업데이트 manifest에는 `signing_key_id`, `signing_key_version`, `signing_algorithm`, `signing_reference`가 들어간다. 구버전 공개키는 과거 릴리스 검증을 위해 유지한다. 기존 단일 키 Agent에는 먼저 공개키 묶음 지원 버전을 배포한다.
6. 침해 시 즉시 `suspend(compromised=true)`로 신규 서명·해당 CA client 신뢰·해당 키의 업데이트 제공을 차단한다. 외부 검증자의 캐시와 이미 내려받은 artifact까지 원격 회수하지는 못하므로 해당 키를 외부 신뢰 파일에서도 제거한다.
7. 인증서 의존성은 만료까지, 릴리스 의존성은 revoke까지 유지한다. 이관된 기존 의존성도 등록한다. 승인된 삭제 예약 후 최소 7일을 기다리고, 새 DESTROY 요청에 다시 2인 승인을 받는다.

승인된 키 변경의 DB 커밋과 TLS 파일 갱신은 별도 단계다. 파일 갱신 실패로 요청이 오류를 반환해도 키 변경은 실행되었을 수 있다. 요청·키 상태를 조회한 뒤 원인을 복구하고 갱신 스케줄러의 재시도를 확인한다.

DESTROY는 온라인 소프트웨어 저장소의 암호문 제거와 논리적 tombstone을 의미한다. SSD·스냅샷·외부 백업의 물리적 삭제를 증명하지 않는다. 백업 사본 처리 증적은 운영자의 확인이며 API가 외부 저장소를 삭제하지 않는다. COMPROMISED 키는 RESUME할 수 없다. HSM 키의 물리적 파기는 이 API에서 거부하며 해당 장비의 검증된 파기 절차가 필요하다.

## 기존 설치 이관

서비스를 중지하고 기존 전체 data-dir와 별도 비밀 설정의 보호 사본을 먼저 확보한다. 기존 파일 키를 유지하며 첫 실행에서 다음을 수행한다.

- CA와 업데이트 키 쌍의 일치·알고리즘을 확인하고 동일 공개키를 version 1로 등록한다.
- TLS 개인키 PEM을 `CASTRELSIGN_KEYSTORE_PASSWORD`로 암호화하고 PEM bundle의 `keystore.private-key-password` 및 `key.password`를 설정한다.
- KMS와 TLS 저장 완료 후 기존 평문 `ca.key`, `update-signing/private.pem` 및 해당 `.bak`을 삭제한다. 파일 삭제가 디스크의 과거 블록까지 제거한다는 의미는 아니다.
- 공개키만 남은 부분 키 쌍, 과거 발급 이력은 있는데 키가 사라진 상태, 초기화된 KMS DB/wrapping key/checkpoint 유실은 자동 재생성하지 않는다.

이관 후 구버전 서버 바이너리로 바로 되돌릴 수 없다. 구버전은 암호화된 키 저장소를 이해하지 못한다. 키를 새로 만들거나 checkpoint를 지워 해결하지 말고 검증된 복구 절차를 사용한다.

## 오프라인 보호 백업·복원

현재 구현은 **단일 writer, 오프라인 전체 스냅샷**이다. 온라인 HA 백업이 아니다. 백업 암호화 키는 wrapping key와 다른 32바이트 난수를 Base64 파일로 보관하고, archive·두 키·최신 checkpoint·관리자 정책·HSM 복구 매체를 서로 구분해 관리한다.

서비스를 종료한 뒤 다음을 실행한다. 명령행에는 비밀값 대신 경로만 전달한다.

```powershell
java -jar .\build\libs\CastrelSign-0.1.0.jar kms-backup create `
  D:\CastrelSignData E:\Backup\castrelsign-20260924.cskb `
  E:\Secrets\backup.key E:\Secrets\kms-master.key castrelyx production
```

DB·키 정책·감사·TLS 인증서·릴리스 artifact 등 data-dir의 파일을 함께 묶고 AES-GCM으로 보호한다. 업무 SQLite DB는 `VACUUM INTO` 스냅샷을 사용한다. `secrets/`와 lock/journal은 제외하며 symlink는 거부한다. 관리자 파일·PIN·TLS 비밀번호·wrapping key는 별도 복구 자료다. HSM 백업은 참조만 포함하고 토큰 개인키는 포함하지 않는다. 현재 도구는 메모리 방식이므로 전체 512 MiB까지 지원한다. 더 큰 설치는 서비스 중지 후 암호화 볼륨 스냅샷 절차를 별도 마련한다.

복원은 **원래 절대 경로의 빈 data-dir**에만 수행한다. 다른 경로로 옮기면 업무 DB의 artifact 경로 등이 달라지므로 지원하지 않는다. 잘못된 백업을 살아 있는 디렉터리에 덮어쓰지 않는다.

```powershell
java -jar .\build\libs\CastrelSign-0.1.0.jar kms-backup restore `
  D:\CastrelSignData E:\Backup\castrelsign-20260924.cskb `
  E:\Secrets\backup.key E:\Secrets\kms-master.key castrelyx production
```

AEAD·파일 hash·tenant/environment·원래 경로·독립적으로 보존한 checkpoint를 검사한다. **최신 checkpoint보다 오래된 스냅샷은 거부한다.** 백업 이후 파기·침해·권한 변경을 과거 상태로 되살리지 않기 위한 제한이다. 과거 스냅샷에 최신 변경을 재적용하는 자동 재해 복구는 아직 없으며, 최신 상태의 별도 복구·대조 절차가 필요하다. checkpoint를 백업 속 과거 값으로 덮어쓰면 이 보호를 무력화한다.

백업 실패 후 의존성이 남는 경우에도 자동으로 해제하지 않는다. 사본 유무를 조사한 뒤 2인 승인으로 정리한다. 복원 중 실패하면 `.restore-in-progress`가 남아 서버 기동을 막는다. 부분 복원된 디렉터리를 운영에 사용하지 않는다. RPO·RTO는 운영 데이터로 별도 복구 훈련해 측정해야 한다.

## PKCS#11 연결

Java SunPKCS11 설정 파일에 토큰 제조사의 library·slot을 지정한다. `ca-root.v1` 및 교체 시 `.v2` 등의 alias로 **개인키와 X.509 인증서 엔트리**를 장비에 먼저 생성해야 한다. CA는 P-256·자체 서명 CA·keyCertSign을 요구한다. 키 쌍 challenge를 검증하고 JCA에서 `getEncoded()`가 반환되는 개인키는 거부한다. 장비 자체의 `CKA_SENSITIVE=true`, `CKA_EXTRACTABLE=false` 정책은 제조사 도구로 확인한다.

현재 PKCS#11 연결 대상은 **CA 키**다. Java 21 SunPKCS11의 문서화된 서명 알고리즘에 Ed25519가 포함되지 않으므로 업데이트 키는 명시적으로 암호화 소프트웨어 backend를 유지한다. 업데이트 키까지 HSM에 두려면 Ed25519를 지원하는 별도 JCA provider 또는 외부 서명 backend를 추가·검증해야 한다. health는 이 구성을 `MIXED_PKCS11_SOFTWARE`로 표시하고 각 키의 backend도 노출한다.

설정이 잘못됐거나 HSM 키가 없으면 소프트웨어 키를 대신 생성하지 않는다. 기존 소프트웨어 키를 HSM으로 옮기는 자동 BYOK는 없다. 이미 관리되는 소프트웨어 키에서 HSM 새 버전으로 신뢰 이전하거나 새 인스턴스를 별도 구성한다. 실제 HSM·PIN 잠금·세션 만료·vendor 장애 조치·물리적 파기는 장비별 통합 검증이 필요하다.

## 감사·상태 전파·운영 한계

감사는 연속 sequence·시간·actor·operation·key/version·tenant/environment·결과를 저장하고 HMAC 체인으로 연결한다. 키 상태·권한·승인·의존성의 MAC과 외부 checkpoint도 검사한다. 개인키·token·TOTP·서명 원문 payload는 감사하지 않는다. 감사 저장 실패 시 서명 응답을 내보내지 않는다. 관리 HTTP 작업은 요청 ID로 시작·결과를 연결하며 키 변경 자체와 감사는 원자적으로 커밋한다. 업무 DB 변경과 관리 HTTP 응답 감사까지 서로 다른 DB의 분산 트랜잭션으로 묶지는 않는다.

checkpoint를 DB 커밋보다 먼저 내구성 있게 기록한다. 두 단계 사이의 장애로 checkpoint가 DB보다 앞서면 저장소 사용을 거부하고 운영자 복구를 요구한다. 이 경우 checkpoint를 과거 값으로 낮춰 자동 재개하지 않는다. 가용성보다 파기·회수 상태의 과거 복원 방지를 우선한 동작이다.

`/audit`를 sequence 단위로 수집하여 외부 보관소에 보존한다. 내장 자동 삭제·WORM·SIEM 전송은 없다. 로그에 `KMS_DEGRADED`, `KMS_ROTATION_DUE`, `KMS_INTEGRITY_OR_STORAGE_FAILURE` 경보를 남기며 기본 60초마다 검사한다. health의 503, 비활성 현재 키, 만료 임박 키, 감사 수집 지연을 운영 모니터에서 경보로 연결한다.

인증서 상태 API와 운영 모드 `/api/applications/{id}/vault-access`는 VERIFIER 인증이 필요하다. 결과 캐시는 최대 60초이며 인증서 만료보다 오래 사용하지 않아야 한다. 외부 검증자는 조회 실패 시 접근을 허용하지 않는 정책을 적용해야 한다. 기존 Vault 호출자가 인증 헤더를 보내지 않는다면 운영 모드 전환 전에 해당 연동을 수정해야 한다. CRL/OCSP는 구현하지 않았다.

파일 잠금은 동일 data-dir의 두 번째 writer를 거부한다. 네트워크 공유 파일시스템·다중 노드 HA는 지원하지 않는다. 정책 MAC은 매 연산에 관리 테이블을 검사하므로 키·의존성 증가에 따른 지연을 운영 규모에서 측정해야 한다. 다중 프로세스 분산 rate limit·자동 장애 조치·고부하 SLO 보장은 이번 구현 범위가 아니다.

설정 근거: [Spring Boot SSL bundles](https://docs.spring.io/spring-boot/3.5/reference/features/ssl.html), [Java 21 PKCS#11 가이드](https://docs.oracle.com/en/java/javase/21/security/pkcs11-reference-guide1.html).
