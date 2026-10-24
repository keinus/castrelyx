import { useEffect, useRef, useState } from "react";
import {
  huntApi,
  type HuntException,
  type HuntExceptionPage,
} from "../lib/huntApi";
import type { Role } from "../lib/types";

const states: Record<string, string> = {
  REQUESTED: "검토 대기",
  APPROVED: "승인됨",
  SCHEDULED: "적용 예정",
  REJECTED: "반려",
  REVOKED: "철회",
  EXPIRED: "만료",
};
const stamp = (value: string) =>
  new Date(value).toLocaleString("ko-KR", { hour12: false });
const failure = (cause: unknown) =>
  cause instanceof Error ? cause.message : String(cause);
function inputTime(instant: string) {
  const date = new Date(instant);
  return new Date(date.getTime() - date.getTimezoneOffset() * 60000)
    .toISOString()
    .slice(0, 23);
}
function candidateTime(value: unknown) {
  if (typeof value !== "string") return new Date().toISOString();
  const text = value.replace(" ", "T");
  const parsed = new Date(
    /[zZ]$|[+-]\d\d:\d\d$/.test(text) ? text : text + "Z",
  );
  return Number.isFinite(parsed.getTime())
    ? parsed.toISOString()
    : new Date().toISOString();
}

export function HuntExceptionRequest({
  jobId,
  index,
  scenarioId,
  candidate,
  sourceKind = "FINDING",
}: {
  jobId: string;
  index: number;
  scenarioId: string;
  candidate: Record<string, string | number | null>;
  sourceKind?: "FINDING" | "SUPPRESSED";
}) {
  const [open, setOpen] = useState(false),
    [title, setTitle] = useState(""),
    [reason, setReason] = useState("");
  const [from, setFrom] = useState(() =>
    inputTime(candidateTime(candidate.first_seen)),
  );
  const [until, setUntil] = useState(() =>
    inputTime(new Date(Date.now() + 86400000).toISOString()),
  );
  const [busy, setBusy] = useState(false),
    [error, setError] = useState(""),
    [created, setCreated] = useState<HuntException | null>(null);
  const alive = useRef(true);
  useEffect(() => {
    alive.current = true;
    return () => {
      alive.current = false;
    };
  }, []);
  async function submit() {
    if (
      !Number.isFinite(Date.parse(from)) ||
      !Number.isFinite(Date.parse(until))
    ) {
      setError("예외 시작·종료 시각을 확인하세요.");
      return;
    }
    setBusy(true);
    setError("");
    try {
      const value = await huntApi.requestException({
        jobId,
        candidateIndex: index,
        title: title.trim(),
        reason: reason.trim(),
        validFrom: new Date(from).toISOString(),
        expiresAt: new Date(until).toISOString(),
        ...(sourceKind === "SUPPRESSED" ? { sourceKind } : {}),
      });
      if (alive.current) setCreated(value);
    } catch (cause) {
      if (alive.current) setError(failure(cause));
    } finally {
      if (alive.current) setBusy(false);
    }
  }
  return (
    <div
      className="hw-exception-request"
      role="group"
      aria-label={`${scenarioId} ${sourceKind === "SUPPRESSED" ? "억제 후보" : "후보"} ${index + 1} 승인 예외`}
    >
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        aria-expanded={open}
      >
        {sourceKind === "SUPPRESSED"
          ? `억제 후보 ${index + 1} 예외 갱신 요청`
          : `후보 ${index + 1} 승인 예외 요청`}
      </button>
      {open && (
        <div className="hw-exception-form">
          <p className="hw-help">
            선택 후보의 모든 엔터티 식별자·시나리오 버전·현재 원천 조건·관측자
            매핑에 묶입니다. 다른 관리자의 승인 후 조직에 공유됩니다. 최대
            30일이며, 승인 전에는 후보를 억제하지 않습니다.
          </p>
          <label>
            예외 제목
            <input
              value={title}
              maxLength={240}
              disabled={busy || !!created}
              onChange={(e) => setTitle(e.target.value)}
            />
          </label>
          <label>
            요청 근거
            <textarea
              value={reason}
              maxLength={4000}
              rows={3}
              disabled={busy || !!created}
              onChange={(e) => setReason(e.target.value)}
            />
          </label>
          <div className="hw-exception-dates">
            <label>
              예외 시작
              <input
                type="datetime-local"
                step="0.001"
                value={from}
                disabled={busy || !!created}
                onChange={(e) => setFrom(e.target.value)}
              />
            </label>
            <label>
              예외 종료
              <input
                type="datetime-local"
                step="0.001"
                value={until}
                disabled={busy || !!created}
                onChange={(e) => setUntil(e.target.value)}
              />
            </label>
          </div>
          {error && (
            <p className="hw-feedback hw-error" role="alert">
              {error}
            </p>
          )}
          {created ? (
            <p className="hw-feedback" role="status">
              예외 요청을 등록했습니다. 규칙·운영의 승인 예외에서 검토하세요.
              승인 전에는 적용되지 않습니다. 요청 ID: {created.id}
            </p>
          ) : (
            <button
              type="button"
              disabled={
                busy || !title.trim() || !reason.trim() || !from || !until
              }
              onClick={() => void submit()}
            >
              {busy ? "등록 중…" : "검토 요청 등록"}
            </button>
          )}
        </div>
      )}
    </div>
  );
}

