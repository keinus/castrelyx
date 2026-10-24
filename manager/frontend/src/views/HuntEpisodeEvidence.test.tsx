import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { HuntEpisodeEvidence } from "./HuntEpisodeEvidence";
import {
  huntApi,
  type HuntEpisodeDetail,
  type HuntEpisodeProof,
} from "../lib/huntApi";
vi.mock("../lib/huntApi", () => ({
  huntApi: { findingEvidence: vi.fn(), findingProofs: vi.fn() },
}));
const proof = (id: number, source = `proof-${id}`): HuntEpisodeProof => ({
  id,
  jobId: `job-${id}`,
  createdAt: "2026-10-23T01:00:00Z",
  evidence: {},
  evidenceJson: source,
  evidenceSha256: "original-sha",
  exportEvidenceSha256: "role-copy-sha",
});
const detail = (id = "candidate-a"): HuntEpisodeDetail => ({
  id,
  rule_id: "rule",
  rule_version: 3,
  scenario_id: "A01",
  version: 2,
  status: "OPEN",
  merged_into: null,
  last_job_id: "job-1",
  firstSeen: "2026-10-23T00:00:00Z",
  lastSeen: "2026-10-23T01:00:00Z",
  latestCandidate: {},
  occurrences: { items: [proof(1)], hasMore: false, nextCursor: null },
  merges: { items: [], hasMore: false, nextCursor: null },
});
const props = {
  id: "candidate-a",
  role: "OPERATOR" as const,
  onSelect: vi.fn(),
  onClose: vi.fn(),
};
afterEach(() => vi.resetAllMocks());
describe("episode source evidence", () => {
  it("renders exact server proof bytes, separate hashes and legacy provenance", async () => {
    const raw =
      '{"wide":18446744073709551615,"decimal":1234.000000000000000001}';
    const item = { ...proof(1, raw), origin: "LEGACY_LAST_RESULT" };
    vi.mocked(huntApi.findingEvidence).mockResolvedValue({
      ...detail(),
      occurrences: { items: [item], hasMore: false, nextCursor: null },
    });
    render(<HuntEpisodeEvidence {...props} />);
    expect(await screen.findByText(raw, { exact: true })).toHaveTextContent(
      raw,
    );
    expect(
      screen.getByText(/이전 버전의 마지막 결과 사본/),
    ).toBeInTheDocument();
    expect(screen.getByText("original-sha")).toBeInTheDocument();
    expect(screen.getByText("role-copy-sha")).toBeInTheDocument();
    expect(huntApi.findingProofs).not.toHaveBeenCalled();
  });
  it("pages source occurrences explicitly and deduplicates boundary records", async () => {
    vi.mocked(huntApi.findingEvidence).mockResolvedValue({
      ...detail(),
      occurrences: { items: [proof(1)], hasMore: true, nextCursor: 1 },
    });
    vi.mocked(huntApi.findingProofs).mockResolvedValue({
      items: [proof(1), proof(2)],
      hasMore: false,
      nextCursor: null,
    });
    render(<HuntEpisodeEvidence {...props} />);
    fireEvent.click(
      await screen.findByRole("button", { name: "실행 근거 20개 더 보기" }),
    );
    expect(
      await screen.findByText("proof-2", { exact: true }),
    ).toBeInTheDocument();
    expect(screen.getAllByText("proof-1", { exact: true })).toHaveLength(1);
    expect(huntApi.findingProofs).toHaveBeenCalledWith(
      "candidate-a",
      "occurrences",
      1,
    );
  });
  it("pages merge history independently from execution proofs", async () => {
    vi.mocked(huntApi.findingEvidence).mockResolvedValue({
      ...detail(),
      merges: {
        items: [proof(3, "merge-before")],
        hasMore: true,
        nextCursor: 3,
      },
    });
    vi.mocked(huntApi.findingProofs).mockResolvedValue({
      items: [proof(2, "older-merge")],
      hasMore: false,
      nextCursor: null,
    });
    render(<HuntEpisodeEvidence {...props} />);
    fireEvent.click(
      await screen.findByRole("button", { name: "병합 이력 20개 더 보기" }),
    );
    expect(
      await screen.findByText("older-merge", { exact: true }),
    ).toBeInTheDocument();
    expect(huntApi.findingProofs).toHaveBeenCalledWith(
      "candidate-a",
      "merges",
      3,
    );
  });
  it("discards a previous candidate response after selecting a different episode", async () => {
    let release!: (value: HuntEpisodeDetail) => void;
    vi.mocked(huntApi.findingEvidence).mockImplementation((id) =>
      id === "candidate-a"
        ? new Promise((resolve) => {
            release = resolve;
          })
        : Promise.resolve(detail("candidate-b")),
    );
    const rendered = render(<HuntEpisodeEvidence {...props} />);
    rendered.rerender(<HuntEpisodeEvidence {...props} id="candidate-b" />);
    expect(
      await screen.findByText("candidate-b", { exact: true }),
    ).toBeInTheDocument();
    await act(async () => {
      release(detail("candidate-a"));
    });
    expect(
      screen.queryByText("candidate-a", { exact: true }),
    ).not.toBeInTheDocument();
  });
  it("clears privileged cached proof immediately when role changes", async () => {
    const secret = "ADMIN_ONLY_PROOF";
    vi.mocked(huntApi.findingEvidence)
      .mockResolvedValueOnce({
        ...detail(),
        occurrences: {
          items: [proof(1, secret)],
          hasMore: false,
          nextCursor: null,
        },
      })
      .mockImplementationOnce(() => new Promise(() => {}));
    const rendered = render(<HuntEpisodeEvidence {...props} role="ADMIN" />);
    expect(
      await screen.findByText(secret, { exact: true }),
    ).toBeInTheDocument();
    rendered.rerender(<HuntEpisodeEvidence {...props} role="VIEWER" />);
    expect(screen.queryByText(secret, { exact: true })).not.toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent("불러오는 중");
  });
  it("reports a permission/load error and retries only after explicit reload", async () => {
    vi.mocked(huntApi.findingEvidence)
      .mockRejectedValueOnce(new Error("권한이 변경되었습니다."))
      .mockResolvedValueOnce(detail());
    render(<HuntEpisodeEvidence {...props} />);
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "권한이 변경되었습니다.",
    );
    fireEvent.click(screen.getByRole("button", { name: "근거 최신 상태" }));
    expect(
      await screen.findByText("candidate-a", { exact: true }),
    ).toBeInTheDocument();
    await waitFor(() =>
      expect(screen.queryByRole("alert")).not.toBeInTheDocument(),
    );
  });
  it("opens the explicit canonical episode and provides an always available close action", async () => {
    vi.mocked(huntApi.findingEvidence).mockResolvedValue({
      ...detail(),
      status: "MERGED",
      merged_into: "canonical-id",
    });
    render(<HuntEpisodeEvidence {...props} role="VIEWER" />);
    fireEvent.click(
      await screen.findByRole("button", { name: "대표 episode 열기" }),
    );
    expect(props.onSelect).toHaveBeenCalledWith("canonical-id");
    fireEvent.click(screen.getByRole("button", { name: "근거 닫기" }));
    expect(props.onClose).toHaveBeenCalledOnce();
  });
});
