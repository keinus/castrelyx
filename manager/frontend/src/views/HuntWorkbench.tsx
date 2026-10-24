import { useCallback, useEffect, useRef, useState } from "react";
import {
  Activity,
  ArrowLeft,
  BookmarkPlus,
  ChevronRight,
  Download,
  FileSearch,
  Filter,
  Play,
  Plus,
  Save,
  Search,
  Square,
  X,
} from "lucide-react";
import {
  Bar,
  BarChart,
  CartesianGrid,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import { ViewFrame } from "../components/ViewFrame";
import {
  huntApi,
  type HuntCursor,
  type HuntEventV2,
  type HuntFieldV2,
  type HuntFilter,
  type HuntJobV2,
  type HuntPageV2,
  type HuntQueryV2,
  type HuntResource,
  type HuntScenario,
  type HuntCaseWorkflow,
} from "../lib/huntApi";
import type { Asset, Role } from "../lib/types";
import "./hunt-workbench.css";
import { HuntOperations } from "./HuntOperations";
import { HuntFilterEditor } from "./HuntFilterEditor";
import { HuntAggregationPanel } from "./HuntAggregationPanel";
import { HuntRelationsPanel } from "./HuntRelationsPanel";
import { HuntCaseCollaboration } from "./HuntCaseCollaboration";
import { HuntExceptionRequest } from "./HuntExceptions";
import {
  appendFilter,
  operatorLabels,
  operators,
  requireScenarioScope,
  querySignature,
  validateFilter,
} from "../lib/huntFilters";

const tabs = [
  ["explore", "탐색"],
  ["entities", "엔터티"],
  ["scenarios", "헌팅 시나리오"],
  ["investigations", "조사"],
  ["operations", "규칙·운영"],
] as const;
const statusNames: Record<string, string> = {
  QUEUED: "실행 대기",
  RUNNING: "전체 범위 조회 중",
  COMPLETED: "조회 완료",
  CANCELLED: "취소됨",
  FAILED: "실패",
  READY: "실행 가능",
  UNAVAILABLE: "미수집",
  PARTIAL: "필드 부족",
  NEEDS_CONFIGURATION: "룩업 설정 필요",
  WARMING_UP: "14일 기준선 준비 중",
};
const modes: Record<string, string> = {
  COUNT: "빈도",
  DISTINCT: "고유값",
  SEQUENCE: "순서 연관",
  BURST_SEQUENCE: "반복 후 연관",
  NEW_SEQUENCE: "신규·연관",
  NEW: "신규 활동",
  RARE: "희귀 활동",
  BEACON: "주기성",
  SUM: "합계",
  ASYMMETRY: "비율",
  BASELINE_COUNT: "기준선 비교",
  BASELINE_SUM: "기준선 비교",
  NEW_SUM: "신규·합계",
  FANOUT: "확산",
  FILE_BURST: "대량 변경",
  SLOW_SCAN: "저속 탐색",
  DNS_FAILURE: "DNS 실패",
  FAST_FLUX: "주소 변화",
  IOC: "인텔리전스",
};
const date = (value: unknown) => {
  if (!value) return "—";
  const raw = String(value).replace(" ", "T");
  return new Date(
    /(?:Z|[+-]\d{2}:\d{2})$/.test(raw) ? raw : `${raw}Z`,
  ).toLocaleString("ko-KR", { hour12: false });
};
const local = (time: number) => {
  const d = new Date(time);
  return new Date(time - d.getTimezoneOffset() * 60_000)
    .toISOString()
    .slice(0, 23);
};
const text = (value: unknown) =>
  value === null || value === undefined || value === "" ? "—" : String(value);
const count = (value: number | undefined) =>
  value === undefined ? "—" : Number(value).toLocaleString("ko-KR");

export function HuntWorkbench({
  assets,
  role,
  onLegacy,
}: {
  assets: Asset[];
  role: Role;
  onLegacy: () => void;
}) {
  const [tab, setTab] = useState<string>("explore");
  const [from, setFrom] = useState(() =>
    new Date(Date.now() - 3_600_000).toISOString(),
  );
  const [to, setTo] = useState(() => new Date().toISOString());
  const [search, setSearch] = useState("");
  const [asset, setAsset] = useState("");
  const [filter, setFilter] = useState<HuntFilter | null>(null);
  const [field, setField] = useState("src_ip");
  const [operator, setOperator] = useState("eq");
  const [value, setValue] = useState("");
  const [groupBy, setGroupBy] = useState("event_type");
  const [extraGroups, setExtraGroups] = useState<string[]>([]);
  const [includeRelations, setIncludeRelations] = useState(false);
  const [metric, setMetric] = useState("count");
  const [pageSize, setPageSize] = useState(200);
  const [fields, setFields] = useState<HuntFieldV2[]>([]);
  const [scenarios, setScenarios] = useState<HuntScenario[]>([]);
  const [scenarioGroup, setScenarioGroup] = useState("ALL");
  const [scenarioSearch, setScenarioSearch] = useState("");
  const [saved, setSaved] = useState<HuntResource[]>([]);
  const [investigations, setInvestigations] = useState<HuntResource[]>([]);
  const [rules, setRules] = useState<HuntResource[]>([]);
  const [job, setJob] = useState<HuntJobV2 | null>(null);
  const [page, setPage] = useState<HuntPageV2 | null>(null);
  const [pageHistory, setPageHistory] = useState<(HuntCursor | null)[]>([null]);
  const [selected, setSelected] = useState<HuntEventV2 | null>(null);
  const [entityType, setEntityType] = useState("src_ip");
  const [entityValue, setEntityValue] = useState("");
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [pending, setPending] = useState(false);
  const [exportingCase, setExportingCase] = useState<string | null>(null);
  const [saveOpen, setSaveOpen] = useState(false);
  const [saveTitle, setSaveTitle] = useState("");
  const [shared, setShared] = useState(false);
  const [queryShared, setQueryShared] = useState(false);
  const [investigationId, setInvestigationId] = useState("");
  const [edit, setEdit] = useState<HuntResource | null>(null);
  const [caseWorkflow, setCaseWorkflow] = useState<HuntCaseWorkflow | null>(
    null,
  );
  const [caseTitle, setCaseTitle] = useState("");
  const [hypothesis, setHypothesis] = useState("");
  const [notes, setNotes] = useState("");
  const [caseStatus, setCaseStatus] = useState("INVESTIGATING");
  const [verdict, setVerdict] = useState("UNDETERMINED");
  const [evidence, setEvidence] = useState<Record<string, unknown>[]>([]);
  const [audit, setAudit] = useState<Record<string, unknown>[]>([]);
  const caseSelection = useRef<string | null>(null);
  const caseReadGeneration = useRef(0);
  const poll = useRef<AbortController | null>(null);
  const detailRef = useRef<HTMLElement>(null);
  const currentJobId = useRef<string | null>(null);
  const detailRequest = useRef<AbortController | null>(null);
  const revealDetail = useRef(false);
  const busy = pending || job?.state === "QUEUED" || job?.state === "RUNNING";
  const canEdit = role !== "VIEWER";
  const caseCanEdit =
    canEdit &&
    (!edit ||
      (!!caseWorkflow &&
        caseWorkflow.version === edit.version &&
        caseWorkflow.permissions.edit));
  const caseDirty =
    !!edit &&
    (caseTitle !== edit.title ||
      hypothesis !== String(edit.body.hypothesis ?? "") ||
      notes !== String(edit.body.notes ?? "") ||
      caseStatus !== String(edit.body.status ?? "INVESTIGATING") ||
      verdict !== String(edit.body.verdict ?? "UNDETERMINED") ||
      shared !== edit.shared);
  const onCaseWorkflow = useCallback(
    (id: string, value: HuntCaseWorkflow | null) => {
      if (caseSelection.current === id) setCaseWorkflow(value);
    },
    [],
  );
  const result = job?.result;
  const draftChanged =
    !!result &&
    querySignature(result.query) !==
      querySignature({
        from,
        to,
        filter: asset
          ? appendFilter(filter, {
              op: "eq",
              field: "asset_uid",
              values: [asset],
            })
          : filter,
        text: search,
        groupBy,
        metric,
        pageSize,
        ...(extraGroups.length
          ? { groupFields: [groupBy, ...extraGroups] }
          : {}),
        ...(includeRelations ? { includeRelations: true } : {}),
      });
  const fieldLabel = (name: string) =>
    fields.find((item) => item.name === name)?.label ?? name;
  const loadResources = useCallback(async () => {
    const results = await Promise.allSettled([
      huntApi.fields(),
      huntApi.scenarios(),
      huntApi.resources("saved-search"),
      huntApi.resources("investigation"),
      huntApi.resources("rule"),
    ]);
    if (results[0].status === "fulfilled")
      setFields(
        results[0].value.filter(
          (item) =>
            role !== "VIEWER" ||
            !["command_line", "message"].includes(item.name),
        ),
      );
    if (results[1].status === "fulfilled") setScenarios(results[1].value);
    if (results[2].status === "fulfilled") setSaved(results[2].value);
    if (results[3].status === "fulfilled") setInvestigations(results[3].value);
    if (results[4].status === "fulfilled") setRules(results[4].value);
    const failed = results.find((item) => item.status === "rejected");
    if (failed?.status === "rejected")
      setError(
        failed.reason instanceof Error
          ? failed.reason.message
          : "헌팅 구성을 불러오지 못했습니다.",
      );
  }, [role]);
  useEffect(() => {
    void loadResources();
    return () => {
      poll.current?.abort();
      detailRequest.current?.abort();
    };
  }, [loadResources]);
  useEffect(() => {
    if (selected) {
      detailRef.current?.focus({ preventScroll: true });
      if (revealDetail.current) {
        detailRef.current?.scrollIntoView?.({ block: "start" });
        revealDetail.current = false;
      }
    }
  }, [selected]);

  function query(scopeOnly = false): HuntQueryV2 {
    if (!from || !to) throw new Error("조회 시작·종료 시각을 지정하세요.");
    const combined = asset
      ? appendFilter(filter, { op: "eq", field: "asset_uid", values: [asset] })
      : filter;
    const errors = validateFilter(combined, fields);
    if (errors.length) throw new Error(errors.join(" "));
    if (scopeOnly) requireScenarioScope(combined, search);
    return {
      from,
      to,
      filter: combined,
      text: search,
      groupBy,
      metric,
      pageSize,
      ...(extraGroups.length ? { groupFields: [groupBy, ...extraGroups] } : {}),
      ...(includeRelations ? { includeRelations: true } : {}),
    };
  }
  async function watch(created: HuntJobV2) {
    poll.current?.abort();
    const controller = new AbortController();
    poll.current = controller;
    detailRequest.current?.abort();
    currentJobId.current = created.id;
    let current = created;
    setJob(current);
    setPage(null);
    setSelected(null);
    setPageHistory([null]);
    while (
      ["QUEUED", "RUNNING"].includes(current.state) &&
      !controller.signal.aborted
    ) {
      await new Promise<void>((resolve) => {
        const finish = () => {
          window.clearTimeout(timeout);
          controller.signal.removeEventListener("abort", finish);
          resolve();
        };
        const timeout = window.setTimeout(finish, 700);
        controller.signal.addEventListener("abort", finish, { once: true });
      });
      if (controller.signal.aborted) return;
      current = await huntApi.job(created.id, controller.signal);
      if (controller.signal.aborted) return;
      setJob(current);
    }
    if (current.state === "FAILED")
      throw new Error(current.error ?? "조회에 실패했습니다.");
    if (current.result) {
      setPage(current.result.page);
      if (current.result.scenario)
        setNotice(
          `시나리오 ${current.result.scenario.id} · ${statusNames[current.result.scenario.readiness] ?? current.result.scenario.readiness}`,
        );
    }
  }
  async function run(scenario?: HuntScenario, supplied?: HuntQueryV2) {
    setError("");
    setNotice("");
    setPending(true);
    try {
      const spec = supplied ?? query(!!scenario);
      setTab("explore");
      await watch(
        scenario
          ? await huntApi.run(scenario.id, spec)
          : await huntApi.query(spec),
      );
    } catch (cause) {
      if (!(cause instanceof DOMException && cause.name === "AbortError"))
        setError(
          cause instanceof Error ? cause.message : "조회에 실패했습니다.",
        );
    } finally {
      setPending(false);
    }
  }
  function addFilter(name: string, operand: string, target: string) {
    if (!target && operand !== "exists") return;
    setFilter((current) =>
      appendFilter(current, {
        op: operand,
        field: name,
        values: operand === "exists" ? [] : [target],
      }),
    );
    setValue("");
  }
  function range(hours: number) {
    const anchor = Date.now();
    setFrom(new Date(anchor - hours * 3_600_000).toISOString());
    setTo(new Date(anchor).toISOString());
  }
  async function paginate(next: boolean) {
    if (!job || !page) return;
    detailRequest.current?.abort();
    setPending(true);
    setError("");
    try {
      const history = next
        ? [...pageHistory, page.nextCursor]
        : pageHistory.slice(0, -1);
      const updated = await huntApi.page(job.id, history.at(-1) ?? null);
      setPage(updated);
      setPageHistory(history);
      setSelected(null);
    } catch (cause) {
      setError(String(cause instanceof Error ? cause.message : cause));
    } finally {
      setPending(false);
    }
  }
  function useSaved(item: HuntResource) {
    const stored = item.body.query as Partial<HuntQueryV2> | undefined;
    if (stored?.redacted) {
      setError(
        "이 저장 검색에는 권한에 따라 가려진 조건이 있습니다. 현재 권한으로 조건을 새로 작성하세요.",
      );
      return;
    }
    if (
      (stored?.groupFields != null &&
        (!Array.isArray(stored.groupFields) ||
          stored.groupFields.length < 1 ||
          stored.groupFields.length > 3 ||
          stored.groupFields.some(
            (name) =>
              typeof name !== "string" ||
              !fields.some((field) => field.name === name),
          ) ||
          new Set(stored.groupFields).size !== stored.groupFields.length)) ||
      (stored?.includeRelations != null &&
        typeof stored.includeRelations !== "boolean")
    ) {
      setError("저장 검색의 집계 필드·관계 분석 설정을 확인하세요.");
      return;
    }
    const spec: HuntQueryV2 = {
      from: stored?.from ?? "",
      to: stored?.to ?? "",
      filter: stored?.filter ?? null,
      text: stored?.text ?? "",
      groupBy: stored?.groupFields?.[0] ?? stored?.groupBy ?? "event_type",
      metric: stored?.metric ?? "count",
      pageSize: stored?.pageSize || 200,
      ...(stored?.groupFields && stored.groupFields.length > 1
        ? { groupFields: stored.groupFields }
        : {}),
      ...(stored?.includeRelations ? { includeRelations: true } : {}),
    };
    if (
      !spec ||
      typeof spec.from !== "string" ||
      typeof spec.to !== "string" ||
      !Number.isFinite(Date.parse(spec.from)) ||
      !Number.isFinite(Date.parse(spec.to))
    ) {
      setError("저장 검색의 시간 범위를 확인하세요.");
      return;
    }
    const errors = validateFilter(spec.filter, fields);
    if (errors.length) {
      setError(errors.join(" "));
      return;
    }
    setFrom(spec.from);
    setTo(spec.to);
    setSearch(spec.text);
    setAsset("");
    setFilter(spec.filter);
    setGroupBy(spec.groupBy);
    setExtraGroups(spec.groupFields?.slice(1) ?? []);
    setIncludeRelations(spec.includeRelations ?? false);
    setMetric(spec.metric);
    setPageSize(spec.pageSize);
    void run(undefined, spec);
  }

  async function openRelationEvidence(eventId: string) {
    if (!job || job.state !== "COMPLETED") return;
    detailRequest.current?.abort();
    const controller = new AbortController();
    detailRequest.current = controller;
    const jobId = job.id;
    const event = await huntApi.event(jobId, eventId, controller.signal);
    if (controller.signal.aborted || currentJobId.current !== jobId) return;
    revealDetail.current = true;
    setSelected(event);
  }
  async function saveSearch(event: React.FormEvent) {
    event.preventDefault();
    setPending(true);
    setError("");
    try {
      await huntApi.save(
        "saved-search",
        saveTitle,
        { query: query() },
        queryShared,
      );
      await loadResources();
      setSaveOpen(false);
      setSaveTitle("");
      setNotice("조회 조건과 절대 시간을 저장했습니다.");
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : String(cause));
    } finally {
      setPending(false);
    }
  }
  async function refreshCaseEvidence(id: string) {
    const generation = ++caseReadGeneration.current;
    try {
      const [rows, history] = await Promise.all([
        huntApi.evidence(id),
        huntApi.audit(id),
      ]);
      if (
        caseSelection.current !== id ||
        caseReadGeneration.current !== generation
      )
        return;
      setEvidence(rows);
      setAudit(history);
    } catch (cause) {
      if (
        caseSelection.current === id &&
        caseReadGeneration.current === generation
      )
        setError(cause instanceof Error ? cause.message : String(cause));
    }
  }
  async function openCase(item: HuntResource) {
    caseSelection.current = item.id;
    setCaseWorkflow(null);
    setEdit(item);
    setCaseTitle(item.title);
    setHypothesis(String(item.body.hypothesis ?? ""));
    setNotes(String(item.body.notes ?? ""));
    setCaseStatus(String(item.body.status ?? "INVESTIGATING"));
    setVerdict(String(item.body.verdict ?? "UNDETERMINED"));
    setShared(item.shared);
    setError("");
    setEvidence([]);
    setAudit([]);
    await refreshCaseEvidence(item.id);
  }
  function clearCaseEditor() {
    caseSelection.current = null;
    caseReadGeneration.current++;
    setEdit(null);
    setCaseWorkflow(null);
    setCaseTitle("");
    setShared(false);
    setHypothesis("");
    setNotes("");
    setVerdict("UNDETERMINED");
    setCaseStatus("INVESTIGATING");
    setEvidence([]);
    setAudit([]);
  }
  function forgetCase(id: string) {
    setInvestigations((previous) => previous.filter((item) => item.id !== id));
    setInvestigationId((previous) => (previous === id ? "" : previous));
    if (caseSelection.current === id) clearCaseEditor();
    setNotice("변경을 저장했습니다. 접근 권한이 해제되어 조사를 닫았습니다.");
  }
  async function saveCase(event: React.FormEvent) {
    event.preventDefault();
    if (!caseCanEdit) return;
    const selection = caseSelection.current;
    setPending(true);
    setError("");
    try {
      const item = await huntApi.save(
        "investigation",
        caseTitle,
        {
          ...(edit?.body ?? {}),
          hypothesis,
          notes,
          status: caseStatus,
          verdict,
        },
        shared,
        edit ?? undefined,
      );
      await loadResources();
      if (item.casePermissions?.read === false) {
        forgetCase(item.id);
      } else {
        if (caseSelection.current === selection) {
          await openCase(item);
          setInvestigationId(item.id);
        }
        setNotice("조사를 저장했습니다.");
      }
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : String(cause));
    } finally {
      setPending(false);
    }
  }
  function applyCaseChange(item: HuntResource, preserveDraft: boolean) {
    if (item.casePermissions?.read === false) {
      forgetCase(item.id);
      return;
    }
    setInvestigations((previous) =>
      previous.map((value) => (value.id === item.id ? item : value)),
    );
    if (caseSelection.current !== item.id) return;
    if (!preserveDraft) {
      void openCase(item);
      return;
    }
    setEdit(item);
    setCaseWorkflow(null);
    void refreshCaseEvidence(item.id);
  }
  async function reloadCase() {
    const id = caseSelection.current;
    if (!id) return;
    const item = await huntApi.resource("investigation", id);
    if (caseSelection.current === id) await openCase(item);
    setInvestigations((previous) =>
      previous.map((value) => (value.id === item.id ? item : value)),
    );
  }
  async function downloadCase(item: HuntResource) {
    if (exportingCase) return;
    setExportingCase(item.id);
    setError("");
    try {
      const download = await huntApi.downloadExport(item.id, item.version);
      const link = document.createElement("a");
      link.href = download.url;
      link.download = download.filename;
      document.body.appendChild(link);
      try {
        link.click();
      } finally {
        link.remove();
      }
      setNotice(
        `저장된 조사 ‘${item.title}’ 다운로드를 시작했습니다. 파일 SHA-256: ${download.sha256}`,
      );
      if (caseSelection.current === item.id) await refreshCaseEvidence(item.id);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : String(cause));
    } finally {
      setExportingCase(null);
    }
  }
  async function pin() {
    if (!job || !selected || !investigationId) return;
    const targetId = investigationId;
    setPending(true);
    setError("");
    try {
      const pinned = await huntApi.pin(targetId, job.id, selected.id);
      if (
        caseSelection.current === targetId &&
        pinned.previousVersion !== undefined &&
        pinned.caseVersion !== undefined
      ) {
        setEdit((previous) =>
          previous?.id === targetId &&
          previous.version === pinned.previousVersion
            ? { ...previous, version: pinned.caseVersion! }
            : previous,
        );
      }
      setNotice("원본 참조·조회 조건·해시와 함께 증거를 고정했습니다.");
      if (caseSelection.current === targetId)
        await refreshCaseEvidence(targetId);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : String(cause));
    } finally {
      setPending(false);
    }
  }
  async function createRule(scenario: HuntScenario) {
    setPending(true);
    setError("");
    try {
      await huntApi.save(
        "rule",
        scenario.title,
        {
          scenarioId: scenario.id,
          scenarioVersion: scenario.version,
          state: "DRAFT",
          intervalSeconds: 60,
          query: query(true),
        },
        true,
      );
      await loadResources();
      setNotice("규칙 초안을 저장했습니다. 규칙·운영에서 검토하세요.");
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : String(cause));
    } finally {
      setPending(false);
    }
  }
  const table = (
    <div className="hw-table-wrap">
      <table className="hw-events">
        <thead>
          <tr>
            <th>시각 / 유형</th>
            <th>관측 자산</th>
            <th>출발지</th>
            <th>목적지</th>
            <th>계정</th>
            <th>조사</th>
          </tr>
        </thead>
        <tbody>
          {page?.items.map((row) => (
            <tr
              key={row.id}
              className={selected?.id === row.id ? "is-selected" : ""}
            >
              <td>
                <time>{date(row.at)}</time>
                <code>{row.event_type}</code>
              </td>
              <td>
                {row.asset_uid}
                <small>{text(row.source_id)}</small>
              </td>
              <td>
                <button
                  type="button"
                  className="hw-value"
                  disabled={!row.src_ip}
                  onClick={() => addFilter("src_ip", "eq", String(row.src_ip))}
                >
                  {text(row.src_ip)}
                </button>
              </td>
              <td>
                <button
                  type="button"
                  className="hw-value"
                  disabled={!row.dst_ip}
                  onClick={() => addFilter("dst_ip", "eq", String(row.dst_ip))}
                >
                  {text(row.dst_ip)}
                </button>
                <small>
                  {row.dst_port
                    ? `:${row.dst_port} · ${text(row.protocol)}`
                    : ""}
                </small>
              </td>
              <td>{text(row.actor)}</td>
              <td>
                <button
                  type="button"
                  aria-label={`${row.event_type} 이벤트 조사`}
                  onClick={() => setSelected(row)}
                >
                  <ChevronRight size={15} />
                  상세
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {!page?.items.length && (
        <div className="hw-empty">
          <FileSearch size={28} />
          <strong>
            {busy
              ? "선택 기간 전체를 조회하고 있습니다."
              : result
                ? "조건에 맞는 이벤트가 없습니다."
                : "데이터에서 조사할 질문을 시작하세요."}
          </strong>
          <span>
            {result
              ? "필터와 원천 수집 상태를 확인하세요."
              : "기간·원천·주소·계정을 지정하거나 헌팅 시나리오를 실행하세요."}
          </span>
        </div>
      )}
    </div>
  );

  return (
    <ViewFrame
      title="Threat Hunting"
      subtitle="데이터를 탐색하고, 가설을 검증하고, 증거로 조사를 이어갑니다."
      actions={
        <button type="button" onClick={onLegacy}>
          <ArrowLeft size={14} />
          기존 분석
        </button>
      }
    >
      <nav className="hw-tabs" aria-label="헌팅 작업 공간">
        {tabs.map(([key, name]) => (
          <button
            key={key}
            type="button"
            aria-current={tab === key ? "page" : undefined}
            onClick={() => setTab(key)}
          >
            {name}
            {key === "scenarios" && <span>{scenarios.length || 48}</span>}
          </button>
        ))}
      </nav>
      {error && (
        <div className="hw-feedback hw-error" role="alert">
          <span>{error}</span>
          <button
            type="button"
            onClick={() => setError("")}
            aria-label="오류 메시지 닫기"
          >
            <X size={14} />
          </button>
        </div>
      )}
      {notice && (
        <div className="hw-feedback" role="status">
          <span>{notice}</span>
        </div>
      )}
      <section
        className="data-panel hw-search-panel"
        aria-label="공통 조회 조건"
      >
        <form
          onSubmit={(event) => {
            event.preventDefault();
            void run();
          }}
          className="hw-query-form"
        >
          <label className="hw-search">
            <Search size={17} />
            <input
              aria-label="헌팅 검색어"
              disabled={role === "VIEWER"}
              aria-describedby={
                role === "VIEWER" ? "hw-viewer-search-help" : undefined
              }
              placeholder="IP, 도메인, 메시지 검색"
              value={search}
              onChange={(event) => setSearch(event.target.value)}
              maxLength={512}
            />
          </label>
          {role === "VIEWER" && (
            <p id="hw-viewer-search-help" className="hw-hint">
              조회 사용자는 필드 조건으로 검색하세요. 원문 전체 검색에는 분석가
              권한이 필요합니다.
            </p>
          )}
          <select
            aria-label="관측 자산"
            value={asset}
            onChange={(event) => setAsset(event.target.value)}
          >
            <option value="">모든 관측 자산</option>
            {assets.map((item) => (
              <option value={item.assetUid} key={item.assetUid}>
                {item.name || item.assetUid}
              </option>
            ))}
          </select>
          <button type="submit" className="hw-primary" disabled={busy}>
            <Play size={15} />
            조회 실행
          </button>
          {busy && job && (
            <button
              type="button"
              onClick={() => {
                void huntApi
                  .cancel(job.id)
                  .then((cancelled) => {
                    poll.current?.abort();
                    setPending(false);
                    setJob(cancelled);
                  })
                  .catch((cause) => setError(cause.message));
              }}
            >
              <Square size={14} />
              취소
            </button>
          )}
        </form>
        <div className="hw-timebar">
          <label>
            시작
            <input
              type="datetime-local"
              step="0.001"
              aria-label="조회 시작 시각"
              value={from ? local(Date.parse(from)) : ""}
              onChange={(event) =>
                setFrom(
                  event.target.value
                    ? new Date(event.target.value).toISOString()
                    : "",
                )
              }
              required
            />
          </label>
          <label>
            종료
            <input
              type="datetime-local"
              step="0.001"
              aria-label="조회 종료 시각"
              value={to ? local(Date.parse(to)) : ""}
              onChange={(event) =>
                setTo(
                  event.target.value
                    ? new Date(event.target.value).toISOString()
                    : "",
                )
              }
              required
            />
          </label>
          <div className="hw-quick-ranges">
            {[
              [1, "1시간"],
              [24, "24시간"],
              [168, "7일"],
              [720, "30일"],
            ].map(([hours, label]) => (
              <button
                key={hours}
                type="button"
                onClick={() => range(Number(hours))}
              >
                {label}
              </button>
            ))}
          </div>
          <span>현재 브라우저 시간대 · 최대 30일</span>
        </div>
        <div className="hw-filterbar">
          <Filter size={14} />
          <select
            aria-label="조건 필드"
            value={field}
            onChange={(event) => {
              setField(event.target.value);
              if (
                !operators(
                  fields.find((item) => item.name === event.target.value)
                    ?.type ?? "string",
                ).includes(operator)
              )
                setOperator("eq");
            }}
          >
            {(fields.length
              ? fields
              : [{ name: "src_ip", label: "출발지 IP", type: "ip" }]
            ).map((item) => (
              <option value={item.name} key={item.name}>
                {item.label}
              </option>
            ))}
          </select>
          <select
            aria-label="조건 연산자"
            value={operator}
            onChange={(event) => setOperator(event.target.value)}
          >
            {operators(fields.find((item) => item.name === field)?.type ?? "ip")
              .filter((op) => !["in", "not_in"].includes(op))
              .map((op) => (
                <option value={op} key={op}>
                  {operatorLabels[op]}
                </option>
              ))}
          </select>
          <input
            aria-label="조건 값"
            placeholder="조건 값"
            value={value}
            disabled={operator === "exists"}
            onChange={(event) => setValue(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === "Enter") {
                event.preventDefault();
                addFilter(field, operator, value);
              }
            }}
          />
          <button
            type="button"
            onClick={() => addFilter(field, operator, value)}
          >
            <Plus size={14} />
            조건 추가
          </button>
          {canEdit && (
            <button
              type="button"
              className="hw-save"
              onClick={() => setSaveOpen((current) => !current)}
            >
              <BookmarkPlus size={14} />
              검색 저장
            </button>
          )}
        </div>
        <HuntFilterEditor value={filter} fields={fields} onChange={setFilter} />
        <label className="hw-relations-toggle">
          <input
            type="checkbox"
            checked={includeRelations}
            onChange={(event) => setIncludeRelations(event.target.checked)}
          />
          관계 분석 포함
        </label>
        {saveOpen && (
          <form
            className="hw-inline-form"
            onSubmit={(event) => {
              void saveSearch(event);
            }}
          >
            <input
              aria-label="저장 검색 이름"
              placeholder="저장 검색 이름"
              value={saveTitle}
              onChange={(event) => setSaveTitle(event.target.value)}
              required
              maxLength={240}
            />
            <label>
              <input
                type="checkbox"
                checked={queryShared}
                onChange={(event) => setQueryShared(event.target.checked)}
              />
              조직에 공유
            </label>
            <button type="submit" disabled={pending}>
              <Save size={14} />
              저장
            </button>
          </form>
        )}
      </section>

      {result && draftChanged && (
        <p className="hw-feedback" role="status">
          편집한 조건은 아직 조회하지 않았습니다. 표와 차트는 마지막 실행
          결과입니다.
        </p>
      )}

      {tab === "explore" && (
        <>
          <div className="hw-stats">
            <div>
              <span>일치 이벤트</span>
              <strong>{count(result?.summary.total)}</strong>
              <small>선택 기간 전체 · 중복 제거</small>
            </div>
            <div>
              <span>관측 자산</span>
              <strong>{count(result?.summary.assets)}</strong>
              <small>통신 주체와 센서는 구분됩니다</small>
            </div>
            <div>
              <span>주소 필드 보유</span>
              <strong>{count(result?.summary.addresses)}</strong>
              <small>조사 연결에 사용할 기록</small>
            </div>
            <div>
              <span>조회 상태</span>
              <strong className="hw-job-state">
                {job ? (statusNames[job.state] ?? job.state) : "실행 전"}
              </strong>
              <small>
                {job
                  ? `기준 시점 ${date(job.asOf)}`
                  : "필터를 지정하고 실행하세요"}
              </small>
            </div>
          </div>
          {result?.scenario && (
            <section className="data-panel hw-scenario-result">
              <div className="panel-heading">
                <h3>
                  {result.scenario.id} ·{" "}
                  {
                    scenarios.find((item) => item.id === result.scenario?.id)
                      ?.title
                  }
                </h3>
                <span
                  className={`hw-tag ${result.scenario.readiness === "READY" ? "hw-ready" : ""}`}
                >
                  {statusNames[result.scenario.readiness]}
                </span>
              </div>
              <div className="hw-result-body">
                {!!result.scenario.missingFields.length && (
                  <p>
                    필요 필드:{" "}
                    {result.scenario.missingFields.map(fieldLabel).join(", ")}
                  </p>
                )}
                {!!result.scenario.missingLookups.length && (
                  <p>필요 룩업: {result.scenario.missingLookups.join(", ")}</p>
                )}
                {result.scenario.readiness === "READY" && (
                  <>
                    <p>
                      {result.scenario.findings.length}개 조사 후보
                      {result.scenario.hasMore
                        ? " · 상위 100개 표시, 기간을 좁혀 나머지 구간을 확인하세요."
                        : ""}{" "}
                      · 규칙 일치는 침해 확정이 아닙니다.
                    </p>
                    {result.scenario.findings.map((finding, index) => (
                      <div className="hw-finding" key={index}>
                        {Object.entries(finding).map(([key, val]) => (
                          <span key={key}>
                            <small>{fieldLabel(key)}</small>
                            {text(val)}
                          </span>
                        ))}
                        {role !== "VIEWER" && job && (
                          <HuntExceptionRequest
                            key={`${job.id}:${index}`}
                            jobId={job.id}
                            index={index}
                            scenarioId={result.scenario!.id}
                            candidate={finding}
                          />
                        )}
                      </div>
                    ))}
                    {!!result.scenario.suppressed?.length && (
                      <details className="hw-exception-proof">
                        <summary>
                          승인 예외로 억제한 후보{" "}
                          {result.scenario.suppressed.length}개
                        </summary>
                        <p>
                          실행 당시의 승인 상태입니다. 원래 후보와 승인 근거를
                          보존합니다.
                        </p>
                        {result.scenario.suppressed.map((item, index) => (
                          <div key={index}>
                            <pre>{JSON.stringify(item.finding, null, 2)}</pre>
                            {item.approvals.map((approval) => (
                              <p key={approval.id}>
                                {approval.title} · 버전 {approval.version} ·{" "}
                                {approval.reason} · 만료{" "}
                                {date(approval.expiresAt)}
                                <code>{approval.id}</code>
                              </p>
                            ))}
                            {role !== "VIEWER" && job && (
                              <HuntExceptionRequest
                                key={`${job.id}:suppressed:${index}`}
                                jobId={job.id}
                                index={index}
                                scenarioId={result.scenario!.id}
                                sourceKind="SUPPRESSED"
                                candidate={item.finding}
                              />
                            )}
                          </div>
                        ))}
                      </details>
                    )}
                  </>
                )}
              </div>
            </section>
          )}
          <section className="data-panel hw-timeline">
            <div className="panel-heading">
              <h3>
                <Activity size={16} />
                시간 분포
              </h3>
              <span>
                {result
                  ? `${date(result.query.from)} ~ ${date(result.query.to)}`
                  : "실행 후 전체 범위의 시간 분포를 표시합니다."}
              </span>
            </div>
            {result?.histogram.length ? (
              <div className="hw-chart">
                <ResponsiveContainer width="100%" height={160}>
                  <BarChart
                    data={result.histogram}
                    onClick={(state) => {
                      const point =
                        state?.activeIndex === undefined
                          ? undefined
                          : result.histogram[Number(state.activeIndex)];
                      if (point?.time) {
                        const start = Math.max(
                          point.time * 1000,
                          Date.parse(result.query.from),
                        );
                        const minutes = Math.max(
                          1,
                          Math.floor(
                            Math.floor(
                              (Date.parse(result.query.to) -
                                Date.parse(result.query.from)) /
                                60000,
                            ) / 90,
                          ),
                        );
                        setFrom(new Date(start).toISOString());
                        setTo(
                          new Date(
                            Math.min(
                              point.time * 1000 + minutes * 60000,
                              Date.parse(result.query.to),
                            ),
                          ).toISOString(),
                        );
                      }
                    }}
                  >
                    <CartesianGrid vertical={false} stroke="#e6edf1" />
                    <XAxis
                      dataKey="time"
                      tickFormatter={(timestamp) =>
                        new Date(Number(timestamp) * 1000).toLocaleTimeString(
                          "ko-KR",
                          { hour: "2-digit", minute: "2-digit" },
                        )
                      }
                      tick={{ fontSize: 10 }}
                      minTickGap={50}
                    />
                    <YAxis tick={{ fontSize: 10 }} width={45} />
                    <Tooltip
                      labelFormatter={(timestamp) =>
                        date(new Date(Number(timestamp) * 1000).toISOString())
                      }
                    />
                    <Bar
                      dataKey="count"
                      name="이벤트"
                      fill="#318b96"
                      radius={[2, 2, 0, 0]}
                    />
                  </BarChart>
                </ResponsiveContainer>
                <small>
                  막대를 선택해 해당 시간 구간으로 좁힐 수 있습니다. 변경 후
                  조회를 실행하세요.
                </small>
              </div>
            ) : (
              <div className="hw-chart-empty">
                {busy ? "분포를 집계하고 있습니다." : "분포 데이터가 없습니다."}
              </div>
            )}
          </section>
          <HuntAggregationPanel
            fields={fields}
            dimensions={[groupBy, ...extraGroups]}
            metric={metric}
            onDimensions={(names) => {
              setGroupBy(names[0]);
              setExtraGroups(names.slice(1));
            }}
            onMetric={setMetric}
            onRun={() => {
              void run();
            }}
            busy={!!busy}
            result={result}
            onFilter={(condition) =>
              setFilter((current) => appendFilter(current, condition))
            }
          />
          <HuntRelationsPanel
            key={job?.id ?? "before-query"}
            graph={result?.relations}
            busy={!!busy}
            onEvent={openRelationEvidence}
            onRun={() => {
              try {
                const spec = query();
                setIncludeRelations(true);
                void run(undefined, { ...spec, includeRelations: true });
              } catch (cause) {
                setError(
                  cause instanceof Error ? cause.message : String(cause),
                );
              }
            }}
          />
          <div className={`hw-exploration ${selected ? "hw-with-detail" : ""}`}>
            <aside className="data-panel hw-fields">
              <div className="panel-heading">
                <h3>저장 검색</h3>
              </div>
              {!!saved.length && (
                <details open>
                  <summary>저장 검색 · {saved.length}</summary>
                  {saved.map((item) => (
                    <button
                      type="button"
                      className="hw-saved"
                      key={item.id}
                      disabled={busy}
                      onClick={() => useSaved(item)}
                    >
                      {item.title}
                      <small>{item.shared ? "조직 공유" : "개인"}</small>
                    </button>
                  ))}
                </details>
              )}
            </aside>
            <section className="data-panel hw-results">
              <div className="panel-heading">
                <h3>이벤트</h3>
                <span>{count(result?.summary.total)}건 · 최신순</span>
              </div>
              {table}
              <div className="hw-pagination">
                <label>
                  페이지 크기
                  <select
                    aria-label="페이지 크기"
                    value={pageSize}
                    onChange={(event) =>
                      setPageSize(Number(event.target.value))
                    }
                  >
                    {[50, 100, 200, 500, 1000].map((size) => (
                      <option value={size} key={size}>
                        {size}건
                      </option>
                    ))}
                    {![50, 100, 200, 500, 1000].includes(pageSize) && (
                      <option value={pageSize}>{pageSize}건</option>
                    )}
                  </select>
                </label>
                <span>
                  {page
                    ? `${pageHistory.length}페이지 · ${page.items.length}건 표시`
                    : `페이지당 ${pageSize}건`}
                </span>
                <button
                  type="button"
                  disabled={busy || pageHistory.length <= 1}
                  onClick={() => {
                    void paginate(false);
                  }}
                >
                  이전
                </button>
                <button
                  type="button"
                  disabled={busy || !page?.hasMore}
                  onClick={() => {
                    void paginate(true);
                  }}
                >
                  다음
                </button>
              </div>
            </section>
            {selected && (
              <aside
                className="data-panel hw-detail"
                ref={detailRef}
                tabIndex={-1}
                aria-label="이벤트 조사 패널"
              >
                <div className="panel-heading">
                  <h3>이벤트 조사</h3>
                  <button
                    type="button"
                    onClick={() => setSelected(null)}
                    aria-label="조사 패널 닫기"
                  >
                    <X size={16} />
                  </button>
                </div>
                <div className="hw-detail-body">
                  <span className="hw-tag">{selected.event_type}</span>
                  <h3>
                    {text(selected.src_ip)} → {text(selected.dst_ip)}
                  </h3>
                  <p>
                    {date(selected.at)} · 관측 {selected.asset_uid}
                  </p>
                  <dl>
                    {Object.entries(selected)
                      .filter(
                        ([key, val]) =>
                          !["event_json", "observed_at", "id"].includes(key) &&
                          val !== "" &&
                          val !== null,
                      )
                      .map(([key, val]) => (
                        <div key={key}>
                          <dt>{fieldLabel(key)}</dt>
                          <dd>{text(val)}</dd>
                        </div>
                      ))}
                  </dl>
                  <h4>관련 활동으로 이어가기</h4>
                  <div className="hw-pivots">
                    {[
                      "src_ip",
                      "dst_ip",
                      "actor",
                      "domain",
                      "connection_uid",
                      "community_id",
                      "process_id",
                    ]
                      .filter((key) => selected[key])
                      .map((key) => (
                        <button
                          type="button"
                          key={key}
                          onClick={() => {
                            addFilter(key, "eq", String(selected[key]));
                            setNotice(
                              "관련 활동 조건을 추가했습니다. 조회 실행으로 범위 내 기록을 확인하세요.",
                            );
                          }}
                        >
                          {fieldLabel(key)}로 검색
                        </button>
                      ))}
                  </div>
                  {canEdit && (
                    <div className="hw-pin">
                      <label>
                        증거를 저장할 조사
                        <select
                          aria-label="증거 대상 조사"
                          value={investigationId}
                          onChange={(event) =>
                            setInvestigationId(event.target.value)
                          }
                        >
                          <option value="">조사 선택</option>
                          {investigations
                            .filter(
                              (item) => item.casePermissions?.edit !== false,
                            )
                            .map((item) => (
                              <option key={item.id} value={item.id}>
                                {item.title}
                              </option>
                            ))}
                        </select>
                      </label>
                      <button
                        type="button"
                        className="hw-primary"
                        disabled={
                          busy ||
                          !investigationId ||
                          investigations.find(
                            (item) => item.id === investigationId,
                          )?.casePermissions?.edit === false
                        }
                        onClick={() => {
                          void pin();
                        }}
                      >
                        <BookmarkPlus size={14} />
                        증거 고정
                      </button>
                    </div>
                  )}
                  {selected.event_json && (
                    <details>
                      <summary>원문 · 관리자 권한</summary>
                      <pre>{String(selected.event_json)}</pre>
                    </details>
                  )}
                </div>
              </aside>
            )}
          </div>
        </>
      )}

      {tab === "entities" && (
        <section className="data-panel hw-entity">
          <div className="panel-heading">
            <h3>엔터티 활동 조사</h3>
            <span>같은 IP가 항상 같은 호스트를 의미하지는 않습니다.</span>
          </div>
          <form
            className="hw-inline-form"
            onSubmit={(event) => {
              event.preventDefault();
              const spec = query();
              spec.filter = {
                op: "eq",
                field: entityType,
                values: [entityValue],
              };
              spec.text = "";
              void run(undefined, spec);
            }}
          >
            <select
              aria-label="엔터티 종류"
              value={entityType}
              onChange={(event) => setEntityType(event.target.value)}
            >
              {[
                "src_ip",
                "dst_ip",
                "actor",
                "domain",
                "process_id",
                "connection_uid",
                "community_id",
              ].map((name) => (
                <option key={name} value={name}>
                  {fieldLabel(name)}
                </option>
              ))}
            </select>
            <input
              aria-label="엔터티 값"
              placeholder="조사할 주소·계정·도메인·연결 ID"
              value={entityValue}
              onChange={(event) => setEntityValue(event.target.value)}
              required
            />
            <button className="hw-primary" type="submit" disabled={busy}>
              <Search size={15} />
              관련 활동 조회
            </button>
          </form>
          <p className="hw-help">
            관측 자산·수집원과 실제 활동 주체를 함께 확인하세요. 주소와 식별자가
            없는 기록을 임의로 연결하지 않습니다.
          </p>
        </section>
      )}

      {tab === "scenarios" && (
        <>
          <div className="hw-section-intro">
            <div>
              <h3>가설에서 시작하는 위협 헌팅</h3>
              <p>
                8개 영역 · {scenarios.length}개 시나리오. 실행 시
                필드·룩업·기준선을 확인합니다.
              </p>
            </div>
            <input
              aria-label="시나리오 검색"
              placeholder="시나리오 검색"
              value={scenarioSearch}
              onChange={(event) => setScenarioSearch(event.target.value)}
            />
          </div>
          <div className="hw-category-tabs">
            <button
              type="button"
              aria-pressed={scenarioGroup === "ALL"}
              onClick={() => setScenarioGroup("ALL")}
            >
              전체
            </button>
            {[...new Set(scenarios.map((item) => item.group))].map((group) => (
              <button
                key={group}
                type="button"
                aria-pressed={scenarioGroup === group}
                onClick={() => setScenarioGroup(group)}
              >
                {group}
              </button>
            ))}
          </div>
          <div className="hw-scenario-grid">
            {scenarios
              .filter(
                (item) =>
                  (scenarioGroup === "ALL" || item.group === scenarioGroup) &&
                  `${item.id} ${item.title} ${item.group}`
                    .toLowerCase()
                    .includes(scenarioSearch.toLowerCase()),
              )
              .map((item) => (
                <article key={item.id} className="data-panel hw-scenario-card">
                  <div>
                    <span className="hw-scenario-id">{item.id}</span>
                    <span className="hw-tag">
                      {modes[item.mode] ?? item.mode}
                    </span>
                  </div>
                  <h3>{item.title}</h3>
                  <p>{item.hypothesis}</p>
                  <div className="hw-scenario-meta">
                    <span>{item.windowMinutes}분 구간</span>
                    <span>기준 {item.threshold.toLocaleString()}</span>
                    {item.baseline && <span>14일 기준선</span>}
                  </div>
                  <details>
                    <summary>필요 데이터·조사 절차</summary>
                    <p>
                      {item.requiredFields.map(fieldLabel).join(", ") ||
                        "활성 IOC와 일치 필드"}
                      {item.requiredLookups.length
                        ? ` · 룩업: ${item.requiredLookups.join(", ")}`
                        : ""}
                    </p>
                    <ol>
                      {item.steps.map((step) => (
                        <li key={step}>{step}</li>
                      ))}
                    </ol>
                    <p>오탐 확인: {item.falsePositives.join(" · ")}</p>
                  </details>
                  <div className="hw-card-actions">
                    <button
                      type="button"
                      className="hw-primary"
                      disabled={busy}
                      onClick={() => {
                        void run(item);
                      }}
                    >
                      <Play size={14} />
                      가설 검증
                    </button>
                    {canEdit && (
                      <button
                        type="button"
                        disabled={busy}
                        onClick={() => {
                          void createRule(item);
                        }}
                      >
                        규칙 초안 저장
                      </button>
                    )}
                  </div>
                </article>
              ))}
          </div>
        </>
      )}

      {tab === "investigations" && (
        <div className="hw-case-layout">
          <section className="data-panel">
            <div className="panel-heading">
              <h3>저장된 조사 · {investigations.length}</h3>
              {canEdit && (
                <button
                  type="button"
                  onClick={() => {
                    clearCaseEditor();
                  }}
                >
                  <Plus size={14} />새 조사
                </button>
              )}
            </div>
            {investigations.length ? (
              investigations.map((item) => (
                <button
                  type="button"
                  className={`hw-case-item ${edit?.id === item.id ? "is-selected" : ""}`}
                  key={item.id}
                  onClick={() => {
                    void openCase(item);
                  }}
                >
                  <strong>{item.title}</strong>
                  <span>
                    {String(item.body.status ?? "DRAFT")} ·{" "}
                    {date(item.updatedAt)}
                  </span>
                </button>
              ))
            ) : (
              <p className="hw-help">
                가설과 증거를 저장하면 다음 세션에서 조사를 이어갈 수 있습니다.
              </p>
            )}
          </section>
          <section className="data-panel hw-case-editor">
            <div className="panel-heading">
              <h3>{edit ? "조사 기록" : "새 조사"}</h3>
              {edit && (
                <a
                  href={huntApi.exportUrl(edit.id)}
                  download
                  aria-disabled={!!exportingCase}
                  aria-busy={exportingCase === edit.id}
                  onClick={(event) => {
                    event.preventDefault();
                    void downloadCase(edit);
                  }}
                >
                  <Download size={14} />
                  {exportingCase === edit.id
                    ? "JSON 준비 중…"
                    : "JSON 내보내기"}
                </a>
              )}
            </div>
            {edit && caseDirty && (
              <p className="hw-help">
                내보내기에는 서버에 저장된 조사 내용만 포함됩니다. 미저장 초안은
                유지됩니다.
              </p>
            )}
            <form
              onSubmit={(event) => {
                void saveCase(event);
              }}
            >
              <label>
                조사 제목
                <input
                  aria-label="조사 제목"
                  value={caseTitle}
                  onChange={(event) => setCaseTitle(event.target.value)}
                  required
                  maxLength={240}
                  disabled={!caseCanEdit}
                />
              </label>
              <label>
                검증할 가설
                <textarea
                  aria-label="조사 가설"
                  value={hypothesis}
                  onChange={(event) => setHypothesis(event.target.value)}
                  rows={3}
                  disabled={!caseCanEdit}
                />
              </label>
              <div className="hw-case-selects">
                <label>
                  진행 상태
                  <select
                    aria-label="조사 진행 상태"
                    value={caseStatus}
                    onChange={(event) => setCaseStatus(event.target.value)}
                    disabled={!caseCanEdit}
                  >
                    <option value="DRAFT">작성 중</option>
                    <option value="INVESTIGATING">조사 중</option>
                    {caseStatus === "REVIEW" && (
                      <option value="REVIEW">
                        검토 중 · 협업 절차에서 변경
                      </option>
                    )}
                    {caseStatus === "CLOSED" && (
                      <option value="CLOSED">종료 · 협업 절차에서 재개</option>
                    )}
                  </select>
                </label>
                <label>
                  가설 판정
                  <select
                    aria-label="조사 판정"
                    value={verdict}
                    onChange={(event) => setVerdict(event.target.value)}
                    disabled={!caseCanEdit}
                  >
                    <option value="UNDETERMINED">미판정</option>
                    <option value="CONFIRMED">확인됨</option>
                    <option value="REFUTED">반증됨</option>
                    <option value="INSUFFICIENT">자료 부족</option>
                  </select>
                </label>
              </div>
              <label>
                분석 메모·한계
                <textarea
                  aria-label="조사 메모"
                  value={notes}
                  onChange={(event) => setNotes(event.target.value)}
                  rows={5}
                  disabled={!caseCanEdit}
                />
              </label>
              {canEdit && (
                <div className="hw-editor-actions">
                  <label>
                    <input
                      type="checkbox"
                      checked={shared}
                      onChange={(event) => setShared(event.target.checked)}
                      disabled={
                        !!edit &&
                        (!caseWorkflow?.permissions.manage || !caseCanEdit)
                      }
                    />
                    조직 공유
                  </label>
                  <button
                    type="submit"
                    className="hw-primary"
                    disabled={busy || !caseCanEdit}
                  >
                    <Save size={14} />
                    조사 저장
                  </button>
                </div>
              )}
            </form>
            {edit && (
              <HuntCaseCollaboration
                key={edit.id}
                item={edit}
                dirty={caseDirty}
                disabled={busy}
                onWorkflow={onCaseWorkflow}
                onChange={applyCaseChange}
                onReload={reloadCase}
              />
            )}
            <div className="hw-evidence">
              <h4>고정 증거 · {evidence.length}</h4>
              {evidence.map((item) => (
                <details key={String(item.id)}>
                  <summary>
                    {String(item.eventId).slice(0, 16)} · {date(item.createdAt)}
                  </summary>
                  <p>
                    고정 원본 SHA-256: <code>{String(item.sha256)}</code>
                  </p>
                  {item.redacted === true && (
                    <p>
                      권한에 따라 원문을 제외한 사본입니다. 사본 SHA-256:{" "}
                      <code>{String(item.exportSha256)}</code>
                    </p>
                  )}
                  <p>
                    해시 대상 UTF-8 JSON · 숫자를 다시 변환하지 않고 검증하세요.
                  </p>
                  <pre>
                    {typeof item.snapshotJson === "string"
                      ? item.snapshotJson
                      : JSON.stringify(item.snapshot, null, 2)}
                  </pre>
                </details>
              ))}
              {!evidence.length && (
                <p>탐색 화면의 이벤트 상세에서 이 조사로 증거를 고정하세요.</p>
              )}
            </div>
            <details className="hw-audit">
              <summary>조사 이력 · {audit.length}</summary>
              {audit.map((item, index) => (
                <p key={index}>
                  {date(item.at)} · {String(item.action)} · 사용자{" "}
                  {String(item.actorId)}
                </p>
              ))}
            </details>
          </section>
        </div>
      )}

      {tab === "operations" && (
        <HuntOperations role={role} rules={rules} reload={loadResources} />
      )}
    </ViewFrame>
  );
}
