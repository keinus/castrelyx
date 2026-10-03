# CastrelSign KMS 기능 구현 현황

> 이 문서는 2026-09-24 **구현 전 기준선**이다. 이후 구현·검증 결과는 [implementation_report.md](implementation_report.md), 현재 운영 절차는 [KMS_OPERATIONS.md](KMS_OPERATIONS.md)를 참조한다. 아래의 미구현 판정은 비교 이력을 위해 유지한다.

## 요약

**현재 CastrelSign은 인증서 발급·인증과 업데이트 서명 기능을 갖춘 파일 기반 서비스다. `report.md`에서 제안한 KMS로 확장하려면 키 인벤토리·버전·권한·수명주기와 개인키 보호 체계를 추가해야 한다.**

| 구분 | 현재 상태 |
| --- | --- |
| **구현됨** | CA·서버 TLS 키 생성, CSR 검증과 인증서 발급, Agent 인증서 갱신, enrollment token 관리, mTLS 기반 Agent 신원 확인, Agent 차단·재등록, Application 등록·Vault 접근 판단, Ed25519 업데이트 서명·공개키 조회 |
| **일부 구현됨** | 서버 TLS 키·인증서 자동 갱신, 인증서 상태 관리, 일부 파일 접근 제한, 파일 교체 실패 복구, 업무 감사 이벤트. 각각의 기능은 있으나 모든 키를 관리하는 KMS 수준으로 확장되지는 않았다. |
| **미구현인 핵심 기능** | CA·업데이트 키의 비반출 보호, 키 ID·버전·소유자 관리, 키별·연산별 권한, 관리자 역할 분리·MFA·다자 승인, CA·업데이트 키 교체와 신뢰 이전, 키 비활성화·침해 상태, 삭제 예약·최종 파기, 전체 키 백업·재해 복구, 전면적인 키 사용 감사 |
| **조건부 확장 기능** | DEK/KEK·봉투 암호화, BYOK, 범용 암복호화·MAC API, KMIP 연동. 현재 CA·서명 서비스에 모두 필수인 것은 아니며 실제 사용 사례가 생길 때 범위를 결정한다. |
| **우선 개발 방향** | ① 키 목록·상태 모델과 서명 연동 계층 → ② 개인키 보호·키별 권한 → ③ CA·업데이트 키 교체 및 복구 → ④ 감사·안전한 파기·운영 자동화 |
| **기존 테스트 확인** | 2026-09-24에 9개 테스트 클래스의 **46개 테스트를 새로 실행해 모두 통과**했다. 미구현 KMS 기능이나 운영 인프라까지 검증한 결과는 아니다. |

**구분해서 읽어야 할 세 가지:** 서버 TLS 키의 자동 갱신은 이미 구현되어 있지만 CA·업데이트 서명 키의 교체는 없다. Agent·인증서·릴리스의 상태는 관리하지만 서명 키 자체의 상태는 관리하지 않는다. `.bak` 파일 복구는 있지만 전체 키 저장소를 복원하는 재해 복구 체계는 없다.

README에는 Agent blocklist와 이전 인증서 사용 중단이 미구현이라고 적힌 부분이 있으나, 현재 소스에는 관련 기능이 있다. 이 문서는 **실제 코드와 테스트를 우선하여 판정**했다. 자세한 차이는 7절에 정리했다.

## 1. 점검 기준과 범위

- 점검일: **2026-09-24**
- 기준 문서: [report.md](report.md)의 3절·4절 및 5.1~5.14 기능 요구사항
- 기준 코드: 작업 트리의 `CastrelSign` 소스·설정·스키마·테스트. 점검 시 저장소 HEAD는 `d6d0f758cffa28b10393b9e96d9c353c1ddb5753`이다.
- 확인 방법: 주요 실행 경로 직접 읽기, 전체 Java 소스 43개 파일에 대한 API·키 관리 기능 검색, 테스트 클래스 9개 확인
- 경계: CastrelSign이 직접 제공하는 기능을 평가한다. `manager`, `CastrelVault`, `agent`, `logparser`의 별도 구현을 CastrelSign의 KMS 기능으로 합산하지 않는다.
- 한계: 운영 서버의 ACL, 디스크 암호화, 외부 백업·SIEM·접근제어 구성은 확인하지 않았다. 코드에 없는 외부 운영 통제의 존재 여부는 **미확인**이다.

| 판정 | 적용 기준 |
| --- | --- |
| 구현됨 | 명시한 좁은 범위의 기능을 수행하는 실행 코드가 있다. 해당 기능이 모든 KMS 요구사항을 충족한다는 뜻은 아니다. |
| 부분 구현 | 관련 동작은 있으나 관리 대상·정책·보호 수준·복구·감사 등 요구사항 일부가 빠져 있다. |
| 미구현 | 확인한 API, 서비스, 스키마, 설정에 해당 기능을 수행하는 구현이 없다. |
| 미구현·조건부 | 미구현이지만 `report.md`에서 사용 사례에 따라 선택하도록 한 확장 기능이다. |

아래 2절은 실제로 있는 **개별 기능**, 3절은 여러 기능을 묶은 **요구사항 영역 전체**를 평가한다. 따라서 개별 기능이 구현됨이어도 상위 영역은 부분 구현일 수 있다. 기능의 크기가 달라 단순 구현률은 산출하지 않았다.

## 2. 현재 구현된 기능

### 2.1 키 생성과 인증서 발급

