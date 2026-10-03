import { useEffect, useState } from "react";
import {
  adminNav,
  userNav,
  roles,
  screens,
  initialKeys,
  initialServices,
  initialRequests,
  initialAudit,
  agents,
  TODAY,
} from "./data";
import {
  Icon,
  Button,
  Status,
  Notice,
  Field,
  Facts,
  Panel,
  Tabs,
  Search,
  Table,
  Empty,
  Steps,
  Modal,
  downloadText,
} from "./ui";

const parseRoute = () => {
  const [page = "keys", params = ""] = location.hash.slice(1).split("?");
  return {
    page: page || "keys",
    tab: new URLSearchParams(params).get("tab") || "",
  };
};
const titles = {
  keys: ["키 관리", "현재 버전과 다음 교체를 한곳에서 확인하세요."],
  rotation: [
    "키 교체 준비",
    "새 버전을 준비하고, 신뢰 자료 배포 후 활성화를 요청합니다.",
  ],
  approvals: [
    "승인 요청",
    "변경 내용과 영향을 확인하고 다른 담당자가 승인합니다.",
  ],
  certificates: [
    "인증서",
    "발급 대상과 서명 키를 확인하고 현재 상태를 조회하세요.",
  ],
  principals: ["서비스·에이전트", "등록 대상과 접근 상태를 관리하세요."],
  tls: ["서버 TLS", "HTTPS 인증서의 현재 버전과 발급 이력을 확인하세요."],
  releases: ["업데이트 서명", "릴리스의 서명 키와 배포 상태를 관리하세요."],
  trust: ["신뢰 자료", "검증에 사용하는 공개 인증서와 공개키를 확인하세요."],
  audit: ["감사 기록", "관리 작업과 서명 이력을 요청 단위로 확인하세요."],
  health: ["운영 상태", "키 사용 가능 여부와 저장소 무결성을 확인하세요."],
  settings: ["설정·구성", "현재 적용된 정책과 운영 절차를 확인하세요."],
  services: ["내 서비스", "서비스 연결과 인증서 상태를 확인하세요."],
  "my-certificates": [
    "내 인증서",
    "내 서비스에 발급된 공개 인증서를 확인하세요.",
  ],
  "my-requests": ["내 요청", "등록·갱신 요청의 진행 상태를 확인하세요."],
  enroll: ["인증서 요청", "서비스 환경에서 만든 CSR을 제출하세요."],
  guide: ["연결 가이드", "인증서 발급부터 신뢰 자료 적용까지 안내합니다."],
  "my-activity": [
    "사용 이력",
    "내 서비스의 인증서 요청과 다운로드 이력입니다.",
  ],
  account: ["내 계정", "내 역할과 접근 가능한 서비스를 확인하세요."],
};
const actionTitles = {
  ACTIVATE: "활성화 요청",
  RESUME: "사용 재개 요청",
  GRANT: "권한 추가 요청",
  REVOKE: "권한 회수 요청",
  SCHEDULE_DELETION: "삭제 예약 요청",
  DESTROY: "최종 파기 요청",
  SUSPEND: "긴급 사용 중지",
  COMPROMISED: "키 침해 선언",
  CANCEL: "삭제 예약 취소",
  APPROVE: "변경 승인",
  BLOCK: "서비스 차단",
  REACTIVATE: "서비스 재활성화",
  TOKEN: "등록 토큰 생성",
  UPLOAD: "릴리스 등록",
  PUBLISH: "릴리스 활성화",
  REVOKE_RELEASE: "릴리스 폐기",
};
const demoCSR =
  "-----BEGIN CERTIFICATE REQUEST-----\n[화면 설계용 CSR 예시 · 실제 인증서 요청에 사용할 수 없음]\n-----END CERTIFICATE REQUEST-----";

