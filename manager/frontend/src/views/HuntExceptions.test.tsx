import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { HuntExceptionRequest, HuntExceptionsPanel } from "./HuntExceptions";
import { huntApi, type HuntException } from "../lib/huntApi";

vi.mock("../lib/huntApi", () => ({
  huntApi: {
    exceptions: vi.fn(),
    exception: vi.fn(),
    requestException: vi.fn(),
    decideException: vi.fn(),
    exceptionUsage: vi.fn(),
  },
}));
const item: HuntException = {
  id: "exception-1",
  state: "REQUESTED",
  effectiveState: "REQUESTED",
  version: 3,
  title: "QA 승인 작업",
  reason: "변경 요청 QA-1",
  scenarioId: "A01",
  scenarioVersion: "2.1.0",
  scopeKey: "a".repeat(64),
  scope: { asset_uid: "qa-host", actor: "alice" },
  requestedBy: 1,
  approvedBy: null,
  approvedAt: null,
  validFrom: "2026-10-20T00:00:00.123Z",
  expiresAt: "2026-10-21T00:00:00.456Z",
  createdAt: "2026-10-20T00:00:00Z",
  updatedAt: "2026-10-20T00:00:00Z",
  evidenceSha256: "b".repeat(64),
  permissions: { decide: true, revoke: true },
  evidenceJson: '{"n":9007199254740993,"decimal":1.0}',
  history: [
    {
      version: 1,
      state: "REQUESTED",
      actor_id: 1,
      note: "변경 요청 QA-1",
      created_at: "2026-10-20T00:00:00Z",
    },
  ],
};
const candidate = {
  src_ip: "192.0.2.1",
  actor: "alice",
  first_seen: "2026-10-20T00:00:00.123Z",
  last_seen: "2026-10-20T00:00:30.456Z",
};
function prepare() {
  vi.mocked(huntApi.exceptions).mockResolvedValue({
    items: [item],
    nextCursor: null,
    hasMore: false,
  });
  vi.mocked(huntApi.exception).mockResolvedValue(item);
}
async function open() {
  fireEvent.click(screen.getByRole("button", { name: "승인 예외 검토" }));
  fireEvent.click(await screen.findByRole("button", { name: /^QA 승인 작업/ }));
  return await screen.findByRole("article", { name: "선택 예외 검토" });
}
afterEach(() => vi.clearAllMocks());