| 기능 | 판정 | 확인한 구현과 범위 | 코드 근거 |
| --- | --- | --- | --- |
| 자체 Root CA 생성·재사용 | 구현됨 | CA 파일이 없으면 EC P-256 키와 self-signed 인증서를 생성한다. 기존 CA가 있으면 유효성·CA 속성·서명 권한·키 쌍 일치를 확인한다. | [CertificateAuthority.initializeLocked / validateRootMaterial][CA] |
| 서버 TLS 키·인증서 생성 | 구현됨 | CA와 별도의 EC P-256 서버 키를 만들고 serverAuth·SAN을 포함한 인증서를 발급한다. | [CertificateAuthority.signedServerCertificate][CA] |
| CA·서버 키 쌍 일치 검증 | 구현됨 | P-256 곡선 매개변수를 확인하고 challenge 서명·검증으로 개인키와 공개키의 대응을 확인한다. | [CertificateAuthority.verifyMatchingEcKey][CA] |
| CSR 검증 | 구현됨 | CSR 서명, CN과 요청 ID 일치, EC 공개키 여부를 검사한다. CSR의 허용 곡선·알고리즘을 관리 정책으로 선택하는 기능까지 있는 것은 아니다. | [CsrService.parseAndValidate][CSR] |
| Agent 인증서 발급 | 구현됨 | enrollment token을 확인한 뒤 CSR의 공개키로 clientAuth 인증서를 발급·저장한다. 응답은 인증서와 CA 인증서이며 Agent 개인키 생성·수집 API가 아니다. | [AgentController.enroll][AGENTAPI], [AgentCertificateService.issue][ISSUER] |
| Application 인증서 발급 | 구현됨 | 등록된 Application principal의 상태, CSR, 1회용 token을 확인해 클라이언트 인증서를 발급한다. | [ApplicationController.enroll][APPAPI] |
| 인증서 수명 제한 | 구현됨 | 인증서 유효 기간을 설정하고 신규 leaf의 만료일이 보존된 Root CA의 만료일을 넘는 발급을 거부한다. | [CertificateAuthority.ensureRootCovers][CA], [application.yml][YAML] |

### 2.2 인증·등록·접근 통제

| 기능 | 판정 | 확인한 구현과 범위 | 코드 근거 |
| --- | --- | --- | --- |
| 관리자 Bearer token 확인 | 구현됨 | 설정된 하나의 admin token과 요청 값을 비교한다. 관리자별 신원·역할·키 권한 모델은 아니다. | [AdminTokenService.requireValid][ADMINAUTH] |
| Agent enrollment token 관리 | 구현됨 | 안전한 난수로 생성하고 SHA-256 해시만 저장한다. TTL·사용 횟수·Agent 제한·폐기를 검사하며 생성 응답에만 원문을 넣는다. | [EnrollmentTokenService][TOKEN], [AdminEnrollmentTokenController][TOKENAPI] |
| token 사용 횟수 경쟁 방지 | 구현됨 | DB 조건부 갱신으로 만료·폐기·사용 한도를 재확인하면서 사용 횟수를 증가시킨다. | [EnrollmentTokenRepository.markUsed][TOKENREPO] |
| Agent mTLS 신원 확인 | 구현됨 | TLS에서 받은 인증서를 대상으로 유효 기간·Root CA 서명·clientAuth·비CA 여부·CN을 확인한다. DB의 Agent 차단 상태와 활성 인증서 serial도 확인한다. | [ClientCertificateService.requireClientIdentity][CLIENTAUTH] |
| Agent 인증서 갱신 | 구현됨 | 기존 활성 인증서의 CN과 요청 ID가 같을 때 새 CSR로 발급한다. 새 인증서를 저장하면서 기존 ACTIVE 인증서를 SUPERSEDED로 바꾼다. | [AgentController.renew][AGENTAPI], [AgentRepository.saveCertificate][AGENTREPO] |
| Agent 차단·재등록 | 구현됨 | BLOCKED 처리, 기존 ACTIVE 인증서의 REVOKED 처리, 해당 Agent의 사용 가능 token 폐기를 수행한다. 재활성화는 PENDING으로 바꾸고 새 등록을 요구한다. | [AgentRepository.blockAgent / reactivateAgent][AGENTREPO] |
| Application 권한·상태 확인 | 구현됨 | principal별 권한 문자열과 상태, 인증서 serial·ACTIVE·만료 여부로 Vault 접근 판단을 반환한다. | [ApplicationController.vaultAccess][APPAPI], [ApplicationPrincipalRepository][APPREPO] |

이 기능들은 **등록·인증서·업무 접근 통제**다. 예를 들어 `vault:resolve` 권한이 존재한다는 사실을 `key_id`별 CA 서명·키 삭제 권한이 구현되었다는 근거로 볼 수는 없다.

### 2.3 서명·공개키 배포·파일 유지 관리

| 기능 | 판정 | 확인한 구현과 범위 | 코드 근거 |
| --- | --- | --- | --- |
| 업데이트 Ed25519 서명 | 구현됨 | CA와 별도의 키 쌍으로 manifest JSON 바이트를 서명한다. manifest에는 배포 버전·OS·아키텍처·채널·파일 SHA-256·크기·생성 시각이 들어간다. | [UpdateSigner.sign][SIGNER], [AgentUpdateService.createRelease][UPDATE] |
| 업데이트 공개키 조회 | 구현됨 | 관리자 API가 PEM 공개키를 반환한다. 키 버전별 조회·다중 공개키 배포는 없다. | [AdminAgentUpdateController.publicKey][UPDATEADMIN] |
| 릴리스 활성화·사용 중단 | 구현됨 | DRAFT·ACTIVE·REVOKED 상태를 저장하고 업데이트 조회·artifact 제공 시 상태를 확인한다. | [AgentUpdateRepository][UPDATEREPO], [AgentUpdateService][UPDATE] |
| Root CA 공개 인증서 조회 | 구현됨 | 관리자 API 및 발급 응답으로 공개 CA 인증서를 제공한다. | [AdminAgentController.rootCa][ADMINAPI], [AgentCertificateService][ISSUER] |
| 서버 TLS 키·인증서 자동 갱신 | 구현됨 | 주기적으로 상태를 검사하고 만료 임박·부정합 시 새 서버 키와 인증서를 만든다. Root CA는 유지한다. 기본 검사 간격은 1시간이다. | [ServerCertificateRenewalService][RENEW], [CertificateAuthority][CA] |
| PEM bundle 갱신 반영 설정 | 구현됨 | 기본 생성 TLS identity에 `reload-on-update=true`를 설정한다. 외부 TLS identity를 지정한 구성은 별도 경로다. | [BootstrapEnvironment.configureServerIdentity][BOOT] |
| PKCS12 생성·재생성 | 구현됨 | 비밀번호로 저장하는 서버 keystore와 truststore를 만들고, 누락·부정합 시 갱신한다. | [CertificateAuthority.writeKeyStoresLocked / keyStoresMatch][CA] |
| 일부 파일 쓰기 보호·복구 | 구현됨 | CA/TLS 경로에서 파일 잠금, 임시 파일, 원자 이동 시도, transaction marker, `.bak` 복구를 사용한다. OS·파일시스템별 제한이 있고 업데이트 키에는 같은 경로가 적용되지 않는다. | [CertificateAuthority.replaceMaterialPair / recoverInterruptedPair][CA] |
| 업무 감사 저장·조회 | 구현됨 | Agent 등록·갱신·차단·재활성화, ingest 수신, 업데이트 상태 보고 등의 이벤트를 SQLite에 저장하고 조회한다. | [AgentRepository.audit][AGENTREPO], [AdminAgentController.auditEvents][ADMINAPI] |

