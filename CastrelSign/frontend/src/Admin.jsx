import { useState } from "react";
import { api, segment, keyPath, fmt, csvValue } from "./api";
import { useRuntime, useLoad, Loaded, reasonField } from "./runtime";
import {
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
  Icon,
  downloadText,
} from "./ui";
import { Settings } from "./Settings";
import { Operations } from "./Operations";
const KMS = "/api/admin/kms";
const keyId = (k) => `${k.keyId}:${k.version}`;
const labels = {
  ACTIVATE: "활성화 요청",
  RESUME: "사용 재개 요청",
  GRANT: "권한 추가 요청",
  REVOKE: "권한 회수 요청",
  SCHEDULE_DELETION: "삭제 예약 요청",
  DESTROY: "최종 파기 요청",
  ACK_BACKUP_PURGE: "백업 제거 확인 요청",
};
export function Admin({ page, tab }) {
  if (page === "keys") return <Keys tab={tab} />;
  if (page === "rotation") return <Rotation />;
  if (page === "approvals") return <Approvals />;
  if (page === "audit") return <Audit />;
  if (page === "health") return <Health />;
  if (page === "settings") return <Settings tab={tab} />;
  if (page === "tls") return <Tls />;
  if (page === "trust") return <Trust />;
  if (["certificates", "principals", "releases", "reviews"].includes(page))
    return <Operations page={page} tab={tab} />;
  return <Empty title="화면을 찾을 수 없습니다" />;
}
function Keys({ tab }) {
  const state = useLoad(`${KMS}/keys`),
    [search, setSearch] = useState(""),
    [selected, setSelected] = useState("");
  const { session, navigate } = useRuntime();
  return (
    <Loaded state={state}>
      {(data) => {
        const rows = data
            .map((k) => ({ ...k, id: keyId(k) }))
            .sort(
              (a, b) => a.alias.localeCompare(b.alias) || b.version - a.version,
            )
            .filter((k) =>
              `${k.alias} ${k.version} ${k.state}`
                .toLowerCase()
                .includes(search.toLowerCase()),
            ),
          key = rows.find((k) => k.id === selected) || rows[0];
        const expiring = data.filter(
          (k) =>
            k.state === "ACTIVE" &&
            Date.parse(k.signingExpiresAt) < Date.now() + 30 * 86400000,
        );
        return (
          <>
            {expiring.length > 0 && (
              <Notice tone="warn">
                {expiring.map((k) => `${k.alias} v${k.version}`).join(", ")} ·
                서명 기한이 30일 이내입니다. 교체 준비 상태를 확인하세요.
              </Notice>
            )}
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
                    [
                      "purpose",
                      "용도",
                      (r) =>
                        r.alias === "ca-root" ? "인증서 서명" : "업데이트 서명",
                    ],
                    ["state", "상태", (r) => <Status state={r.state} />],
                  ]}
                  rows={rows}
                  selected={key?.id}
                  onSelect={(k) => setSelected(k.id)}
                />
                <div className="panel-bottom">
                  <span>총 {rows.length}개 버전</span>
                  <Button
                    icon="add"
                    disabled={!session.roles.includes("KEY_ADMIN")}
                    onClick={() => navigate("rotation")}
                  >
                    교체 준비
                  </Button>
                </div>
              </Panel>
              {key ? (
                <KeyDetail key={key.id} item={key} tab={tab} />
              ) : (
                <Panel>
                  <Empty title="선택할 키가 없습니다" />
                </Panel>
              )}
            </div>
          </>
        );
      }}
    </Loaded>
  );
}
function KeyDetail({ item, tab }) {
  const state = useLoad(keyPath(item)),
    { session, open, navigate, notify } = useRuntime(),
    can =
      session.roles.includes("KEY_ADMIN") && session.keys.includes(item.alias);
  const propose = (action, subject) =>
    open({
      title: labels[action],
      danger: ["SCHEDULE_DELETION", "DESTROY"].includes(action),
      description:
        action === "DESTROY"
          ? "최종 파기는 되돌릴 수 없습니다. 유예기간과 백업 제거 증적을 다시 확인합니다."
          : action === "ACTIVATE"
            ? "신뢰 자료를 외부 검증자에 배포하고 확인한 후 요청하세요. 다른 보안 담당자의 승인이 필요합니다."
            : "변경 내용은 요청에 고정되며 다른 보안 담당자가 승인해야 적용됩니다.",
      fields: [
        ...(["GRANT", "REVOKE"].includes(action)
          ? [
              {
                name: "subject",
                label: "서비스 주체",
                type: "select",
                value: subject,
                options:
                  item.alias === "ca-root"
                    ? ["service:certificate-issuer", "service:tls"]
                    : ["service:update-signer"],
              },
            ]
          : []),
        ...(action === "ACTIVATE"
          ? [{ name: "trustAcknowledgement", label: "신뢰 자료 배포 증적" }]
          : []),
        ...(action === "DESTROY"
          ? [{ name: "backupPurgeEvidence", label: "백업 제거 증적" }]
          : []),
        ...(action === "ACK_BACKUP_PURGE"
          ? [
              { name: "backupId", label: "백업 참조", value: subject },
              { name: "evidence", label: "외부 백업 제거 증적" },
            ]
          : []),
        reasonField,
      ],
      run: async (v, totp) => {
        const payload = {};
        for (const field of [
          "subject",
          "trustAcknowledgement",
          "backupPurgeEvidence",
          "backupId",
          "evidence",
        ])
          if (v[field]) payload[field] = v[field];
        if (v.subject) payload.operation = "SIGN";
        await api(`${keyPath(item)}/requests`, {
          method: "POST",
          body: { action, reason: v.reason, payload },
          totp,
        });
        navigate("approvals");
      },
    });
  const suspend = (compromised) =>
    open({
      title: compromised ? "키 침해 선언" : "긴급 사용 중지",
      danger: true,
      description: compromised
        ? "침해된 키는 재개할 수 없습니다. 외부 검증자에서도 신뢰를 제거하세요."
        : "해당 키를 이용한 신규 서명과 인증 검증에 영향을 줄 수 있습니다.",
      fields: [reasonField],
      run: (v, totp) =>
        api(`${keyPath(item)}/suspend`, {
          method: "POST",
          body: { reason: v.reason, compromised },
          totp,
        }),
    });
  return (
    <Loaded state={state}>
      {({ key: k, grants, dependencies, current }) => {
        const live = dependencies.filter(
          (d) => !d.expires_at || Date.parse(d.expires_at) > Date.now(),
        );
        const deletable =
          can &&
          !current &&
          !live.length &&
          ["PRE_ACTIVE", "VERIFY_ONLY", "SUSPENDED", "COMPROMISED"].includes(
            k.state,
          );
        return (
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
              onChange={(v) => navigate(`keys?tab=${v}`)}
            />
            {tab === "grants" ? (
              <>
                <h3>서비스 서명 권한</h3>
                <p className="muted">
                  같은 키 ID의 모든 버전에 적용되는 SIGN 권한입니다.
                </p>
                {grants.map((g) => (
                  <div className="resource-row" key={g.subject}>
                    <div>
                      <strong className="mono">{g.subject}</strong>
                      <small>{g.operation}</small>
                    </div>
                    <Button
                      disabled={!can}
                      onClick={() => propose("REVOKE", g.subject)}
                    >
                      회수 요청
                    </Button>
                  </div>
                ))}
                <Button disabled={!can} onClick={() => propose("GRANT")}>
                  권한 추가 요청
                </Button>
              </>
            ) : tab === "dependencies" ? (
              <>
                <h3>삭제 전 의존성</h3>
                <Facts
                  rows={[
                    ["현재 서명 버전", current ? "예 · 삭제 불가" : "아니요"],
                    ["살아 있는 의존성", `${live.length}개`],
                    ["삭제 예정", fmt(k.deleteAt)],
                  ]}
                />
                {dependencies.map((d, i) => (
                  <div className="resource-row" key={i}>
                    <div>
                      <strong>{d.kind}</strong>
                      <small>{d.reference}</small>
                      <small>
                        {d.expires_at ? fmt(d.expires_at) : "해제 확인 필요"}
                      </small>
                    </div>
                    {d.kind === "BACKUP" && (
                      <Button
                        disabled={!can}
                        onClick={() => propose("ACK_BACKUP_PURGE", d.reference)}
                      >
                        제거 확인 요청
                      </Button>
                    )}
                  </div>
                ))}
                <Notice tone="warn">
                  현재 버전 또는 유효 인증서·릴리스·백업 참조가 있으면 삭제할 수
                  없습니다.
                </Notice>
                <Button
                  danger
                  disabled={!deletable}
                  onClick={() => propose("SCHEDULE_DELETION")}
                >
                  삭제 예약 요청
                </Button>
              </>
            ) : (
              <>
                <Facts
                  rows={[
                    [
                      "알고리즘",
                      k.algorithm === "SHA256withECDSA"
                        ? "ECDSA P-256"
                        : k.algorithm,
                    ],
                    [
                      "저장 방식",
                      k.backend === "ENCRYPTED_SOFTWARE"
                        ? "암호화 소프트웨어"
                        : k.backend === "PKCS11"
                          ? "HSM · PKCS#11"
                          : k.backend,
                    ],
                    ["생성일", fmt(k.createdAt)],
                    ["서명 기한", fmt(k.signingExpiresAt)],
                  ]}
                />
                <h3>키 수명주기</h3>
                <Steps
                  labels={["버전 준비", "신뢰 자료 배포", "승인", "활성화"]}
                  current={
                    k.state === "PRE_ACTIVE" ? 1 : k.state === "ACTIVE" ? 4 : -1
                  }
                />
                {k.state === "PRE_ACTIVE" ? (
                  <>
                    <h3>신뢰 자료 배포 확인</h3>
                    <p className="muted">
                      외부 배포를 확인한 근거를 요청에 첨부하세요.
                    </p>
                    <div className="readonly-input">
                      요청 시 배포 증적을 입력하세요
                    </div>
                    <Button
                      primary
                      disabled={!can}
                      onClick={() => propose("ACTIVATE")}
                    >
                      활성화 요청
                    </Button>
                    <small>다른 보안 담당자의 승인과 MFA가 필요합니다.</small>
                  </>
                ) : (
                  <>
                    <Notice tone={k.state === "COMPROMISED" ? "bad" : "info"}>
                      {k.state === "COMPROMISED"
                        ? "침해된 키는 재개할 수 없습니다."
                        : k.state === "PENDING_DELETION"
                          ? `삭제 유예 종료: ${fmt(k.deleteAt)}`
                          : current
                            ? "현재 서명 버전입니다."
                            : "이전 버전의 검증·의존성을 확인하세요."}
                    </Notice>
                    <div className="actions">
                      {["ACTIVE", "VERIFY_ONLY"].includes(k.state) && (
                        <Button
                          danger
                          disabled={!can}
                          onClick={() => suspend(false)}
                        >
                          긴급 중지
                        </Button>
                      )}
                      {k.state === "SUSPENDED" && (
                        <Button
                          disabled={!can}
                          onClick={() => propose("RESUME")}
                        >
                          재개 요청
                        </Button>
                      )}
                      {k.state === "PENDING_DELETION" && (
                        <>
                          <Button
                            disabled={!can}
                            onClick={() =>
                              open({
                                title: "삭제 예약 취소",
                                description:
                                  "취소 후에도 키는 중지 상태입니다. 사용 재개는 별도 승인이 필요합니다.",
                                run: (v, totp) =>
                                  api(`${keyPath(k)}/cancel-deletion`, {
                                    method: "POST",
                                    totp,
                                  }),
                              })
                            }
                          >
                            삭제 취소
                          </Button>
                          <Button
                            danger
                            disabled={
                              !can ||
                              live.length > 0 ||
                              Date.parse(k.deleteAt) > Date.now() ||
                              k.backend === "PKCS11"
                            }
                            onClick={() => propose("DESTROY")}
                          >
                            파기 요청
                          </Button>
                        </>
                      )}
                      {!["COMPROMISED", "DESTROYED"].includes(k.state) && (
                        <Button
                          danger
                          disabled={!can}
                          onClick={() => suspend(true)}
                        >
                          침해 선언
                        </Button>
                      )}
                    </div>
                  </>
                )}
                <button
                  className="text-link spaced"
                  onClick={() => {
                    const pem = k.certificatePem || k.publicKeyPem;
                    if (pem) downloadText(`${k.alias}-v${k.version}.pem`, pem);
                    else notify("공개 자료가 없습니다.");
                  }}
                >
                  <Icon name="download" />
                  공개 인증서·키 다운로드
                </button>
                <small className="mono">키 ID: {k.keyId}</small>
                <div className="panel-note">
                  <button
                    className="text-link"
                    onClick={() => navigate("settings?tab=lifecycle")}
                  >
                    수명주기 구성 보기
                  </button>
                </div>
              </>
            )}
          </Panel>
        );
      }}
    </Loaded>
  );
}
function Rotation() {
  const { session, open, navigate } = useRuntime(),
    [step, setStep] = useState(0),
    [result, setResult] = useState(null);
  return (
    <div className="narrow">
      <Panel className="inspector">
        <Steps
          labels={["대상 선택", "버전 준비", "신뢰 자료 배포"]}
          current={step}
        />
        {result ? (
          <>
            <Status state="SUCCESS" label="버전 준비 완료" />
            <h2>
              {result.alias} · v{result.version}
            </h2>
            <Notice>
              신구 공개 자료를 검증자에 배포하고 reload 결과를 확인하세요.
              배포는 자동 수행하지 않습니다.
            </Notice>
            <Button primary onClick={() => navigate("keys")}>
              키 관리로 이동
            </Button>
            <Button onClick={() => navigate("trust")}>신뢰 자료 보기</Button>
          </>
        ) : (
          <form
            onSubmit={(e) => {
              e.preventDefault();
              const values = Object.fromEntries(new FormData(e.currentTarget));
              setStep(1);
              open({
                title: "새 버전 준비",
                description: `${values.alias}의 새 서명 버전을 PRE_ACTIVE로 준비합니다. 작업 ID: ${values.requestId}`,
                run: async (v, totp) => {
                  const k = await api(
                    `${KMS}/keys/${segment(values.alias)}/rotate`,
                    {
                      method: "POST",
                      body: { requestId: values.requestId },
                      totp,
                    },
                  );
                  setResult(k);
                  setStep(2);
                },
              });
            }}
          >
            <h2>교체할 키 선택</h2>
            <Field label="키 별칭">
              <select name="alias">
                {session.keys.map((k) => (
                  <option key={k}>{k}</option>
                ))}
              </select>
            </Field>
            <Field
              label="작업 식별자"
              hint="같은 작업을 재시도할 때 동일한 ID를 유지하세요."
            >
              <input
                name="requestId"
                defaultValue={crypto.randomUUID()}
                required
                pattern="[A-Za-z0-9_-]{8,100}"
              />
            </Field>
            <Notice>HSM 키는 장비에서 사전 준비가 필요합니다.</Notice>
            <div className="dialog-actions">
              <Button
                primary
                disabled={!session.roles.includes("KEY_ADMIN")}
                type="submit"
              >
                변경 내용 확인
              </Button>
            </div>
          </form>
        )}
      </Panel>
    </div>
  );
}
function Approvals() {
  const state = useLoad([`${KMS}/requests`, `${KMS}/keys`]),
    [search, setSearch] = useState(""),
    [selected, setSelected] = useState("");
  const { session, open, navigate } = useRuntime();
  return (
    <Loaded state={state}>
      {([requests, keys]) => {
        const name = (r) =>
            `${keys.find((k) => k.keyId === r.key_id)?.alias || r.key_id} v${r.version}`,
          rows = requests.filter((r) =>
            `${name(r)} ${r.action} ${r.requested_by}`
              .toLowerCase()
              .includes(search.toLowerCase()),
          ),
          r = rows.find((r) => r.id === selected) || rows[0];
        return (
          <div className="split queue-split">
            <Panel
              title={`요청 목록 (${requests.filter((r) => r.status === "PENDING" && Date.parse(r.expires_at) > Date.now()).length}건 대기)`}
              action={<Search value={search} onChange={setSearch} />}
            >
              <div className="list">
                {rows.map((row) => (
                  <button
                    key={row.id}
                    className={`list-item ${r?.id === row.id ? "selected" : ""}`}
                    onClick={() => setSelected(row.id)}
                  >
                    <div>
                      <strong>
                        {name(row)} · {labels[row.action] || row.action}
                      </strong>
                      <Status
                        state={
                          row.status === "EXECUTED"
                            ? "APPROVED"
                            : row.status === "PENDING" &&
                                Date.parse(row.expires_at) < Date.now()
                              ? "EXPIRED"
                              : row.status
                        }
                      />
                    </div>
                    <small>{row.requested_by}</small>
                  </button>
                ))}
                {!rows.length && <Empty />}
              </div>
            </Panel>
            {r ? (
              <Panel className="inspector">
                <div className="inspector-title">
                  <h2>{name(r)}</h2>
                  <Status
                    state={r.status === "EXECUTED" ? "APPROVED" : r.status}
                  />
                </div>
                <Facts
                  rows={[
                    ["작업", labels[r.action] || r.action],
                    ["요청 ID", r.id],
                    ["요청자", r.requested_by],
                    ["사유", r.reason],
                    ["유효기간", fmt(r.expires_at)],
                    ["승인자", r.approved_by || "—"],
                  ]}
                />
                <h3>요청에 고정된 변경 내용</h3>
                <pre className="json-detail approval-payload">
                  {JSON.stringify(parse(r.payload), null, 2)}
                </pre>
                <Notice>
                  요청자와 다른 보안 담당자가 승인해야 합니다. 승인 시 현재
                  권한·키 상태·의존성을 다시 검사합니다.
                </Notice>
                {r.status === "PENDING" && (
                  <Button
                    primary
                    disabled={
                      !session.roles.includes("SECURITY_OFFICER") ||
                      session.id === r.requested_by ||
                      Date.parse(r.expires_at) < Date.now()
                    }
                    onClick={() =>
                      open({
                        title: "변경 승인",
                        description: `${name(r)} · ${labels[r.action] || r.action}. 승인하면 요청된 변경이 실행됩니다.`,
                        danger: ["DESTROY", "SCHEDULE_DELETION"].includes(
                          r.action,
                        ),
                        run: (v, totp) =>
                          api(`${KMS}/requests/${segment(r.id)}/approve`, {
                            method: "POST",
                            totp,
                          }),
                      })
                    }
                  >
                    내용 확인 후 승인
                  </Button>
                )}
                <Button onClick={() => navigate("audit")}>
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
      }}
    </Loaded>
  );
}
export function parse(value) {
  try {
    return typeof value === "string" ? JSON.parse(value) : value;
  } catch {
    return {};
  }
}
function Audit() {
  const [after, setAfter] = useState(0),
    [history, setHistory] = useState([0]),
    [search, setSearch] = useState(""),
    [result, setResult] = useState("all"),
    [detail, setDetail] = useState(null),
    state = useLoad(`${KMS}/audit?after=${after}&limit=100`);
  return (
    <Loaded state={state}>
      {(data) => {
        const all = data.map((r) => ({
          id: String(r.sequence),
          sequence: r.sequence,
          ...parse(r.event),
          time: parse(r.event).timestamp,
          action: parse(r.event).operation,
        }));
        const rows = all.filter(
          (r) =>
            JSON.stringify(r).toLowerCase().includes(search.toLowerCase()) &&
            (result === "all" || r.result === result),
        );
        return (
          <>
            <Panel
              title="감사 이벤트"
              action={
                <div className="actions">
                  <Search value={search} onChange={setSearch} />
                  <select
                    aria-label="감사 결과 필터"
                    value={result}
                    onChange={(e) => setResult(e.target.value)}
                  >
                    <option value="all">전체 결과</option>
                    <option>SUCCESS</option>
                    <option>DENIED</option>
                  </select>
                  <Button
                    onClick={() =>
                      downloadText(
                        "audit.csv",
                        "\uFEFF" +
                          [
                            "sequence,time,actor,action,result",
                            ...rows.map((r) =>
                              [r.sequence, r.time, r.actor, r.action, r.result]
                                .map(csvValue)
                                .join(","),
                            ),
                          ].join("\n"),
                      )
                    }
                  >
                    내보내기
                  </Button>
                </div>
              }
            >
              <Table
                columns={[
                  ["sequence", "순번"],
                  ["time", "시각", (r) => fmt(r.time)],
                  ["actor", "수행자"],
                  ["action", "작업"],
                  ["result", "결과"],
                ]}
                rows={rows}
                onSelect={setDetail}
              />
              <div className="panel-bottom">
                <span>조회한 범위 {rows.length}건</span>
                <div className="actions">
                  <Button
                    disabled={history.length < 2}
                    onClick={() => {
                      const next = history.slice(0, -1);
                      setHistory(next);
                      setAfter(next.at(-1));
                    }}
                  >
                    이전 범위
                  </Button>
                  <Button
                    disabled={data.length < 100}
                    onClick={() => {
                      const cursor = data.at(-1).sequence;
                      setAfter(cursor);
                      setHistory((h) => [...h, cursor]);
                    }}
                  >
                    다음 범위
                  </Button>
                </div>
              </div>
            </Panel>
            {detail && (
              <Panel className="inspector">
                <h2>감사 이벤트 상세</h2>
                <pre className="json-detail">
                  {JSON.stringify(detail, null, 2)}
                </pre>
                <Button onClick={() => setDetail(null)}>상세 닫기</Button>
              </Panel>
            )}
            <Notice>
              내보내기는 현재 불러온 검색 결과에 한정됩니다. 감사 체인의
              무결성은 운영 상태에서 확인합니다.
            </Notice>
          </>
        );
      }}
    </Loaded>
  );
}
function Health() {
  const state = useLoad(`${KMS}/health`),
    { navigate } = useRuntime();
  return (
    <Loaded state={state}>
      {(data) => (
        <>
          <div className="health-head">
            <Status
              state={data.status === "UP" ? "SUCCESS" : "EXPIRING"}
              label={data.status === "UP" ? "운영 가능" : "상태 확인 필요"}
            />
            <span>확인 시각 {new Date().toLocaleString("ko-KR")}</span>
          </div>
          <div className="split">
            <Panel title="현재 키 상태">
              <Facts
                rows={[
                  ["사용 불가 현재 키", `${data.inactiveCurrentKeys}개`],
                  [
                    "30일 내 서명 기한 도래",
                    `${data.keysExpiringWithin30Days}개`,
                  ],
                  ["감사 이벤트", data.auditEvents],
                  ["서명 backend", data.backend],
                  ["운영 모드", data.mode],
                ]}
              />
              <Button onClick={() => navigate("keys")}>키 상태 확인</Button>
            </Panel>
            <Panel title="무결성·장애 대응">
              <Notice>
                서버의 상태 조회는 감사 체인과 저장소 무결성 검사를 통과한
                경우에만 성공합니다.
              </Notice>
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
      )}
    </Loaded>
  );
}
function Tls() {
  const state = useLoad(`${KMS}/tls`),
    [selection, setSelection] = useState(null);
  return (
    <Loaded state={state}>
      {(data) => {
        const rows = data
            .map((t) => ({ ...t, id: String(t.version) }))
            .sort((a, b) => b.version - a.version),
          item = rows.find((t) => t.id === selection) || rows[0];
        return (
          <div className="split">
            <Panel title="TLS 이력">
              <Table
                columns={[
                  ["version", "버전"],
                  ["issuer_version", "발급 CA 버전"],
                  ["state", "상태", (r) => <Status state={r.state} />],
                  ["expires_at", "만료일", (r) => fmt(r.expires_at)],
                ]}
                rows={rows}
                selected={item?.id}
                onSelect={(r) => setSelection(r.id)}
              />
            </Panel>
            <Panel className="inspector">
              {item ? (
                <>
                  <h2>서버 인증서 · v{item.version}</h2>
                  <Status state={item.state} />
                  <Facts
                    rows={[
                      ["키 지문 SHA-256", item.key_id],
                      ["발급 CA ID", item.issuer_key_id],
                      ["발급 CA 버전", item.issuer_version],
                      ["만료일", fmt(item.expires_at)],
                    ]}
                  />
                  <Button
                    icon="download"
                    onClick={() =>
                      downloadText(
                        `server-v${item.version}.pem`,
                        item.certificate,
                      )
                    }
                  >
                    공개 인증서 다운로드
                  </Button>
                  <Notice>
                    CA 활성화와 서버 TLS 파일 적용은 별도 단계입니다.
                  </Notice>
                </>
              ) : (
                <Empty />
              )}
            </Panel>
          </div>
        );
      }}
    </Loaded>
  );
}
function Trust() {
  const { session, notify } = useRuntime();
  const download = async (type) => {
    try {
      const text = await api(
        `${KMS}/${type === "ca" ? "trust-bundle.pem" : "update-trust-bundle.pem"}`,
        { text: true },
      );
      downloadText(`${type}-trust-bundle.pem`, text);
    } catch (e) {
      notify(e.message);
    }
  };
  return (
    <div className="split form-split">
      <Panel className="inspector">
        <h2>공개 신뢰 자료</h2>
        {[
          ["ca", "CA 신뢰 번들", "ca-root"],
          ["update", "업데이트 검증 키", "update-signing"],
        ].map(([type, title, alias]) => (
          <div className="resource-row" key={type}>
            <Icon name="document" />
            <div>
              <strong>{title}</strong>
              <small>준비·사용 중·검증 전용 버전</small>
            </div>
            <Button
              icon="download"
              disabled={!session.keys.includes(alias)}
              onClick={() => download(type)}
            >
              다운로드
            </Button>
          </div>
        ))}
        <Notice>다운로드한 공개 자료의 지문을 별도 경로로 확인하세요.</Notice>
      </Panel>
      <aside className="guidance">
        <h3>신뢰 자료 적용 순서</h3>
        <ol>
          <li>지문과 대상 버전 확인</li>
          <li>검증자에 신구 공개 자료 설치</li>
          <li>서비스 reload와 검증 확인</li>
          <li>활성화 요청에 배포 증적 첨부</li>
        </ol>
        <p>침해되거나 파기한 버전은 신뢰 묶음에 포함하지 않습니다.</p>
      </aside>
    </div>
  );
}
