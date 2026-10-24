import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { HuntWorkbench } from "./HuntWorkbench";
import type { Role } from "../lib/types";
import {
  huntApi,
  type HuntScenario,
  type HuntResource,
  type HuntJobV2,
  type HuntCaseWorkflow,
} from "../lib/huntApi";

vi.mock("../lib/huntApi", () => ({
  huntApi: {
    fields: vi.fn(),
    scenarios: vi.fn(),
    resources: vi.fn(),
    query: vi.fn(),
    job: vi.fn(),
    save: vi.fn(),
    run: vi.fn(),
    pin: vi.fn(),
    evidence: vi.fn(),
    audit: vi.fn(),
    resource: vi.fn(),
    caseWorkflow: vi.fn(),
    caseAnalysts: vi.fn(),
    assignCase: vi.fn(),
    submitCaseReview: vi.fn(),
    decideCaseReview: vi.fn(),
    reopenCase: vi.fn(),
    caseReviews: vi.fn(),
    caseReviewDetail: vi.fn(),
    downloadExport: vi.fn(),
    exportUrl: vi.fn(
      (id: string) => `/api/security/hunt/v2/investigations/${id}/export`,
    ),
  },
}));
function caseAccess(version = 1): HuntCaseWorkflow {
  return {
    version,
    assignee: null,
    reviewer: null,
    permissions: {
      edit: true,
      manage: true,
      requestReview: false,
      decide: false,
      approve: false,
      reopen: false,
    },
    reviewReadiness: [],
    pendingReview: null,
    evidenceChanged: false,
    history: { items: [], nextBefore: null },
  };
}
vi.mock("recharts", () => ({
  ResponsiveContainer: ({ children }: { children: React.ReactNode }) => (
    <div>{children}</div>
  ),
  BarChart: () => <div>시간 분포 차트</div>,
  Bar: () => null,
  CartesianGrid: () => null,
  Tooltip: () => null,
  XAxis: () => null,
  YAxis: () => null,
}));

function setup(
  investigations: HuntResource[] = [],
  saved: HuntResource[] = [],
  role: Role = "OPERATOR",
) {
  vi.mocked(huntApi.fields).mockResolvedValue([
    { name: "src_ip", label: "출발지 IP", type: "ip" },
    { name: "event_type", label: "이벤트 유형", type: "string" },
  ]);
  vi.mocked(huntApi.scenarios).mockResolvedValue(
    Array.from({ length: 48 }, (_, index) => ({
      id: `A${index}`,
      group: "인증·계정",
      title: `검증 시나리오 ${index}`,
      hypothesis: "실제 실행 조건 확인",
      windowMinutes: 5,
      threshold: 5,
      mode: "COUNT",
      baseline: false,
      requiredFields: ["src_ip"],
      requiredLookups: [],
      steps: ["원본 확인"],
      falsePositives: ["승인 작업"],
      version: "2.0",
    })) as HuntScenario[],
  );
  vi.mocked(huntApi.resources).mockImplementation(async (kind) =>
    kind === "investigation"
      ? investigations
      : kind === "saved-search"
        ? saved
        : [],
  );
  vi.mocked(huntApi.caseWorkflow).mockImplementation(async (id) =>
    caseAccess(investigations.find((value) => value.id === id)?.version ?? 1),
  );
  vi.mocked(huntApi.caseAnalysts).mockResolvedValue({
    items: [],
    hasMore: false,
  });
  render(<HuntWorkbench assets={[]} role={role} onLegacy={vi.fn()} />);
}
afterEach(() => {
  vi.clearAllMocks();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});
