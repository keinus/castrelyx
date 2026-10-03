# CastrelSign KMS 구현 완료 보고서

## 요약

**`dev_report.md`의 핵심 미구현 항목을 실제 인증서 발급·업데이트 서명 경로에 연결했다.** CA·업데이트 키를 중앙 관리하고, 암호화 저장·버전 교체·권한·상태·2인 승인·감사·안전한 삭제·오프라인 백업/복원을 추가했다. 기존 Agent·Application·업데이트 API도 새 키 정책을 적용받는다.

| 구분 | 결과 |
| --- | --- |
| 구현 완료 | 키 ID·버전·별칭·용도·소유자·tenant/environment·서명 기한, 서비스별 SIGN 권한, 개인별 관리자 역할·MFA, 2인 승인, CA/업데이트 교체, 긴급 중지·침해 상태, 삭제 예약·취소·tombstone, 암호화 백업·정합성/롤백 검사, 구조화 감사·health·로그 경보 |
| 기존 기능 개선 | CSR을 P-256으로 제한, TLS PEM 암호화, Windows 소유자 ACL 적용, 기존 키·의존성 이관, 키 유실 시 임의 재생성 거부, Application 차단 시 인증서·token 폐기, 등록·인증서 상태 변경 트랜잭션 적용 |
| 연동 보완 | 업데이트 manifest에 서명 키 참조 추가, Agent가 로컬 PEM 공개키 묶음으로 신구 서명 검증, 인증된 인증서 상태 조회와 최대 60초 캐시 계약 |
| 추가한 문서 | 이 보고서와 [KMS_OPERATIONS.md](KMS_OPERATIONS.md). README의 오래된 revocation 설명과 저장 구조·운영 안내 수정 |
| 별도 확인 필요 | 실제 PKCS#11 장비의 생성·비반출 속성·장애·복구·파기, 외부 검증자의 신뢰 배포, 대규모 부하·HA·외부 감사 보관 |
| 남은 개발 | HA·복제, 과거 백업에 최신 상태 재적용, 업데이트 Ed25519 키의 HSM backend, wrapping key 재래핑·교체, 자동 신뢰 배포·CRL/OCSP·외부 감사 보관 연동 |

완료의 범위는 **저장소 코드와 로컬 검증**이다. 운영 서버에 배포하거나 사용자의 실제 키를 이관하지 않았다. PKCS#11 연결 코드는 추가했지만 실제 장비 검증 완료로 표시하지 않는다. 일반 암복호화·DEK/KEK·BYOK·KMIP는 기준 문서의 조건부 확장이므로 이번 CA·서명 서비스 범위에 추가하지 않았다.

## 1. 요구사항별 반영 결과