describe("approved hunting exceptions", () => {
  it("clears privileged cached details when the role changes", async () => {
    prepare();
    const view = render(<HuntExceptionsPanel role="ADMIN" />);
    await open();
    view.rerender(<HuntExceptionsPanel role="VIEWER" />);
    expect(
      screen.queryByRole("article", { name: "선택 예외 검토" }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: /^QA 승인 작업/ }),
    ).not.toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "승인 예외 검토" }),
    ).toBeInTheDocument();
  });
  it("renews a suppressed candidate through a new request rather than editing an approval", async () => {
    vi.mocked(huntApi.requestException).mockResolvedValue(item);
    render(
      <HuntExceptionRequest
        jobId="suppressed-job"
        index={0}
        scenarioId="A01"
        candidate={candidate}
        sourceKind="SUPPRESSED"
      />,
    );
    fireEvent.click(
      screen.getByRole("button", { name: "억제 후보 1 예외 갱신 요청" }),
    );
    fireEvent.change(screen.getByLabelText("예외 제목"), {
      target: { value: "다음 기간" },
    });
    fireEvent.change(screen.getByLabelText("요청 근거"), {
      target: { value: "새 변경 요청" },
    });
    fireEvent.click(screen.getByRole("button", { name: "검토 요청 등록" }));
    await waitFor(() =>
      expect(huntApi.requestException).toHaveBeenCalledWith(
        expect.objectContaining({
          jobId: "suppressed-job",
          candidateIndex: 0,
          sourceKind: "SUPPRESSED",
        }),
      ),
    );
  });
  it("loads on demand and binds approval to the reviewed version with explicit confirmation", async () => {
    prepare();
    vi.mocked(huntApi.decideException).mockResolvedValue({
      ...item,
      state: "APPROVED",
      effectiveState: "APPROVED",
      version: 4,
      permissions: { decide: false, revoke: true },
    });
    render(<HuntExceptionsPanel role="ADMIN" />);
    expect(huntApi.exceptions).not.toHaveBeenCalled();
    const detail = await open();
    expect(
      within(detail).getByRole("button", { name: "예외 승인" }),
    ).toBeDisabled();
    fireEvent.change(within(detail).getByLabelText("판정 근거"), {
      target: { value: "범위와 변경 일정을 검토함" },
    });
    expect(
      within(detail).getByRole("button", { name: "예외 승인" }),
    ).toBeDisabled();
    fireEvent.click(
      within(detail).getByRole("checkbox", {
        name: "범위·기간·근거를 검토했습니다.",
      }),
    );
    fireEvent.click(within(detail).getByRole("button", { name: "예외 승인" }));
    await waitFor(() =>
      expect(huntApi.decideException).toHaveBeenCalledWith(
        item,
        "APPROVE",
        "범위와 변경 일정을 검토함",
      ),
    );
    expect(await screen.findByRole("status")).toHaveTextContent(
      "새 시나리오·규칙 실행부터",
    );
  });
  it("keeps exact evidence text including wide integers and decimals", async () => {
    prepare();
    render(<HuntExceptionsPanel role="ADMIN" />);
    const detail = await open();
    fireEvent.click(within(detail).getByText("요청 당시 근거와 해시"));
    expect(detail.querySelector("pre")?.textContent).toBe(item.evidenceJson);
  });
  it("never offers mutation controls to a viewer even if a stale response has permissions", async () => {
    prepare();
    render(<HuntExceptionsPanel role="VIEWER" />);
    await open();
    expect(
      screen.queryByRole("button", { name: "예외 승인" }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "예외 철회" }),
    ).not.toBeInTheDocument();
    expect(screen.queryByLabelText("판정 근거")).not.toBeInTheDocument();
  });
  it("preserves the decision draft on a stale-version failure until explicitly reloaded", async () => {
    prepare();
    vi.mocked(huntApi.decideException).mockRejectedValue(
      new Error("예외가 변경되었습니다. 최신 버전을 불러오세요."),
    );
    render(<HuntExceptionsPanel role="ADMIN" />);
    const detail = await open();
    fireEvent.change(within(detail).getByLabelText("판정 근거"), {
      target: { value: "남겨둘 검토 초안" },
    });
    fireEvent.click(within(detail).getByRole("checkbox"));
    fireEvent.click(within(detail).getByRole("button", { name: "예외 승인" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("변경");
    expect(screen.getByLabelText("판정 근거")).toHaveValue("남겨둘 검토 초안");
    expect(huntApi.exception).toHaveBeenCalledTimes(1);
    vi.mocked(huntApi.exception).mockResolvedValue({ ...item, version: 4 });
    fireEvent.click(
      screen.getByRole("button", { name: "선택 예외 최신 버전" }),
    );
    await screen.findByText(/버전 4/);
    expect(screen.getByLabelText("판정 근거")).toHaveValue("");
    expect(screen.getByRole("checkbox")).not.toBeChecked();
  });
  it("ignores the late detail of a previously selected request", async () => {
    prepare();
    const second = { ...item, id: "exception-2", title: "다른 예외" };
    vi.mocked(huntApi.exceptions).mockResolvedValue({
      items: [item, second],
      nextCursor: null,
      hasMore: false,
    });
    let resolve!: (value: HuntException) => void;
    vi.mocked(huntApi.exception).mockImplementation((id) =>
      id === item.id
        ? new Promise((r) => {
            resolve = r;
          })
        : Promise.resolve(second),
    );
    render(<HuntExceptionsPanel role="ADMIN" />);
    fireEvent.click(screen.getByRole("button", { name: "승인 예외 검토" }));
    fireEvent.click(
      await screen.findByRole("button", { name: /^QA 승인 작업/ }),
    );
    fireEvent.click(screen.getByRole("button", { name: /^다른 예외/ }));
    const detail = await screen.findByRole("article", {
      name: "선택 예외 검토",
    });
    fireEvent.change(within(detail).getByLabelText("판정 근거"), {
      target: { value: "두 번째 예외의 초안" },
    });
    await act(async () => resolve(item));
    expect(
      within(screen.getByRole("article", { name: "선택 예외 검토" })).getByRole(
        "heading",
        { name: "다른 예외" },
      ),
    ).toBeInTheDocument();
    expect(screen.getByLabelText("판정 근거")).toHaveValue(
      "두 번째 예외의 초안",
    );
  });
  it("requests the actual candidate index and keeps millisecond dates", async () => {
    vi.mocked(huntApi.requestException).mockResolvedValue(item);
    render(
      <HuntExceptionRequest
        jobId="job-current"
        index={2}
        scenarioId="A01"
        candidate={candidate}
      />,
    );
    fireEvent.click(
      screen.getByRole("button", { name: "후보 3 승인 예외 요청" }),
    );
    fireEvent.change(screen.getByLabelText("예외 제목"), {
      target: { value: "점검" },
    });
    fireEvent.change(screen.getByLabelText("요청 근거"), {
      target: { value: "변경 요청 승인 근거" },
    });
    fireEvent.click(screen.getByRole("button", { name: "검토 요청 등록" }));
    await waitFor(() =>
      expect(huntApi.requestException).toHaveBeenCalledWith(
        expect.objectContaining({
          jobId: "job-current",
          candidateIndex: 2,
          title: "점검",
          reason: "변경 요청 승인 근거",
          validFrom: candidate.first_seen,
        }),
      ),
    );
    expect(await screen.findByRole("status")).toHaveTextContent(
      "승인 전에는 적용되지 않습니다",
    );
  });
  it("keeps the request draft after a server error", async () => {
    vi.mocked(huntApi.requestException).mockRejectedValue(
      new Error("예외 기간을 확인하세요."),
    );
    render(
      <HuntExceptionRequest
        jobId="job-current"
        index={0}
        scenarioId="A01"
        candidate={candidate}
      />,
    );
    fireEvent.click(
      screen.getByRole("button", { name: "후보 1 승인 예외 요청" }),
    );
    fireEvent.change(screen.getByLabelText("예외 제목"), {
      target: { value: "초안 제목" },
    });
    fireEvent.change(screen.getByLabelText("요청 근거"), {
      target: { value: "보존할 근거" },
    });
    fireEvent.click(screen.getByRole("button", { name: "검토 요청 등록" }));
    await screen.findByRole("alert");
    expect(screen.getByLabelText("예외 제목")).toHaveValue("초안 제목");
    expect(screen.getByLabelText("요청 근거")).toHaveValue("보존할 근거");
    expect(
      screen.getByRole("button", { name: "검토 요청 등록" }),
    ).toBeEnabled();
  });
  it("pages without duplicate cards and uses the server cursor", async () => {
    prepare();
    const cursor = { at: item.createdAt, id: item.id };
    vi.mocked(huntApi.exceptions)
      .mockResolvedValueOnce({
        items: [item],
        nextCursor: cursor,
        hasMore: true,
      })
      .mockResolvedValueOnce({
        items: [
          { ...item, version: 4 },
          { ...item, id: "another-id", title: "다음 예외" },
        ],
        nextCursor: null,
        hasMore: false,
      });
    render(<HuntExceptionsPanel role="ADMIN" />);
    fireEvent.click(screen.getByRole("button", { name: "승인 예외 검토" }));
    fireEvent.click(
      await screen.findByRole("button", { name: "예외 50개 더 보기" }),
    );
    await screen.findByRole("button", { name: /^다음 예외/ });
    expect(huntApi.exceptions).toHaveBeenLastCalledWith(cursor);
    expect(
      screen.getAllByRole("button", { name: /^QA 승인 작업/ }),
    ).toHaveLength(1);
  });
});
