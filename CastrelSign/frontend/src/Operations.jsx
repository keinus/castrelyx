import { useState } from "react";
import { api, segment, fmt, csvValue } from "./api";
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
  downloadText,
} from "./ui";
const ROOT = "/api/admin";
export function Operations({ page, tab }) {
  if (page === "principals") return <Principals tab={tab} />;
  if (page === "releases") return <Releases tab={tab} />;
  if (page === "reviews") return <Reviews />;
  return <Certificates />;
}
export function certificateRows(apps, agents = []) {
  return [
    ...apps.map((r) => ({
      ...r,
      kind: "application",
      principal: r.principalId,
      serial: r.serialNumber,
      expiry: r.notAfter,
      issued: r.issuedAt,
      subject: r.subjectDn,
    })),
    ...agents.map((r) => ({
      ...r,
      kind: "agent",
      principal: r.agent_id,
      serial: r.serial_number,
      expiry: r.not_after,
      issued: r.issued_at,
      subject: r.subject_dn,
    })),
  ].map((r) => ({
    ...r,
    id: `${r.kind}:${r.serial}`,
    status:
      r.status === "ACTIVE" && Date.parse(r.expiry) < Date.now()
        ? "EXPIRED"
        : r.status,
  }));
}
function Certificates() {
  const state = useLoad([
      `${ROOT}/applications/certificates`,
      `${ROOT}/certificates`,
    ]),
    [search, setSearch] = useState(""),
    [selected, setSelected] = useState(""),
    [status, setStatus] = useState(null),
    { notify } = useRuntime();
  return (
    <Loaded state={state}>
      {([a, b]) => {
        const rows = certificateRows(a, b).filter((r) =>
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
                  ["principal", "발급 대상"],
                  ["serial", "일련번호"],
                  ["expiry", "만료일", (r) => fmt(r.expiry)],
                  ["status", "상태", (r) => <Status state={r.status} />],
                ]}
                rows={rows}
                selected={item?.id}
                onSelect={(r) => {
                  setSelected(r.id);
                  setStatus(null);
                }}
              />
            </Panel>
            <Panel className="inspector">
              {item ? (
                <>
                  <h2>{item.principal}</h2>
                  <Status state={item.status} />
                  <Facts
                    rows={[
                      ["유형", item.kind],
                      ["일련번호", item.serial],
                      ["Subject", item.subject],
                      ["발급일", fmt(item.issued)],
                      ["만료일", fmt(item.expiry)],
                    ]}
                  />
                  <Button
                    onClick={async () => {
                      try {
                        const result = await api(
                          `/api/admin/kms/certificates/status?serial=${segment(item.serial)}`,
                        );
                        setStatus({ serial: item.serial, ...result });
                      } catch (e) {
                        notify(e.message);
                      }
                    }}
                  >
                    현재 상태 확인
                  </Button>
                  {status?.serial === item.serial && (
                    <Notice>{status.status || JSON.stringify(status)}</Notice>
                  )}
                  <Button
                    icon="download"
                    onClick={async () => {
                      try {
                        downloadText(
                          `${item.principal}-${item.serial}.pem`,
                          await api(
                            `${ROOT}/console/certificate.pem?kind=${item.kind}&serial=${segment(item.serial)}`,
                            { text: true },
                          ),
                        );
                      } catch (e) {
                        notify(e.message);
                      }
                    }}
                  >
                    공개 인증서 다운로드
                  </Button>
                  <Notice>
                    발급 당시 기록과 현재 인증 가능 여부는 다를 수 있습니다.
                    상태 확인은 VERIFIER 역할이 필요합니다.
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
function Principals({ tab }) {
  const current = tab || "applications",
    { session, navigate, open } = useRuntime(),
    can = session.roles.includes("OPERATOR");
  const [search, setSearch] = useState("");
  const state = useLoad(
    current === "tokens"
      ? [`${ROOT}/enrollment-tokens`, `${ROOT}/application-enrollment-tokens`]
      : `${ROOT}/${current === "agents" ? "agents" : "applications"}`,
  );
  const token = () =>
    open({
      title: "등록 토큰 생성",
      description:
        "원문은 발급 직후 한 번만 표시합니다. 만료시간과 등록 대상을 확인하세요.",
      fields: [
        {
          name: "kind",
          label: "대상 유형",
          type: "select",
          options: [
            { value: "agent", label: "에이전트" },
            { value: "application", label: "애플리케이션" },
          ],
        },
        { name: "target", label: "대상 식별자" },
        { name: "name", label: "토큰 이름" },
        {
          name: "ttl",
          label: "유효시간(초)",
          type: "number",
          value: 3600,
          min: 60,
          max: 604800,
        },
        {
          name: "uses",
          label: "최대 사용 횟수 (애플리케이션은 1회)",
          type: "number",
          value: 1,
          min: 1,
          max: 100,
        },
      ],
      run: (v, totp) =>
        api(
          `${ROOT}/${v.kind === "agent" ? "enrollment-tokens" : "application-enrollment-tokens"}`,
          {
            method: "POST",
            totp,
            body:
              v.kind === "agent"
                ? {
                    name: v.name,
                    agent_id: v.target,
                    ttl_seconds: Number(v.ttl),
                    max_uses: Number(v.uses),
                  }
                : {
                    name: v.name,
                    principal_id: v.target,
                    ttl_seconds: Number(v.ttl),
                  },
          },
        ),
    });
  return (
    <>
      <Tabs
        items={[
          ["applications", "애플리케이션"],
          ["agents", "에이전트"],
          ["tokens", "등록 토큰"],
        ]}
        value={current}
        onChange={(v) => navigate(`principals?tab=${v}`)}
      />
      <Loaded state={state}>
        {(data) => {
          const rows = (
            current === "tokens"
              ? [
                  ...data[0].map((t) => ({ ...t, kind: "agent" })),
                  ...data[1].map((t) => ({ ...t, kind: "application" })),
                ].map((t) => ({
                  ...t,
                  tokenId: t.id,
                  id: `${t.kind}:${t.id}`,
                  target: t.agent_id || t.principal_id,
                  expiry: t.expires_at,
                  state: t.revoked_at
                    ? "REVOKED"
                    : Date.parse(t.expires_at) < Date.now()
                      ? "EXPIRED"
                      : t.used_at || t.used_count >= t.max_uses
                        ? "USED"
                        : "ACTIVE",
                }))
              : data.map((r) => ({
                  ...r,
                  id: r.principalId || r.agent_id,
                  permission: (r.permissions || []).join(", "),
                }))
          ).filter((r) =>
            JSON.stringify(r).toLowerCase().includes(search.toLowerCase()),
          );
          return (
            <Panel
              title={
                current === "tokens"
                  ? "등록 토큰"
                  : current === "agents"
                    ? "등록 에이전트"
                    : "등록 애플리케이션"
              }
              action={
                <div className="actions">
                  <Search value={search} onChange={setSearch} />
                  {current === "applications" && (
                    <Button
                      disabled={!can}
                      onClick={() =>
                        open({
                          title: "애플리케이션 등록",
                          fields: [
                            { name: "principal_id", label: "서비스 식별자" },
                            { name: "display_name", label: "표시 이름" },
                          ],
                          run: (v, totp) =>
                            api(`${ROOT}/applications`, {
                              method: "POST",
                              body: v,
                              totp,
                            }),
                        })
                      }
                    >
                      서비스 등록
                    </Button>
                  )}
                  <Button primary disabled={!can} onClick={token}>
                    등록 토큰 생성
                  </Button>
                </div>
              }
            >
              <Table
                columns={
                  current === "tokens"
                    ? [
                        ["tokenId", "참조 ID"],
                        ["kind", "유형"],
                        ["target", "등록 대상"],
                        ["expiry", "만료일", (r) => fmt(r.expiry)],
                        ["state", "상태", (r) => <Status state={r.state} />],
                        [
                          "actions",
                          "관리",
                          (r) => (
                            <Button
                              disabled={!can || r.state !== "ACTIVE"}
                              onClick={() =>
                                open({
                                  title: "등록 토큰 회수",
                                  danger: true,
                                  description: `${r.target}의 미사용 토큰을 회수합니다.`,
                                  run: (v, totp) =>
                                    api(
                                      `${ROOT}/${r.kind === "agent" ? "enrollment-tokens" : "application-enrollment-tokens"}/${r.tokenId}/revoke`,
                                      { method: "POST", totp },
                                    ),
                                })
                              }
                            >
                              회수
                            </Button>
                          ),
                        ],
                      ]
                    : [
                        ["id", "대상 이름"],
                        ["permission", "권한"],
                        ["status", "상태", (r) => <Status state={r.status} />],
                        [
                          "actions",
                          "관리",
                          (r) => (
                            <div className="actions">
                              {current === "applications" && (
                                <Button
                                  disabled={!can}
                                  onClick={() =>
                                    open({
                                      title: "애플리케이션 권한 추가",
                                      description: `대상: ${r.id}`,
                                      fields: [
                                        {
                                          name: "permission",
                                          label: "업무 권한",
                                        },
                                      ],
                                      run: (v, totp) =>
                                        api(
                                          `${ROOT}/applications/${segment(r.id)}/permissions`,
                                          {
                                            method: "POST",
                                            body: { permission: v.permission },
                                            totp,
                                          },
                                        ),
                                    })
                                  }
                                >
                                  권한
                                </Button>
                              )}
                              <Button
                                disabled={!can}
                                danger={r.status !== "BLOCKED"}
                                onClick={() =>
                                  open({
                                    title:
                                      r.status === "BLOCKED"
                                        ? "서비스 재활성화"
                                        : "서비스 차단",
                                    danger: r.status !== "BLOCKED",
                                    description:
                                      r.status === "BLOCKED"
                                        ? `${r.id}를 재활성화합니다. 기존 인증서는 복구되지 않으며 새 등록이 필요합니다.`
                                        : `${r.id}의 기존 인증서와 미사용 등록 토큰을 회수합니다.`,
                                    run: (v, totp) =>
                                      api(
                                        `${ROOT}/${current}/${segment(r.id)}/${r.status === "BLOCKED" ? "reactivate" : "block"}`,
                                        { method: "POST", totp },
                                      ),
                                  })
                                }
                              >
                                {r.status === "BLOCKED" ? "재활성화" : "차단"}
                              </Button>
                            </div>
                          ),
                        ],
                      ]
                }
                rows={rows}
              />
            </Panel>
          );
        }}
      </Loaded>
      <Notice tone="warn">
        서비스 차단은 기존 인증서와 미사용 등록 토큰을 회수합니다. 재활성화 후
        새 등록이 필요합니다.
      </Notice>
    </>
  );
}
function Releases({ tab }) {
  const { session, navigate, open } = useRuntime(),
    can = session.roles.includes("OPERATOR"),
    state = useLoad(
      tab === "policy"
        ? `${ROOT}/agent-update-policies`
        : tab === "attempts"
          ? `${ROOT}/agent-update-attempts`
          : `${ROOT}/agent-releases`,
    );
  return (
    <>
      <Tabs
        items={[
          ["list", "릴리스"],
          ["policy", "배포 정책"],
          ["attempts", "배포 이력"],
        ]}
        value={tab || "list"}
        onChange={(v) => navigate(`releases?tab=${v}`)}
      />
      <Loaded state={state}>
        {(data) =>
          tab === "policy" ? (
            <>
              <Panel title="적용된 배포 정책">
                <Table
                  columns={[
                    ["policyKey", "정책"],
                    ["agentId", "에이전트", (r) => r.agentId || "전체 기본값"],
                    ["enabled", "배포", (r) => (r.enabled ? "활성화" : "중지")],
                    ["channel", "채널"],
                    [
                      "targetVersion",
                      "목표 버전",
                      (r) => r.targetVersion || "최신",
                    ],
                  ]}
                  rows={data.map((r) => ({ ...r, id: r.policyKey }))}
                />
              </Panel>
              <Panel className="inspector narrow">
                <h2>배포 정책 변경</h2>
                <Button
                  primary
                  disabled={!can}
                  onClick={() =>
                    open({
                      title: "배포 정책 적용",
                      fields: [
                        {
                          name: "agentId",
                          label: "에이전트 ID",
                          hint: "비우면 전체 기본 정책을 변경합니다.",
                          required: false,
                        },
                        {
                          name: "enabled",
                          label: "배포",
                          type: "select",
                          options: [
                            { value: "true", label: "활성화" },
                            { value: "false", label: "중지" },
                          ],
                        },
                        { name: "channel", label: "채널", value: "stable" },
                        {
                          name: "targetVersion",
                          label: "목표 버전",
                          required: false,
                          hint: "비우면 최신 버전",
                        },
                      ],
                      run: (v, totp) =>
                        api(`${ROOT}/agent-update-policy`, {
                          method: "POST",
                          totp,
                          body: {
                            agentId: v.agentId || null,
                            enabled: v.enabled === "true",
                            channel: v.channel,
                            targetVersion: v.targetVersion || null,
                          },
                        }),
                    })
                  }
                >
                  정책 변경
                </Button>
                <Notice>
                  서명 키가 중지·침해 상태인 릴리스는 정책과 관계없이 제공이
                  차단됩니다.
                </Notice>
              </Panel>
            </>
          ) : tab === "attempts" ? (
            <Panel title="배포 시도">
              <Table
                columns={[
                  ["agentId", "에이전트"],
                  ["releaseId", "릴리스"],
                  ["status", "상태"],
                  ["message", "메시지"],
                  ["updatedAt", "갱신 시각", (r) => fmt(r.updatedAt)],
                ]}
                rows={data}
              />
            </Panel>
          ) : (
            <Panel
              title="서명된 릴리스"
              action={
                <Button
                  primary
                  disabled={!can}
                  onClick={() =>
                    open({
                      title: "릴리스 등록",
                      description:
                        "실제 파일이 서버로 전송되며 현재 업데이트 키로 서명됩니다.",
                      fields: [
                        { name: "version", label: "버전" },
                        {
                          name: "os",
                          label: "운영체제",
                          type: "select",
                          options: ["linux", "windows", "darwin"],
                        },
                        {
                          name: "arch",
                          label: "아키텍처",
                          type: "select",
                          options: ["amd64", "arm64"],
                        },
                        { name: "channel", label: "채널", value: "stable" },
                        { name: "artifact", label: "배포 파일", type: "file" },
                      ],
                      run: (v, totp) => {
                        const form = new FormData();
                        for (const name of [
                          "version",
                          "os",
                          "arch",
                          "channel",
                          "artifact",
                        ])
                          form.append(name, v[name]);
                        return api(`${ROOT}/agent-releases`, {
                          method: "POST",
                          body: form,
                          totp,
                        });
                      },
                    })
                  }
                >
                  릴리스 등록
                </Button>
              }
            >
              <Table
                columns={[
                  ["version", "버전"],
                  ["os", "대상", (r) => `${r.os} / ${r.arch}`],
                  ["channel", "채널"],
                  ["status", "상태", (r) => <Status state={r.status} />],
                  [
                    "sizeBytes",
                    "크기",
                    (r) => `${r.sizeBytes.toLocaleString()} B`,
                  ],
                  [
                    "actions",
                    "관리",
                    (r) => (
                      <Button
                        disabled={!can || r.status === "REVOKED"}
                        danger={r.status === "ACTIVE"}
                        onClick={() =>
                          open({
                            title:
                              r.status === "DRAFT"
                                ? "릴리스 활성화"
                                : "릴리스 폐기",
                            danger: r.status !== "DRAFT",
                            description:
                              r.status === "DRAFT"
                                ? `${r.version}을 배포합니다.`
                                : "폐기한 릴리스는 재활성화할 수 없습니다.",
                            run: (v, totp) =>
                              api(
                                `${ROOT}/agent-releases/${r.id}/${r.status === "DRAFT" ? "activate" : "revoke"}`,
                                { method: "POST", totp },
                              ),
                          })
                        }
                      >
                        {r.status === "DRAFT"
                          ? "활성화"
                          : r.status === "REVOKED"
                            ? "폐기 완료"
                            : "폐기"}
                      </Button>
                    ),
                  ],
                ]}
                rows={data}
              />
            </Panel>
          )
        }
      </Loaded>
    </>
  );
}
function Reviews() {
  const state = useLoad(`${ROOT}/portal/requests`),
    [selected, setSelected] = useState(""),
    { session, open } = useRuntime();
  return (
    <Loaded state={state}>
      {(rows) => {
        const r = rows.find((r) => r.id === selected) || rows[0];
        return (
          <div className="split queue-split">
            <Panel title="인증서 요청">
              <Table
                columns={[
                  ["principal_id", "서비스"],
                  ["requested_by", "요청자"],
                  ["state", "상태", (r) => <Status state={r.state} />],
                ]}
                rows={rows}
                selected={r?.id}
                onSelect={(r) => setSelected(r.id)}
              />
            </Panel>
            <Panel className="inspector">
              {r ? (
                <>
                  <h2>{r.principal_id}</h2>
                  <Facts
                    rows={[
                      ["요청 ID", r.id],
                      ["요청자", r.requested_by],
                      ["접수일", fmt(r.created_at)],
                      ["처리일", fmt(r.reviewed_at)],
                      ["검토자", r.reviewed_by || "—"],
                      ["검토 사유", r.reason || "—"],
                    ]}
                  />
                  <Status state={r.state} />
                  <Notice>
                    승인 시 요청자의 현재 서비스 접근 권한, 서비스 상태, CSR
                    서명을 다시 검증합니다. 인증서 발급 시 기존 인증서는 이전
                    버전으로 전환됩니다.
                  </Notice>
                  {r.state === "PENDING" && (
                    <div className="actions">
                      {[true, false].map((approve) => (
                        <Button
                          key={String(approve)}
                          primary={approve}
                          danger={!approve}
                          disabled={!session.roles.includes("OPERATOR")}
                          onClick={() =>
                            open({
                              title: approve
                                ? "인증서 발급 승인"
                                : "인증서 요청 거절",
                              description: `${r.principal_id} · 요청자 ${r.requested_by}`,
                              danger: !approve,
                              fields: [reasonField],
                              run: (v, totp) =>
                                api(
                                  `${ROOT}/portal/requests/${segment(r.id)}/review`,
                                  {
                                    method: "POST",
                                    totp,
                                    body: { approve, reason: v.reason },
                                  },
                                ),
                            })
                          }
                        >
                          {approve ? "승인 및 발급" : "거절"}
                        </Button>
                      ))}
                    </div>
                  )}
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