| 기준 문서 영역 | 이번 변경 | 판정·남은 경계 |
| --- | --- | --- |
| §3 인벤토리 | CA·업데이트의 불변 UUID, 버전, 별칭, purpose, owner, tenant, environment, backend, 상태·기한·공개키. TLS 공개키 fingerprint·버전·발급 CA 이력 | 구현. owner는 현재 플랫폼 보안 담당 역할이며 다중 tenant 공유 서비스는 아님 |
| §4 수명주기 | PRE_ACTIVE, ACTIVE, VERIFY_ONLY, SUSPENDED, COMPROMISED, PENDING_DELETION, DESTROYED와 전이 검사 | 구현. TLS는 별도 ACTIVE/RETIRED 갱신 이력 |
| §5.1 생성·등록 | JCA EC P-256/Ed25519, 기존 키 쌍 검증·동일키 이관, 회전 요청 멱등성, 경계 값 변경 거부 | 구현. 선택 가능한 임의 알고리즘 정책 편집은 없음 |
| §5.2 보호 | AES-256-GCM 키 암호문과 AAD, 외부 wrapping key, TLS 암호화 PKCS#8·PKCS12, 생성 파일 접근 제한 | 소프트웨어 보호 구현. JVM 내부 비반출을 보장하지 않음 |
| §5.3 인증·인가 | 개인별 token hash, KEY_ADMIN/SECURITY_OFFICER/AUDITOR/OPERATOR/VERIFIER, 키 범위, TOTP·재사용 방지·시도 한도, 2인 승인 | 구현. IdP/SSO 연동·비상 계정 발급 자동화는 없음 |
| §5.4 서명 경계 | SigningBackend, key ID/version 참조, 상태·기한·서비스 grant 확인, 실제 CA·업데이트 호출 경로 연결 | 구현. 범용 외부 raw sign API는 제공하지 않음 |
| §5.5 DEK/KEK | 추가하지 않음 | 조건부 후속 범위 |
| §5.6 공개 배포 | CA 신뢰 묶음, 업데이트 버전별 공개키, Agent 고정 공개키 묶음 검증 | 구현. 자동 배포·검증자별 수신 확인은 운영 절차 필요 |
| §5.7 교체 | 준비→신뢰 배포 증적→다른 관리자 승인→별칭 전환, 구키 검증 유지, TLS 재발급, 서명 사용 기한 | 구현. CA·업데이트 자동 일정 교체는 없음 |
| §5.8 침해·폐지 | 중지/침해 시 서명·CA 신뢰·업데이트 제공 거부. Application 차단으로 기존 인증서/token 회수. 인증된 상태 API | 서버 적용 구현. CRL/OCSP·모든 외부 검증자에 대한 강제 회수는 없음 |
| §5.9 파기 | 의존성 검사, 최소 7일 삭제 예약, 취소 시 중지 상태 유지, 새 승인으로 암호문 제거·공개 tombstone 보존 | 소프트웨어 논리 파기 구현. 물리 매체·외부 사본·HSM 파기는 별도 증적/장비 절차 필요 |
| §5.10 복구 | 오프라인 암호화 전체 스냅샷, 업무 DB 스냅샷, 파일 hash, AEAD, 경계·경로·최신 checkpoint 검사, 불완전 복원 기동 차단 | 구현. 최신 checkpoint와 같은 스냅샷만 자동 복원. 과거 스냅샷 상태 재조정·HA 복구는 후속 |
| §5.11 감사 | 키 연산 성공/거부/가능한 오류, 관리 요청·결과·승인자 추적, HMAC 체인·상태 MAC, 감사 실패 시 서명 반환 차단 | 구현. WORM·SIEM·자동 보존기간/아카이브는 외부 연동 필요 |
| §5.12 운영 | 단일 writer 잠금, 키당 서명 시도 제한, MFA 시도 제한, health·만료/무결성 로그 경보, 동시 서명 검사 | 부분 구현. 클러스터 HA·분산 quota·운영 SLO·대규모 부하 검증은 미완료 |
| §5.13 표준 연동 | JCA 서명 경계, CA용 PKCS#11 토큰 alias와 기존 인증서 엔트리로 실제 sign 호출 경로 | 연결 코드 구현. 실제 HSM 성공·장애 시나리오는 미검증. Java 21의 Ed25519 업데이트 키는 소프트웨어 backend 유지, KMIP는 제외 |
| §5.14 운영 문서 | 설정·관리자 역할·API·이관·교체·복원·파기·장애 대응 문서, 기존 README 정정 | 구현. 실제 조직의 담당자 지정·복구 훈련은 운영 단계 필요 |

## 2. 실행 경로에서 달라진 점

### 인증서 발급

`CertificateAuthority`가 개인 CA 키를 직접 소유하던 애플리케이션 경로를 KMS 참조로 변경했다. 발급할 root 버전을 읽고, 해당 root로 인증서 builder를 구성한 뒤 KMS ContentSigner에 서명을 요청한다. 교체가 경합하여 이전 키가 더 이상 ACTIVE가 아니면 이전 키로 서명하지 않고 실패한다.

KMS는 권한·상태·서명 기한을 확인하고 인증서 serial·만료 의존성을 감사와 함께 저장한 후 서명을 반환한다. CA 교체 후에도 이전 root가 VERIFY_ONLY이면 기존 client 인증서를 검증할 수 있다. COMPROMISED/SUSPENDED root는 검증에서 제외한다. 등록 token 소비와 인증서 업무 저장은 Spring 트랜잭션으로 묶어 서명 거부 시 token만 소진되는 상황을 줄였다.

서버 TLS 키는 HTTPS 엔진이 사용하는 암호화 PEM으로 유지하며 Spring bundle에 복호화 비밀번호를 전달한다. 기존 정적 CA 테스트용 저수준 메서드는 남아 있지만 애플리케이션 bootstrap·bean·갱신 스케줄러는 모두 managed 경로를 사용한다.

### 업데이트 서명·검증