describe("hunting workspace", () => {
  it("keeps public field searches available to viewers without enabling raw text search", async () => {
    vi.mocked(huntApi.query).mockResolvedValue({
      id: "viewer-query",
      state: "COMPLETED",
      createdAt: "",
      asOf: "",
    });
    setup([], [], "VIEWER");
    expect(screen.getByLabelText("헌팅 검색어")).toBeDisabled();
    expect(
      screen.getByText(/조회 사용자는 필드 조건으로 검색하세요/),
    ).toBeInTheDocument();
    await screen.findAllByRole("option", { name: "출발지 IP" });
    fireEvent.change(screen.getByLabelText("조건 값"), {
      target: { value: "10.0.0.9" },
    });
    fireEvent.click(screen.getByRole("button", { name: "조건 추가" }));
    fireEvent.click(screen.getByRole("button", { name: "조회 실행" }));
    await waitFor(() =>
      expect(huntApi.query).toHaveBeenCalledWith(
        expect.objectContaining({
          text: "",
          filter: {
            op: "and",
            children: [{ op: "eq", field: "src_ip", values: ["10.0.0.9"] }],
          },
        }),
      ),
    );
  });

  it("refuses masked saved conditions without replacing the current draft or submitting them", async () => {
    const item: HuntResource = {
      id: "masked-search",
      kind: "saved-search",
      title: "가려진 검색",
      ownerId: 1,
      shared: true,
      version: 1,
      createdAt: "",
      updatedAt: "",
      body: {
        query: {
          redacted: true,
          from: "2026-01-01T00:00:00Z",
          to: "2026-01-01T01:00:00Z",
          text: "password=[마스킹]",
          groupBy: "event_type",
        },
      },
    };
    setup([], [item]);
    const search = screen.getByLabelText("헌팅 검색어");
    fireEvent.change(search, { target: { value: "작성 중인 공개 검색" } });
    fireEvent.click(await screen.findByRole("button", { name: /^가려진 검색/ }));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "권한에 따라 가려진 조건",
    );
    expect(search).toHaveValue("작성 중인 공개 검색");
    expect(huntApi.query).not.toHaveBeenCalled();
  });

  it("downloads saved bytes with a file hash while preserving the unsaved analysis", async () => {
    const item: HuntResource = {
      id: "download-case",
      kind: "investigation",
      title: "내보낼 조사",
      ownerId: 1,
      shared: false,
      version: 1,
      body: { notes: "저장된 메모" },
      createdAt: "2026-10-22T00:00:00Z",
      updatedAt: "2026-10-22T00:00:00Z",
    };
    const exact = '{"wide":9007199254740993,"decimal":1.0}';
    vi.mocked(huntApi.evidence).mockResolvedValue([
      {
        id: "evidence-wide",
        eventId: "wide-event",
        sha256: "a".repeat(64),
        snapshot: { wide: 9007199254740992, decimal: 1 },
        snapshotJson: exact,
      },
    ]);
    vi.mocked(huntApi.audit).mockResolvedValue([]);
    vi.mocked(huntApi.downloadExport).mockResolvedValue({
      url: "/api/security/hunt/v2/exports/11111111-1111-4111-8111-111111111111",
      length: 100,
      caseVersion: 1,
      filename: "investigation-download-case.json",
      sha256: "b".repeat(64),
    });
    const downloads: string[] = [];
    const urls: (string | null)[] = [];
    vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(function (
      this: HTMLAnchorElement,
    ) {
      downloads.push(this.download);
      urls.push(this.getAttribute("href"));
    });
    setup([item]);
    fireEvent.click(screen.getByRole("button", { name: "조사" }));
    fireEvent.click(
      await screen.findByRole("button", { name: /^내보낼 조사/ }),
    );
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "조사 저장" })).toBeEnabled(),
    );
    fireEvent.change(screen.getByLabelText("조사 메모"), {
      target: { value: "내보내기 중에도 유지할 미저장 초안" },
    });
    fireEvent.click(screen.getByRole("link", { name: "JSON 내보내기" }));
    await waitFor(() =>
      expect(downloads).toEqual(["investigation-download-case.json"]),
    );
    expect(huntApi.downloadExport).toHaveBeenCalledWith(item.id, item.version);
    expect(urls).toEqual([
      "/api/security/hunt/v2/exports/11111111-1111-4111-8111-111111111111",
    ]);
    expect(screen.getByLabelText("조사 메모")).toHaveValue(
      "내보내기 중에도 유지할 미저장 초안",
    );
    expect(screen.getByText(/파일 SHA-256: b{64}/)).toBeInTheDocument();
    expect(screen.getByText(exact)).toBeInTheDocument();
    expect(huntApi.save).not.toHaveBeenCalled();
  });

  it("reports a failed export and keeps the current draft without downloading an error file", async () => {
    const item: HuntResource = {
      id: "failed-export",
      kind: "investigation",
      title: "한도 조사",
      ownerId: 1,
      shared: false,
      version: 1,
      body: { notes: "원래 메모" },
      createdAt: "2026-10-22T00:00:00Z",
      updatedAt: "2026-10-22T00:00:00Z",
    };
    vi.mocked(huntApi.evidence).mockResolvedValue([]);
    vi.mocked(huntApi.audit).mockResolvedValue([]);
    vi.mocked(huntApi.downloadExport).mockRejectedValue(
      new Error("내보내기 파일 크기 한도를 초과했습니다."),
    );
    const click = vi
      .spyOn(HTMLAnchorElement.prototype, "click")
      .mockImplementation(() => {});
    setup([item]);
    fireEvent.click(screen.getByRole("button", { name: "조사" }));
    fireEvent.click(await screen.findByRole("button", { name: /^한도 조사/ }));
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "조사 저장" })).toBeEnabled(),
    );
    fireEvent.change(screen.getByLabelText("조사 메모"), {
      target: { value: "지켜야 할 분석 초안" },
    });
    fireEvent.click(screen.getByRole("link", { name: "JSON 내보내기" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "파일 크기 한도를 초과",
    );
    expect(screen.getByLabelText("조사 메모")).toHaveValue(
      "지켜야 할 분석 초안",
    );
    expect(click).not.toHaveBeenCalled();
  });

  it.each(["visibility", "assignment"])(
    "removes a case after a successful %s change revokes our own read access",
    async (action) => {
      const item: HuntResource = {
        id: "revoked-case",
        kind: "investigation",
        title: "공유 변경 조사",
        ownerId: 2,
        shared: action === "visibility",
        version: 1,
        createdAt: "2026-10-22T00:00:00Z",
        updatedAt: "2026-10-22T00:00:00Z",
        body: { notes: "이 조사에만 속한 메모" },
      };
      const other = { ...item, id: "retained-case", title: "다른 조사" };
      const revoked = {
        ...item,
        shared: false,
        version: 2,
        casePermissions: { read: false, edit: false, manage: false },
      };
      vi.mocked(huntApi.evidence).mockResolvedValue([]);
      vi.mocked(huntApi.audit).mockResolvedValue([]);
      vi.mocked(huntApi.save).mockResolvedValue(revoked);
      vi.mocked(huntApi.assignCase).mockResolvedValue(revoked);
      setup([item, other], [], "ADMIN");
      fireEvent.click(screen.getByRole("button", { name: "조사" }));
      fireEvent.click(
        await screen.findByRole("button", { name: /^공유 변경 조사/ }),
      );
      await waitFor(() =>
        expect(screen.getByRole("button", { name: "조사 저장" })).toBeEnabled(),
      );
      if (action === "visibility") {
        fireEvent.click(screen.getByRole("checkbox", { name: "조직 공유" }));
        fireEvent.click(screen.getByRole("button", { name: "조사 저장" }));
      } else {
        fireEvent.click(
          screen.getByRole("button", { name: "담당·검토자 저장" }),
        );
      }
      await waitFor(() =>
        expect(
          screen.queryByRole("button", { name: /^공유 변경 조사/ }),
        ).not.toBeInTheDocument(),
      );
      expect(
        screen.getByRole("button", { name: /^다른 조사/ }),
      ).toBeInTheDocument();
      expect(screen.getByLabelText("조사 제목")).toHaveValue("");
      expect(screen.getByLabelText("조사 메모")).toHaveValue("");
      expect(
        screen.queryByRole("region", { name: "조사 담당과 검토" }),
      ).not.toBeInTheDocument();
      expect(
        screen.queryByRole("link", { name: "JSON 내보내기" }),
      ).not.toBeInTheDocument();
      expect(huntApi.evidence).toHaveBeenCalledTimes(1);
      expect(huntApi.audit).toHaveBeenCalledTimes(1);
      expect(huntApi.caseWorkflow).toHaveBeenCalledTimes(1);
      expect(
        screen.getByText(
          "변경을 저장했습니다. 접근 권한이 해제되어 조사를 닫았습니다.",
        ),
      ).toBeInTheDocument();
    },
  );

  it("keeps the selected case and its draft when a different case finishes saving", async () => {
    const first: HuntResource = {
      id: "first-case",
      kind: "investigation",
      title: "첫 조사",
      ownerId: 1,
      shared: true,
      version: 1,
      body: { notes: "첫 메모" },
      createdAt: "2026-10-22T00:00:00Z",
      updatedAt: "2026-10-22T00:00:00Z",
    };
    const second = {
      ...first,
      id: "second-case",
      title: "두 번째 조사",
      body: { notes: "둘째 메모" },
    };
    let finish!: (item: HuntResource) => void;
    vi.mocked(huntApi.save).mockReturnValue(
      new Promise((resolve) => {
        finish = resolve;
      }),
    );
    vi.mocked(huntApi.evidence).mockResolvedValue([]);
    vi.mocked(huntApi.audit).mockResolvedValue([]);
    setup([first, second]);
    fireEvent.click(screen.getByRole("button", { name: "조사" }));
    fireEvent.click(await screen.findByRole("button", { name: /^첫 조사/ }));
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "조사 저장" })).toBeEnabled(),
    );
    fireEvent.click(screen.getByRole("button", { name: "조사 저장" }));
    fireEvent.click(screen.getByRole("button", { name: /^두 번째 조사/ }));
    await waitFor(() =>
      expect(screen.getByLabelText("조사 메모")).toHaveValue("둘째 메모"),
    );
    fireEvent.change(screen.getByLabelText("조사 메모"), {
      target: { value: "둘째 조사 미저장 초안" },
    });
    await act(async () => finish({ ...first, version: 2 }));
    expect(screen.getByLabelText("조사 제목")).toHaveValue("두 번째 조사");
    expect(screen.getByLabelText("조사 메모")).toHaveValue(
      "둘째 조사 미저장 초안",
    );
    expect(huntApi.evidence).toHaveBeenCalledTimes(2);
  });

  it("restores nested saved conditions and exact instants, then saves the edited draft instead of the previous result", async () => {
    const condition = {
      op: "or",
      children: [
        { op: "eq", field: "src_ip", values: ["10.0.0.1"] },
        { op: "not", children: [{ op: "exists", field: "event_type" }] },
      ],
    };
    const spec = {
      from: "2026-10-21T00:01:02.345Z",
      to: "2026-10-22T00:05:06.789Z",
      filter: condition,
      text: "",
      groupBy: "src_ip",
      metric: "count",
      pageSize: 500,
      groupFields: ["src_ip", "event_type"],
      includeRelations: true,
    };
    const saved: HuntResource = {
      id: "saved-1",
      kind: "saved-search",
      title: "복합 범위",
      version: 1,
      ownerId: 1,
      shared: false,
      createdAt: spec.to,
      updatedAt: spec.to,
      body: { query: spec },
    };
    vi.mocked(huntApi.query).mockImplementation(async (query) => ({
      id: "job-saved",
      state: "COMPLETED",
      asOf: spec.to,
      createdAt: spec.to,
      result: {
        query,
        summary: {
          total: 0,
          assets: 0,
          addresses: 0,
          accounts: 0,
          oldest: "",
          newest: "",
        },
        histogram: [],
        groups: [],
        page: { items: [], nextCursor: null, hasMore: false },
        coverage: { complete: true, sampled: false },
      },
    }));
    vi.mocked(huntApi.save).mockResolvedValue(saved);
    setup([], [saved]);
    fireEvent.click(await screen.findByRole("button", { name: /복합 범위/ }));
    await waitFor(() => expect(huntApi.query).toHaveBeenCalledWith(spec));
    expect(screen.getByLabelText("그룹 루트 결합")).toHaveValue("or");
    expect(screen.getByLabelText("조건 1 값")).toHaveValue("10.0.0.1");
    expect(screen.getByLabelText("집계 필드 2")).toHaveValue("event_type");
    expect(
      screen.getByRole("checkbox", { name: "관계 분석 포함" }),
    ).toBeChecked();
    expect(
      screen.queryByText(
        "편집한 조건은 아직 조회하지 않았습니다. 표와 차트는 마지막 실행 결과입니다.",
      ),
    ).not.toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("조건 1 값"), {
      target: { value: "10.0.0.9" },
    });
    expect(
      screen.getByText(
        "편집한 조건은 아직 조회하지 않았습니다. 표와 차트는 마지막 실행 결과입니다.",
      ),
    ).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "검색 저장" }));
    fireEvent.change(screen.getByLabelText("저장 검색 이름"), {
      target: { value: "수정한 복합 범위" },
    });
    fireEvent.click(screen.getByRole("button", { name: "저장" }));
    await waitFor(() =>
      expect(huntApi.save).toHaveBeenCalledWith(
        "saved-search",
        "수정한 복합 범위",
        {
          query: {
            ...spec,
            filter: {
              ...condition,
              children: [
                { ...condition.children[0], values: ["10.0.0.9"] },
                condition.children[1],
              ],
            },
          },
        },
        false,
      ),
    );
    fireEvent.click(screen.getByRole("button", { name: "조회 실행" }));
    await waitFor(() =>
      expect(huntApi.query).toHaveBeenLastCalledWith(
        expect.objectContaining({
          from: spec.from,
          to: spec.to,
          pageSize: 500,
        }),
      ),
    );
    await waitFor(() =>
      expect(
        screen.queryByText(
          "편집한 조건은 아직 조회하지 않았습니다. 표와 차트는 마지막 실행 결과입니다.",
        ),
      ).not.toBeInTheDocument(),
    );
  });
  it("uses one clock instant for the full 30-day preset", async () => {
    vi.mocked(huntApi.query).mockResolvedValue({
      id: "range",
      state: "COMPLETED",
      createdAt: "",
      asOf: "",
    });
    setup();
    fireEvent.click(screen.getByRole("button", { name: "30일" }));
    fireEvent.click(screen.getByRole("button", { name: "조회 실행" }));
    await waitFor(() => expect(huntApi.query).toHaveBeenCalled());
    const spec = vi.mocked(huntApi.query).mock.calls.at(-1)![0];
    expect(Date.parse(spec.to) - Date.parse(spec.from)).toBe(30 * 86400000);
  });
  it("rejects non-scope scenario conditions without sending a weakened query", async () => {
    setup();
    await screen.findAllByRole("option", { name: "이벤트 유형" });
    fireEvent.change(screen.getByLabelText("조건 필드"), {
      target: { value: "event_type" },
    });
    fireEvent.change(screen.getByLabelText("조건 값"), {
      target: { value: "process.start" },
    });
    fireEvent.click(screen.getByRole("button", { name: "조건 추가" }));
    fireEvent.click(screen.getByRole("button", { name: /헌팅 시나리오/ }));
    fireEvent.change(screen.getByLabelText("시나리오 검색"), {
      target: { value: "시나리오 47" },
    });
    fireEvent.click(screen.getByRole("button", { name: "가설 검증" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(/자산·주소/);
    expect(huntApi.run).not.toHaveBeenCalled();
  });
  it("does not display a late evidence response under a different investigation", async () => {
    const a: HuntResource = {
      id: "case-a",
      kind: "investigation",
      title: "조사 A",
      ownerId: 1,
      shared: false,
      version: 1,
      createdAt: "2026-10-22T00:00:00Z",
      updatedAt: "2026-10-22T00:00:00Z",
      body: {},
    };
    const b: HuntResource = { ...a, id: "case-b", title: "조사 B" };
    let resolveA!: (rows: Record<string, unknown>[]) => void;
    const lateA = new Promise<Record<string, unknown>[]>((resolve) => {
      resolveA = resolve;
    });
    vi.mocked(huntApi.evidence).mockImplementation(async (id) =>
      id === a.id
        ? lateA
        : [{ id: "evidence-b", eventId: "event-b", createdAt: a.createdAt }],
    );
    vi.mocked(huntApi.audit).mockResolvedValue([]);
    setup([a, b]);
    fireEvent.click(screen.getByRole("button", { name: "조사" }));
    fireEvent.click(await screen.findByRole("button", { name: /^조사 A / }));
    fireEvent.click(screen.getByRole("button", { name: /^조사 B / }));
    expect(await screen.findByText(/^event-b ·/)).toBeInTheDocument();
    await act(async () => {
      resolveA([
        { id: "evidence-a", eventId: "event-a", createdAt: a.createdAt },
      ]);
    });
    expect(screen.getByLabelText("조사 제목")).toHaveValue("조사 B");
    expect(screen.getByText(/^event-b ·/)).toBeInTheDocument();
    expect(screen.queryByText(/^event-a ·/)).not.toBeInTheDocument();
  });
  it.each([1, 2])(
    "preserves unsaved analysis and only adopts our own pin version when the previous version is %s",
    async (previousVersion) => {
      const investigation: HuntResource = {
        id: "case-1",
        kind: "investigation",
        title: "인증 조사",
        ownerId: 1,
        shared: false,
        version: 1,
        createdAt: "2026-10-22T00:00:00Z",
        updatedAt: "2026-10-22T00:00:00Z",
        body: { notes: "저장된 메모" },
      };
      const query = {
        from: "2026-10-21T00:00:00Z",
        to: "2026-10-22T00:00:00Z",
        text: "",
        filter: null,
        groupBy: "event_type",
        metric: "count",
        pageSize: 200,
      };
      const job: HuntJobV2 = {
        id: "job-1",
        state: "COMPLETED",
        asOf: query.to,
        createdAt: query.to,
        result: {
          query,
          summary: {
            total: 1,
            assets: 1,
            addresses: 1,
            accounts: 1,
            oldest: query.from,
            newest: query.to,
          },
          histogram: [],
          groups: [],
          coverage: { complete: true, sampled: false },
          page: {
            items: [
              {
                id: "event-1",
                at: query.from,
                event_type: "auth.login.failure",
                asset_uid: "sensor-1",
              },
            ],
            nextCursor: null,
            hasMore: false,
          },
        },
      };
      vi.mocked(huntApi.query).mockResolvedValue(job);
      vi.mocked(huntApi.evidence)
        .mockResolvedValueOnce([])
        .mockResolvedValue([
          {
            id: "evidence-1",
            eventId: "event-1",
            createdAt: query.to,
            sha256: "a".repeat(64),
            snapshot: {},
          },
        ]);
      vi.mocked(huntApi.audit).mockResolvedValue([]);
      let pinned = false;
      vi.mocked(huntApi.pin).mockImplementation(async () => {
        pinned = true;
        return {
          id: "evidence-1",
          sha256: "a".repeat(64),
          previousVersion,
          caseVersion: previousVersion + 1,
        };
      });
      setup([investigation]);
      vi.mocked(huntApi.caseWorkflow).mockImplementation(async () =>
        caseAccess(pinned ? previousVersion + 1 : 1),
      );
      fireEvent.click(screen.getByRole("button", { name: "조사" }));
      fireEvent.click(
        await screen.findByRole("button", { name: /^인증 조사/ }),
      );
      await waitFor(() => expect(huntApi.evidence).toHaveBeenCalledTimes(1));
      fireEvent.change(screen.getByLabelText("조사 메모"), {
        target: { value: "저장하지 않은 분석 초안" },
      });
      fireEvent.click(screen.getByRole("button", { name: "탐색" }));
      fireEvent.click(screen.getByRole("button", { name: "조회 실행" }));
      fireEvent.click(
        await screen.findByRole("button", {
          name: "auth.login.failure 이벤트 조사",
        }),
      );
      fireEvent.change(screen.getByLabelText("증거 대상 조사"), {
        target: { value: investigation.id },
      });
      fireEvent.click(screen.getByRole("button", { name: "증거 고정" }));
      await waitFor(() => expect(huntApi.evidence).toHaveBeenCalledTimes(2));
      fireEvent.click(screen.getByRole("button", { name: "조사" }));
      expect(await screen.findByText("고정 증거 · 1")).toBeInTheDocument();
      expect(screen.getByLabelText("조사 메모")).toHaveValue(
        "저장하지 않은 분석 초안",
      );
      expect(huntApi.pin).toHaveBeenCalledWith("case-1", "job-1", "event-1");
      if (previousVersion === 1) {
        await waitFor(() =>
          expect(
            screen.getByRole("button", { name: "조사 저장" }),
          ).toBeEnabled(),
        );
        expect(screen.getByText("조사 버전 2")).toBeInTheDocument();
      } else {
        expect(
          await screen.findByText("서버에 더 최신 버전이 있습니다"),
        ).toBeInTheDocument();
        expect(
          screen.getByRole("button", { name: "조사 저장" }),
        ).toBeDisabled();
        expect(screen.getByText("조사 버전 1")).toBeInTheDocument();
      }
    },
  );
  it("applies field predicates to the server query and shows complete-interval totals", async () => {
    const result = {
      id: "job",
      state: "COMPLETED",
      asOf: "2026-01-01T00:00:00Z",
      createdAt: "2026-01-01T00:00:00Z",
      result: {
        query: {
          from: "2025-12-31T00:00:00Z",
          to: "2026-01-01T00:00:00Z",
          filter: null,
          text: "",
          groupBy: "event_type",
          metric: "count",
          pageSize: 200,
        },
        summary: {
          total: 10050,
          assets: 2,
          oldest: "",
          newest: "",
          addresses: 10050,
          accounts: 0,
        },
        histogram: [],
        groups: [],
        page: { items: [], nextCursor: null, hasMore: false },
        coverage: { complete: true, sampled: false },
      },
    };
    vi.mocked(huntApi.query).mockResolvedValue(result);
    setup();
    await screen.findAllByRole("option", { name: "출발지 IP" });
    fireEvent.change(screen.getByLabelText("조건 값"), {
      target: { value: "10.0.0.9" },
    });
    fireEvent.click(screen.getByRole("button", { name: "조건 추가" }));
    fireEvent.click(screen.getByRole("button", { name: "조회 실행" }));
    await waitFor(() =>
      expect(huntApi.query).toHaveBeenCalledWith(
        expect.objectContaining({
          filter: {
            op: "and",
            children: [{ op: "eq", field: "src_ip", values: ["10.0.0.9"] }],
          },
        }),
      ),
    );
    expect((await screen.findAllByText("10,050")).length).toBeGreaterThan(0);
  });
  it("makes the full catalogue discoverable and runs a selected hypothesis", async () => {
    setup();
    fireEvent.click(screen.getByRole("button", { name: /헌팅 시나리오/ }));
    expect(await screen.findByText("검증 시나리오 47")).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("시나리오 검색"), {
      target: { value: "시나리오 47" },
    });
    expect(screen.getAllByRole("button", { name: "가설 검증" })).toHaveLength(
      1,
    );
  });
  it("saves investigations with hypothesis and an explicit inconclusive verdict", async () => {
    vi.mocked(huntApi.save).mockImplementation(
      async (kind, title, body, shared) => ({
        id: "new-case",
        kind,
        title,
        body,
        shared,
        version: 1,
        ownerId: 1,
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString(),
      }),
    );
    setup();
    fireEvent.click(screen.getByRole("button", { name: /^조사$/ }));
    fireEvent.change(screen.getByLabelText("조사 제목"), {
      target: { value: "신규 접속 조사" },
    });
    fireEvent.change(screen.getByLabelText("조사 가설"), {
      target: { value: "새 원본의 인증 성공이 있었는가?" },
    });
    fireEvent.change(screen.getByLabelText("조사 판정"), {
      target: { value: "INSUFFICIENT" },
    });
    fireEvent.click(screen.getByRole("button", { name: "조사 저장" }));
    await waitFor(() =>
      expect(huntApi.save).toHaveBeenCalledWith(
        "investigation",
        "신규 접속 조사",
        expect.objectContaining({
          verdict: "INSUFFICIENT",
          hypothesis: "새 원본의 인증 성공이 있었는가?",
        }),
        false,
        undefined,
      ),
    );
  });
});
