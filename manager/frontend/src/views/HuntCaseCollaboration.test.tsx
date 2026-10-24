import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { HuntCaseCollaboration } from "./HuntCaseCollaboration";
import {
  huntApi,
  type HuntCaseWorkflow,
  type HuntResource,
  type HuntCaseReview,
} from "../lib/huntApi";

vi.mock("../lib/huntApi", () => ({
  huntApi: {
    caseWorkflow: vi.fn(),
    caseAnalysts: vi.fn(),
    assignCase: vi.fn(),
    submitCaseReview: vi.fn(),
    decideCaseReview: vi.fn(),
    reopenCase: vi.fn(),
    caseReviews: vi.fn(),
    caseReviewDetail: vi.fn(),
  },
}));
const resource: HuntResource = {
  id: "case-a",
  kind: "investigation",
  ownerId: 1,
  title: "조사 A",
  shared: false,
  body: {
    status: "INVESTIGATING",
    hypothesis: "test",
    verdict: "INSUFFICIENT",
  },
  version: 4,
  createdAt: "2026-10-22T00:00:00Z",
  updatedAt: "2026-10-22T00:00:00Z",
};
const a = {
    id: 1,
    username: "analyst",
    displayName: "분석 담당",
    role: "OPERATOR",
    enabled: true,
  },
  b = {
    id: 2,
    username: "reviewer",
    displayName: "검토 담당",
    role: "OPERATOR",
    enabled: true,
  };
