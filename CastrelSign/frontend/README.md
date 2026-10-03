# CastrelSign Console

선택된 SAP Fundamental Styles 기반 다크 화면을 실제 CastrelSign API와 연결한 React 프런트엔드입니다. `design/prototype`은 예시 데이터 설계본이며, 이 디렉터리가 서비스에 포함되는 구현입니다.

## 빌드와 실행

JDK 21과 Node.js 22 이상, npm이 필요합니다. CastrelSign 디렉터리에서 `./gradlew bootJar`를 실행하면 npm 의존성 설치·Vite 빌드 후 `/console/` 정적 리소스를 JAR에 포함합니다. 서버 실행 후 `https://서버:8443/console/`로 접속합니다. 기존 서버 실행·인증서 신뢰 설정은 상위 README와 KMS_OPERATIONS.md를 따릅니다.

프런트엔드 개발:

```powershell
npm ci
$env:CASTRELSIGN_API_URL = 'https://localhost:8443'
npm run dev
```

Vite 주소 뒤에 `/console/`로 접속합니다. API는 같은 출처 프록시를 사용합니다. 서버 인증서를 개발 머신의 Node 신뢰 저장소에 등록하세요. 격리된 개발 서버에만 필요한 경우 `CASTRELSIGN_DEV_INSECURE_TLS=true`로 개발 프록시 인증서 검증을 끌 수 있습니다. 운영 빌드에는 이 프록시가 포함되지 않습니다.

## 인증과 역할

- 관리자는 기존 외부 관리자 파일의 개인별 토큰으로 로그인합니다. 변경 작업마다 현재 MFA 코드를 입력합니다. 서버가 같은 코드 재사용을 차단합니다.
- 키 조회에는 `AUDITOR`와 해당 키 범위가 필요합니다. 키 변경은 `KEY_ADMIN`, 다른 담당자의 승인은 `SECURITY_OFFICER`, 서비스·CSR·릴리스 관리는 `OPERATOR`, 인증서 상태 검증은 `VERIFIER` 역할을 사용합니다. 조회 화면을 사용할 변경 담당자에게 필요한 조회 역할도 부여하세요.
- 개발 호환 `CASTRELSIGN_ADMIN_TOKEN`은 기존 legacy-admin 역할만 제공하며 2인 승인 권한은 제공하지 않습니다.
- 토큰을 localStorage/sessionStorage에 저장하지 않습니다. 서버는 토큰 해시를 세션에 보관하고 매 요청마다 정책을 다시 읽습니다. 세션은 유휴 15분·최대 8시간, HttpOnly/SameSite=Strict 쿠키를 사용합니다. 운영 모드 브라우저 API는 HTTPS를 요구합니다.
- 세션 변경 요청은 CSRF 토큰과 동일 출처 검사를 통과해야 합니다. 기존 Bearer API 클라이언트 경로는 유지합니다.

## 일반 사용자 등록

먼저 관리 화면의 서비스·에이전트에서 Application 서비스를 등록합니다. 그 다음 `CASTRELSIGN_PORTAL_USERS_FILE`로 외부 JSON 파일을 지정합니다. 운영에서는 data-dir 밖에 두고 OS 접근 권한을 제한하세요. 파일 내용은 다음 구조입니다.

```json
[
  {
    "id": "service-owner",
    "tokenSha256": "<개별 고엔트로피 토큰의 SHA-256 소문자 hex 64자리>",
    "services": ["payment-api", "order-worker"]
  }
]
```

운영자가 암호학적으로 안전하게 생성한 토큰 원문을 해당 사용자에게 안전한 경로로 전달합니다. 관리자 파일의 토큰과 공유하지 마세요. 사용자 로그인에서 일반 사용자를 선택합니다. 정책에서 제거하거나 해시를 교체하면 기존 세션도 다음 요청부터 거부됩니다. 서비스 목록·요청·인증서·활동은 서버에서 현재 서비스 범위로 제한합니다.

인증서 발급 흐름: 서비스 호스트에서 P-256 개인키·CSR 생성 → 사용자 CSR 제출 → 관리자의 **인증서 요청 검토**에서 사유와 함께 승인/반려 → 사용자 공개 인증서 다운로드. CSR은 CN과 서비스 ID가 같아야 하고 서명이 유효해야 합니다. 개인키는 업로드하지 않습니다. 한 서비스당 대기 요청 1개, 사용자당 일 20건, CSR 최대 64 KiB입니다. 발급 직전에 소유권과 서비스 차단 여부를 재검사합니다. 발급 완료/반려 후 저장된 CSR 원문을 제거합니다.

## 구현 범위

키·버전·권한·의존성, 교체 준비와 2인 승인, 인증서·서비스·에이전트·등록 토큰, TLS 이력, 릴리스 업로드/활성화/폐기, 업데이트 정책/실행 이력, 감사 조회/CSV, 공개 신뢰 자료, 상태·설정 조회, 사용자 서비스/CSR/요청/다운로드/활동 화면이 실제 API로 동작합니다.

외부 관리자·사용자 파일 편집, HSM 설정, wrapping key 변경, 백업·복원은 기존 운영 절차로 실행합니다. 설정 화면은 적용값 조회와 절차 안내입니다. OIDC/SSO, 사용자 비밀번호 관리, 온라인 백업 복원, 범용 KMS 암·복호화 API는 이 UI 변경 범위에 포함되지 않습니다. 사용자 포털은 Application 서비스 인증서용이며 Agent 등록은 기존 등록 토큰 흐름을 유지합니다.

## 검증

```powershell
npm test
npm run build
cd ..
./gradlew test bootJar
```

브라우저 검증 결과와 비교 이미지는 [design-qa.md](design-qa.md), 기능·제약은 [UI 구현 보고서](../console_implementation_report.md)에 기록합니다.