## 3. `report.md` 요구사항과의 전체 대조표

각 행의 번호는 `report.md`의 절 번호다. 현재 구현을 확인한 소스는 2절과 4절, 파일 링크는 8절을 참조한다.

| 기준 | 요구사항 영역 | 판정 | 현재 있는 기능 | 아직 없는 기능 |
| --- | --- | --- | --- | --- |
| §3 | 키 종류·메타데이터·인벤토리 | **부분 구현** | 용도별 별도 키 파일, 발급 인증서·principal·릴리스 정보 | 키 자체의 ID·버전·별칭·소유자·허용 연산·보존 기간·의존성 모델 |
| §4 | 키 수명주기·상태 | **미구현** | 인증서·Agent·릴리스 상태는 있으나 관리 대상이 다름 | CA·TLS·업데이트 키의 Pre-activation·Active·Suspended·Compromised·Destroyed 및 상태별 연산 검사 |
| §5.1 | 안전한 생성·등록 | **부분 구현** | EC·Ed25519 키 생성, CA/TLS 키 쌍 검증, 일부 파일 부정합 처리 | 정책 기반 키 등록, 불변 키 ID, 생성 요청 멱등성, 운영·테스트 키 사용 차단 정책 |
| §5.2 | 저장·사용 중 보호 | **부분 구현** | 일부 POSIX 권한 제한, PKCS12, HTTPS, 클라이언트 개인키를 받지 않는 CSR 흐름 | CA·업데이트 키의 HSM/KMS 비반출 보호, 암호화된 PEM 저장, 메모리·진단 파일의 비밀값 통제 |
| §5.3 | 인증·인가·역할 분리 | **부분 구현** | admin token, Agent mTLS, 제한된 enrollment token, Application 업무 권한 | 키·연산·테넌트별 ACL, 관리자 개인 식별·RBAC·MFA, 다자 승인·비상 접근 절차 |
| §5.4 | 키를 노출하지 않는 암호 연산 | **부분 구현** | 인증서·업데이트 서명과 공개키 제공 | 키 참조 기반 서명 경계, 공통 `SigningService`, 키 버전·상태·정책 검사. 범용 암복호화는 조건부 확장 |
| §5.5 | DEK/KEK·봉투 암호화 | **미구현·조건부** | 없음 | 데이터 키 생성·래핑·복원·재래핑, AAD·nonce 관리 |
| §5.6 | 안전한 반입·반출·배포 | **부분 구현** | CA·업데이트 공개키 제공, 외부 TLS 설정 수용, CSR 발급 | BYOK·래핑된 키 반입, 출처·반출 정책, 버전별 공개키 전파·수신 확인 |
| §5.7 | 교체·버전·사용 기간 | **부분 구현** | 서버 TLS 키·인증서 교체, Agent 인증서 재발급, 인증서 유효 기간 | CA·업데이트 키 버전 교체, 다중 신뢰 키 유지, 사용량 기반 정책·전환 절차 |
| §5.8 | 비활성화·폐지·침해 대응 | **부분 구현** | Agent 차단, 인증서 내부 상태, token 폐기, 릴리스 사용 중단, Application 차단 | 서명 키 자체의 긴급 비활성화·침해 기록, CRL·OCSP, 검증자 전체로의 차단 전파·신뢰 복구 |
| §5.9 | 삭제·최종 파기 | **미구현** | 임시 파일 삭제는 있으나 키 파기 기능이 아님 | 키 삭제 예약·취소·승인·의존성 점검·사본 처리·파기 증적 |
| §5.10 | 백업·복원·재해 복구 | **부분 구현** | CA/TLS 파일 갱신 중단 복구, PKCS12 재생성 | CA·업데이트 키·DB·정책의 일관된 보호 백업, 외부 보관, 복구 API·훈련, RTO/RPO |
| §5.11 | 감사·책임 추적·탐지 | **부분 구현** | 일부 업무 이벤트 저장·조회, 서버 TLS 갱신 일반 로그 | 전 키 연산·관리 작업 감사, 키 버전·호출자·승인자 기록, 변조 방지·보존 정책·경보 |
| §5.12 | 가용성·성능·모니터링 | **부분 구현** | 갱신 스케줄러, 오류 로그, 다음 주기 재검사, 일부 파일 잠금 | 키 서비스 HA·복제·상태 동기화, SLO·부하 검증, rate limit, 전용 지표·알림·KMS 재시도 정책 |
| §5.13 | 표준 연동·알고리즘 변경 | **부분 구현** | X.509·PKCS#10·PKCS#8·PEM·PKCS12, Java 암호 API 사용 | 원격 KMS/HSM·PKCS#11·KMIP 연동 계층, 알고리즘 정책과 버전 전환. 모든 연동 방식 구현은 필수가 아님 |
| §5.14 | 정책·운영 절차·증적 | **부분 구현** | README의 실행·등록·보안 운영 안내, 기능 테스트 | 키별 책임·사용 기간·복구·파기 정책, 승인·훈련 증적, 최신 구현과 문서의 일치 |

## 4. 부분 구현으로 분류한 이유

### 4.1 키 파일은 있지만 키 관리 모델은 없다

