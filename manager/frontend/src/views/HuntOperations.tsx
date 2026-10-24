import { useCallback, useEffect, useState } from "react";
import {
  huntApi,
  type HuntFinding,
  type HuntResource,
  type HuntRuleRuntime,
} from "../lib/huntApi";
import type { Role } from "../lib/types";
import { HuntStoragePanel } from "./HuntStoragePanel";
import { HuntExceptionsPanel } from "./HuntExceptions";
import { HuntEpisodeEvidence } from "./HuntEpisodeEvidence";

const lookupNames = [
  "trusted_scanners",
  "internal_networks",
  "approved_dns",
  "approved_services",
  "business_hours",
  "trusted_processes",
  "protected_paths",
];
const time = (value: unknown) =>
  value
    ? new Date(String(value)).toLocaleString("ko-KR", { hour12: false })
    : "—";
const labels: Record<string, string> = {
  DRAFT: "초안",
  OBSERVE: "관찰 운영",
  ACTIVE: "활성",
  PAUSED: "일시 중지",
  READY: "실행 가능",
  COMPLETED: "정상 완료",
  RUNNING: "실행 중",
  IDLE: "대기",
  PARTIAL: "필드 부족",
  UNAVAILABLE: "미수집",
  NEEDS_CONFIGURATION: "설정 필요",
  WARMING_UP: "기준선 준비 중",
  VERSION_MISMATCH: "정의 변경 · 재관찰 필요",
};

