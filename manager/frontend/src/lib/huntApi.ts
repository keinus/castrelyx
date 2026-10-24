export type HuntFilter = {
  op: string;
  field?: string;
  values?: string[];
  children?: HuntFilter[];
};
export type HuntCursor = { at: string; id: string };
export type HuntQueryV2 = {
  redacted?: boolean;
  from: string;
  to: string;
  filter: HuntFilter | null;
  text: string;
  groupBy: string;
  metric: string;
  pageSize: number;
  cursor?: HuntCursor | null;
  groupFields?: string[] | null;
  includeRelations?: boolean;
};
export type HuntRelationNode = {
  id: string;
  kind: "PROCESS" | "CONNECTION" | "ENDPOINT";
  label: string;
  attributes: Record<string, string>;
  referenceOnly: boolean;
  ambiguous: boolean;
  firstSeen: string | null;
  lastSeen: string | null;
  startedAt: string | null;
  endedAt: string | null;
  eventIds: string[];
  evidenceCount: number;
};
export type HuntRelationEdge = {
  id: string;
  from: string;
  to: string;
  kind: string;
  eventIds: string[];
  evidenceCount: number;
};
export type HuntRelationsV2 = {
  version: string;
  nodes: HuntRelationNode[];
  edges: HuntRelationEdge[];
  issues: Record<string, number>;
  issueSamples: { eventId: string; reason: string }[];
  coverage: {
    matchedEvents: number;
    evaluatedEvents: number;
    unexaminedEvents: number;
    inputLimit: number;
    nodeLimit: number;
    edgeLimit: number;
    omittedNodes: number;
    omittedEdges: number;
    truncated: boolean;
    complete: boolean;
  };
};
export type HuntGroupV2 = {
  value: string;
  count: number;
  metric: number;
  dimensions?: Record<string, string | null>;
  hostCount?: number;
  bytesSent?: number;
  bytesReceived?: number;
};
export type HuntEventV2 = Record<string, string | number | null> & {
  id: string;
  at: string;
  event_type: string;
  asset_uid: string;
};
export type HuntPageV2 = {
  items: HuntEventV2[];
  nextCursor: HuntCursor | null;
  hasMore: boolean;
};
export type ScenarioResult = {
  id: string;
  version: string;
  readiness: string;
  missingFields: string[];
  missingLookups: string[];
  findings: Record<string, string | number>[];
  complete: boolean;
  hasMore?: boolean;
  caveat?: string;
  suppressed?: {
    finding: Record<string, string | number | null>;
    approvals: {
      id: string;
      version: number;
      title: string;
      reason: string;
      expiresAt: string;
      evidenceSha256: string;
    }[];
  }[];
  exceptionPolicyVersion?: string;
};
export type HuntException = {
  id: string;
  state: string;
  effectiveState: string;
  version: number;
  title: string;
  reason: string;
  scenarioId: string;
  scenarioVersion: string;
  scopeKey: string;
  scope: Record<string, string | number | null>;
  requestedBy: number;
  approvedBy: number | null;
  approvedAt: string | null;
  validFrom: string;
  expiresAt: string;
  createdAt: string;
  updatedAt: string;
  evidenceSha256: string;
  permissions: { decide: boolean; revoke: boolean };
  evidence?: Record<string, unknown>;
  evidenceJson?: string;
  exportEvidenceSha256?: string;
  redacted?: boolean;
  history?: {
    version: number;
    state: string;
    actor_id: number;
    note: string;
    created_at: string;
  }[];
  usage?: HuntExceptionUsage;
};
export type HuntExceptionUsage = {
  items: {
    id: number;
    actorId: number;
    createdAt: string;
    detail: {
      jobId: string;
      asOf: string;
      scenarioId: string;
      scenarioVersion: string;
      approvalVersion: number;
      evidenceSha256: string;
      finding: Record<string, string | number | null>;
    };
  }[];
  hasMore: boolean;
  nextCursor: number | null;
};
export type HuntExceptionPage = {
  items: HuntException[];
  hasMore: boolean;
  nextCursor: { at: string; id: string } | null;
};
export type HuntJobV2 = {
  id: string;
  state: string;
  asOf: string;
  createdAt: string;
  error?: string;
  result?: {
    query: HuntQueryV2;
    summary: {
      total: number;
      assets: number;
      oldest: string;
      newest: string;
      addresses: number;
      accounts: number;
    };
    histogram: { time: number; count: number }[];
    groups: HuntGroupV2[];
    groupHasMore?: boolean;
    relations?: HuntRelationsV2;
    page: HuntPageV2;
    scenario?: ScenarioResult;
    coverage: { complete: boolean; sampled: boolean };
  };
};
export type HuntFieldV2 = { name: string; label: string; type: string };
export type HuntScenario = {
  id: string;
  group: string;
  title: string;
  hypothesis: string;
  requiredFields: string[];
  requiredLookups: string[];
  windowMinutes: number;
  threshold: number;
  baseline: boolean;
  mode: string;
  falsePositives: string[];
  steps: string[];
  version: string;
};
export type HuntResource = {
  casePermissions?: { read?: boolean; edit: boolean; manage: boolean } | null;
  id: string;
  kind: string;
  ownerId: number;
  shared: boolean;
  title: string;
  body: Record<string, unknown>;
  version: number;
  createdAt: string;
  updatedAt: string;
};
export type HuntRuleRuntime = {
  rule_id: string;
  ready_runs: number;
  observed_since?: string;
  last_state: string;
  last_error?: string;
  last_finished_at?: string;
};
export type HuntCaseAnalyst = {
  id: number;
  username: string;
  displayName: string | null;
  role: string;
  enabled: boolean;
};
export type HuntCaseReview = {
  id: number;
  caseVersion: number;
  submittedBy: number;
  reviewerId: number;
  state: string;
  requestNote: string;
  decisionNote: string | null;
  sha256: string;
  createdAt: string;
  decidedAt: string | null;
  decidedBy: number | null;
};
export type HuntCaseReviewPage = {
  items: HuntCaseReview[];
  nextBefore: number | null;
};
export type HuntCaseWorkflow = {
  version: number;
  assignee: HuntCaseAnalyst | null;
  reviewer: HuntCaseAnalyst | null;
  permissions: {
    edit: boolean;
    manage: boolean;
    requestReview: boolean;
    decide: boolean;
    approve: boolean;
    reopen: boolean;
  };
  reviewReadiness: string[];
  pendingReview: HuntCaseReview | null;
  evidenceChanged: boolean;
  history: HuntCaseReviewPage;
};
export type HuntFinding = {
  id: string;
  scenarioId: string;
  ruleId: string;
  ruleVersion: number;
  version: number;
  firstSeen: string;
  lastSeen: string;
  jobId: string;
  status: string;
  details: Record<string, unknown>;
};
export type HuntEpisodeProof = {
  id: number;
  findingId?: string;
  jobId: string;
  createdAt: string;
  sourceId?: string;
  targetId?: string;
  actorId?: number;
  origin?: string;
  evidence: Record<string, unknown>;
  evidenceJson: string;
  evidenceSha256: string;
  exportEvidenceSha256?: string;
};
export type HuntEpisodePage = {
  items: HuntEpisodeProof[];
  hasMore: boolean;
  nextCursor: number | null;
};
export type HuntEpisodeDetail = {
  id: string;
  rule_id: string;
  rule_version: number;
  scenario_id: string;
  version: number;
  status: string;
  merged_into: string | null;
  last_job_id: string;
  firstSeen: string;
  lastSeen: string;
  latestCandidate: Record<string, unknown>;
  occurrences: HuntEpisodePage;
  merges: HuntEpisodePage;
};
export type HuntStorageStatus = {
  pendingMutations: number;
  queryMode: "PHYSICAL" | "JSON";
  schema: {
    ready: boolean;
    missing: string[];
    incompatible: string[];
    checkedAt: string;
    error?: string | null;
  };
  partitions: {
    partition: string;
    rows: number;
    bytes: number;
    parts: number;
  }[];
  mutations: {
    id: string;
    command: string;
    create_time: string;
    parts_to_do: number;
    is_done: number;
    latest_fail_reason: string;
  }[];
};
const root = "/api/security/hunt/v2";
async function token(): Promise<string> {
  const response = await fetch(`${root}/session`, { credentials: "include" });
  if (!response.ok)
    throw new Error("헌팅 세션을 확인할 수 없습니다. 다시 로그인하세요.");
  return String((await response.json()).csrfToken);
}
async function request<T>(
  path: string,
  body?: unknown,
  method = "POST",
  signal?: AbortSignal,
): Promise<T> {
  const response = await fetch(root + path, {
    method: body === undefined ? "GET" : method,
    credentials: "include",
    signal,
    headers: {
      "Content-Type": "application/json",
      ...(body === undefined ? {} : { "X-Hunt-CSRF": await token() }),
    },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  });
  if (!response.ok) {
    const data = (await response.json().catch(() => null)) as {
      error?: string;
      message?: string;
    } | null;
    throw new Error(
      data?.error ?? data?.message ?? `조회 실패 (${response.status})`,
    );
  }
  return (await response.json()) as T;
}
export const huntApi = {
  findingEvidence: (id: string) =>
    request<HuntEpisodeDetail>(`/findings/${encodeURIComponent(id)}`),
  findingProofs: (id: string, kind: "occurrences" | "merges", before: number) =>
    request<HuntEpisodePage>(
      `/findings/${encodeURIComponent(id)}/${kind}?before=${before}`,
    ),
  exceptions: (cursor?: { at: string; id: string }) =>
    request<HuntExceptionPage>(
      "/exceptions" +
        (cursor
          ? "?" +
            new URLSearchParams({ beforeAt: cursor.at, beforeId: cursor.id })
          : ""),
    ),
  exception: (id: string) =>
    request<HuntException>(`/exceptions/${encodeURIComponent(id)}`),
  requestException: (input: {
    jobId: string;
    candidateIndex: number;
    title: string;
    reason: string;
    validFrom: string;
    expiresAt: string;
    sourceKind?: "FINDING" | "SUPPRESSED";
  }) => request<HuntException>("/exceptions", input),
  exceptionUsage: (id: string, before: number) =>
    request<HuntExceptionUsage>(
      `/exceptions/${encodeURIComponent(id)}/usage?${new URLSearchParams({ before: String(before) })}`,
    ),
  decideException: (
    item: HuntException,
    action: "APPROVE" | "REJECT" | "REVOKE",
    reason: string,
  ) =>
    request<HuntException>(
      `/exceptions/${encodeURIComponent(item.id)}/decision`,
      { version: item.version, action, reason },
    ),
  storage: () => request<HuntStorageStatus>("/storage"),
  installReadModel: () => request<HuntStorageStatus>("/storage/install", {}),
  backfillReadModel: (partition: string) =>
    request<HuntStorageStatus>("/storage/backfill", { partition }),
  fields: () => request<HuntFieldV2[]>("/fields"),
  scenarios: () => request<HuntScenario[]>("/scenarios"),
  query: (query: HuntQueryV2) => request<HuntJobV2>("/queries", query),
  run: (id: string, query: HuntQueryV2) =>
    request<HuntJobV2>(`/scenarios/${encodeURIComponent(id)}/runs`, query),
  job: (id: string, signal?: AbortSignal) =>
    request<HuntJobV2>(
      `/queries/${encodeURIComponent(id)}`,
      undefined,
      "GET",
      signal,
    ),
  cancel: (id: string) =>
    request<HuntJobV2>(`/queries/${encodeURIComponent(id)}/cancel`, {}),
  page: (id: string, cursor: HuntCursor | null) =>
    request<HuntPageV2>(
      `/queries/${encodeURIComponent(id)}/events${cursor ? `?${new URLSearchParams({ at: cursor.at, eventId: cursor.id })}` : ""}`,
    ),
  event: (id: string, eventId: string, signal?: AbortSignal) =>
    request<HuntEventV2>(
      `/queries/${encodeURIComponent(id)}/events/${encodeURIComponent(eventId)}`,
      undefined,
      "GET",
      signal,
    ),
  resources: (kind: string) => request<HuntResource[]>(`/resources/${kind}`),
  resource: (kind: string, id: string) =>
    request<HuntResource>(`/resources/${kind}/${encodeURIComponent(id)}`),
  caseWorkflow: (id: string) =>
    request<HuntCaseWorkflow>(
      `/investigations/${encodeURIComponent(id)}/workflow`,
    ),
  caseAnalysts: (q: string) =>
    request<{ items: HuntCaseAnalyst[]; hasMore: boolean }>(
      `/investigations/analysts?${new URLSearchParams({ q })}`,
    ),
  assignCase: (
    item: HuntResource,
    assigneeId: number | null,
    reviewerId: number | null,
  ) =>
    request<HuntResource>(`/investigations/${item.id}/assignment`, {
      version: item.version,
      assigneeId,
      reviewerId,
    }),
  submitCaseReview: (item: HuntResource, note: string) =>
    request<HuntResource>(`/investigations/${item.id}/review`, {
      version: item.version,
      note,
    }),
  decideCaseReview: (
    item: HuntResource,
    reviewId: number,
    decision: string,
    note: string,
  ) =>
    request<HuntResource>(`/investigations/${item.id}/decision`, {
      version: item.version,
      reviewId,
      decision,
      note,
    }),
  reopenCase: (item: HuntResource, note: string) =>
    request<HuntResource>(`/investigations/${item.id}/reopen`, {
      version: item.version,
      note,
    }),
  caseReviews: (id: string, before: number) =>
    request<HuntCaseReviewPage>(
      `/investigations/${id}/reviews?before=${before}`,
    ),
  caseReviewDetail: (id: string, reviewId: number) =>
    request<{ review: HuntCaseReview; snapshot: Record<string, unknown> }>(
      `/investigations/${id}/reviews/${reviewId}`,
    ),
  save: (
    kind: string,
    title: string,
    body: Record<string, unknown>,
    shared: boolean,
    previous?: HuntResource,
  ) =>
    request<HuntResource>(
      `/resources/${kind}${previous ? `/${previous.id}` : ""}`,
      { title, body, shared, version: previous?.version ?? 0 },
      previous ? "PUT" : "POST",
    ),
  pin: (investigation: string, jobId: string, eventId: string) =>
    request<{
      id: string;
      sha256?: string;
      previousVersion?: number;
      caseVersion?: number;
      alreadyPinned?: boolean;
    }>(`/investigations/${investigation}/evidence`, { jobId, eventId }),
  evidence: (id: string) =>
    request<Record<string, unknown>[]>(`/investigations/${id}/evidence`),
  audit: (id: string) =>
    request<Record<string, unknown>[]>(`/investigations/${id}/audit`),
  coverage: () => request<Record<string, string | number>[]>("/coverage"),
  runtime: () => request<HuntRuleRuntime[]>("/rules/runtime"),
  findings: () => request<HuntFinding[]>("/findings"),
  transition: (rule: HuntResource, state: string) =>
    request<HuntResource>(`/rules/${rule.id}/transition`, {
      state,
      version: rule.version,
    }),
  exportUrl: (id: string) => root + `/investigations/${id}/export`,
  downloadExport: async (id: string, version: number) => {
    const prepared = await request<{
      url: string;
      sha256: string;
      length: number;
      caseVersion: number;
    }>(`/investigations/${encodeURIComponent(id)}/exports`, { version });
    if (
      !prepared.sha256 ||
      !/^[a-f0-9]{64}$/.test(prepared.sha256) ||
      !new RegExp(`^${root}/exports/[a-f0-9-]{36}$`).test(prepared.url) ||
      prepared.caseVersion !== version ||
      !Number.isSafeInteger(prepared.length) ||
      prepared.length < 1
    )
      throw new Error("내보내기 파일의 검증 정보를 확인할 수 없습니다.");
    return { ...prepared, filename: `investigation-${id}.json` };
  },
};