현재 키는 `certs/ca.key`, `certs/server.key`, `update-signing/private.pem` 같은 고정 경로로 식별된다. [SchemaInitializer][SCHEMA]에는 인증서와 릴리스 등의 테이블이 있으나, 암호키 자체를 나타내는 `keys`, `key_versions`, `key_policies`에 해당하는 모델은 없다.

인증서 serial number나 배포 버전은 키 버전과 다르다. 특히 [AgentUpdateService.createRelease][UPDATE]가 만드는 manifest의 `version`은 소프트웨어 버전이며, 서명에 사용한 `key_id`·`key_version`·알고리즘 식별 필드는 없다. 과거 릴리스와 서명 키 버전을 연결하려면 별도 모델과 포맷 변경이 필요하다.

**남은 작업:** 키 레지스트리, 불변 버전 참조, 소유자·용도·상태, 인증서·릴리스와 키의 연결, 조회 API.

### 4.2 일부 파일 보호는 있지만 개인키 비반출은 보장하지 않는다

[PemUtil.privateKeyToPem][PEM]과 [UpdateSigner][SIGNER]는 개인키 바이트를 Base64 PEM으로 직렬화한다. 이 PEM 자체에는 암호화가 적용되지 않는다. `server.p12`를 비밀번호로 보호하더라도 별도로 저장한 `server.key`·`ca.key`·업데이트 PEM까지 보호되는 것은 아니다. 파일시스템 또는 디스크 암호화가 외부에서 적용되었는지는 이번 점검으로 판단할 수 없다.

[CertificateAuthority][CA]는 개인키를 Java 객체로 보유하고, 업데이트 서명은 파일을 읽어 Java `PrivateKey`로 변환한다. 따라서 외부 API가 개인키를 반환하지 않는다는 사실과, 애플리케이션이 개인키 원문에 접근할 수 없다는 보장은 구분해야 한다.

파일 접근 제한에도 범위 차이가 있다.

- CA/TLS 쓰기 경로는 POSIX 권한을 소유자 읽기·쓰기로 제한한다.
- Windows에서는 POSIX 권한 설정 미지원 예외를 처리하며 디렉터리 ACL 상속을 전제로 한다. 해당 코드가 Windows ACL을 직접 설정·검증하지는 않는다.
- `UpdateSigner.ensureKeyPair`는 일반 `Files.writeString`을 사용하며 CA/TLS의 제한 권한·쌍 갱신 helper를 사용하지 않는다.
- 원자 이동을 지원하지 않는 파일시스템에서는 일반 교체로 대체한다. 모든 환경에서 동일한 원자성·내구성을 보장한다고 볼 수 없다.

**남은 작업:** 서명 인터페이스 분리, 운영용 보호 backend 선택, 키 참조 기반 호출, OS별 접근 제어 확인, 로그·dump·임시 파일의 비밀값 처리 정책.

### 4.3 등록 인증은 있지만 키별 권한과 승인 체계는 없다

[AdminTokenService][ADMINAUTH]는 하나의 설정값으로 관리자 요청을 인증한다. 관리자마다 다른 역할을 부여하거나 CA 키와 업데이트 키의 사용 권한을 분리하지 않는다. Application 권한 문자열은 Vault 접근을 판단하는 정보로 사용되며, 서명 서비스의 키별 ACL로 연결되지 않는다.

업데이트에는 업로드와 활성화 endpoint가 나뉘어 있지만, [AgentUpdateService.createRelease][UPDATE]는 **업로드 시점에 먼저 서명**하고 릴리스를 DRAFT로 저장한다. 활성화는 배포 상태 전환이다. 현재 구조를 “두 사람이 승인한 뒤 서명”하는 기능으로 분류해서는 안 된다.

**남은 작업:** 운영자·워크로드 개별 신원, `주체 + 키 + 연산 + 환경` 정책, 역할 분리, MFA 연계, 고위험 작업의 승인 기록과 자기 승인 방지.

### 4.4 키 생성 시 부정합 처리 수준이 다르다

[CertificateAuthority.initializeLocked][CA]는 정상 초기화 경로에서 CA 인증서와 개인키 중 하나만 있으면 기존 Root를 교체하지 않고 오류를 낸다. CA·서버 키 쌍 일치도 검증하며 파일 교체 중단 marker가 있으면 별도의 복구 경로를 실행한다.

반면 [UpdateSigner.ensureKeyPair][SIGNER]는 공개키·개인키 파일이 둘 다 있으면 그대로 사용하고, 둘 중 하나라도 없으면 새 키 쌍을 생성한다. 기존 두 파일의 대응 검증, 키 버전 보존, 유실 시 운영자 확인, 여러 프로세스 사이의 파일 잠금은 이 구현에 없다. `synchronized`는 같은 객체에 대한 JVM 내부 동시 호출만 직렬화한다.

CA도 두 파일이 모두 없으면 새 CA를 생성하므로, 정상 최초 설치와 운영 중 전체 키 유실을 구분하는 외부 상태는 없다. 따라서 “모든 신뢰 키의 유실을 감지하고 자동 교체를 방지한다”는 요구사항은 아직 충족하지 않는다.

**남은 작업:** 최초 설치·복구 모드 구분, 업데이트 키 쌍 검증·보호된 저장, 생성 요청의 멱등성, 기존 신뢰 키 유실 시 명시적인 복구·전환.

### 4.5 서버 TLS 갱신과 CA·업데이트 키 교체를 구분해야 한다