export function HuntOperations({
  role,
  rules,
  reload,
}: {
  role: Role;
  rules: HuntResource[];
  reload: () => Promise<void>;
}) {
  const [runtime, setRuntime] = useState<HuntRuleRuntime[]>([]);
  const [findings, setFindings] = useState<HuntFinding[]>([]);
  const [selectedFinding, setSelectedFinding] = useState<string | null>(null);
  const [coverage, setCoverage] = useState<Record<string, string | number>[]>(
    [],
  );
  const [lookups, setLookups] = useState<HuntResource[]>([]);
  const [indicators, setIndicators] = useState<HuntResource[]>([]);
  const [lookupName, setLookupName] = useState(lookupNames[0]);
  const [lookupDraft, setLookupDraft] = useState<{
    name: string;
    values: string;
  } | null>(null);
  const lookupValues =
    lookupDraft?.name === lookupName
      ? lookupDraft.values
      : ((
          lookups.find((item) => item.title === lookupName)?.body.values as
            | string[]
            | undefined
        )?.join("\n") ?? "");
  const [indicator, setIndicator] = useState<HuntResource | undefined>();
  const [iocType, setIocType] = useState("domain");
  const [iocValue, setIocValue] = useState("");
  const [iocSource, setIocSource] = useState("");
  const [iocConfidence, setIocConfidence] = useState(50);
  const [iocDays, setIocDays] = useState(30);
  const [iocEnabled, setIocEnabled] = useState(true);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [busy, setBusy] = useState(false);
  const admin = role === "ADMIN";
  const load = useCallback(async () => {
    const results = await Promise.allSettled([
      huntApi.runtime(),
      huntApi.findings(),
      huntApi.coverage(),
      huntApi.resources("lookup"),
      huntApi.resources("indicator"),
    ]);
    if (results[0].status === "fulfilled") setRuntime(results[0].value);
    if (results[1].status === "fulfilled") setFindings(results[1].value);
    if (results[2].status === "fulfilled") setCoverage(results[2].value);
    if (results[3].status === "fulfilled") setLookups(results[3].value);
    if (results[4].status === "fulfilled") setIndicators(results[4].value);
    const failure = results.find((result) => result.status === "rejected");
    if (failure?.status === "rejected")
      setError(
        failure.reason instanceof Error
          ? failure.reason.message
          : "운영 상태를 불러오지 못했습니다.",
      );
  }, []);
  useEffect(() => {
    void load();
  }, [load]);
  async function action(operation: () => Promise<unknown>, message: string) {
    setBusy(true);
    setError("");
    setNotice("");
    try {
      await operation();
      await Promise.all([load(), reload()]);
      setNotice(message);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : String(cause));
    } finally {
      setBusy(false);
    }
  }
  function editIoc(item?: HuntResource) {
    setIndicator(item);
    setIocType(String(item?.body.type ?? "domain"));
    setIocValue(String(item?.body.value ?? ""));
    setIocSource(String(item?.body.source ?? ""));
    setIocConfidence(Number(item?.body.confidence ?? 50));
    setIocDays(30);
    setIocEnabled(item?.body.enabled !== false);
  }
  return (
    <>
      {admin && <HuntStoragePanel />}
      <HuntExceptionsPanel role={role} />
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
      <section className="data-panel">
        <div className="panel-heading">
          <h3>탐지 규칙 · {rules.length}</h3>
          <button
            type="button"
            disabled={busy}
            onClick={() => {
              void action(async () => {}, "운영 상태를 갱신했습니다.");
            }}
          >
            상태 새로고침
          </button>
        </div>
        <p className="hw-help">
          관찰 운영은 조사 후보를 기록합니다. 관리자 활성화 후 알림을
          발행합니다. 활성화 조건: 7일 관찰과 필수 데이터가 갖춰진 정상 완료
          10회.
        </p>
        <div className="hw-table-wrap">
          <table>
            <thead>
              <tr>
                <th>규칙 / 시나리오</th>
                <th>운영 상태</th>
                <th>관찰 시작 / 정상 완료</th>
                <th>최근 실행</th>
                <th>운영</th>
              </tr>
            </thead>
            <tbody>
              {rules.map((rule) => {
                const run = runtime.find((item) => item.rule_id === rule.id);
                const state = String(rule.body.state);
                const eligible =
                  !!run?.observed_since &&
                  Date.parse(run.observed_since) <=
                    Date.now() - 7 * 86_400_000 &&
                  run.ready_runs >= 10;
                return (
                  <tr key={rule.id}>
                    <td>
                      {rule.title}
                      <small>
                        {String(rule.body.scenarioId)} · v{rule.version}
                      </small>
                    </td>
                    <td>{labels[state] ?? state}</td>
                    <td>
                      {time(run?.observed_since)}
                      <small>정상 완료 {run?.ready_runs ?? 0}회</small>
                    </td>
                    <td>
                      {labels[run?.last_state ?? "IDLE"] ?? run?.last_state}
                      <small>{time(run?.last_finished_at)}</small>
                      {run?.last_error && <small>{run.last_error}</small>}
                    </td>
                    <td>
                      {admin && (
                        <div className="hw-card-actions">
                          {["DRAFT", "PAUSED"].includes(state) && (
                            <button
                              type="button"
                              disabled={busy}
                              onClick={() => {
                                void action(
                                  () => huntApi.transition(rule, "OBSERVE"),
                                  "관찰 운영을 시작했습니다.",
                                );
                              }}
                            >
                              관찰 시작
                            </button>
                          )}
                          {state === "OBSERVE" && (
                            <button
                              type="button"
                              disabled={busy || !eligible}
                              title="7일 관찰·정상 완료 10회 이후 활성화할 수 있습니다."
                              onClick={() => {
                                void action(
                                  () => huntApi.transition(rule, "ACTIVE"),
                                  "규칙을 활성화했습니다.",
                                );
                              }}
                            >
                              활성화
                            </button>
                          )}
                          {["OBSERVE", "ACTIVE"].includes(state) && (
                            <button
                              type="button"
                              disabled={busy}
                              onClick={() => {
                                void action(
                                  () => huntApi.transition(rule, "PAUSED"),
                                  "규칙을 일시 중지했습니다.",
                                );
                              }}
                            >
                              일시 중지
                            </button>
                          )}
                        </div>
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
          {!rules.length && (
            <p className="hw-help">시나리오에서 규칙 초안을 저장하세요.</p>
          )}
        </div>
      </section>
      <section className="data-panel">
        <div className="panel-heading">
          <h3>반복 실행의 조사 후보 · 최근 {findings.length}개</h3>
          <span>일치 결과의 침해 여부는 조사에서 판정하세요.</span>
        </div>
        <div className="hw-table-wrap">
          <table>
            <thead>
              <tr>
                <th>시나리오 / 규칙</th>
                <th>최초 / 최근 관측</th>
                <th>근거</th>
              </tr>
            </thead>
            <tbody>
              {findings.map((item) => (
                <tr key={item.id}>
                  <td>
                    {item.scenarioId}
                    <small>
                      {rules.find((rule) => rule.id === item.ruleId)?.title}
                    </small>
                    <small>
                      규칙 버전 {item.ruleVersion} · 후보 버전 {item.version}
                    </small>
                  </td>
                  <td>
                    {time(item.firstSeen)}
                    <small>{time(item.lastSeen)}</small>
                  </td>
                  <td>
                    <details>
                      <summary>연관 키와 일치 수 확인</summary>
                      <pre>{JSON.stringify(item.details, null, 2)}</pre>
                    </details>
                    <button
                      type="button"
                      onClick={() => setSelectedFinding(item.id)}
                    >
                      실행·병합 근거
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          {!findings.length && (
            <p className="hw-help">
              기록된 조사 후보가 없습니다. 규칙의 최근 실행 상태도 함께
              확인하세요.
            </p>
          )}
        </div>
      </section>
      {selectedFinding && (
        <HuntEpisodeEvidence
          id={selectedFinding}
          role={role}
          onSelect={setSelectedFinding}
          onClose={() => setSelectedFinding(null)}
        />
      )}
      <div className="hw-config-grid">
        <section className="data-panel hw-config">
          <h3>조직 룩업</h3>
          <p>
            주소·경로·포트는 한 줄에 하나씩 입력하세요. 업무시간은 Asia/Seoul
            기준 9-18 형식입니다.
          </p>
          <form
            onSubmit={(event) => {
              event.preventDefault();
              void action(async () => {
                await huntApi.save(
                  "lookup",
                  lookupName,
                  {
                    values: [
                      ...new Set(
                        lookupValues
                          .split("\n")
                          .map((value) => value.trim())
                          .filter(Boolean),
                      ),
                    ],
                  },
                  true,
                  lookups.find((item) => item.title === lookupName),
                );
                setLookupDraft(null);
              }, "룩업을 저장했습니다.");
            }}
          >
            <label>
              룩업 종류
              <select
                aria-label="룩업 종류"
                value={lookupName}
                onChange={(event) => {
                  setLookupDraft(null);
                  setLookupName(event.target.value);
                }}
              >
                {lookupNames.map((name) => (
                  <option key={name}>{name}</option>
                ))}
              </select>
            </label>
            <label>
              룩업 값
              <textarea
                aria-label="룩업 값"
                rows={6}
                value={lookupValues}
                disabled={!admin}
                onChange={(event) => {
                  setLookupDraft({
                    name: lookupName,
                    values: event.target.value,
                  });
                }}
                required
              />
            </label>
            {admin && (
              <button type="submit" disabled={busy}>
                룩업 저장
              </button>
            )}
          </form>
        </section>
        <section className="data-panel hw-config">
          <h3>IOC 관리</h3>
          <p>
            출처·신뢰도·만료 시각을 보관합니다. 만료되거나 사용 해제한 IOC는
            다음 실행부터 제외합니다.
          </p>
          <form
            onSubmit={(event) => {
              event.preventDefault();
              void action(
                () =>
                  huntApi.save(
                    "indicator",
                    `${iocType}: ${iocValue}`.slice(0, 240),
                    {
                      type: iocType,
                      value: iocValue.trim().toLowerCase(),
                      source: iocSource,
                      confidence: iocConfidence,
                      expiresAt: new Date(
                        Date.now() + iocDays * 86_400_000,
                      ).toISOString(),
                      enabled: iocEnabled,
                    },
                    true,
                    indicator,
                  ),
                "IOC를 저장했습니다.",
              );
            }}
          >
            <label>
              유형
              <select
                aria-label="IOC 유형"
                disabled={!admin}
                value={iocType}
                onChange={(event) => setIocType(event.target.value)}
              >
                <option value="domain">도메인</option>
                <option value="ip">IP 주소</option>
                <option value="sha256">SHA-256</option>
                <option value="tls_fingerprint">TLS 지문</option>
              </select>
            </label>
            <label>
              IOC 값
              <input
                aria-label="IOC 값"
                disabled={!admin}
                value={iocValue}
                onChange={(event) => setIocValue(event.target.value)}
                required
              />
            </label>
            <label>
              출처
              <input
                aria-label="IOC 출처"
                disabled={!admin}
                value={iocSource}
                onChange={(event) => setIocSource(event.target.value)}
                required
                maxLength={240}
              />
            </label>
            <div className="hw-case-selects">
              <label>
                신뢰도
                <input
                  aria-label="IOC 신뢰도"
                  type="number"
                  min={0}
                  max={100}
                  disabled={!admin}
                  value={iocConfidence}
                  onChange={(event) =>
                    setIocConfidence(Number(event.target.value))
                  }
                />
              </label>
              <label>
                저장 후 유효 기간(일)
                <input
                  aria-label="IOC 유효 기간"
                  type="number"
                  min={1}
                  max={365}
                  disabled={!admin}
                  value={iocDays}
                  onChange={(event) => setIocDays(Number(event.target.value))}
                />
              </label>
            </div>
            <label>
              <input
                type="checkbox"
                disabled={!admin}
                checked={iocEnabled}
                onChange={(event) => setIocEnabled(event.target.checked)}
              />
              헌팅에 사용
            </label>
            {admin && (
              <div className="hw-card-actions">
                <button type="submit" disabled={busy}>
                  {indicator ? "IOC 수정" : "IOC 추가"}
                </button>
                {indicator && (
                  <button type="button" onClick={() => editIoc()}>
                    새 IOC
                  </button>
                )}
              </div>
            )}
          </form>
        </section>
      </div>
      <section className="data-panel">
        <div className="panel-heading">
          <h3>등록 IOC · 최근 {indicators.length}개</h3>
        </div>
        <div className="hw-table-wrap">
          <table>
            <thead>
              <tr>
                <th>유형 / 값</th>
                <th>출처 / 신뢰도</th>
                <th>상태 / 만료</th>
                <th>편집</th>
              </tr>
            </thead>
            <tbody>
              {indicators.map((item) => (
                <tr key={item.id}>
                  <td>
                    {String(item.body.type)}
                    <small>{String(item.body.value)}</small>
                  </td>
                  <td>
                    {String(item.body.source)}
                    <small>{String(item.body.confidence)} / 100</small>
                  </td>
                  <td>
                    {item.body.enabled === false
                      ? "사용 해제"
                      : Date.parse(String(item.body.expiresAt)) <= Date.now()
                        ? "만료"
                        : "사용 중"}
                    <small>{time(item.body.expiresAt)}</small>
                  </td>
                  <td>
                    {admin && (
                      <button type="button" onClick={() => editIoc(item)}>
                        편집
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>
      <section className="data-panel">
        <div className="panel-heading">
          <h3>수집 가시성 · 최근 24시간</h3>
          <span>0건과 미수집을 구분해 확인하세요.</span>
        </div>
        <div className="hw-table-wrap">
          <table>
            <thead>
              <tr>
                <th>수집원</th>
                <th>유형</th>
                <th>건수</th>
                <th>최근 관측</th>
                <th>주소·계정·프로세스 필드</th>
              </tr>
            </thead>
            <tbody>
              {coverage.map((item, index) => (
                <tr key={index}>
                  <td>{item.source_id}</td>
                  <td>
                    <code>{item.event_type}</code>
                  </td>
                  <td>{Number(item.count).toLocaleString("ko-KR")}</td>
                  <td>{time(item.newest)}</td>
                  <td>
                    {item.addressFields} / {item.accountFields} /{" "}
                    {item.processFields}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          {!coverage.length && (
            <p className="hw-help">
              수집 상태를 확인할 데이터가 없습니다. 저장소 연결과 원천 수집을
              확인하세요.
            </p>
          )}
        </div>
      </section>
    </>
  );
}