`UpdateSigner`의 평문 파일 생성/읽기를 제거했다. manifest 생성 시 key ID·version·algorithm·signing reference를 포함하고 해당 버전으로 바이트 원문을 서명한다. 키가 중지되거나 침해되면 새 릴리스 서명과 기존 릴리스 제공을 거부한다. revoke한 릴리스는 다시 활성화하지 않으며 업데이트 정책이 disabled이면 artifact 직접 요청도 거부한다.

회전 중 양쪽 버전을 검증할 수 있도록 `agent/internal/updater`도 변경했다. 운영자가 배포한 로컬 파일의 Ed25519 PEM 공개키를 최대 32개까지 읽는다. 신뢰 파일에 없는 키·제거된 키·잘못된 bundle은 거부한다. 네트워크 응답에서 새 신뢰 키를 자동 획득하지 않는다.

### 승인·감사·삭제

관리자 token은 개인별 SHA-256 hash로 확인한다. 변경 요청에는 TOTP를 요구하고 마지막 사용 step을 DB에 저장해 재시작 후에도 재사용을 거부한다. 요청/승인자는 다르게 식별되어야 하며 승인 시 현재 역할도 재검사한다. 승인 요청의 action·대상 버전·payload는 생성 후 고정되고 24시간 후 만료된다.

삭제 예약과 실행은 각각 승인된다. 현재 별칭이 가리키는 키, 아직 만료되지 않은 인증서, 미폐기 릴리스, 남아 있는 백업 의존성이 있으면 삭제를 거부한다. 취소는 SUSPENDED로만 복귀한다. COMPROMISED 키를 정상 상태로 직접 되돌리는 경로는 없다.

감사와 키 관리 상태는 같은 SQLite 트랜잭션으로 커밋한다. HMAC 체인과 관리 테이블의 MAC을 검증하고, wrapping key 옆의 checkpoint로 DB 과거 복원을 탐지한다. DB와 외부 checkpoint까지 함께 되돌릴 권한을 가진 공격자를 이 소프트웨어만으로 막을 수는 없다. 해당 파일은 별도 운영 통제로 보호해야 한다.

### 복구

CLI는 실행 중인 서버의 writer lock과 충돌하면 백업을 거부한다. 키·정책·감사·인증서·업무 DB·artifact를 하나의 암호화 묶음으로 만든다. 복원은 같은 절대 경로의 빈 디렉터리에서만 허용하고 최신 외부 checkpoint가 백업과 정확히 일치해야 한다. 이는 침해·파기·권한 회수를 되살리는 오래된 복원을 자동으로 허용하지 않기 위한 선택이다. 과거 스냅샷에 최신 상태를 재적용하는 기능은 구현하지 않았다.

## 3. 주요 변경 파일

| 파일 | 역할 |
| --- | --- |
| [KeyManagementService.java](src/main/java/org/castrelyx/castrelsign/kms/KeyManagementService.java) | 인벤토리·정책·서명·수명주기·승인·감사·무결성·복구 checkpoint |
| [SigningBackend.java](src/main/java/org/castrelyx/castrelsign/kms/SigningBackend.java), [EncryptedSoftwareBackend.java](src/main/java/org/castrelyx/castrelsign/kms/EncryptedSoftwareBackend.java), [Pkcs11SigningBackend.java](src/main/java/org/castrelyx/castrelsign/kms/Pkcs11SigningBackend.java) | 보호 backend와 서명 경계 |
| [KmsAuthentication.java](src/main/java/org/castrelyx/castrelsign/kms/KmsAuthentication.java), [AdminSecurityFilter.java](src/main/java/org/castrelyx/castrelsign/kms/AdminSecurityFilter.java) | 관리자 신원·MFA·역할 및 HTTP 감사 |
| [KmsController.java](src/main/java/org/castrelyx/castrelsign/kms/KmsController.java) | 키·승인·공개 신뢰·상태·감사·health API |
| [KmsBackupTool.java](src/main/java/org/castrelyx/castrelsign/kms/KmsBackupTool.java) | 오프라인 보호 스냅샷과 검증 복원 |
| [KmsMonitoringService.java](src/main/java/org/castrelyx/castrelsign/kms/KmsMonitoringService.java), [ProtectedFiles.java](src/main/java/org/castrelyx/castrelsign/kms/ProtectedFiles.java) | 운영 경보, 소유자 파일 접근 제한·원자 기록 |
| [CertificateAuthority.java](src/main/java/org/castrelyx/castrelsign/crypto/CertificateAuthority.java) | CA 서명 연동·키 이관·TLS 암호화·다중 root 검증 |
| [UpdateSigner.java](src/main/java/org/castrelyx/castrelsign/update/UpdateSigner.java), [AgentUpdateService.java](src/main/java/org/castrelyx/castrelsign/update/AgentUpdateService.java) | 버전 지정 서명·manifest·의존성·침해 키 배포 차단 |
| [Agent updater](../agent/internal/updater/updater.go) | 로컬 고정 공개키 묶음 검증 |