| 대상 | 현재 동작 | 부족한 범위 |
| --- | --- | --- |
| 서버 TLS | 만료 임박·부정합 시 새 키 쌍과 인증서를 만들고 PEM·PKCS12를 갱신한다. 기본 30일 인증서는 약 10일이 남은 갱신 구간부터 대상이 된다. | 키 버전 목록, 교체 승인·정책 이력, KMS backend 연동 |
| Agent 인증서 | 기존 활성 인증서로 인증한 요청이 새 CSR을 제출하면 재발급한다. | 서버가 Agent 개인키 교체를 직접 수행하는 것은 아니다. 키 생성·보호는 Agent 측 책임이다. |
| Application 인증서 | enrollment token을 사용한 등록·재발급과 이전 인증서 SUPERSEDED 처리가 있다. | CastrelSign에 Application 전용 자동 갱신·`renew` endpoint는 없다. |
| Root CA | 기존 CA를 유지하며 만료·정합성 문제가 있으면 오류를 낸다. | 중간 CA 계층, 새 Root 사전 배포, 복수 신뢰 앵커, 신뢰 이전 워크플로 |
| 업데이트 키 | 고정 PEM 키로 계속 서명한다. | 교체 일정, 버전 전환, 신·구 공개키 공존, Agent 수신 확인, 이전 개인키 사용 종료 |

근거: [CertificateAuthority][CA], [ServerCertificateRenewalService][RENEW], [AgentController][AGENTAPI], [ApplicationController][APPAPI], [UpdateSigner][SIGNER].

**남은 작업:** 인증서 수명과 키 사용 기간의 분리, CA·업데이트 키 교체 설계, 공개키 식별자를 포함한 릴리스 포맷과 검증자 전환.

### 4.6 인증서·릴리스 상태는 있지만 서명 키 상태는 없다

현재 코드의 상태 관리 대상은 다음과 같다.

| 대상 | 확인한 상태·처리 | 적용 범위 |
| --- | --- | --- |
| Agent | ACTIVE·BLOCKED·PENDING | CastrelSign의 등록·인증 처리 |
| Agent 인증서 | ACTIVE·SUPERSEDED·REVOKED | `ClientCertificateService`를 사용하는 요청에서 DB 상태 검사 |
| Application principal | ACTIVE·BLOCKED | Application 등록과 Vault 접근 판단 |
| Application 인증서 | ACTIVE·SUPERSEDED | Vault 접근 판단 시 serial·만료 여부 확인 |
| Enrollment token | 만료·사용량 소진·폐기 시 사용 거부 | 신규 등록 |
| 업데이트 릴리스 | DRAFT·ACTIVE·REVOKED | 업데이트 선택·artifact 제공 |
| CA·서버·업데이트 키 | 별도 상태 모델 없음 | 파일과 Java 객체로 참조 |

Agent 차단은 활성 인증서도 REVOKED로 바꾸지만, Application 차단은 principal 상태를 변경한다. 두 처리의 의미와 재활성화 결과를 같다고 가정해서는 안 된다. 또한 인증서 DB 상태 확인은 해당 코드를 거치는 요청에서 적용된다. 다른 서비스의 TLS 연결, 이미 수립된 세션, 오프라인 업데이트 검증까지 차단 상태가 전파된다는 근거는 아니다.

CA 인증서에 `cRLSign` key usage를 부여하는 코드는 있지만 CRL 발행 API·OCSP responder는 없다. 인증서 확장 필드 하나가 해당 프로토콜의 구현을 의미하지 않는다.

**남은 작업:** 키 상태 전이·연산 직전 검사, 침해 시각·사유 기록, 검증 경로별 폐지 확인, 캐시·세션의 차단 지연 명세, CA·업데이트 신뢰 복구 절차. 근거: [AgentRepository][AGENTREPO], [ApplicationPrincipalRepository][APPREPO], [ClientCertificateService][CLIENTAUTH], [CertificateAuthority][CA].

### 4.7 업무 이벤트 기록을 전면적인 키 감사로 볼 수는 없다

[AgentRepository.audit][AGENTREPO]와 [SchemaInitializer][SCHEMA]의 감사 데이터는 `id`, `event_type`, `agent_id`, `message`, `created_at`이다. 주요 기록 경로는 Agent 발급·갱신, 차단·재활성화, ingest, 업데이트 상태 보고다. 서버 TLS 갱신은 일반 logger에 기록한다.

확인한 소스에는 다음 작업을 공통으로 기록하는 키 감사 경로가 없다.

- CA·업데이트 키 생성 및 모든 서명 호출
- token 생성·개별 폐기와 관리자 인증 실패
- Application 권한·상태 변경과 인증서 발급
- 릴리스 생성·활성화·폐지의 운영자별 관리 이력
- 키 ID·버전, 요청 ID, 호출 주체, 적용 정책, 승인자, 실패 이유의 구조화된 기록

감사 테이블은 다른 업무 테이블과 같은 SQLite 저장소를 사용한다. 별도의 쓰기 권한 경계, 변경 방지 보관, 외부 감사 저장소 전송, 보존 기간, 이상 탐지 경보는 구현에서 확인되지 않는다. 업무 처리와 감사 기록을 하나의 내구성 있는 처리로 묶는 공통 장치도 없다. 예를 들어 인증서 발급 코드는 서명·저장 이후 `audit`를 호출한다.

**남은 작업:** 관리·암호 연산의 공통 감사 모델, 주체·키 버전 추적, 민감값 제외, 감사 장애 시 처리 원칙, 외부 보관·탐지 연계.

### 4.8 파일 갱신 복구와 전체 재해 복구는 범위가 다르다

[CertificateAuthority][CA]의 `.bak`와 transaction marker는 같은 디렉터리에서 파일 쌍의 갱신 실패를 복구하기 위한 장치다. PKCS12는 원래 키·인증서가 남아 있으면 다시 만들 수 있다. 이 기능은 저장장치 자체가 사라졌을 때 CA·업데이트 키·DB를 함께 복원하는 기능이 아니다.

업데이트 키에 대한 보호 백업, 키·정책·인증서 DB의 일관된 snapshot, 외부 보관, 복구자 승인, 최신 폐지 상태의 재적용, RTO/RPO와 복구 훈련은 구현에서 확인되지 않는다. Root CA가 신규 발급을 감당하지 못할 때 명시적 trust migration을 요구하는 오류는 있지만, 그 migration을 수행하는 관리 기능은 없다.

가용성도 같은 경계로 평가했다. 갱신 스케줄러는 실패를 로그에 남기고 다음 주기에 다시 검사하지만, [DataSourceConfig][DB]는 로컬 SQLite를 사용한다. 키 저장소 복제·리더 선출·장애 조치·노드 간 상태 전파, 키 서비스용 지표·할당량·경보는 없다. 운영 환경에서 외부 도구가 제공하는 기능은 별도 확인이 필요하다.

