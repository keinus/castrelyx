import { useRuntime, useLoad, Loaded } from "./runtime";
import {
  Button,
  Notice,
  Facts,
  Panel,
  Steps,
  Icon,
  Table,
  downloadText,
} from "./ui";
const backupGuide = `CastrelSign 오프라인 백업·복구\n\n1. 서비스를 중지하고 단일 writer 종료를 확인합니다.\n2. KMS_OPERATIONS.md의 backup CLI로 암호화 스냅샷을 만듭니다. 최대 크기 512 MiB.\n3. wrapping key, 백업 키, 최신 외부 checkpoint, 관리자·사용자 정책을 별도 보호 보관합니다.\n4. 원래 절대 경로의 빈 data-dir에 복원합니다.\n5. 최신 외부 checkpoint 일치와 침해·삭제·권한 상태를 검증합니다.\n6. 운영 상태를 확인한 뒤 서비스를 재개합니다.\n\n과거 snapshot을 최신 상태로 자동 재조정하지 않습니다. 정확한 명령은 배포본 KMS_OPERATIONS.md를 따르세요.\n`;
export function Settings({ tab }) {
  const state = useLoad("/api/console/configuration"),
    current = tab || "protection",
    { navigate } = useRuntime();
  return (
    <Loaded state={state}>
      {(c) => (
        <div className="settings-layout">
          <nav className="settings-nav" aria-label="설정 분류">
            {[
              ["protection", "키 보호", "locked"],
              ["lifecycle", "수명주기", "history"],
              ["identity", "관리자·MFA", "group"],
              ["backup", "백업·복구", "download"],
              ["integration", "HSM·모니터링", "sys-monitor"],
            ].map(([id, label, icon]) => (
              <button
                key={id}
                className={id === current ? "selected" : ""}
                onClick={() => navigate(`settings?tab=${id}`)}
              >
                <Icon name={icon} />
                {label}
              </button>
            ))}
          </nav>
          <Panel className="settings-content">
            <Notice>
              서버에서 조회한 적용값입니다. 구성 변경은 운영 설정과 재시작
              절차를 따릅니다.
            </Notice>
            {current === "protection" ? (
              <>
                <h2>키 보호 구성</h2>
                <Facts
                  rows={[
                    [
                      "CA 서명 키",
                      c.pkcs11Configured
                        ? "PKCS#11 · ECDSA P-256"
                        : "암호화 소프트웨어 · ECDSA P-256",
                    ],
                    ["업데이트 서명 키", "암호화 소프트웨어 · Ed25519"],
                    [
                      "마스터 키",
                      c.externalMasterKey
                        ? "외부 보호 파일 · 값 비공개"
                        : "개발용 로컬 보호 파일",
                    ],
                    ["키 저장 보호", "AES-256-GCM · 문맥 결합"],
                    ["환경 / 테넌트", `${c.environment} / ${c.tenant}`],
                    ["쓰기 구성", "단일 writer"],
                  ]}
                />
                <h3>구성 적용 절차</h3>
                <Steps
                  labels={["설정 변경", "서버 재시작", "상태 확인"]}
                  current={-1}
                />
                <Notice tone="warn">
                  wrapping key 파일을 새 값으로 덮어쓰면 기존 저장소를 열 수
                  없습니다. 재래핑 교체 API는 제공하지 않습니다.
                </Notice>
              </>
            ) : current === "lifecycle" ? (
              <>
                <h2>수명주기 정책</h2>
                <Facts
                  rows={[
                    ["서명 유효기간", `${c.signingLifetimeDays}일`],
                    ["인증서 유효기간", `${c.certValidDays}일`],
                    ["삭제 유예기간", `${c.deletionGraceDays}일`],
                    ["승인 요청 유효기간", "24시간"],
                    [
                      "서명 시도 한도",
                      `키 ID당 분당 ${c.signsPerMinute}회 · 프로세스 내`,
                    ],
                    ["인증서 상태 캐시", "최대 60초"],
                  ]}
                />
                <h3>삭제 절차</h3>
                <Steps
                  labels={[
                    "의존성 해소",
                    "예약 승인",
                    "유예기간",
                    "최종 파기 승인",
                  ]}
                  current={-1}
                />
                <Notice>
                  삭제 취소 후에는 중지 상태가 됩니다. 사용 재개는 별도 승인이
                  필요합니다.
                </Notice>
              </>
            ) : current === "identity" ? (
              <>
                <h2>관리자 역할과 인증</h2>
                <Table
                  columns={[
                    ["id", "개인 ID"],
                    ["roles", "역할", (r) => r.roles.join(", ")],
                    ["keys", "키 범위", (r) => r.keys.join(", ")],
                    [
                      "mfa",
                      "변경 작업 MFA",
                      (r) => (r.mfa ? "필수" : "개발 호환 계정"),
                    ],
                  ]}
                  rows={c.administrators}
                />
                <Facts
                  rows={[
                    ["계정 관리", "외부 파일 · 요청마다 정책 재검사"],
                    ["승인 분리", "요청자와 승인자는 다른 개인"],
                    ["브라우저 인증", "HttpOnly 세션 · CSRF 보호"],
                    [
                      "사용자 포털 정책",
                      c.portalConfigured ? "구성됨" : "미구성",
                    ],
                  ]}
                />
                <Notice>
                  인증 토큰·MFA secret은 조회 응답에 포함하지 않습니다.
                </Notice>
              </>
            ) : current === "backup" ? (
              <>
                <h2>오프라인 보호 백업·복구</h2>
                <Notice tone="warn">
                  서버 중지 후 운영자가 CLI로 수행합니다.
                </Notice>
                <Steps
                  labels={[
                    "서비스 중지",
                    "암호화 백업",
                    "분리 보관",
                    "복구 검증",
                  ]}
                  current={-1}
                />
                <Facts
                  rows={[
                    ["백업 범위", "data-dir 스냅샷 · 최대 512 MiB"],
                    [
                      "별도 보관",
                      "wrapping key / 백업 키 / 최신 checkpoint / 계정 정책",
                    ],
                    ["복원 대상", "원래 절대 경로의 빈 data-dir"],
                    ["복원 검증", "최신 외부 checkpoint 일치 필요"],
                  ]}
                />
                <Button
                  icon="download"
                  onClick={() =>
                    downloadText("kms-backup-guide.txt", backupGuide)
                  }
                >
                  절차 안내 다운로드
                </Button>
              </>
            ) : (
              <>
                <h2>HSM과 외부 연동</h2>
                <Facts
                  rows={[
                    [
                      "PKCS#11",
                      c.pkcs11Configured ? "구성됨 · CA P-256" : "미구성",
                    ],
                    ["업데이트 서명", "Ed25519 · 소프트웨어 backend"],
                    ["SIEM / WORM", "외부 수집·보존 연동 필요"],
                    ["CRL / OCSP", "미구현"],
                    ["다중 노드 HA", "미지원"],
                  ]}
                />
                <Notice>
                  HSM 장애 시 소프트웨어로 자동 우회하지 않습니다. 장비 PIN과
                  물리 파기는 장비 운영 절차를 따릅니다.
                </Notice>
                <Button onClick={() => navigate("health")}>
                  운영 상태 확인
                </Button>
              </>
            )}
          </Panel>
          <aside className="guidance">
            <h3>적용 전 확인</h3>
            <p>
              정책·저장소 구성을 변경할 때는 재시작과 상태 검증이 필요합니다.
            </p>
            <hr />
            <h3>계정과 권한</h3>
            <p>
              관리자와 사용자 정책 파일은 요청마다 다시 검사합니다. 파일 수정
              권한은 서버 운영자에게 있습니다.
            </p>
            <hr />
            <p>모드: {c.mode}</p>
          </aside>
        </div>
      )}
    </Loaded>
  );
}