export function App() {
  const [route, setRoute] = useState(parseRoute),
    [persona, setPersona] = useState(() =>
      [
        "services",
        "my-certificates",
        "my-requests",
        "enroll",
        "guide",
        "my-activity",
        "account",
      ].includes(parseRoute().page)
        ? "user"
        : "admin",
    );
  const [keys, setKeys] = useState(() =>
      initialKeys.map((k) => ({
        ...k,
        current: k.state === "ACTIVE",
        grants:
          k.alias === "ca-root"
            ? ["service:certificate-issuer", "service:tls"]
            : ["service:update-signer"],
      })),
    ),
    [services, setServices] = useState(initialServices),
    [requests, setRequests] = useState(initialRequests),
    [audit, setAudit] = useState(initialAudit);
  const [search, setSearch] = useState(""),
    [selected, setSelected] = useState("ca-01:3"),
    [serviceId, setServiceId] = useState("payment-api"),
    [requestId, setRequestId] = useState("REQ-1024");
  const [modal, setModal] = useState(null),
    [toast, setToast] = useState(""),
    [visualState, setVisualState] = useState("normal"),
    [mobileNav, setMobileNav] = useState(false);
  const [userRequests, setUserRequests] = useState([
    {
      id: "CSR-2042",
      service: "payment-api",
      type: "인증서 발급",
      state: "APPROVED",
      at: "2026.09.23 14:22",
    },
  ]);
  const [rotationAlias, setRotationAlias] = useState("ca-root"),
    [rotationId, setRotationId] = useState("rotation-20260924"),
    [rotationStep, setRotationStep] = useState(0),
    [rotationMfa, setRotationMfa] = useState("");
  const [csr, setCsr] = useState(""),
    [csrFile, setCsrFile] = useState(""),
    [enrollStep, setEnrollStep] = useState(0),
    [formError, setFormError] = useState("");
  const [tokens, setTokens] = useState([
    {
      id: "ENR-0901",
      target: "collector-seoul-02",
      state: "PENDING",
      expiry: "2026.09.24 20:30",
      uses: "0 / 1",
    },
  ]);
  const [releases, setReleases] = useState([
    {
      id: "v0.8.3-linux-amd64",
      version: "0.8.3",
      target: "linux / amd64",
      key: "update-signing v2",
      state: "ACTIVE",
    },
    {
      id: "v0.8.4-linux-amd64",
      version: "0.8.4",
      target: "linux / amd64",
      key: "update-signing v2",
      state: "DRAFT",
    },
  ]);
  const [policy, setPolicy] = useState({ enabled: true, channel: "stable" }),
    [auditResult, setAuditResult] = useState("all");
  const identity = roles[persona],
    isUser = identity.mode === "user",
    canKey = persona === "admin",
    canOperate = persona === "operator";
  const page = route.page,
    tab = route.tab;
  const navigate = (destination) => {
    location.hash = destination;
    setSearch("");
    setVisualState("normal");
    setMobileNav(false);
    setFormError("");
  };
  useEffect(() => {
    const update = () => setRoute(parseRoute());
    window.addEventListener("hashchange", update);
    return () => window.removeEventListener("hashchange", update);
  }, []);
  useEffect(() => {
    if (!toast) return;
    const timer = setTimeout(() => setToast(""), 5000);
    return () => clearTimeout(timer);
  }, [toast]);
  const notify = (text) => setToast(text);
  const changePersona = (next) => {
    setPersona(next);
    navigate(
      next === "user"
        ? "services"
        : next === "officer"
          ? "approvals"
          : next === "auditor"
            ? "audit"
            : next === "operator"
              ? "principals"
              : "keys",
    );
  };
  const selectedKey = keys.find((k) => k.id === selected) || keys[0],
    service = services.find((s) => s.id === serviceId) || services[0];
  const addAudit = (operation, target) =>
    setAudit((a) => [
      {
        seq: a[0].seq + 1,
        at: `${TODAY} 19:30:00`,
        actor: identity.actor,
        operation,
        target,
        result: "SUCCESS",
        request: `demo-${a[0].seq + 1}`,
      },
      ...a,
    ]);
  const openAction = (action, target = selectedKey, subject = "") => {
    setFormError("");
    setModal({ action, target, subject });
  };
  const download = (label) => {
    downloadText(
      `${label}-설계예시.txt`,
      `CastrelSign KMS 화면 설계용 파일\n${label}\n실제 인증서, 공개키 또는 비밀값이 아닙니다. 운영 API는 연결되지 않았습니다.\n기준일: ${TODAY}`,
    );
    notify("화면 설계용 안내 파일을 다운로드했습니다.");
  };
  const requestChange = (action, target, reason, evidence) => {
    if (
      requests.some(
        (r) =>
          r.key === target.id && r.action === action && r.state === "PENDING",
      )
    ) {
      notify("같은 작업의 승인 대기 요청이 이미 있습니다.");
      navigate("approvals");
      return;
    }
    const id = `REQ-${1025 + requests.length}`;
    setRequests((r) => [
      {
        id,
        key: target.id,
        title: `${target.alias} v${target.version} ${actionTitles[action]}`,
        before: keys.find((k) => k.alias === target.alias && k.current)
          ?.version,
        action,
        actor: identity.actor,
        reason,
        evidence,
        state: "PENDING",
        at: `${TODAY} 19:30`,
        expires: "2026.09.25 19:30",
      },
      ...r,
    ]);
    setRequestId(id);
    addAudit(`REQUEST_${action}`, `${target.alias} v${target.version}`);
    notify("변경 요청을 등록했습니다. 다른 보안 담당자의 승인이 필요합니다.");
    navigate("approvals");
  };
  const submitAction = (e) => {
    e.preventDefault();
    const form = new FormData(e.currentTarget),
      { action, target } = modal;
    if (form.get("mfa") !== "000000") {
      setFormError(
        "프로토타입 인증 코드는 000000입니다. 실제 인증 코드를 입력하지 마세요.",
      );
      return;
    }
    const reason = String(form.get("reason") || "검토 완료").trim(),
      evidence = String(form.get("evidence") || "");
    if (action === "APPROVE") {
      if (persona !== "officer" || target.actor === identity.actor) {
        setFormError("요청자와 다른 보안 담당자만 승인할 수 있습니다.");
        return;
      }
      const key = keys.find((k) => k.id === target.key);
      if (!key) {
        setFormError("대상 키를 찾을 수 없습니다.");
        return;
      }
      if (
        target.state !== "PENDING" ||
        ["COMPROMISED", "DESTROYED"].includes(key.state) ||
        (target.action === "ACTIVATE" && key.state !== "PRE_ACTIVE") ||
        (target.action === "RESUME" && key.state !== "SUSPENDED") ||
        (target.action === "SCHEDULE_DELETION" &&
          (key.current || key.refs + key.backup > 0))
      ) {
        setFormError(
          "대상 상태가 변경되어 적용할 수 없습니다. 키 상태를 다시 확인하세요.",
        );
        return;
      }
      setRequests((rs) =>
        rs.map((r) =>
          r.id === target.id
            ? { ...r, state: "APPROVED", approver: identity.actor }
            : r,
        ),
      );
      setKeys((ks) =>
        ks.map((k) =>
          target.action === "ACTIVATE" && k.alias === key.alias
            ? k.id === key.id
              ? { ...k, state: "ACTIVE", current: true }
              : k.current
                ? {
                    ...k,
                    state: k.state === "ACTIVE" ? "VERIFY_ONLY" : k.state,
                    current: false,
                  }
                : k
            : k.id !== key.id
              ? k
              : target.action === "RESUME"
                ? { ...k, state: k.current ? "ACTIVE" : "VERIFY_ONLY" }
                : target.action === "GRANT"
                  ? {
                      ...k,
                      grants: [
                        ...new Set([
                          ...k.grants,
                          target.evidence.split(" · ")[0],
                        ]),
                      ],
                    }
                  : target.action === "REVOKE"
                    ? {
                        ...k,
                        grants: k.grants.filter(
                          (s) => s !== target.evidence.split(" · ")[0],
                        ),
                      }
                    : target.action === "SCHEDULE_DELETION"
                      ? {
                          ...k,
                          state: "PENDING_DELETION",
                          deletionDate: "2026.10.01",
                        }
                      : target.action === "DESTROY"
                        ? { ...k, state: "DESTROYED" }
                        : k,
        ),
      );
      addAudit(`APPROVE_${target.action}`, target.title);
      notify("승인과 변경 적용을 시뮬레이션했습니다.");
    } else if (
      [
        "ACTIVATE",
        "RESUME",
        "GRANT",
        "REVOKE",
        "SCHEDULE_DELETION",
        "DESTROY",
      ].includes(action)
    ) {
      if (!canKey) {
        setFormError("키 관리자 역할이 필요합니다.");
        return;
      }
      if (
        action === "SCHEDULE_DELETION" &&
        (target.refs + target.backup > 0 || target.current)
      ) {
        setFormError(
          "현재 버전 또는 살아 있는 의존성이 있는 키는 삭제할 수 없습니다.",
        );
        return;
      }
      if (action === "DESTROY") {
        setFormError(
          "예시 키의 삭제 유예기간이 경과하지 않았습니다. 최종 파기는 아직 요청할 수 없습니다.",
        );
        return;
      }
      requestChange(action, target, reason, evidence);
    } else if (["SUSPEND", "COMPROMISED", "CANCEL"].includes(action)) {
      if (!canKey) return;
      setKeys((ks) =>
        ks.map((k) =>
          k.id === target.id
            ? {
                ...k,
                state: action === "COMPROMISED" ? "COMPROMISED" : "SUSPENDED",
              }
            : k,
        ),
      );
      addAudit(action, target.alias);
      notify(
        action === "CANCEL"
          ? "삭제 예약을 취소했습니다. 키는 중지 상태로 유지됩니다."
          : "키 상태 변경을 시뮬레이션했습니다.",
      );
    } else if (action === "BLOCK" || action === "REACTIVATE") {
      if (!canOperate) return;
      setServices((ss) =>
        ss.map((s) =>
          s.id === target.id
            ? { ...s, state: action === "BLOCK" ? "SUSPENDED" : "PENDING" }
            : s,
        ),
      );
      addAudit(action, target.id);
      notify(
        action === "BLOCK"
          ? "서비스 차단과 기존 인증서 회수를 시뮬레이션했습니다."
          : "재활성화 후 새 등록이 필요합니다.",
      );
    } else if (action === "TOKEN") {
      setTokens((ts) => [
        {
          id: `ENR-${902 + ts.length}`,
          target: String(form.get("target")),
          state: "PENDING",
          expiry: "2026.09.24 20:30",
          uses: "0 / 1",
        },
        ...ts,
      ]);
      notify("등록 토큰 예시를 생성했습니다. 실제 토큰은 생성하지 않습니다.");
    } else if (action === "UPLOAD") {
      setReleases((rs) => [
        {
          id: `demo-${rs.length}`,
          version: String(form.get("version")),
          target: "linux / amd64",
          key: "update-signing v2",
          state: "DRAFT",
        },
        ...rs,
      ]);
      notify(
        "릴리스 초안을 예시 목록에 추가했습니다. 파일은 서버로 전송하지 않았습니다.",
      );
    } else if (action === "PUBLISH" || action === "REVOKE_RELEASE") {
      setReleases((rs) =>
        rs.map((r) =>
          r.id === target.id
            ? { ...r, state: action === "PUBLISH" ? "ACTIVE" : "REVOKED" }
            : r,
        ),
      );
      notify("릴리스 상태 변경을 시뮬레이션했습니다.");
    }
    setModal(null);
    setFormError("");
  };
  const filtered = (rows) =>
    rows.filter((r) =>
      Object.values(r).join(" ").toLowerCase().includes(search.toLowerCase()),
    );
  const nav = isUser ? userNav : adminNav;
  const commonColumns = [
    ["id", "서비스"],
    [
      "state",
      "상태",
      (s) => (
        <Status
          state={s.state}
          label={s.state === "ACTIVE" ? "연결됨" : undefined}
        />
      ),
    ],
    ["expiry", "인증서 만료일"],
  ];

  function renderKeys() {
    const visible = filtered(keys),
      k = visible.find((x) => x.id === selected) || visible[0] || selectedKey;
    if (!visible.length) {
      return (
        <Panel
          title="키와 버전"
          action={<Search value={search} onChange={setSearch} />}
        >
          <Empty
            action={<Button onClick={() => setSearch("")}>검색 초기화</Button>}
          />
        </Panel>
      );
    }
    return (
      <>
        <Notice
          tone={
            keys.some(
              (x) => x.alias === "ca-root" && x.version === 2 && x.current,
            )
              ? "warn"
              : "info"
          }
        >
          {keys.some(
            (x) => x.alias === "ca-root" && x.version === 2 && x.current,
          )
            ? "ca-root 서명 만료까지 21일"
            : "ca-root 현재 서명 버전: v" +
              keys.find((x) => x.alias === "ca-root" && x.current)?.version}
          <span className="notice-separator">·</span>
          {keys.some((x) => x.alias === "ca-root" && x.state === "PRE_ACTIVE")
            ? "새 버전 준비 완료"
            : "키 상태를 확인하세요"}
        </Notice>
        <div className="split key-split">
          <Panel
            title="키와 버전"
            action={
              <Search
                value={search}
                onChange={setSearch}
                placeholder="키 이름으로 검색"
              />
            }
          >
            <Table
              label="키와 버전"
              columns={[
                ["alias", "키"],
                ["version", "버전", (r) => `v${r.version}`],
                ["purpose", "용도"],
                ["state", "상태", (r) => <Status state={r.state} />],
              ]}
              rows={visible}
              selected={k.id}
              onSelect={(r) => setSelected(r.id)}
            />
            <div className="panel-bottom">
              <span>총 {visible.length}개 버전</span>
              <Button
                icon="add"
                disabled={!canKey}
                onClick={() => {
                  setRotationStep(0);
                  setRotationMfa("");
                  navigate("rotation");
                }}
              >
                교체 준비
              </Button>
            </div>
          </Panel>
          <Panel className="inspector">
            <div className="inspector-title">
              <h2>
                {k.alias} <span>· v{k.version}</span>
              </h2>
              <Status state={k.state} />
            </div>
            <Tabs
              items={[
                ["overview", "개요"],
                ["grants", "권한"],
                ["dependencies", "의존성"],
              ]}
              value={tab || "overview"}
              onChange={(v) => {
                setSelected(k.id);
                navigate(`keys?tab=${v}`);
              }}
            />
            {tab === "grants" ? (
              <>
                <h3>서비스 서명 권한</h3>
                <p className="muted">
                  키 목적에 맞는 서비스만 서명을 사용할 수 있습니다.
                </p>
                {k.grants.map((subject) => (
                  <div className="resource-row" key={subject}>
                    <div>
                      <strong className="mono">{subject}</strong>
                      <small>SIGN · 서비스 권한</small>
                    </div>
                    <Button
                      disabled={!canKey}
                      onClick={() => openAction("REVOKE", k, subject)}
                    >
                      회수 요청
                    </Button>
                  </div>
                ))}
                <Button
                  disabled={!canKey}
                  onClick={() => openAction("GRANT", k)}
                >
                  권한 변경 요청
                </Button>
              </>
            ) : tab === "dependencies" ? (
              <>
                <h3>삭제 전 의존성</h3>
                <Facts
                  rows={[
                    [
                      k.alias === "ca-root" ? "유효 인증서" : "배포 릴리스",
                      `${k.refs}개`,
                    ],
                    ["외부 백업 참조", `${k.backup}개`],
                    ["현재 서명 버전", k.current ? "예 · 삭제 불가" : "아니요"],
                  ]}
                />
                <Notice tone={k.refs + k.backup ? "warn" : "info"}>
                  {k.refs + k.backup
                    ? "유효한 의존성을 먼저 해소해야 합니다. 외부 백업 삭제는 운영자가 별도로 확인합니다."
                    : "현재 예시 데이터에는 살아 있는 의존성이 없습니다. 삭제 예약 후에도 최소 7일을 기다립니다."}
                </Notice>
                <Button
                  danger
                  disabled={
                    !canKey ||
                    k.current ||
                    k.refs + k.backup > 0 ||
                    k.state === "DESTROYED"
                  }
                  onClick={() => openAction("SCHEDULE_DELETION", k)}
                >
                  삭제 예약 요청
                </Button>
              </>
            ) : (
              <>
                <Facts
                  rows={[
                    ["알고리즘", k.algorithm],
                    ["저장 방식", "암호화 소프트웨어"],
                    ["생성일", k.created],
                    ["서명 기한", k.expiry],
                  ]}
                />
                <h3>키 수명주기</h3>
                <Steps
                  labels={["버전 준비", "신뢰 자료 배포", "승인", "활성화"]}
                  current={
                    k.state === "PRE_ACTIVE" ? 2 : k.state === "ACTIVE" ? 4 : 0
                  }
                />
                {k.state === "PRE_ACTIVE" ? (
                  <>
                    <h3>신뢰 자료 배포 확인</h3>
                    <p className="muted">
                      외부 배포 완료를 확인한 근거를 요청에 첨부하세요.
                    </p>
                    <div className="readonly-input">
                      요청 시 배포 증적을 입력하세요
                    </div>
                    <Button
                      primary
                      disabled={!canKey}
                      onClick={() => openAction("ACTIVATE", k)}
                    >
                      활성화 요청
                    </Button>
                    <small>다른 보안 담당자의 승인과 MFA가 필요합니다.</small>
                  </>
                ) : (
                  <>
                    <Notice tone={k.state === "COMPROMISED" ? "bad" : "info"}>
                      {k.state === "COMPROMISED"
                        ? "침해된 키는 재개할 수 없습니다. 검증자에서도 신뢰를 제거하세요."
                        : k.state === "PENDING_DELETION"
                          ? `삭제 유예 종료: ${k.deletionDate}. 취소해도 서명은 자동 재개되지 않습니다.`
                          : k.state === "DESTROYED"
                            ? "키 자료는 파기되었으며 공개 이력은 보존됩니다."
                            : "상태 변경은 기존 인증서와 업데이트 검증에 영향을 줄 수 있습니다."}
                    </Notice>
                    <div className="actions">
                      {["ACTIVE", "VERIFY_ONLY"].includes(k.state) && (
                        <Button
                          danger
                          disabled={!canKey}
                          onClick={() => openAction("SUSPEND", k)}
                        >
                          긴급 중지
                        </Button>
                      )}
                      {k.state === "SUSPENDED" && (
                        <Button
                          disabled={!canKey}
                          onClick={() => openAction("RESUME", k)}
                        >
                          재개 요청
                        </Button>
                      )}
                      {k.state === "PENDING_DELETION" && (
                        <>
                          <Button
                            disabled={!canKey}
                            onClick={() => openAction("CANCEL", k)}
                          >
                            삭제 취소
                          </Button>
                          <Button
                            danger
                            disabled={!canKey}
                            onClick={() => openAction("DESTROY", k)}
                          >
                            파기 요청
                          </Button>
                        </>
                      )}
                      {!["COMPROMISED", "DESTROYED"].includes(k.state) && (
                        <Button
                          danger
                          disabled={!canKey}
                          onClick={() => openAction("COMPROMISED", k)}
                        >
                          침해 선언
                        </Button>
                      )}
                    </div>
                  </>
                )}
                <button
                  className="text-link spaced"
                  onClick={() => download(`${k.alias}-v${k.version}-공개자료`)}
                >
                  <Icon name="download" />
                  공개 인증서·키 보기
                </button>
                <div className="panel-note">
                  서명 정책 365일 · 삭제 유예 7일{" "}
                  <button
                    className="text-link"
                    onClick={() => navigate("settings?tab=lifecycle")}
                  >
                    구성 보기
                  </button>
                </div>
              </>
            )}
          </Panel>
        </div>
      </>
    );
  }
  function renderApprovals() {
    const visible = filtered(requests),
      r = visible.find((r) => r.id === requestId) || visible[0];
    return (
      <div className="split queue-split">
        <Panel
          title={`요청 목록 (${requests.filter((r) => r.state === "PENDING").length}건 대기)`}
          action={<Search value={search} onChange={setSearch} />}
        >
          <div className="list">
            {visible.map((item) => (
              <button
                key={item.id}
                className={`list-item ${r?.id === item.id ? "selected" : ""}`}
                onClick={() => setRequestId(item.id)}
              >
                <div>
                  <strong>{item.title}</strong>
                  <Status state={item.state} />
                </div>
                <small>
                  {item.actor} · {item.at}
                </small>
              </button>
            ))}
            {!visible.length && <Empty />}
          </div>
        </Panel>
        {r ? (
          <Panel className="inspector">
            <div className="inspector-title">
              <h2>{r.title}</h2>
              <Status state={r.state} />
            </div>
            <Facts
              rows={[
                ["요청 ID", r.id],
                ["요청자", r.actor],
                ["요청 사유", r.reason],
                ["유효기간", r.expires],
                ["배포·권한 근거", r.evidence],
              ]}
            />
            <h3>승인 후 적용</h3>
            {r.action === "ACTIVATE" ? (
              <div className="change-comparison">
                <div>
                  <small>변경 전</small>
                  <strong>
                    {keys.find((k) => k.id === r.key)?.alias} v{r.before || 2}
                  </strong>
                  <Status state="ACTIVE" />
                </div>
                <Icon name="navigation-right-arrow" />
                <div>
                  <small>승인 후</small>
                  <strong>
                    {keys.find((k) => k.id === r.key)?.alias} v
                    {keys.find((k) => k.id === r.key)?.version}
                  </strong>
                  <Status state="ACTIVE" />
                </div>
              </div>
            ) : (
              <Notice>
                요청에 고정된 {r.action} 작업을 대상 키에 한 번 적용합니다.
              </Notice>
            )}
            <p className="muted">
              {r.action === "ACTIVATE"
                ? "새 버전은 신규 서명에 사용합니다. 기존 버전은 검증 전용으로 유지합니다."
                : "승인 시 요청자의 현재 권한과 대상 키 상태를 다시 확인합니다."}
            </p>
            {r.state === "PENDING" ? (
              <>
                <Notice>요청자와 다른 보안 담당자만 승인할 수 있습니다.</Notice>
                <Button
                  primary
                  disabled={persona !== "officer" || r.actor === identity.actor}
                  onClick={() => openAction("APPROVE", r)}
                >
                  내용 확인 후 승인
                </Button>
                {persona !== "officer" && (
                  <p className="muted">
                    상단 역할을 ‘보안 담당자’로 바꾸면 승인 흐름을 확인할 수
                    있습니다.
                  </p>
                )}
              </>
            ) : (
              <Notice>
                승인 담당자: {r.approver || "security.park"} · 처리 완료
              </Notice>
            )}
            <Button
              onClick={() => {
                setSearch("");
                navigate("audit");
              }}
            >
              감사 기록 확인
            </Button>
          </Panel>
        ) : (
          <Panel>
            <Empty />
          </Panel>
        )}
      </div>
    );
  }
  function renderCertificates(user = false) {
    const rows = filtered(user ? services : [...services, ...agents]);
    const s = rows.find((x) => x.id === serviceId) || rows[0];
    return (
      <div className="split">
        <Panel
          title="발급 인증서"
          action={<Search value={search} onChange={setSearch} />}
        >
          <Table
            columns={[
              ["id", "발급 대상"],
              ["serial", "일련번호"],
              ["state", "상태", (x) => <Status state={x.state} />],
              ["expiry", "만료일"],
            ]}
            rows={rows}
            selected={s?.id}
            onSelect={(x) => setServiceId(x.id)}
          />
        </Panel>
        <Panel className="inspector" title="인증서 상세">
          {s ? (
            <>
              <h2>{s.id}</h2>
              <Facts
                rows={[
                  ["일련번호", s.serial],
                  ["알고리즘", "ECDSA P-256"],
                  ["발급 CA", "ca-root v2"],
                  ["만료일", s.expiry],
                  ["현재 상태", <Status state={s.state} />],
                ]}
              />
              <Notice>
                상태 조회 결과는 최대 60초만 캐시합니다. 조회 실패 시 유효하다고
                판단하지 않습니다.
              </Notice>
              <div className="actions">
                <Button
                  icon="refresh"
                  onClick={() =>
                    notify(
                      "예시 인증서 상태를 다시 확인했습니다. 실제 상태 조회는 수행하지 않습니다.",
                    )
                  }
                >
                  상태 확인
                </Button>
                <Button icon="download" onClick={() => download(s.id)}>
                  공개 인증서
                </Button>
                {user && (
                  <Button
                    primary
                    onClick={() => {
                      setServiceId(s.id);
                      setEnrollStep(0);
                      navigate("enroll");
                    }}
                  >
                    갱신 요청
                  </Button>
                )}
              </div>
            </>
          ) : (
            <Empty />
          )}
        </Panel>
      </div>
    );
  }
  function renderPrincipals() {
    const active = tab || "applications";
    return (
      <>
        <Tabs
          items={[
            ["applications", "애플리케이션"],
            ["agents", "에이전트"],
            ["tokens", "등록 토큰"],
          ]}
          value={active}
          onChange={(v) => navigate(`principals?tab=${v}`)}
        />
        {!canOperate && (
          <Notice>
            관리 작업은 서비스 운영자(OPERATOR) 역할에서 수행합니다.
          </Notice>
        )}
        <Panel
          title={
            active === "tokens"
              ? "등록 토큰"
              : active === "agents"
                ? "등록 에이전트"
                : "등록 애플리케이션"
          }
          action={
            <div className="actions">
              <Search value={search} onChange={setSearch} />
              <Button
                primary
                disabled={!canOperate}
                onClick={() => openAction("TOKEN", null)}
              >
                등록 토큰 생성
              </Button>
            </div>
          }
        >
          {active === "tokens" ? (
            <Table
              columns={[
                ["id", "토큰 참조"],
                ["target", "대상"],
                ["uses", "사용 횟수"],
                ["expiry", "만료"],
                ["state", "상태", (r) => <Status state={r.state} />],
                [
                  "action",
                  "관리",
                  (r) => (
                    <Button
                      disabled={!canOperate || r.state === "REVOKED"}
                      onClick={() => {
                        setTokens((ts) =>
                          ts.map((t) =>
                            t.id === r.id ? { ...t, state: "REVOKED" } : t,
                          ),
                        );
                        notify("등록 토큰 회수를 시뮬레이션했습니다.");
                      }}
                    >
                      회수
                    </Button>
                  ),
                ],
              ]}
              rows={filtered(tokens)}
            />
          ) : (
            <Table
              columns={[
                ["id", "대상 이름"],
                ["type", "유형"],
                ["permission", "권한"],
                ["state", "상태", (r) => <Status state={r.state} />],
                [
                  "action",
                  "관리",
                  (r) =>
                    active === "agents" ? (
                      <Button
                        onClick={() => {
                          setServiceId(r.id);
                          navigate("certificates");
                        }}
                      >
                        인증서 보기
                      </Button>
                    ) : (
                      <Button
                        disabled={!canOperate}
                        onClick={() =>
                          openAction(
                            r.state === "SUSPENDED" ? "REACTIVATE" : "BLOCK",
                            r,
                          )
                        }
                      >
                        {r.state === "SUSPENDED" ? "재활성화" : "차단"}
                      </Button>
                    ),
                ],
              ]}
              rows={filtered(active === "agents" ? agents : services)}
            />
          )}
        </Panel>
        <Notice tone="warn">
          서비스 차단은 기존 인증서와 미사용 등록 토큰을 회수합니다. 재활성화 후
          새 등록이 필요합니다.
        </Notice>
      </>
    );
  }
  function renderRotation() {
    return (
      <div className="narrow">
        <Panel className="inspector">
          <Steps
            labels={["대상 선택", "버전 준비", "신뢰 자료 배포"]}
            current={rotationStep}
          />
          {rotationStep === 0 ? (
            <form
              onSubmit={(e) => {
                e.preventDefault();
                setRotationStep(1);
              }}
            >
              <h2>교체할 키 선택</h2>
              <Field label="키 별칭">
                <select
                  value={rotationAlias}
                  onChange={(e) => setRotationAlias(e.target.value)}
                >
                  <option>ca-root</option>
                  <option>update-signing</option>
                </select>
              </Field>
              <Field
                label="작업 식별자"
                hint="같은 작업을 재시도할 때 동일한 식별자를 사용합니다."
              >
                <input
                  value={rotationId}
                  pattern="[A-Za-z0-9_-]{8,100}"
                  required
                  onChange={(e) => setRotationId(e.target.value)}
                />
              </Field>
              <Notice>
                준비된 버전은 아직 서명에 사용되지 않습니다. HSM 사용 시 장비에
                키를 먼저 준비해야 합니다.
              </Notice>
              <Button primary disabled={!canKey} type="submit">
                변경 내용 확인
              </Button>
            </form>
          ) : rotationStep === 1 ? (
            <>
              <h2>새 버전 준비 확인</h2>
              <Facts
                rows={[
                  ["대상", rotationAlias],
                  [
                    "알고리즘",
                    rotationAlias === "ca-root" ? "ECDSA P-256" : "Ed25519",
                  ],
                  ["작업 ID", rotationId],
                  ["준비 후 상태", "PRE_ACTIVE · 준비됨"],
                ]}
              />
              <Field
                label="MFA 인증 코드"
                hint="데모 코드 000000 · 실제 코드를 입력하지 마세요."
              >
                <input
                  value={rotationMfa}
                  onChange={(e) => setRotationMfa(e.target.value)}
                  inputMode="numeric"
                  maxLength={6}
                  placeholder="000000"
                />
              </Field>
              {formError && <Notice tone="bad">{formError}</Notice>}
              <div className="actions">
                <Button onClick={() => setRotationStep(0)}>이전</Button>
                <Button
                  primary
                  disabled={!canKey}
                  onClick={() => {
                    if (rotationMfa !== "000000") {
                      setFormError("프로토타입 MFA 코드는 000000입니다.");
                      return;
                    }
                    setFormError("");
                    const existing = keys.find(
                      (k) =>
                        k.alias === rotationAlias && k.state === "PRE_ACTIVE",
                    );
                    if (existing) {
                      setSelected(existing.id);
                    } else {
                      const old = keys.find((k) => k.alias === rotationAlias);
                      const version =
                        Math.max(
                          ...keys
                            .filter((k) => k.alias === rotationAlias)
                            .map((k) => k.version),
                        ) + 1;
                      const next = {
                        ...old,
                        id: `${old.keyId}:${version}`,
                        version,
                        state: "PRE_ACTIVE",
                        current: false,
                        created: TODAY,
                        expiry: "2027.09.24",
                        refs: 0,
                        backup: 0,
                      };
                      setKeys((ks) => [next, ...ks]);
                      setSelected(next.id);
                    }
                    setRotationStep(2);
                    addAudit("ROTATE_PREPARE", rotationAlias);
                  }}
                >
                  버전 준비 시뮬레이션
                </Button>
              </div>
            </>
          ) : (
            <>
              <Status state="SUCCESS" label="버전 준비 완료" />
              <h2>신뢰 자료를 먼저 배포하세요</h2>
              <p>
                검증 대상에 신구 공개 자료를 배포하고 설치·reload 결과를 확인한
                후 활성화를 요청합니다.
              </p>
              <Notice>
                외부 배포를 자동 수행하거나 완료 여부를 자동 감지하지 않습니다.
              </Notice>
              <div className="actions">
                <Button onClick={() => navigate("trust")}>
                  신뢰 자료 보기
                </Button>
                <Button primary onClick={() => navigate("keys")}>
                  키 상세로 이동
                </Button>
              </div>
            </>
          )}
        </Panel>
      </div>
    );
  }

  function renderSettings() {
    const current = tab || "protection";
    return (
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
            현재 값은 서버 구성에서 관리됩니다. 변경은 운영 절차에 따라
            적용하세요.
          </Notice>
          {current === "protection" ? (
            <>
              <h2>키 보호 구성</h2>
              <Facts
                rows={[
                  ["CA 서명 키", "암호화 소프트웨어 · ECDSA P-256"],
                  ["업데이트 서명 키", "암호화 소프트웨어 · Ed25519"],
                  ["마스터 키", "외부 보호 파일 · 값 비공개"],
                  ["키 저장 보호", "AES-256-GCM · 문맥 결합"],
                  ["환경 / 테넌트", "production / default · 최초 생성 후 고정"],
                  ["쓰기 구성", "단일 writer"],
                ]}
              />
              <h3>구성 적용 절차</h3>
              <Steps
                labels={["설정 변경", "서버 재시작", "상태 확인"]}
                current={0}
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
                  ["서명 유효기간", "365일"],
                  ["인증서 유효기간", "30일"],
                  ["삭제 유예기간", "최소 7일"],
                  ["승인 요청 유효기간", "24시간"],
                  ["서명 시도 한도", "키 ID당 분당 600회 · 프로세스 내"],
                  ["인증서 상태 캐시", "최대 60초"],
                ]}
              />
              <h3>삭제 절차</h3>
              <Steps
                labels={[
                  "의존성 해소",
                  "삭제 예약 승인",
                  "7일 이상 대기",
                  "최종 파기 승인",
                ]}
                current={0}
              />
              <Notice>
                삭제 취소 후 키는 중지 상태가 됩니다. 사용 재개에는 별도 승인이
                필요합니다.
              </Notice>
            </>
          ) : current === "identity" ? (
            <>
              <h2>관리자 역할과 인증</h2>
              <Table
                columns={[
                  ["id", "개인 ID"],
                  ["role", "역할"],
                  ["scope", "키 범위"],
                  ["mfa", "변경 작업 인증"],
                ]}
                rows={[
                  {
                    id: "security.kim",
                    role: "KEY_ADMIN",
                    scope: "ca-root, update-signing",
                    mfa: "TOTP 필수",
                  },
                  {
                    id: "security.park",
                    role: "SECURITY_OFFICER",
                    scope: "ca-root, update-signing",
                    mfa: "TOTP 필수",
                  },
                  {
                    id: "audit.choi",
                    role: "AUDITOR",
                    scope: "허용된 키 / 전역 감사",
                    mfa: "조회 전용",
                  },
                ]}
              />
              <Facts
                rows={[
                  ["계정 관리", "외부 관리자 파일 · 요청 시 다시 읽음"],
                  ["TOTP", "6자리 · 30초 · 같은 코드 재사용 불가"],
                  ["승인 분리", "요청자와 승인자는 다른 개인"],
                  ["웹 로그인 / SSO", "브라우저 인증 계층 추가 개발 필요"],
                ]}
              />
              <Notice>
                비밀 토큰·MFA secret·복구 코드는 화면에 표시하지 않습니다.
                사용자 추가·역할 편집 UI는 현재 제공하지 않습니다.
              </Notice>
            </>
          ) : current === "backup" ? (
            <>
              <h2>오프라인 보호 백업·복구</h2>
              <Notice tone="warn">
                서버를 중지한 상태에서 운영자가 CLI로 수행합니다.
              </Notice>
              <Steps
                labels={[
                  "서비스 중지",
                  "암호화 백업",
                  "분리 보관",
                  "복구 훈련",
                ]}
                current={0}
              />
              <Facts
                rows={[
                  ["백업 범위", "data-dir 전체 스냅샷 · 최대 512 MiB"],
                  [
                    "별도 보관",
                    "wrapping key / 백업 키 / 최신 checkpoint / 관리자 정책",
                  ],
                  ["복원 대상", "원래 절대 경로의 빈 data-dir"],
                  ["복원 검증", "최신 외부 checkpoint 일치 필요"],
                  ["과거 스냅샷 재조정", "자동 지원하지 않음"],
                ]}
              />
              <h3>복구 전 확인</h3>
              <ul className="check-list">
                <li>독립 보관한 최신 checkpoint와 백업의 상태 일치 확인</li>
                <li>개인키 보호 매체와 관리자 설정을 별도 확보</li>
                <li>복구 후 침해·삭제·권한 회수 상태 대조</li>
              </ul>
              <Button
                icon="download"
                onClick={() => download("백업-복구-체크리스트")}
              >
                절차 안내 다운로드
              </Button>
            </>
          ) : (
            <>
              <h2>HSM과 외부 연동</h2>
              <Facts
                rows={[
                  ["PKCS#11", "선택 구성 · CA P-256만 지원"],
                  ["업데이트 서명", "Ed25519 · 소프트웨어 backend"],
                  ["HSM 전환", "장비별 alias provisioning과 검증 필요"],
                  ["SIEM / WORM", "외부 수집·보존 연동 필요"],
                  ["CRL / OCSP", "미구현"],
                  ["다중 노드 HA", "미지원"],
                ]}
              />
              <Notice>
                HSM 장애 시 소프트웨어 키로 자동 우회하지 않습니다. 토큰 PIN,
                비반출 정책과 물리 파기는 장비 절차로 관리합니다.
              </Notice>
              <Button onClick={() => navigate("health")}>운영 상태 확인</Button>
            </>
          )}
        </Panel>
        <aside className="guidance">
          <h3>적용 전 확인</h3>
          <p>정책·저장소 구성을 변경할 때는 재시작과 상태 검증이 필요합니다.</p>
          <hr />
          <h3>권한 회수</h3>
          <p>
            관리자 파일은 요청마다 다시 읽습니다. 파일 변경 권한은 서버
            운영자에게 있습니다.
          </p>
          <hr />
          <button
            className="text-link"
            onClick={() =>
              setModal({
                info: "설정 조회 화면은 제안된 API가 필요합니다. 값은 문서화된 기본값에 따른 예시이며, 실제 서버에서 읽어온 값이 아닙니다.",
              })
            }
          >
            설계 범위 확인
          </button>
        </aside>
      </div>
    );
  }

  function renderAudit(user = false) {
    const rows = filtered(
      user
        ? audit
            .filter((a) => a.actor === identity.actor || a.target === serviceId)
            .concat([
              {
                seq: 1200,
                at: "2026.09.23 14:22:00",
                actor: "dev.lee",
                operation: "CERTIFICATE_ISSUED",
                target: "payment-api",
                result: "SUCCESS",
                request: "CSR-2042",
              },
            ])
        : audit,
    )
      .filter((r) => auditResult === "all" || r.result === auditResult)
      .map((r) => ({ ...r, id: String(r.seq) }));
    return (
      <>
        <Panel
          title={user ? "내 서비스 이력" : "감사 이벤트"}
          action={
            <div className="actions">
              <Search value={search} onChange={setSearch} />
              <select
                aria-label="감사 결과 필터"
                value={auditResult}
                onChange={(e) => setAuditResult(e.target.value)}
              >
                <option value="all">전체 결과</option>
                <option value="SUCCESS">성공</option>
                <option value="DENIED">거부</option>
              </select>
              <Button
                icon="download"
                onClick={() =>
                  downloadText(
                    "audit-demo.csv",
                    "sequence,time,actor,operation,target,result\n" +
                      rows
                        .map((r) =>
                          [
                            r.seq,
                            r.at,
                            r.actor,
                            r.operation,
                            r.target,
                            r.result,
                          ].join(","),
                        )
                        .join("\n"),
                  )
                }
              >
                예시 내보내기
              </Button>
            </div>
          }
        >
          <Table
            columns={[
              ["seq", "순번"],
              ["at", "발생 시각"],
              ["actor", "수행자"],
              ["operation", "작업"],
              ["target", "대상"],
              ["result", "결과", (r) => <Status state={r.result} />],
            ]}
            rows={rows}
            onSelect={(r) => setModal({ audit: r })}
          />
          <div className="panel-bottom">
            <span>{rows.length}건 · 예시 범위</span>
            <Button
              onClick={() =>
                notify(
                  "마지막 예시 이벤트입니다. 운영 화면은 sequence를 기준으로 추가 조회합니다.",
                )
              }
            >
              이전 기록
            </Button>
          </div>
        </Panel>
        <Notice>
          개인키·인증 토큰·MFA 코드·서명 원문은 기록하지 않습니다. 감사 무결성은
          운영 상태에서 확인합니다.
        </Notice>
      </>
    );
  }

  function renderServices() {
    const visible = filtered(services),
      s = visible.find((s) => s.id === serviceId) || visible[0];
    return (
      <div className="split service-split">
        <Panel
          title="서비스 목록"
          action={
            <Search
              value={search}
              onChange={setSearch}
              placeholder="서비스 이름으로 검색"
            />
          }
        >
          <Table
            columns={[
              ["id", "서비스 이름"],
              [
                "state",
                "상태",
                (x) => (
                  <Status
                    state={x.state}
                    label={x.state === "ACTIVE" ? "연결됨" : undefined}
                  />
                ),
              ],
              [
                "expiry",
                "인증서 만료일",
                (x) => (
                  <>
                    {x.expiry}
                    <small className="inline"> ({x.days}일 남음)</small>
                  </>
                ),
              ],
            ]}
            rows={visible}
            selected={s?.id}
            onSelect={(s) => setServiceId(s.id)}
          />
          <div className="panel-bottom">
            <span>접근 가능한 서비스 {visible.length}개</span>
            <Button
              onClick={() => {
                setEnrollStep(0);
                navigate("enroll");
              }}
            >
              인증서 요청
            </Button>
          </div>
        </Panel>
        <Panel className="inspector">
          {s ? (
            <>
              <div className="inspector-title">
                <h2>{s.id}</h2>
                <Status
                  state={s.state}
                  label={s.state === "ACTIVE" ? "연결됨" : undefined}
                />
              </div>
              <Facts
                rows={[
                  ["서비스 식별자", `app:${s.id}`],
                  ["인증서 상태", `${s.expiry} 만료 · ${s.days}일 남음`],
                ]}
              />
              <div className="actions">
                <Button
                  primary
                  onClick={() => {
                    setServiceId(s.id);
                    setEnrollStep(0);
                    navigate("enroll");
                  }}
                >
                  갱신 요청
                </Button>
                <Button icon="download" onClick={() => download(s.id)}>
                  공개 인증서 다운로드
                </Button>
              </div>
              <Notice>
                인증서는 CSR로 발급합니다.
                <br />
                개인키는 서비스 환경에 보관하세요.
              </Notice>
              <h3>인증서 갱신 절차</h3>
              <Steps
                labels={["CSR 제출", "검토", "인증서 수령"]}
                current={-1}
              />
              <h3>최근 처리</h3>
              <div className="resource-row">
                <small>2026.09.23 14:22</small>
                <span>인증서 발급 완료</span>
              </div>
              <button
                className="text-link"
                onClick={() => navigate("my-requests")}
              >
                요청 이력 보기 <Icon name="slim-arrow-right" />
              </button>
            </>
          ) : (
            <Empty />
          )}
        </Panel>
      </div>
    );
  }
  function renderEnroll() {
    return (
      <div className="split form-split">
        <Panel className="inspector">
          <Steps
            labels={["CSR 등록", "내용 확인", "요청 완료"]}
            current={enrollStep}
          />
          {enrollStep === 0 ? (
            <form
              onSubmit={(e) => {
                e.preventDefault();
                if (csr.includes("PRIVATE KEY")) {
                  setFormError(
                    "개인키는 업로드할 수 없습니다. CSR만 제출하세요.",
                  );
                  return;
                }
                if (!csr.includes("BEGIN CERTIFICATE REQUEST")) {
                  setFormError(
                    "CSR 예시를 불러오거나 PEM 형식의 CSR을 입력하세요.",
                  );
                  return;
                }
                setFormError("");
                setEnrollStep(1);
              }}
            >
              <h2>CSR 등록</h2>
              <Field label="서비스">
                <select
                  value={serviceId}
                  onChange={(e) => setServiceId(e.target.value)}
                >
                  {services.map((s) => (
                    <option key={s.id}>{s.id}</option>
                  ))}
                </select>
              </Field>
              <Field
                label="CSR 파일"
                hint=".csr 또는 .pem · 개인키 파일은 업로드하지 마세요."
              >
                <input
                  type="file"
                  accept=".csr,.pem"
                  onChange={async (e) => {
                    const f = e.target.files?.[0];
                    if (!f) return;
                    if (f.size > 65536) {
                      setFormError("64 KiB 이하의 CSR 파일을 선택하세요.");
                      return;
                    }
                    const text = await f.text();
                    if (text.includes("PRIVATE KEY")) {
                      setFormError(
                        "개인키 파일은 처리하지 않습니다. CSR 파일을 선택하세요.",
                      );
                      setCsr("");
                      setCsrFile("");
                      return;
                    }
                    setCsr(text);
                    setCsrFile(f.name);
                  }}
                />
              </Field>
              <Field label="CSR 내용">
                <textarea
                  rows={6}
                  value={csr}
                  onChange={(e) => setCsr(e.target.value)}
                  placeholder="-----BEGIN CERTIFICATE REQUEST-----"
                />
              </Field>
              <div className="actions">
                <Button
                  type="button"
                  onClick={() => {
                    setCsr(demoCSR);
                    setCsrFile("payment-api-demo.csr");
                    setFormError("");
                  }}
                >
                  예시 CSR 사용
                </Button>
                <Button primary type="submit">
                  다음: 내용 확인
                </Button>
              </div>
              {formError && <Notice tone="bad">{formError}</Notice>}
            </form>
          ) : enrollStep === 1 ? (
            <>
              <h2>요청 내용을 확인하세요</h2>
              <Facts
                rows={[
                  ["서비스", serviceId],
                  ["요청 유형", "인증서 갱신"],
                  ["CSR 파일", csrFile || "직접 입력"],
                  ["요청 알고리즘", "ECDSA P-256 · 예시"],
                  ["유효기간", "발급 시점부터 30일"],
                ]}
              />
              <Notice>
                이 프로토타입은 CSR의 서명을 검증하지 않습니다. 실제 서버에서는
                서명과 키 알고리즘을 검증합니다.
              </Notice>
              <div className="actions">
                <Button onClick={() => setEnrollStep(0)}>이전</Button>
                <Button
                  primary
                  onClick={() => {
                    setUserRequests((rs) => [
                      {
                        id: `CSR-${2043 + rs.length}`,
                        service: serviceId,
                        type: "인증서 갱신",
                        state: "PENDING",
                        at: `${TODAY} 19:30`,
                      },
                      ...rs,
                    ]);
                    setEnrollStep(2);
                    addAudit("CSR_REQUEST", serviceId);
                  }}
                >
                  갱신 요청 보내기
                </Button>
              </div>
            </>
          ) : (
            <>
              <Status state="SUCCESS" label="요청을 등록했습니다" />
              <h2>검토 후 인증서를 받을 수 있습니다</h2>
              <p>
                {serviceId}의 갱신 요청이 등록되었습니다. 진행 상황은 내
                요청에서 확인하세요.
              </p>
              <Button primary onClick={() => navigate("my-requests")}>
                내 요청 보기
              </Button>
            </>
          )}
        </Panel>
        <aside className="guidance">
          <h3>서비스에서 개인키 생성</h3>
          <p>
            서비스 환경에서 키 쌍과 CSR을 생성하세요. KMS에는 CSR과 공개 자료만
            전달합니다.
          </p>
          <hr />
          <h3>인증서 적용</h3>
          <p>
            발급된 인증서를 내려받아 서비스에 적용하고, 연결 상태를 확인하세요.
          </p>
          <button className="text-link" onClick={() => navigate("guide")}>
            연결 가이드 보기
          </button>
        </aside>
      </div>
    );
  }

  function renderContent() {
    if (
      isUser &&
      ![
        "services",
        "my-certificates",
        "my-requests",
        "enroll",
        "guide",
        "my-activity",
        "account",
      ].includes(page)
    )
      return (
        <Panel>
          <Empty
            title="관리자 역할이 필요한 화면입니다"
            body="내 서비스로 돌아가거나 미리보기 역할을 변경하세요."
            action={
              <Button onClick={() => navigate("services")}>내 서비스로</Button>
            }
          />
        </Panel>
      );
    if (page === "keys") return renderKeys();
    if (page === "rotation") return renderRotation();
    if (page === "approvals") return renderApprovals();
    if (page === "certificates") return renderCertificates();
    if (page === "principals") return renderPrincipals();
    if (page === "settings") return renderSettings();
    if (page === "audit") return renderAudit();
    if (page === "services") return renderServices();
    if (page === "my-certificates") return renderCertificates(true);
    if (page === "enroll") return renderEnroll();
    if (page === "my-activity") return renderAudit(true);
    if (page === "tls")
      return (
        <div className="split">
          <Panel title="현재 서버 인증서" className="inspector">
            <Status state="ACTIVE" label="유효한 인증서" />
            <h2>kms.internal.example</h2>
            <Facts
              rows={[
                ["TLS 버전", "v5"],
                ["발급 CA", "ca-root v2"],
                ["알고리즘", "ECDSA P-256"],
                ["만료일", "2026.10.08"],
                ["개인키 보호", "암호화 PKCS#8 / PKCS12"],
                ["키 지문", "SHA-256 · 예시 식별자"],
              ]}
            />
            <Button
              icon="download"
              onClick={() => download("tls-public-certificate")}
            >
              공개 인증서 다운로드
            </Button>
          </Panel>
          <Panel title="TLS 이력">
            <Table
              columns={[
                ["id", "버전"],
                ["issuer", "발급 CA"],
                ["state", "상태", (r) => <Status state={r.state} />],
                ["expiry", "만료일"],
              ]}
              rows={[
                {
                  id: "v5",
                  issuer: "ca-root v2",
                  state: "ACTIVE",
                  expiry: "2026.10.08",
                },
                {
                  id: "v4",
                  issuer: "ca-root v2",
                  state: "RETIRED",
                  expiry: "2026.09.08",
                },
              ]}
            />
            <Notice>
              CA 활성화와 TLS 파일 갱신은 별도 단계입니다. 갱신 실패 시 실제 키
              상태와 파일 상태를 함께 확인합니다.
            </Notice>
          </Panel>
        </div>
      );
    if (page === "trust" || page === "guide")
      return (
        <div className="split form-split">
          <Panel className="inspector">
            <h2>
              {page === "guide" ? "서비스 연결 가이드" : "공개 자료 다운로드"}
            </h2>
            {page === "guide" && (
              <Steps
                labels={["CSR 준비", "인증서 발급", "신뢰 자료 적용"]}
                current={2}
              />
            )}
            <p className="muted">
              준비·사용 중·검증 전용 버전만 신뢰 묶음에 포함합니다.
            </p>
            {[
              [
                "CA 신뢰 번들",
                "ca-trust-bundle.pem",
                keys
                  .filter(
                    (k) =>
                      k.alias === "ca-root" &&
                      ["PRE_ACTIVE", "ACTIVE", "VERIFY_ONLY"].includes(k.state),
                  )
                  .map((k) => `v${k.version}`)
                  .join(" · "),
              ],
              [
                "업데이트 검증 키",
                "update-trust-bundle.pem",
                keys
                  .filter(
                    (k) =>
                      k.alias === "update-signing" &&
                      ["PRE_ACTIVE", "ACTIVE", "VERIFY_ONLY"].includes(k.state),
                  )
                  .map((k) => `v${k.version}`)
                  .join(" · "),
              ],
            ].map(([title, file, versions]) => (
              <div className="resource-row" key={title}>
                <Icon name="document" />
                <div>
                  <strong>{title}</strong>
                  <small>{file}</small>
                  <small>포함 버전: {versions}</small>
                </div>
                <Button icon="download" onClick={() => download(title)}>
                  다운로드
                </Button>
              </div>
            ))}
            <Notice>
              적용 전 지문을 별도 경로로 확인하세요. 검증 대상의 신뢰 파일
              설치와 reload는 담당자가 수행합니다.
            </Notice>
            <Button
              onClick={() => navigate(page === "guide" ? "enroll" : "keys")}
            >
              {page === "guide" ? "인증서 요청하기" : "키 관리로 이동"}
            </Button>
          </Panel>
          <aside className="guidance">
            <h3>신뢰 자료 적용 순서</h3>
            <ol>
              <li>운영 담당자가 제공한 지문과 대조</li>
              <li>신구 버전 공개 자료를 검증자에 설치</li>
              <li>서비스 reload와 검증 결과 확인</li>
              <li>관리자에게 배포 증적 전달</li>
            </ol>
            <hr />
            <h3>주의 사항</h3>
            <p>
              서버 응답에 포함됐다는 이유만으로 공개키를 자동 신뢰하지 않습니다.
              침해·파기 버전은 배포하지 않습니다.
            </p>
            <button
              className="text-link"
              onClick={() => download("서비스-연결-가이드")}
            >
              연결 가이드 다운로드
            </button>
          </aside>
        </div>
      );
    if (page === "releases")
      return (
        <>
          <Tabs
            items={[
              ["list", "릴리스"],
              ["policy", "배포 정책"],
            ]}
            value={tab || "list"}
            onChange={(v) => navigate(`releases?tab=${v}`)}
          />
          {tab === "policy" ? (
            <Panel className="inspector narrow">
              <h2>기본 배포 정책</h2>
              <Field label="업데이트 배포">
                <select
                  disabled={!canOperate}
                  value={String(policy.enabled)}
                  onChange={(e) =>
                    setPolicy({ ...policy, enabled: e.target.value === "true" })
                  }
                >
                  <option value="true">활성화</option>
                  <option value="false">중지</option>
                </select>
              </Field>
              <Field label="채널">
                <select
                  disabled={!canOperate}
                  value={policy.channel}
                  onChange={(e) =>
                    setPolicy({ ...policy, channel: e.target.value })
                  }
                >
                  <option>stable</option>
                  <option>preview</option>
                </select>
              </Field>
              <Notice>
                키가 중지·침해 상태이면 정책과 관계없이 해당 서명 릴리스의
                제공이 차단됩니다.
              </Notice>
              <Button
                primary
                disabled={!canOperate}
                onClick={() =>
                  notify("정책 변경을 이 프로토타입에만 적용했습니다.")
                }
              >
                예시 정책 적용
              </Button>
            </Panel>
          ) : (
            <Panel
              title="서명된 릴리스"
              action={
                <Button
                  primary
                  disabled={!canOperate}
                  onClick={() => openAction("UPLOAD", null)}
                >
                  릴리스 등록
                </Button>
              }
            >
              <Table
                columns={[
                  ["version", "버전"],
                  ["target", "대상"],
                  ["key", "서명 키"],
                  ["state", "배포 상태", (r) => <Status state={r.state} />],
                  [
                    "action",
                    "관리",
                    (r) => (
                      <Button
                        disabled={!canOperate || r.state === "REVOKED"}
                        danger={r.state === "ACTIVE"}
                        onClick={() =>
                          openAction(
                            r.state === "DRAFT" ? "PUBLISH" : "REVOKE_RELEASE",
                            r,
                          )
                        }
                      >
                        {r.state === "DRAFT"
                          ? "활성화"
                          : r.state === "REVOKED"
                            ? "폐기 완료"
                            : "폐기"}
                      </Button>
                    ),
                  ],
                ]}
                rows={releases}
              />
              <Notice>
                폐기한 릴리스는 재활성화하지 않습니다. 새 릴리스로 등록하세요.
              </Notice>
            </Panel>
          )}
        </>
      );
    if (page === "health")
      return (
        <>
          <div className="health-head">
            <Status state="SUCCESS" label="저장소 검사 통과 · 예시" />
            <span>확인 시각 2026.09.24 19:24 KST</span>
            <Button
              icon="refresh"
              onClick={() => notify("운영 상태 예시를 새로 표시했습니다.")}
            >
              다시 확인
            </Button>
          </div>
          <div className="split">
            <Panel title="현재 키 상태">
              <Facts
                rows={[
                  [
                    "사용 불가 현재 키",
                    `${keys.filter((k) => k.current && ["SUSPENDED", "COMPROMISED"].includes(k.state)).length}개`,
                  ],
                  ["30일 내 서명 기한 도래", "1개 · ca-root"],
                  ["감사 이벤트", `${audit.length}개 · 화면 예시 범위`],
                  ["서명 backend", "ENCRYPTED_SOFTWARE"],
                  ["쓰기 모드", "단일 writer"],
                ]}
              />
              <Button onClick={() => navigate("keys")}>키 상태 확인</Button>
            </Panel>
            <Panel title="무결성·장애 대응">
              <Facts
                rows={[
                  ["감사 체인", "HMAC 체인 검사 통과 · 예시"],
                  ["상태 MAC", "검사 통과 · 예시"],
                  ["외부 checkpoint", "일치 · 예시"],
                ]}
              />
              <Notice tone="warn">
                무결성 실패 시 키 사용을 중지합니다. checkpoint를 과거 값으로
                변경해 재개하지 마세요.
              </Notice>
              <Button onClick={() => navigate("settings?tab=backup")}>
                복구 절차 확인
              </Button>
            </Panel>
          </div>
        </>
      );
    if (page === "my-requests")
      return (
        <Panel
          title="내 요청"
          action={
            <Button
              primary
              onClick={() => {
                setEnrollStep(0);
                navigate("enroll");
              }}
            >
              새 인증서 요청
            </Button>
          }
        >
          <Table
            columns={[
              ["id", "요청 번호"],
              ["service", "서비스"],
              ["type", "업무"],
              ["at", "등록 시각"],
              ["state", "상태", (r) => <Status state={r.state} />],
            ]}
            rows={userRequests}
            onSelect={(r) => setModal({ userRequest: r })}
          />
        </Panel>
      );
    if (page === "account")
      return (
        <div className="split">
          <Panel className="inspector" title="내 계정">
            <h2>dev.lee</h2>
            <Facts
              rows={[
                ["역할", "서비스 담당자"],
                ["접근 범위", "내 서비스에 한정"],
                ["환경", "production · default"],
                ["계정 관리", "조직 계정 연동 필요"],
              ]}
            />
            <Notice>
              다른 사용자의 서비스, 관리자 키, 비밀 자료에는 접근할 수 없습니다.
            </Notice>
            <Button onClick={() => navigate("login")}>데모 나가기</Button>
          </Panel>
          <Panel title="접근 가능한 서비스">
            <Table
              columns={[
                ["id", "서비스"],
                ["permission", "업무 권한"],
              ]}
              rows={services}
            />
          </Panel>
        </div>
      );
    return (
      <Empty
        title="화면을 찾을 수 없습니다"
        action={
          <Button onClick={() => navigate(isUser ? "services" : "keys")}>
            시작 화면으로
          </Button>
        }
      />
    );
  }

  function renderModal() {
    if (!modal) return null;
    if (modal.catalog)
      return (
        <Modal title="전체 화면 목록" wide onClose={() => setModal(null)}>
          <p className="muted">
            관리자 20개 · 사용자 7개 · 공통 진입 화면. 상세 흐름은 각 화면의
            버튼으로 확인합니다.
          </p>
          <div className="catalog">
            {screens.map(([id, path, name, area, coverage]) => (
              <button
                key={id}
                onClick={() => {
                  setPersona(area === "사용자" ? "user" : "admin");
                  navigate(path);
                  setModal(null);
                }}
              >
                <small>
                  {id} · {area}
                </small>
                <strong>{name}</strong>
                <span>{coverage}</span>
              </button>
            ))}
          </div>
        </Modal>
      );
    if (modal.info)
      return (
        <Modal title="화면 설계 안내" onClose={() => setModal(null)}>
          <p>{modal.info}</p>
          <Notice>
            모든 데이터와 변경은 브라우저 메모리에만 존재합니다. 새로고침하면
            초기화됩니다. 실제 키·인증서·서버 설정은 변경하지 않습니다.
          </Notice>
          <Button onClick={() => setModal(null)}>확인</Button>
        </Modal>
      );
    if (modal.audit)
      return (
        <Modal title="감사 이벤트 상세" onClose={() => setModal(null)}>
          <Facts
            rows={Object.entries(modal.audit)
              .filter(([k]) => k !== "id")
              .map(([k, v]) => [k, String(v)])}
          />
          <Notice>
            동일 요청 ID의 시작·결과 이벤트를 연결합니다. 예시 데이터에는
            비밀값을 포함하지 않습니다.
          </Notice>
        </Modal>
      );
    if (modal.userRequest)
      return (
        <Modal
          title={`${modal.userRequest.id} · 요청 상세`}
          onClose={() => setModal(null)}
        >
          <Facts
            rows={[
              ["서비스", modal.userRequest.service],
              ["업무", modal.userRequest.type],
              ["상태", <Status state={modal.userRequest.state} />],
            ]}
          />
          <Steps
            labels={["요청 접수", "검토", "인증서 수령"]}
            current={modal.userRequest.state === "APPROVED" ? 3 : 1}
          />
          {modal.userRequest.state === "APPROVED" ? (
            <Button primary onClick={() => download(modal.userRequest.service)}>
              공개 인증서 다운로드
            </Button>
          ) : (
            <Notice>
              검토를 기다리고 있습니다. 완료 전까지 기존 인증서의 만료일을
              확인하세요.
            </Notice>
          )}
        </Modal>
      );
    const { action, target } = modal,
      danger = [
        "DESTROY",
        "SCHEDULE_DELETION",
        "COMPROMISED",
        "BLOCK",
        "REVOKE_RELEASE",
      ].includes(action);
    return (
      <Modal title={actionTitles[action]} onClose={() => setModal(null)}>
        <form onSubmit={submitAction}>
          <p className="muted">
            {target?.title || target?.alias || target?.id || "신규 등록"}{" "}
            {target?.version ? `· v${target.version}` : ""}
          </p>
          <Notice tone={danger ? "warn" : "info"}>
            {action === "APPROVE"
              ? "승인하면 요청에 고정된 변경이 한 번 실행됩니다."
              : action === "COMPROMISED"
                ? "침해 선언 후 이 키는 다시 사용하도록 재개할 수 없습니다. 외부 검증자에서도 신뢰를 제거해야 합니다."
                : action === "SCHEDULE_DELETION"
                  ? "현재 버전과 살아 있는 의존성이 없는 키만 예약할 수 있습니다. 최종 파기는 7일 이후 별도 승인이 필요합니다."
                  : action === "BLOCK"
                    ? "기존 인증서와 미사용 등록 토큰이 회수됩니다. 다시 사용하려면 새 등록이 필요합니다."
                    : action === "DESTROY"
                      ? "파기 후 되돌릴 수 없습니다. 유예기간과 의존성을 다시 확인합니다. HSM 물리 파기는 장비 절차로 수행합니다."
                      : "작업 내용과 영향을 확인하세요. 실제 서버에는 전송하지 않습니다."}
          </Notice>
          {action === "TOKEN" && (
            <Field label="등록 대상">
              <input name="target" placeholder="collector-seoul-02" required />
            </Field>
          )}
          {action === "UPLOAD" && (
            <>
              <Field label="릴리스 버전">
                <input
                  name="version"
                  placeholder="0.8.5"
                  pattern="[0-9]+\.[0-9]+\.[0-9]+"
                  required
                />
              </Field>
              <Field label="배포 파일">
                <input type="file" required />
              </Field>
            </>
          )}
          {["GRANT", "REVOKE"].includes(action) && (
            <Field label="서비스 주체">
              <select name="evidence" defaultValue={modal.subject || undefined}>
                {(target.alias === "ca-root"
                  ? ["service:certificate-issuer", "service:tls"]
                  : ["service:update-signer"]
                ).map((s) => (
                  <option key={s}>{s}</option>
                ))}
              </select>
              <small>허용 연산: SIGN</small>
            </Field>
          )}
          {action === "ACTIVATE" && (
            <Field label="신뢰 자료 배포 증적">
              <input name="evidence" required defaultValue="CHG-2026-0924" />
            </Field>
          )}
          {action !== "APPROVE" && (
            <Field label="작업 사유">
              <textarea
                name="reason"
                rows={3}
                required
                minLength={5}
                placeholder="변경 이유와 영향 확인 내용을 입력하세요."
              />
            </Field>
          )}
          <Field
            label="MFA 인증 코드"
            hint="데모 코드 000000을 입력하세요. 실제 코드는 사용하지 않습니다."
          >
            <input
              name="mfa"
              inputMode="numeric"
              autoComplete="off"
              maxLength={6}
              pattern="[0-9]{6}"
              required
              placeholder="000000"
            />
          </Field>
          <label className="checkbox">
            <input type="checkbox" required />
            작업의 영향과 대상 정보를 확인했습니다.
          </label>
          {formError && <Notice tone="bad">{formError}</Notice>}
          <div className="dialog-actions">
            <Button type="button" onClick={() => setModal(null)}>
              취소
            </Button>
            <Button type="submit" primary={!danger} danger={danger}>
              {action === "APPROVE" ? "승인 시뮬레이션" : actionTitles[action]}
            </Button>
          </div>
        </form>
      </Modal>
    );
  }
  const isLogin = page === "login",
    title = titles[page] || ["화면 설계", ""];
  return (
    <>
      <div className="prototype-bar">
        <span>
          <strong>DESIGN PREVIEW</strong>
          <span className="bar-description">
            {" "}
            예시 데이터 · 운영 서버 미연결
          </span>
        </span>
        <div className="preview-controls">
          <select
            aria-label="미리보기 역할"
            value={persona}
            onChange={(e) => changePersona(e.target.value)}
          >
            {Object.entries(roles).map(([id, r]) => (
              <option key={id} value={id}>
                {r.name}
              </option>
            ))}
          </select>
          <select
            aria-label="화면 상태"
            value={visualState}
            onChange={(e) => setVisualState(e.target.value)}
          >
            <option value="normal">정상 상태</option>
            <option value="loading">불러오는 중</option>
            <option value="empty">데이터 없음</option>
            <option value="error">연결 오류</option>
            <option value="denied">권한 없음</option>
          </select>
          <button onClick={() => setModal({ catalog: true })}>화면 목록</button>
          <button
            onClick={() =>
              setModal({
                info: "SAP Fundamental Styles를 적용한 무채색 다크 화면 설계입니다. 관리자 핵심 API는 구현되어 있으나 웹 인증, 일반 사용자 포털, 구성 조회 API는 추가 개발이 필요합니다.",
              })
            }
          >
            설계 안내
          </button>
        </div>
      </div>
      {isLogin ? (
        <main className="login">
          <div className="wordmark">
            CastrelSign <span>KMS</span>
          </div>
          <h1>키와 신뢰를 관리하는 작업 공간</h1>
          <p>확인할 역할을 선택하면 예시 화면으로 이동합니다.</p>
          <div className="login-roles">
            {Object.entries(roles).map(([id, r]) => (
              <button key={id} onClick={() => changePersona(id)}>
                <Icon name={id === "user" ? "person-placeholder" : "shield"} />
                <strong>{r.name}</strong>
                <small>{r.role}</small>
                <Icon name="navigation-right-arrow" />
              </button>
            ))}
          </div>
          <Notice>
            운영 로그인 화면은 별도 인증 연동이 필요합니다. 이 화면에서는 자격
            증명을 입력받지 않습니다.
          </Notice>
        </main>
      ) : (
        <div className={`app-shell ${isUser ? "user-shell" : ""}`}>
          <header className="shellbar">
            <button
              className="mobile-toggle icon-button"
              aria-label="메뉴 열기"
              onClick={() => setMobileNav(!mobileNav)}
            >
              <Icon name="menu2" />
            </button>
            <button
              className="wordmark"
              onClick={() => navigate(isUser ? "services" : "keys")}
            >
              CastrelSign <span>KMS</span>
            </button>
            <span className="environment">production · default</span>
            {isUser && (
              <nav className="topnav" aria-label="사용자 메뉴">
                {userNav.slice(0, 4).map(([id, label, icon]) => (
                  <button
                    key={id}
                    className={page === id ? "active" : ""}
                    onClick={() => navigate(id)}
                  >
                    <Icon name={icon} />
                    {label}
                  </button>
                ))}
              </nav>
            )}
            <button
              className="identity"
              onClick={() =>
                isUser
                  ? navigate("account")
                  : setModal({
                      info: `${identity.actor} · ${identity.role}. 역할 선택은 설계 확인용입니다. 운영 화면의 권한은 서버에서 검증해야 합니다.`,
                    })
              }
            >
              <Icon name="person-placeholder" />
              <span>{identity.actor}</span>
              <small>{identity.role}</small>
            </button>
          </header>
          {(!isUser || mobileNav) && (
            <nav
              className={`sidebar ${mobileNav ? "open" : ""}`}
              aria-label="주 메뉴"
            >
              {nav.map(([id, label, icon]) => (
                <button
                  key={id}
                  className={page === id ? "active" : ""}
                  onClick={() => navigate(id)}
                >
                  <Icon name={icon} />
                  <span>{label}</span>
                  {id === "approvals" && (
                    <em>
                      {requests.filter((r) => r.state === "PENDING").length}
                    </em>
                  )}
                </button>
              ))}
              <div className="nav-foot">
                <small>CastrelSign KMS</small>
                <button onClick={() => navigate("login")}>
                  <Icon name="log" />
                  데모 나가기
                </button>
              </div>
            </nav>
          )}
          <main id="main" className="content">
            <div className="page-heading">
              <div>
                <div className="breadcrumb">
                  {isUser ? "서비스 워크스페이스" : "관리 콘솔"}{" "}
                  <Icon name="slim-arrow-right" /> {title[0]}
                </div>
                <h1>{title[0]}</h1>
                <p>{title[1]}</p>
              </div>
              {isUser && (
                <button
                  className="text-link"
                  onClick={() => navigate("my-activity")}
                >
                  사용 이력
                </button>
              )}
            </div>
            {visualState === "normal" ? (
              renderContent()
            ) : (
              <Panel>
                {visualState === "loading" ? (
                  <div role="status" className="loading">
                    <span className="loading-line" />
                    <h2>정보를 불러오는 중입니다</h2>
                    <p>
                      실제 운영에서는 마지막 확인 시각과 최신성도 함께
                      표시합니다.
                    </p>
                  </div>
                ) : (
                  <Empty
                    title={
                      visualState === "empty"
                        ? "아직 등록된 항목이 없습니다"
                        : visualState === "error"
                          ? "정보를 불러오지 못했습니다"
                          : "이 화면에 접근할 권한이 없습니다"
                    }
                    body={
                      visualState === "error"
                        ? "연결 상태를 확인하고 다시 시도하세요. 확인하지 못한 상태를 정상으로 표시하지 않습니다."
                        : visualState === "denied"
                          ? "운영 담당자에게 필요한 접근 권한을 요청하세요."
                          : "등록 후 이곳에서 목록과 상태를 확인할 수 있습니다."
                    }
                    action={
                      <Button onClick={() => setVisualState("normal")}>
                        {visualState === "error"
                          ? "다시 시도"
                          : "예시 데이터 보기"}
                      </Button>
                    }
                  />
                )}
              </Panel>
            )}
            <footer className="page-footer">
              <span>예시 기준일 2026.09.24</span>
              <span>
                {isUser
                  ? "셀프서비스 포털 확장 설계"
                  : "개인키와 인증 비밀은 화면에 표시하지 않습니다."}
              </span>
            </footer>
          </main>
        </div>
      )}
      {renderModal()}
      {toast && (
        <div className="toast" role="status">
          <Icon name="accept" />
          {toast}
          <button aria-label="알림 닫기" onClick={() => setToast("")}>
            <Icon name="decline" />
          </button>
        </div>
      )}
    </>
  );
}