**남은 작업:** 보호된 일관성 백업, 실제 복원 절차, 상태 역행 방지, 운영 목표, 장애·동시성·복제 설계와 검증.

## 5. 미구현 기능과 개발 우선순위

아래 항목은 새로 구현하거나 기존 기능을 확장해야 할 작업이다. 우선순위는 `report.md`의 방향을 현재 코드 구조에 맞춰 구체화한 제안이며 실제 개발 일정은 아니다.

| 우선순위 | 개발 항목 | 먼저 필요한 이유 | 완료 판단 기준 |
| --- | --- | --- | --- |
| P0 | 키 인벤토리·버전·상태 모델 | 권한·교체·감사·파기가 참조할 공통 관리 대상이 없다. | CA·TLS·업데이트 키마다 ID, 버전, 용도, 소유자, 상태를 조회할 수 있다. |
| P0 | 서명 연동 계층과 운영용 개인키 보호 | 업무 코드가 PEM 개인키를 직접 읽고 사용한다. | CA `ContentSigner`와 업데이트 서명이 선택한 보호 backend를 사용하고 운영 경로의 비반출 정책을 검증한다. |
| P0 | 키별·연산별 권한 및 관리자 식별 | 현재 admin token만으로 관리자 역할과 키 사용 권한을 분리할 수 없다. | 업데이트 서비스의 CA 서명, 다른 환경·테넌트 키 사용, 무권한 정책 변경을 거부한다. |
| P0 | 긴급 키 사용 중단 | Agent 차단만으로 CA·업데이트 키의 신규 서명을 중단할 수 없다. | 서명 키 비활성화·침해 상태에서 신규 연산을 거부하고 진행 중 요청의 처리 규칙을 확인한다. |
| P0 | 키 생성·복구의 일관성 | 업데이트 키 일부 유실 시 새 키를 만들고, CA 전체 유실도 최초 설치와 구분하지 못한다. | 기존 키 유실을 탐지하고 승인된 복구·신뢰 이전 없이 조용히 교체하지 않는다. |
| P0 | 전체 키·DB·정책의 복구 계획 | 로컬 파일 갱신 복구만으로 디스크 유실을 복구할 수 없다. | 백업 복원 또는 명시적 키 전환을 수행하고 최신 차단·폐지 상태를 유지한다. |
| P0 | 관리·서명 감사 확대 | 누가 어떤 키를 어떤 정책으로 사용했는지 재구성하기 어렵다. | 성공·거부·변경 이벤트를 요청·주체·키 버전으로 연결하고 비밀값을 제외한다. |
| P1 | CA·업데이트 키 교체 및 신뢰 이전 | 기존 공개키를 신뢰하는 검증자와의 호환성을 유지해야 한다. | 새 키 배포 후 서명을 전환하고 필요한 과거 서명을 계속 검증한다. |
| P1 | 삭제 예약·취소·최종 파기 | 키 삭제를 관리하는 기능이 없다. | 의존성 확인·승인·대기·취소·사본 처리·파기 증적을 검증한다. |
| P1 | 폐지 전파·침해 대응 운영 | 내부 DB 상태와 외부 검증자의 판단을 연결해야 한다. | 필요한 경우 CRL·OCSP 또는 명시적 상태 확인을 적용하고 최대 차단 지연을 측정한다. |
| P1 | 감사 보존·경보·운영 모니터링 | 일반 로그만으로 키 서비스 운영 목표를 확인하기 어렵다. | 만료·교체 실패·서명 이상량·권한 거부·감사 지연 경보가 동작한다. |
| P1 | 가용성·성능·동시성 검증 | 현재 파일·SQLite·JVM 내부 잠금의 보장 범위를 넘는 운영 요구를 검증해야 한다. | 목표 부하·장애 조건에서 중복 발급, 키 상태 역행, 비밀키 fallback 없이 동작한다. |
| P1 | 키 관리 운영 문서 갱신 | 일부 README 내용이 현재 구현과 다르다. | 실제 API·상태 의미와 책임·교체·복구·파기 절차가 문서와 일치한다. |
| 조건부 | DEK/KEK·봉투 암호화·범용 암복호화·MAC | 데이터 암호화 또는 메시지 인증 서비스를 제공할 때 필요하다. | 신규 사용 사례의 키 수명·복호화·교체·보존 요구를 검증한다. |
| 조건부 | BYOK·KMIP·공급자 간 이관 | 외부 고객 키·표준 키 관리 연동 요구가 있을 때 필요하다. | 보호된 반입·출처·복구 책임·지원 알고리즘·반출 제약을 검증한다. |

HSM을 직접 운영할지, 관리형 KMS를 사용할지, 소프트웨어 키 저장소를 강화할지는 후속 설계 결정이다. 위 표는 특정 제품 도입을 완료 조건으로 강제하지 않으며, 선택한 보호 수준과 비반출 요구를 실제로 만족하는지를 기준으로 삼는다.

## 6. 기존 테스트로 확인한 범위

### 6.1 이번 점검의 실행 결과

- 실행 위치: `CastrelSign`
- 실행 명령: `./gradlew.bat test --no-daemon --rerun-tasks`
- 실행 환경: Windows, Temurin OpenJDK 21.0.12.1, Gradle Wrapper 9.1.0
- 실행일: 2026-09-24, 한국 시간
- 결과: **BUILD SUCCESSFUL, 테스트 46개, 실패 0개, 오류 0개, 건너뜀 0개**
- 근거: 실행 종료 코드 0 및 `build/test-results/test/TEST-*.xml`의 테스트 집계

최초 일반 `test` 호출은 UP-TO-DATE였으므로 결과를 재사용하지 않고 `--rerun-tasks`로 다시 실행했다. 위 결과는 실제 재실행 결과다. 보고서 작성을 위해 테스트나 애플리케이션 코드를 변경하지 않았다.