export function HuntExceptionsPanel({ role }: { role: Role }) {
  return <HuntExceptionsPanelContent key={role} role={role} />;
}
function HuntExceptionsPanelContent({ role }: { role: Role }) {
  const [open, setOpen] = useState(false),
    [items, setItems] = useState<HuntException[]>([]),
    [cursor, setCursor] = useState<HuntExceptionPage["nextCursor"]>(null);
  const [selected, setSelected] = useState<HuntException | null>(null),
    [reason, setReason] = useState(""),
    [reviewed, setReviewed] = useState(false);
  const [loading, setLoading] = useState(false),
    [busy, setBusy] = useState(false),
    [error, setError] = useState(""),
    [notice, setNotice] = useState("");
  const [usageLoading, setUsageLoading] = useState(false);
  const listGeneration = useRef(0),
    detailGeneration = useRef(0);
  useEffect(
    () => () => {
      listGeneration.current++;
      detailGeneration.current++;
    },
    [],
  );
  async function load(next?: NonNullable<HuntExceptionPage["nextCursor"]>) {
    const generation = ++listGeneration.current;
    setLoading(true);
    setError("");
    try {
      const page = await huntApi.exceptions(next);
      if (generation !== listGeneration.current) return;
      setItems((previous) =>
        next
          ? [
              ...new Map(
                [...previous, ...page.items].map((item) => [item.id, item]),
              ).values(),
            ]
          : page.items,
      );
      setCursor(page.nextCursor);
    } catch (cause) {
      if (generation === listGeneration.current) setError(failure(cause));
    } finally {
      if (generation === listGeneration.current) setLoading(false);
    }
  }
  async function select(id: string) {
    const generation = ++detailGeneration.current;
    setSelected(null);
    setUsageLoading(false);
    setReviewed(false);
    setReason("");
    setError("");
    setNotice("");
    try {
      const value = await huntApi.exception(id);
      if (generation === detailGeneration.current) setSelected(value);
    } catch (cause) {
      if (generation === detailGeneration.current) setError(failure(cause));
    }
  }
  async function decide(action: "APPROVE" | "REJECT" | "REVOKE") {
    if (!selected) return;
    const item = selected,
      generation = detailGeneration.current;
    setBusy(true);
    setError("");
    setNotice("");
    try {
      const value = await huntApi.decideException(item, action, reason.trim());
      if (generation !== detailGeneration.current) return;
      setSelected(value);
      setReason("");
      setReviewed(false);
      setNotice(
        action === "APPROVE"
          ? "승인했습니다. 새 시나리오·규칙 실행부터 유효한 범위와 기간에 적용합니다."
          : action === "REJECT"
            ? "요청을 반려했습니다."
            : "예외를 철회했습니다. 새 실행부터 후보를 다시 평가합니다.",
      );
      await load();
    } catch (cause) {
      if (generation === detailGeneration.current) setError(failure(cause));
    } finally {
      if (generation === detailGeneration.current) setBusy(false);
    }
  }
  async function moreUsage() {
    if (!selected?.usage?.nextCursor) return;
    const id = selected.id,
      before = selected.usage.nextCursor,
      generation = detailGeneration.current;
    setUsageLoading(true);
    try {
      const page = await huntApi.exceptionUsage(id, before);
      if (generation !== detailGeneration.current) return;
      setSelected((current) =>
        current?.id === id
          ? {
              ...current,
              usage: {
                ...page,
                items: [
                  ...new Map(
                    [...(current.usage?.items ?? []), ...page.items].map(
                      (item) => [item.id, item],
                    ),
                  ).values(),
                ],
              },
            }
          : current,
      );
    } catch (cause) {
      if (generation === detailGeneration.current) setError(failure(cause));
    } finally {
      if (generation === detailGeneration.current) setUsageLoading(false);
    }
  }
  return (
    <section className="data-panel hw-exceptions" aria-label="승인 예외 관리">
      <div className="panel-heading">
        <h3>승인 예외</h3>
        <button
          type="button"
          disabled={busy || loading}
          aria-expanded={open}
          onClick={() => {
            setOpen(true);
            void load();
          }}
        >
          {open ? "예외 목록 새로고침" : "승인 예외 검토"}
        </button>
      </div>
      <p className="hw-help">
        실제 시나리오 후보에서 요청합니다. 검토 대기는 요청자와 관리자만
        열람하며, 승인된 범위와 철회 이력은 조직에 공유합니다. 과거 결과는 실행
        당시의 승인 상태를 보존합니다.
      </p>
      {error && (
        <p className="hw-feedback hw-error" role="alert">
          {error}
        </p>
      )}
      {notice && (
        <p className="hw-feedback" role="status">
          {notice}
        </p>
      )}
      {open && (
        <div className="hw-exception-layout">
          <div
            className="hw-exception-list"
            aria-label="예외 목록"
            aria-busy={loading}
          >
            {!items.length && (
              <p>
                {loading
                  ? "예외 목록을 불러오는 중입니다."
                  : "열람할 예외 요청이 없습니다."}
              </p>
            )}
            {items.map((item) => (
              <button
                type="button"
                key={item.id}
                disabled={busy}
                aria-pressed={selected?.id === item.id}
                onClick={() => void select(item.id)}
              >
                <strong>{item.title}</strong>
                <span>
                  {item.scenarioId} ·{" "}
                  {states[item.effectiveState] ?? item.effectiveState} · 버전{" "}
                  {item.version}
                </span>
                <small>
                  {stamp(item.validFrom)} ~ {stamp(item.expiresAt)}
                </small>
              </button>
            ))}
            {cursor && (
              <button
                type="button"
                disabled={busy || loading}
                onClick={() => void load(cursor)}
              >
                예외 50개 더 보기
              </button>
            )}
          </div>
          {selected && (
            <article
              className="hw-exception-detail"
              aria-label="선택 예외 검토"
            >
              <div className="panel-heading">
                <h4>{selected.title}</h4>
                <button
                  type="button"
                  disabled={busy}
                  onClick={() => void select(selected.id)}
                >
                  선택 예외 최신 버전
                </button>
              </div>
              <p>
                {selected.scenarioId} · 정의 {selected.scenarioVersion} ·{" "}
                {states[selected.effectiveState] ?? selected.effectiveState} ·
                버전 {selected.version}
              </p>
              <p>요청 근거: {selected.reason}</p>
              <p>
                {stamp(selected.validFrom)}부터 {stamp(selected.expiresAt)}{" "}
                전까지
              </p>
              <dl className="hw-exception-scope">
                {Object.entries(selected.scope).map(([key, value]) => (
                  <div key={key}>
                    <dt>{key}</dt>
                    <dd>{value === null ? "누락값" : String(value)}</dd>
                  </div>
                ))}
              </dl>
              <p className="hw-help">
                이 식별자 전체와 원천 조건·관측자 매핑이 모두 같아야 적용됩니다.
                일부만 겹치는 관측 구간은 억제하지 않습니다.
              </p>
              <details className="hw-exception-proof">
                <summary>요청 당시 근거와 해시</summary>
                <p>
                  원본 SHA-256: <code>{selected.evidenceSha256}</code>
                </p>
                {selected.exportEvidenceSha256 && (
                  <p>
                    권한 사본 SHA-256:{" "}
                    <code>{selected.exportEvidenceSha256}</code>
                  </p>
                )}
                <pre>{selected.evidenceJson}</pre>
              </details>
              <h4>검토 이력</h4>
              <ol>
                {selected.history?.map((entry) => (
                  <li key={entry.version}>
                    버전 {entry.version} · {states[entry.state] ?? entry.state}{" "}
                    · 사용자 {entry.actor_id} · {stamp(entry.created_at)}
                    <p>{entry.note}</p>
                  </li>
                ))}
              </ol>
              <h4>실제 적용 이력</h4>
              {!selected.usage?.items.length && (
                <p>
                  기록된 적용이 없습니다. 승인 후 새 실행 결과를 확인하세요.
                </p>
              )}
              <ol>
                {selected.usage?.items.map((entry) => (
                  <li key={entry.id}>
                    {stamp(entry.createdAt)} · 사용자 {entry.actorId} · 승인
                    버전 {entry.detail.approvalVersion}
                    <p>
                      {entry.detail.scenarioId} · 실행 시점{" "}
                      {stamp(entry.detail.asOf)}
                    </p>
                    <code>{entry.detail.jobId}</code>
                    <details className="hw-exception-proof">
                      <summary>억제한 원본 후보</summary>
                      <pre>{JSON.stringify(entry.detail.finding, null, 2)}</pre>
                    </details>
                  </li>
                ))}
              </ol>
              {selected.usage?.nextCursor && (
                <button
                  type="button"
                  disabled={busy || usageLoading}
                  onClick={() => void moreUsage()}
                >
                  적용 이력 20개 더 보기
                </button>
              )}
              {((role === "ADMIN" && selected.permissions.decide) ||
                (role !== "VIEWER" && selected.permissions.revoke)) && (
                <div className="hw-exception-form">
                  <label>
                    판정 근거
                    <textarea
                      value={reason}
                      maxLength={4000}
                      rows={3}
                      disabled={busy}
                      onChange={(e) => setReason(e.target.value)}
                    />
                  </label>
                  {role === "ADMIN" && selected.permissions.decide && (
                    <>
                      <label className="hw-check">
                        <input
                          type="checkbox"
                          checked={reviewed}
                          disabled={busy}
                          onChange={(e) => setReviewed(e.target.checked)}
                        />
                        범위·기간·근거를 검토했습니다.
                      </label>
                      <button
                        type="button"
                        disabled={busy || !reviewed || !reason.trim()}
                        onClick={() => void decide("APPROVE")}
                      >
                        예외 승인
                      </button>
                      <button
                        type="button"
                        disabled={busy || !reason.trim()}
                        onClick={() => void decide("REJECT")}
                      >
                        요청 반려
                      </button>
                    </>
                  )}
                  {selected.permissions.revoke && (
                    <button
                      type="button"
                      disabled={busy || !reason.trim()}
                      onClick={() => void decide("REVOKE")}
                    >
                      예외 철회
                    </button>
                  )}
                </div>
              )}
            </article>
          )}
        </div>
      )}
    </section>
  );
}