const review: HuntCaseReview = {
  id: 7,
  caseVersion: 4,
  submittedBy: 1,
  reviewerId: 2,
  state: "REQUESTED",
  requestNote: "정상 작업 여부 확인",
  decisionNote: null,
  sha256: "a".repeat(64),
  createdAt: resource.createdAt,
  decidedAt: null,
  decidedBy: null,
};
function workflow(overrides: Partial<HuntCaseWorkflow> = {}): HuntCaseWorkflow {
  return {
    version: 4,
    assignee: a,
    reviewer: b,
    permissions: {
      edit: true,
      manage: true,
      requestReview: true,
      decide: false,
      approve: false,
      reopen: false,
    },
    reviewReadiness: [],
    pendingReview: null,
    evidenceChanged: false,
    history: { items: [], nextBefore: null },
    ...overrides,
  };
}
function setup(value = workflow(), dirty = false) {
  vi.mocked(huntApi.caseWorkflow).mockResolvedValue(value);
  vi.mocked(huntApi.caseAnalysts).mockResolvedValue({
    items: [a, b],
    hasMore: false,
  });
  const props = {
    item: resource,
    dirty,
    disabled: false,
    onWorkflow: vi.fn(),
    onChange: vi.fn(),
    onReload: vi.fn().mockResolvedValue(undefined),
  };
  const rendered = render(<HuntCaseCollaboration {...props} />);
  return { ...rendered, props };
}
afterEach(() => vi.resetAllMocks());
describe("investigation collaboration", () => {
  it("saves explicit assignment while preserving a dirty analysis and requires a saved draft for review", async () => {
    const changed = { ...resource, version: 5 };
    vi.mocked(huntApi.assignCase).mockResolvedValue(changed);
    const { props } = setup(workflow(), true);
    await screen.findByLabelText("조사 담당자");
    expect(
      screen.getByRole("button", { name: "독립 검토 요청" }),
    ).toBeDisabled();
    fireEvent.change(screen.getByLabelText("요청·판정·재개 이유"), {
      target: { value: "검토 필요" },
    });
    expect(
      screen.getByRole("button", { name: "독립 검토 요청" }),
    ).toBeDisabled();
    fireEvent.click(screen.getByRole("button", { name: "담당·검토자 저장" }));
    await waitFor(() =>
      expect(huntApi.assignCase).toHaveBeenCalledWith(resource, 1, 2),
    );
    expect(props.onChange).toHaveBeenCalledWith(changed, true);
    expect(huntApi.submitCaseReview).not.toHaveBeenCalled();
  });
  it("sends a reviewer decision against the exact pending review and case version", async () => {
    const changed = {
      ...resource,
      version: 5,
      body: { ...resource.body, status: "CLOSED" },
    };
    vi.mocked(huntApi.decideCaseReview).mockResolvedValue(changed);
    const { props } = setup(
      workflow({
        permissions: {
          edit: false,
          manage: false,
          requestReview: false,
          decide: true,
          approve: true,
          reopen: false,
        },
        pendingReview: review,
      }),
    );
    await screen.findByRole("button", { name: "승인하고 종료" });
    expect(screen.queryByLabelText("조사 담당자")).not.toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "승인하고 종료" }),
    ).toBeDisabled();
    fireEvent.change(screen.getByLabelText("요청·판정·재개 이유"), {
      target: { value: "근거 대조 완료" },
    });
    fireEvent.click(screen.getByRole("button", { name: "승인하고 종료" }));
    await waitFor(() =>
      expect(huntApi.decideCaseReview).toHaveBeenCalledWith(
        resource,
        7,
        "APPROVED",
        "근거 대조 완료",
      ),
    );
    expect(props.onChange).toHaveBeenCalledWith(changed, false);
  });
  it("retains reasons after conflicts and offers an explicit reload rather than silently adopting a new version", async () => {
    vi.mocked(huntApi.submitCaseReview).mockRejectedValue(
      new Error("다른 사용자가 변경했습니다"),
    );
    const { props } = setup();
    await screen.findByLabelText("요청·판정·재개 이유");
    fireEvent.change(screen.getByLabelText("요청·판정·재개 이유"), {
      target: { value: "보존할 제출 이유" },
    });
    fireEvent.click(screen.getByRole("button", { name: "독립 검토 요청" }));
    await screen.findByRole("alert");
    expect(screen.getByLabelText("요청·판정·재개 이유")).toHaveValue(
      "보존할 제출 이유",
    );
    expect(props.onChange).not.toHaveBeenCalled();
    fireEvent.click(
      screen.getByRole("button", {
        name: "최신 버전 불러오기 · 미저장 내용 교체",
      }),
    );
    await waitFor(() => expect(props.onReload).toHaveBeenCalledTimes(1));
  });
  it("disables stale-version mutations and expired-evidence approval while allowing changes requests", async () => {
    const { unmount } = setup(workflow({ version: 5 }));
    await screen.findByText("서버에 더 최신 버전이 있습니다");
    expect(
      screen.getByRole("button", { name: "담당·검토자 저장" }),
    ).toBeDisabled();
    unmount();
    setup(
      workflow({
        permissions: {
          edit: false,
          manage: false,
          requestReview: false,
          decide: true,
          approve: false,
          reopen: false,
        },
        pendingReview: review,
        evidenceChanged: true,
      }),
    );
    await screen.findByText("제출한 근거가 달라졌습니다");
    fireEvent.change(screen.getByLabelText("요청·판정·재개 이유"), {
      target: { value: "근거 재확인" },
    });
    expect(
      screen.getByRole("button", { name: "승인하고 종료" }),
    ).toBeDisabled();
    expect(screen.getByRole("button", { name: "보완 요청" })).toBeEnabled();
  });
  it("does not show late workflow or snapshot results under another investigation", async () => {
    let resolve!: (value: HuntCaseWorkflow) => void;
    const late = new Promise<HuntCaseWorkflow>((done) => {
      resolve = done;
    });
    const { rerender, props } = setup();
    vi.mocked(huntApi.caseWorkflow).mockImplementation((id) =>
      id === "case-b"
        ? late
        : Promise.resolve(
            workflow({ history: { items: [review], nextBefore: null } }),
          ),
    );
    rerender(
      <HuntCaseCollaboration {...props} item={{ ...resource, id: "case-b" }} />,
    );
    rerender(
      <HuntCaseCollaboration {...props} item={{ ...resource, id: "case-c" }} />,
    );
    await screen.findByRole("button", { name: "검토 #7 제출 버전 보기" });
    await act(async () =>
      resolve(
        workflow({
          version: 999,
          history: { items: [{ ...review, id: 999 }], nextBefore: null },
        }),
      ),
    );
    expect(screen.queryByText(/검토 #999/)).not.toBeInTheDocument();
    expect(props.onWorkflow).not.toHaveBeenCalledWith(
      "case-b",
      expect.objectContaining({ version: 999 }),
    );
    let detail!: (value: {
      review: HuntCaseReview;
      snapshot: Record<string, unknown>;
    }) => void;
    vi.mocked(huntApi.caseReviewDetail).mockReturnValue(
      new Promise((done) => {
        detail = done;
      }),
    );
    fireEvent.click(
      screen.getByRole("button", { name: "검토 #7 제출 버전 보기" }),
    );
    rerender(
      <HuntCaseCollaboration {...props} item={{ ...resource, id: "case-d" }} />,
    );
    await act(async () =>
      detail({ review, snapshot: { notes: "other case secret fixture" } }),
    );
    expect(
      screen.queryByText(/other case secret fixture/),
    ).not.toBeInTheDocument();
  });
  it("loads older reviews and the selected immutable snapshot on demand", async () => {
    vi.mocked(huntApi.caseReviews).mockResolvedValue({
      items: [{ ...review, id: 6, state: "CHANGES_REQUESTED" }],
      nextBefore: null,
    });
    vi.mocked(huntApi.caseReviewDetail).mockResolvedValue({
      review,
      snapshot: { version: 4, notes: "submitted notes" },
    });
    setup(workflow({ history: { items: [review], nextBefore: 7 } }));
    fireEvent.click(
      await screen.findByRole("button", { name: "이전 검토 20건 더 보기" }),
    );
    await screen.findByRole("button", { name: "검토 #6 제출 버전 보기" });
    expect(huntApi.caseReviews).toHaveBeenCalledWith("case-a", 7);
    fireEvent.click(
      screen.getByRole("button", { name: "검토 #7 제출 버전 보기" }),
    );
    await screen.findByText(/submitted notes/);
    expect(huntApi.caseReviewDetail).toHaveBeenCalledWith("case-a", 7);
  });
  it("does not let a late initial directory overwrite a newer analyst search", async () => {
    let initial!: (value: { items: (typeof a)[]; hasMore: boolean }) => void;
    vi.mocked(huntApi.caseWorkflow).mockResolvedValue(workflow());
    vi.mocked(huntApi.caseAnalysts).mockImplementation((q) =>
      q
        ? Promise.resolve({
            items: [{ ...a, id: 99, username: "search-hit" }],
            hasMore: false,
          })
        : new Promise((done) => {
            initial = done;
          }),
    );
    render(
      <HuntCaseCollaboration
        item={resource}
        dirty={false}
        disabled={false}
        onWorkflow={vi.fn()}
        onChange={vi.fn()}
        onReload={vi.fn()}
      />,
    );
    fireEvent.change(await screen.findByLabelText("분석가 검색"), {
      target: { value: "search-hit" },
    });
    fireEvent.click(screen.getByRole("button", { name: "분석가 찾기" }));
    await screen.findAllByRole("option", { name: /search-hit/ });
    await act(async () =>
      initial({
        items: [{ ...a, id: 77, username: "old-result" }],
        hasMore: true,
      }),
    );
    expect(
      screen.queryByRole("option", { name: /old-result/ }),
    ).not.toBeInTheDocument();
    expect(screen.getAllByRole("option", { name: /search-hit/ })).toHaveLength(
      2,
    );
  });
});