| 테스트 클래스 | 테스트 수 | 확인하는 주요 동작 |
| --- | ---: | --- |
| [CertificateAuthorityTest][TCA] | 15 | CSR 기반 발급, keystore, 서버 인증서·키 갱신, Root 유지, 키·곡선·SAN·체인 부정합, 중단된 파일 쌍 복구, Root 유효 기간 제한 |
| [AgentApiControllerTest][TAGENT] | 15 | 등록·갱신, token의 해시 저장·제한·폐기, 부정 CSR 처리, Agent 차단, ingest 인증·입력 검증 |
| [AdminAgentControllerTest][TADMIN] | 4 | 관리자 인증, Agent·인증서 목록, 차단·재활성화, 감사·CA 공개 인증서 조회 |
| [ApplicationPrincipalControllerTest][TAPP] | 3 | Application 등록·권한·1회용 token, 인증서 발급, 상태·권한·serial을 통한 Vault 접근 판단 |
| [AgentUpdateControllerTest][TUPDATE] | 1 | 릴리스 업로드·활성화·제공, 서명 문자열과 공개키 존재, 배포 상태 기록 |
| [AgentHttpsIntegrationTest][THTTPS] | 1 | 실제 HTTPS 서버에서 등록 후 클라이언트 인증서를 사용한 ingest |
| [BootstrapEnvironmentTest][TBOOT] | 4 | reloadable PEM 설정, 외부 TLS 설정 보존, 불완전한 외부 PEM identity 거부 |
| [ServerCertificateRenewalServiceTest][TRENEW] | 1 | 만료 임박 PEM·PKCS12 갱신 및 반복 검사 시 불필요한 재교체 방지 |
| [AgentFileManagerControllerTest][TFILE] | 2 | Agent 파일 명령·업로드·다운로드 연결. KMS 자체 요구사항의 증거로는 사용하지 않았다. |

### 6.2 테스트 결과를 해석할 때의 경계

- 단위·MockMvc 테스트의 인증서 속성 주입은 실제 TLS handshake와 동일하지 않다. 별도의 HTTPS integration test가 있는 범위만 실제 HTTPS 흐름으로 구분했다.
- 업데이트 테스트는 서명 문자열이 비어 있지 않은지 등을 확인한다. 해당 테스트만으로 실제 Agent와의 서명 검증 호환성, 교체된 공개키 처리, 키 유출 대응까지 검증했다고 볼 수 없다.
- Bootstrap 테스트는 TLS 갱신 설정을 확인한다. 실행 중 HTTPS connector의 무중단 교체 전체 시나리오를 직접 검증한 테스트로 확대 해석하지 않았다.
- HSM·KMS 연동, 키 버전·ACL·파기·전체 재해 복구는 현재 구현이 없으므로 이번 성공 결과가 해당 요구사항의 충족을 의미하지 않는다.
- 고부하·장기 운영·장애 조치·외부 검증자 전체·실제 Windows ACL 상태는 이번 실행 범위에 포함하지 않았다.

## 7. README와 실제 코드의 차이

아래 내용은 `dev_report.md`의 판정에 반영했으며 README 자체는 수정하지 않았다.

