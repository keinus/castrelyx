import { useState } from "react";
import { api, segment, fmt, csvValue } from "./api";
import { useRuntime, useLoad, Loaded } from "./runtime";
import {
  Button,
  Status,
  Notice,
  Field,
  Facts,
  Panel,
  Search,
  Table,
  Empty,
  Steps,
  Icon,
  downloadText,
} from "./ui";
import { certificateRows } from "./Operations";
const ROOT = "/api/portal";
export function Portal({ page }) {
  if (page === "services") return <Services />;
  if (page === "my-certificates") return <Certificates />;
  if (page === "enroll") return <Enroll />;
  if (page === "my-requests") return <Requests />;
  if (page === "guide") return <Guide />;
  if (page === "my-activity") return <Activity />;
  return (
    <Empty
      title="이 화면에 접근할 수 없습니다"
      body="내 서비스 메뉴를 이용하세요."
    />
  );
}
async function downloadCertificate(cert, notify) {
  try {
    downloadText(
      `${cert.principal}-${cert.serial}.pem`,
      await api(
        `${ROOT}/certificate.pem?principalId=${segment(cert.principal)}&serial=${segment(cert.serial)}`,
        { text: true },
      ),
    );
  } catch (e) {
    notify(e.message);
  }
}
function Services() {
  const state = useLoad([`${ROOT}/services`, `${ROOT}/certificates`]),
    [search, setSearch] = useState(""),
    [selected, setSelected] = useState(""),
    { navigate, notify } = useRuntime();
  return (
    <Loaded state={state}>
      {([services, certificates]) => {
        const certs = certificateRows(certificates),
          rows = services
            .map((s) => {
              const c =
                certs.find(
                  (c) => c.principal === s.principalId && c.status === "ACTIVE",
                ) || certs.find((c) => c.principal === s.principalId);
              return {
                ...s,
                id: s.principalId,
                certificate: c,
                expiry: c?.expiry,
                state:
                  s.status === "BLOCKED"
                    ? "BLOCKED"
                    : c?.status === "ACTIVE"
                      ? Date.parse(c.expiry) < Date.now() + 7 * 86400000
                        ? "EXPIRING"
                        : "ACTIVE"
                      : c?.status || "NO_CERTIFICATE",
              };
            })
            .filter((s) =>
              `${s.id} ${s.displayName}`
                .toLowerCase()
                .includes(search.toLowerCase()),
            ),
          s = rows.find((s) => s.id === selected) || rows[0];
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
                    (r) => (
                      <Status
                        state={r.state}
                        label={
                          r.state === "ACTIVE"
                            ? "연결 가능"
                            : r.state === "NO_CERTIFICATE"
                              ? "인증서 없음"
                              : undefined
                        }
                      />
                    ),
                  ],
                  ["expiry", "인증서 만료일", (r) => fmt(r.expiry)],
                ]}
                rows={rows}
                selected={s?.id}
                onSelect={(s) => setSelected(s.id)}
              />
              <div className="panel-bottom">
                <span>접근 가능한 서비스 {rows.length}개</span>
                <Button
                  disabled={!rows.length}
                  onClick={() => navigate(`enroll?service=${segment(s.id)}`)}
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
                      label={s.state === "ACTIVE" ? "연결 가능" : undefined}
                    />
                  </div>
                  <Facts
                    rows={[
                      ["서비스 식별자", `app:${s.id}`],
                      ["인증서 만료", fmt(s.expiry)],
                      ["업무 권한", s.permissions.join(", ") || "없음"],
                    ]}
                  />
                  <div className="actions">
                    <Button
                      primary
                      disabled={s.status === "BLOCKED"}
                      onClick={() =>
                        navigate(`enroll?service=${segment(s.id)}`)
                      }
                    >
                      {s.certificate ? "갱신 요청" : "인증서 요청"}
                    </Button>
                    <Button
                      icon="download"
                      disabled={!s.certificate}
                      onClick={() => downloadCertificate(s.certificate, notify)}
                    >
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
                  <h3>최근 인증서 발급</h3>
                  <div className="resource-row">
                    <small>{fmt(s.certificate?.issued)}</small>
                    <span>
                      {s.certificate ? "인증서 발급 기록" : "발급 기록 없음"}
                    </span>
                  </div>
                  <button
                    className="text-link"
                    onClick={() => navigate("my-requests")}
                  >
                    요청 이력 보기 <Icon name="slim-arrow-right" />
                  </button>
                </>
              ) : (
                <Empty
                  title="접근 가능한 서비스가 없습니다"
                  body="운영 담당자에게 서비스 할당을 요청하세요."
                />
              )}
            </Panel>
          </div>
        );
      }}
    </Loaded>
  );
}
function Certificates() {
  const state = useLoad(`${ROOT}/certificates`),
    [search, setSearch] = useState(""),
    [selected, setSelected] = useState(""),
    { notify, refresh } = useRuntime();
  return (
    <Loaded state={state}>
      {(data) => {
        const rows = certificateRows(data).filter((r) =>
            `${r.principal} ${r.serial}`
              .toLowerCase()
              .includes(search.toLowerCase()),
          ),
          item = rows.find((r) => r.id === selected) || rows[0];
        return (
          <div className="split">
            <Panel
              title="발급 인증서"
              action={<Search value={search} onChange={setSearch} />}
            >
              <Table
                columns={[
                  ["principal", "서비스"],
                  ["serial", "일련번호"],
                  ["status", "상태", (r) => <Status state={r.status} />],
                  ["expiry", "만료일", (r) => fmt(r.expiry)],
                ]}
                rows={rows}
                selected={item?.id}
                onSelect={(r) => setSelected(r.id)}
              />
            </Panel>
            <Panel className="inspector">
              {item ? (
                <>
                  <h2>{item.principal}</h2>
                  <Status state={item.status} />
                  <Facts
                    rows={[
                      ["일련번호", item.serial],
                      ["Subject", item.subject],
                      ["발급일", fmt(item.issued)],
                      ["만료일", fmt(item.expiry)],
                    ]}
                  />
                  <Button
                    primary
                    onClick={() => downloadCertificate(item, notify)}
                  >
                    공개 인증서 다운로드
                  </Button>
                  <Button onClick={refresh}>현재 상태 다시 확인</Button>
                  <Notice>
                    서버에서 인증서 유효기간, 서비스 상태와 현재 CA 신뢰 상태를
                    확인한 결과입니다.
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
function Enroll() {
  const state = useLoad(`${ROOT}/services`),
    [step, setStep] = useState(0),
    [form, setForm] = useState(null),
    [error, setError] = useState(""),
    [busy, setBusy] = useState(false),
    [id, setId] = useState(""),
    { navigate, refresh } = useRuntime();
  const service =
    new URLSearchParams(location.hash.split("?")[1] || "").get("service") || "";
  return (
    <Loaded state={state}>
      {(services) => (
        <div className="split form-split">
          <Panel className="inspector">
            <Steps
              labels={["CSR 등록", "내용 확인", "요청 완료"]}
              current={step}
            />
            {step === 0 ? (
              <form
                onSubmit={(e) => {
                  e.preventDefault();
                  const value = Object.fromEntries(
                    new FormData(e.currentTarget),
                  );
                  if (
                    !value.csrPem.includes("BEGIN CERTIFICATE REQUEST") ||
                    value.csrPem.includes("PRIVATE KEY") ||
                    new TextEncoder().encode(value.csrPem).length > 65536
                  ) {
                    setError(
                      "개인키가 아닌 64 KiB 이하의 PEM CSR을 제출하세요.",
                    );
                    return;
                  }
                  setError("");
                  setForm(value);
                  setStep(1);
                }}
              >
                <h2>CSR 등록</h2>
                <Field label="서비스">
                  <select
                    name="principalId"
                    defaultValue={form?.principalId || service}
                    required
                  >
                    <option value="" disabled>
                      서비스 선택
                    </option>
                    {services
                      .filter((s) => s.status === "ACTIVE")
                      .map((s) => (
                        <option key={s.principalId} value={s.principalId}>
                          {s.principalId}
                        </option>
                      ))}
                  </select>
                </Field>
                <Field
                  label="CSR 파일"
                  hint="EC P-256 · CN은 서비스 식별자와 같아야 합니다."
                >
                  <input
                    type="file"
                    accept=".csr,.pem"
                    onChange={async (e) => {
                      const input = e.currentTarget;
                      const f = input.files?.[0];
                      if (!f) return;
                      if (f.size > 65536) {
                        setError("64 KiB 이하의 파일을 선택하세요.");
                        return;
                      }
                      const pem = await f.text();
                      if (!input.isConnected) return;
                      if (pem.includes("PRIVATE KEY")) {
                        setError("개인키는 제출할 수 없습니다.");
                        input.value = "";
                        return;
                      }
                      input.form.elements.csrPem.value = pem;
                      setError("");
                    }}
                  />
                </Field>
                <Field label="CSR 내용">
                  <textarea
                    name="csrPem"
                    rows={7}
                    defaultValue={form?.csrPem || ""}
                    required
                    placeholder="-----BEGIN CERTIFICATE REQUEST-----"
                  />
                </Field>
                {error && <Notice tone="bad">{error}</Notice>}
                <div className="dialog-actions">
                  <Button
                    primary
                    type="submit"
                    disabled={!services.some((s) => s.status === "ACTIVE")}
                  >
                    다음: 내용 확인
                  </Button>
                </div>
              </form>
            ) : step === 1 ? (
              <>
                <h2>요청 내용을 확인하세요</h2>
                <Facts
                  rows={[
                    ["서비스", form.principalId],
                    [
                      "CSR 크기",
                      `${new TextEncoder().encode(form.csrPem).length} B`,
                    ],
                    ["검증", "접수 시 서버에서 CSR 서명·P-256·CN 검증"],
                  ]}
                />
                <Notice>
                  운영자의 검토 후 인증서를 발급합니다. 기존 인증서를 대체하는
                  요청이라면 새 인증서 적용 계획도 확인하세요.
                </Notice>
                {error && <Notice tone="bad">{error}</Notice>}
                <div className="actions">
                  <Button disabled={busy} onClick={() => setStep(0)}>
                    이전
                  </Button>
                  <Button
                    primary
                    disabled={busy}
                    onClick={async () => {
                      setBusy(true);
                      setError("");
                      try {
                        const result = await api(`${ROOT}/requests`, {
                          method: "POST",
                          body: form,
                        });
                        setId(result.id);
                        setForm(null);
                        setStep(2);
                      } catch (e) {
                        setError(e.message);
                      } finally {
                        setBusy(false);
                      }
                    }}
                  >
                    {busy ? "접수 중…" : "인증서 요청 보내기"}
                  </Button>
                </div>
              </>
            ) : (
              <>
                <Status state="SUCCESS" label="요청을 등록했습니다" />
                <h2>검토 후 인증서를 받을 수 있습니다</h2>
                <p className="mono">요청 ID: {id}</p>
                <Button
                  primary
                  onClick={() => {
                    refresh();
                    navigate("my-requests");
                  }}
                >
                  내 요청 보기
                </Button>
              </>
            )}
          </Panel>
          <aside className="guidance">
            <h3>개인키는 서비스에 보관</h3>
            <p>
              서비스에서 키 쌍과 CSR을 생성하세요. KMS에는 공개 CSR만
              전달합니다.
            </p>
            <hr />
            <h3>적용 확인</h3>
            <p>
              발급 완료 후 공개 인증서를 내려받고 서비스의 연결을 확인하세요.
            </p>
            <button className="text-link" onClick={() => navigate("guide")}>
              연결 가이드 보기
            </button>
          </aside>
        </div>
      )}
    </Loaded>
  );
}
function Requests() {
  const state = useLoad(`${ROOT}/requests`),
    [selected, setSelected] = useState(""),
    { navigate, notify } = useRuntime();
  return (
    <Loaded state={state}>
      {(rows) => {
        const r = rows.find((r) => r.id === selected);
        return (
          <>
            <Panel
              title="내 요청"
              action={
                <Button primary onClick={() => navigate("enroll")}>
                  새 인증서 요청
                </Button>
              }
            >
              <Table
                columns={[
                  ["id", "요청 번호"],
                  ["principal_id", "서비스"],
                  ["created_at", "등록 시각", (r) => fmt(r.created_at)],
                  ["state", "상태", (r) => <Status state={r.state} />],
                ]}
                rows={rows}
                selected={r?.id}
                onSelect={(r) => setSelected(r.id)}
              />
              <div className="panel-bottom">최근 200건</div>
            </Panel>
            {r && (
              <Panel className="inspector narrow">
                <h2>{r.principal_id} · 요청 상세</h2>
                <Status state={r.state} />
                <Facts
                  rows={[
                    ["요청 ID", r.id],
                    ["처리일", fmt(r.reviewed_at)],
                    ["검토 사유", r.reason || "검토 대기"],
                  ]}
                />
                <Steps
                  labels={["요청 접수", "검토", "인증서 수령"]}
                  current={
                    r.state === "APPROVED" ? 3 : r.state === "REJECTED" ? -1 : 1
                  }
                />
                {r.state === "APPROVED" && (
                  <Button
                    primary
                    onClick={() =>
                      downloadCertificate(
                        {
                          principal: r.principal_id,
                          serial: r.certificate_serial,
                        },
                        notify,
                      )
                    }
                  >
                    공개 인증서 다운로드
                  </Button>
                )}
                <Button onClick={() => setSelected("")}>상세 닫기</Button>
              </Panel>
            )}
          </>
        );
      }}
    </Loaded>
  );
}
function Guide() {
  const { notify, navigate } = useRuntime();
  return (
    <div className="split form-split">
      <Panel className="inspector">
        <h2>서비스 연결 가이드</h2>
        <Steps
          labels={["CSR 준비", "인증서 발급", "신뢰 자료 적용"]}
          current={-1}
        />
        <p className="muted">개인키는 로컬에서 생성하고 CSR만 제출하세요.</p>
        <pre className="json-detail">
          {
            'openssl ecparam -name prime256v1 -genkey -noout -out client.key\nopenssl req -new -key client.key -out client.csr -subj "/CN=서비스식별자"'
          }
        </pre>
        <Notice>
          client.key는 서비스 담당자만 읽을 수 있도록 권한을 제한하세요.
        </Notice>
        {[
          ["ca", "CA 신뢰 번들"],
          ["update", "업데이트 검증 키"],
        ].map(([type, title]) => (
          <div className="resource-row" key={type}>
            <Icon name="document" />
            <div>
              <strong>{title}</strong>
              <small>신뢰 가능한 공개 버전</small>
            </div>
            <Button
              icon="download"
              onClick={async () => {
                try {
                  downloadText(
                    `${type}-trust-bundle.pem`,
                    await api(`${ROOT}/trust.pem?type=${type}`, { text: true }),
                  );
                } catch (e) {
                  notify(e.message);
                }
              }}
            >
              다운로드
            </Button>
          </div>
        ))}
        <Button primary onClick={() => navigate("enroll")}>
          인증서 요청하기
        </Button>
      </Panel>
      <aside className="guidance">
        <h3>인증서 적용</h3>
        <ol>
          <li>지문을 운영자에게 별도 확인</li>
          <li>개인키와 발급 인증서를 서비스에 설치</li>
          <li>신구 신뢰 번들 적용 후 reload</li>
          <li>mTLS 연결과 만료일 확인</li>
        </ol>
        <p>서버가 제공한다는 이유만으로 공개키를 자동 신뢰하지 않습니다.</p>
      </aside>
    </div>
  );
}
function Activity() {
  const state = useLoad(`${ROOT}/activity`),
    [search, setSearch] = useState(""),
    [selected, setSelected] = useState(null);
  return (
    <Loaded state={state}>
      {(data) => {
        const rows = data.filter((r) =>
          JSON.stringify(r).toLowerCase().includes(search.toLowerCase()),
        );
        return (
          <>
            <Panel
              title="내 서비스 이력"
              action={
                <div className="actions">
                  <Search value={search} onChange={setSearch} />
                  <Button
                    onClick={() =>
                      downloadText(
                        "service-activity.csv",
                        "\uFEFF" +
                          [
                            "time,service,operation,request",
                            ...rows.map((r) =>
                              [
                                r.created_at,
                                r.principal_id,
                                r.operation,
                                r.request_id,
                              ]
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
                  ["id", "순번"],
                  ["created_at", "시각", (r) => fmt(r.created_at)],
                  ["principal_id", "서비스"],
                  ["operation", "작업"],
                ]}
                rows={rows}
                onSelect={setSelected}
              />
              <div className="panel-bottom">
                최근 200건 · 현재 접근 가능한 내 서비스
              </div>
            </Panel>
            {selected && (
              <Panel className="inspector">
                <Facts
                  rows={Object.entries(selected).map(([k, v]) => [
                    k,
                    String(v ?? "—"),
                  ])}
                />
                <Button onClick={() => setSelected(null)}>상세 닫기</Button>
              </Panel>
            )}
          </>
        );
      }}
    </Loaded>
  );
}