설정·bootstrap·관리자 인증·Agent/Application 인증 경로와 테스트도 함께 변경했다. 다른 프로젝트의 기존 작업 변경사항은 수정하지 않았다.

## 4. 검증 결과

검증일: **2026-09-24**, Windows·Java 21·Gradle wrapper 환경.

| 검증 | 결과 |
| --- | --- |
| `CastrelSign`: `.\gradlew.bat test bootJar --no-daemon` | **12개 클래스, 62개 테스트 통과**, 실패·오류·건너뜀 0. 기존 46개에 16개 추가 |
| JAR 패키징 | `build/libs/CastrelSign-0.1.0.jar` 생성 성공 |
| `agent`: `go test ./internal/updater` | 공개키 묶음 교체·제거·비신뢰 키·손상 bundle 검증 포함 통과 |
| `agent`: `go test ./...` | **12개 패키지 통과**. 로컬 PATH에 Go가 없어 공식 SHA-256을 확인한 임시 Go 1.24.13 도구로 실행 |
| 변경 파일 점검 | `git diff --check` 통과, 추가/수정 문서의 로컬 링크 확인 |

검증 중 암호화 PEM 복호화 비밀번호 속성 누락을 발견하여 `keystore.private-key-password`를 연결했고 Spring Boot SSL 컨텍스트 생성·개인키 로딩 검증을 통과했다. quota 테스트는 분 경계와 무관하게 검증하도록 고정 시계를 사용한다. 백그라운드 JAR 기동 명령은 자동 승인 검토에서 구체적 사유 없이 차단되어 실행하지 않았다. 대신 위 SSL bundle 테스트와 기존 실제 HTTPS 통합 테스트로 검증했으며, 두 검증을 하나의 전체 배포 테스트로 표현하지 않는다.

새 검증은 실제 서명 검증, 권한 거부, 두 사람 승인, 승인 재사용·만료, 삭제 의존성·7일 유예·취소, 서명 기한, 저장소 변조·wrapping key 불일치·유실·rollback, 부분 키 쌍 재생성 거부, 동시 서명·quota, 백업 복원·손상·오래된 복원 거부, TOTP 표준 벡터·재시작 후 replay 거부, 키 침해 시 실제 업데이트 제공 차단, CA 교체 후 신구 client 인증서 검증, 암호화 TLS PEM 로딩을 다룬다.

실제 HSM, 외부 Vault/Logparser 전체 연동, Linux ACL·파일시스템 정전 복구, 장시간 부하·HA·운영 RPO/RTO는 검증하지 않았다. 테스트의 성공을 이 범위까지 확장해 해석하지 않는다.

## 5. 운영 적용 전 남은 작업

1. 개인별 관리자 token·MFA, 외부 wrapping key와 최신 checkpoint의 보관/복구 책임을 정하고 production 설정으로 검증한다. 기존 development 키 경계를 이름만 바꿔 사용하지 않는다.
2. 기존 설치는 보호 백업을 확보하고 동일키 이관을 확인한다. 외부 TLS 비밀번호·관리자 파일·PIN·HSM 백업은 별도 관리한다.
3. 모든 Agent/Application/Logparser 검증자에 신뢰 묶음을 배포하고 실제 reload 증적을 확보한다. 운영 모드 Vault status 호출에는 VERIFIER 자격 증명이 필요하다.
4. PKCS#11 CA 사용 시 제조사 장비의 P-256 지원, 비반출 속성, alias provisioning, 세션 장애·복원·물리 파기를 통합 검증한다. 업데이트 키의 HSM 보호는 별도 Ed25519 backend 개발이 필요하다.
5. SIEM/WORM·보존기간·외부 경보, 운영 규모 부하 측정, 과거 스냅샷의 최신 상태 재조정, HA 복제를 후속으로 구성/개발한다.