| README 위치·기존 설명 | 현재 코드에서 확인한 동작 | 이 보고서의 판정 |
| --- | --- | --- |
| [README 734행](README.md#L734): 인증서 폐기·blocklist에는 별도 기능 추가가 필요하다는 설명 | `blockAgent`가 Agent를 차단하고 ACTIVE 인증서를 REVOKED로 바꾸며 사용 가능한 Agent 전용 token도 폐기한다. | **내부 차단·인증서 상태 적용은 구현됨.** token만 폐기할 때 기존 인증서가 유지된다는 설명은 여전히 맞다. |
| [README 744행](README.md#L744): 같은 Agent의 이전 인증서를 자동 폐기하지 않는다는 설명 | `saveCertificate`는 기존 ACTIVE 인증서를 SUPERSEDED로 바꾸고, 인증 경로는 ACTIVE serial만 허용한다. | **이전 인증서 사용 중단은 구현됨.** 물리적 삭제나 CRL 발행을 의미하지 않는다. |
| [README 782행](README.md#L782): 인증서 폐기·CRL·OCSP·Agent blocklist가 v1에 없다는 설명 | 내부 차단·상태 검사는 있으나 CRL·OCSP 구현은 없다. | 해당 항목들을 분리하여 판정했다. |
| README의 관리자 token·API 설명이 enrollment 중심 | 관리자 API가 Agent 차단, Application 권한·상태, 업데이트 릴리스 관리 등에도 같은 admin token을 사용한다. | **관리 기능은 확대됨.** 관리자 역할 분리가 추가되었다는 뜻은 아니다. |

문서만 보면 구현된 차단 기능을 누락하거나, 반대로 `report.md`의 목표 설계를 이미 구현한 기능으로 오인할 수 있다. 향후 문서 갱신에서는 현재 기능 명세와 개발 목표를 별도로 유지하는 것이 좋다.

## 8. 주요 코드 근거

아래 링크는 프로젝트 기준 상대 경로다. 메서드명은 이후 코드 변경으로 행 번호가 달라져도 근거를 찾을 수 있도록 함께 적었다.

| 영역 | 확인한 파일·메서드 |
| --- | --- |
| CA·TLS 키 생성·검증 | [CertificateAuthority.java][CA] — `initializeLocked`, `validateRootMaterial`, `verifyMatchingEcKey`, `ensureRootCovers` |
| CA·TLS 서명·저장·복구 | [CertificateAuthority.java][CA] — `signAgentCertificate`, `writeKeyStoresLocked`, `replaceMaterialPair`, `recoverInterruptedPair`, `setRestrictedPermissions` |
| CSR·PEM 처리 | [CsrService.java][CSR] — `parseAndValidate`; [PemUtil.java][PEM] — `privateKeyToPem` |
| 서버 갱신·TLS 설정 | [ServerCertificateRenewalService.java][RENEW] — `renewOnSchedule`; [BootstrapEnvironment.java][BOOT] — `configureServerIdentity` |
| 관리자·클라이언트 인증 | [AdminTokenService.java][ADMINAUTH] — `requireValid`; [ClientCertificateService.java][CLIENTAUTH] — `requireClientIdentity` |
| Agent token | [EnrollmentTokenService.java][TOKEN] — `create`, `consumeValid`; [EnrollmentTokenRepository.java][TOKENREPO] — `markUsed`, `revokeUnusedForAgent` |
| Agent 발급·갱신 API | [AgentController.java][AGENTAPI] — `enroll`, `renew`; [AgentCertificateService.java][ISSUER] — `issue` |
| 인증서 상태·감사 | [AgentRepository.java][AGENTREPO] — `saveCertificate`, `blockAgent`, `reactivateAgent`, `audit` |
| 관리자 조회·token API | [AdminAgentController.java][ADMINAPI], [AdminEnrollmentTokenController.java][TOKENAPI] |
| Application 관리 | [ApplicationController.java][APPAPI] — `enroll`, `vaultAccess`; [AdminApplicationController.java][APPADMIN]; [ApplicationPrincipalRepository.java][APPREPO] |
| Application token | [ApplicationEnrollmentTokenService.java][APPTOKEN] — `create`, `consumeValid` |
| 업데이트 키·서명 | [UpdateSigner.java][SIGNER] — `ensureKeyPair`, `sign`, `publicKeyPem` |
| 업데이트 처리·상태 | [AgentUpdateService.java][UPDATE] — `createRelease`, `artifact`, `recordStatus`; [AgentUpdateRepository.java][UPDATEREPO] |
| 업데이트 API | [AdminAgentUpdateController.java][UPDATEADMIN], [AgentUpdateController.java][UPDATEAPI] |
| 데이터·설정·의존성 | [SchemaInitializer.java][SCHEMA], [DataSourceConfig.java][DB], [application.yml][YAML], [build.gradle][BUILD] |

미구현 판정은 특정 키워드가 검색되지 않는다는 이유만으로 내리지 않았다. 전체 API 목록, 스키마의 관리 대상, 설정·의존성, 실제 키 생성·저장·서명·갱신·인증·감사 경로를 함께 확인했다. `kms`, `hsm`, `pkcs11`, `kmip`, 키 ID·버전, 래핑·암복호화, CRL·OCSP, MFA·RBAC, 모니터링 관련 검색은 누락 여부를 확인하는 보조 수단으로 사용했다.

[CA]: src/main/java/org/castrelyx/castrelsign/crypto/CertificateAuthority.java
[CSR]: src/main/java/org/castrelyx/castrelsign/crypto/CsrService.java
[PEM]: src/main/java/org/castrelyx/castrelsign/crypto/PemUtil.java
[RENEW]: src/main/java/org/castrelyx/castrelsign/crypto/ServerCertificateRenewalService.java
[BOOT]: src/main/java/org/castrelyx/castrelsign/bootstrap/BootstrapEnvironment.java
[ADMINAUTH]: src/main/java/org/castrelyx/castrelsign/security/AdminTokenService.java
[CLIENTAUTH]: src/main/java/org/castrelyx/castrelsign/security/ClientCertificateService.java
[TOKEN]: src/main/java/org/castrelyx/castrelsign/security/EnrollmentTokenService.java
[TOKENREPO]: src/main/java/org/castrelyx/castrelsign/persistence/EnrollmentTokenRepository.java
[TOKENAPI]: src/main/java/org/castrelyx/castrelsign/api/AdminEnrollmentTokenController.java
[AGENTAPI]: src/main/java/org/castrelyx/castrelsign/api/AgentController.java
[ISSUER]: src/main/java/org/castrelyx/castrelsign/api/AgentCertificateService.java
[AGENTREPO]: src/main/java/org/castrelyx/castrelsign/persistence/AgentRepository.java
[ADMINAPI]: src/main/java/org/castrelyx/castrelsign/api/AdminAgentController.java
[APPAPI]: src/main/java/org/castrelyx/castrelsign/api/ApplicationController.java
[APPADMIN]: src/main/java/org/castrelyx/castrelsign/api/AdminApplicationController.java
[APPREPO]: src/main/java/org/castrelyx/castrelsign/persistence/ApplicationPrincipalRepository.java
[APPTOKEN]: src/main/java/org/castrelyx/castrelsign/security/ApplicationEnrollmentTokenService.java
[SIGNER]: src/main/java/org/castrelyx/castrelsign/update/UpdateSigner.java
[UPDATE]: src/main/java/org/castrelyx/castrelsign/update/AgentUpdateService.java
[UPDATEREPO]: src/main/java/org/castrelyx/castrelsign/update/AgentUpdateRepository.java
[UPDATEADMIN]: src/main/java/org/castrelyx/castrelsign/api/AdminAgentUpdateController.java
[UPDATEAPI]: src/main/java/org/castrelyx/castrelsign/api/AgentUpdateController.java
[SCHEMA]: src/main/java/org/castrelyx/castrelsign/persistence/SchemaInitializer.java
[DB]: src/main/java/org/castrelyx/castrelsign/config/DataSourceConfig.java
[YAML]: src/main/resources/application.yml
[BUILD]: build.gradle
[TCA]: src/test/java/org/castrelyx/castrelsign/CertificateAuthorityTest.java
[TAGENT]: src/test/java/org/castrelyx/castrelsign/AgentApiControllerTest.java
[TADMIN]: src/test/java/org/castrelyx/castrelsign/AdminAgentControllerTest.java
[TAPP]: src/test/java/org/castrelyx/castrelsign/ApplicationPrincipalControllerTest.java
[TUPDATE]: src/test/java/org/castrelyx/castrelsign/AgentUpdateControllerTest.java
[THTTPS]: src/test/java/org/castrelyx/castrelsign/AgentHttpsIntegrationTest.java
[TBOOT]: src/test/java/org/castrelyx/castrelsign/bootstrap/BootstrapEnvironmentTest.java
[TRENEW]: src/test/java/org/castrelyx/castrelsign/crypto/ServerCertificateRenewalServiceTest.java
[TFILE]: src/test/java/org/castrelyx/castrelsign/AgentFileManagerControllerTest.java
