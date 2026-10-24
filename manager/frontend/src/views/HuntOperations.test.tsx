import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { HuntOperations } from "./HuntOperations";
import { huntApi, type HuntResource } from "../lib/huntApi";

vi.mock("../lib/huntApi", () => ({
  huntApi: {
    runtime: vi.fn(),
    findings: vi.fn(),
    coverage: vi.fn(),
    resources: vi.fn(),
    transition: vi.fn(),
    save: vi.fn(),
    storage: vi.fn(),
  },
}));
const rule: HuntResource = {
  id: "rule-1",
  title: "인증 조사",
  kind: "rule",
  body: { state: "OBSERVE", scenarioId: "A01" },
  version: 3,
  ownerId: 1,
  shared: true,
  createdAt: "2026-10-01T00:00:00Z",
  updatedAt: "2026-10-01T00:00:00Z",
};
function prepare(days = 8, runs = 10) {
  vi.mocked(huntApi.storage).mockResolvedValue({
    pendingMutations: 0,
    queryMode: "JSON",
    schema: {
      ready: false,
      missing: [],
      incompatible: [],
      checkedAt: new Date().toISOString(),
    },
    partitions: [],
    mutations: [],
  });
  vi.mocked(huntApi.runtime).mockResolvedValue([
    {
      rule_id: rule.id,
      ready_runs: runs,
      observed_since: new Date(Date.now() - days * 86_400_000).toISOString(),
      last_state: "COMPLETED",
    },
  ]);
  vi.mocked(huntApi.findings).mockResolvedValue([]);
  vi.mocked(huntApi.coverage).mockResolvedValue([]);
  vi.mocked(huntApi.resources).mockResolvedValue([]);
}
afterEach(() => vi.clearAllMocks());
describe("hunting operations", () => {
  it("requires the observation period and successful runs before offering activation", async () => {
    prepare(2, 100);
    render(<HuntOperations role="ADMIN" rules={[rule]} reload={vi.fn()} />);
    await screen.findByText("정상 완료 100회");
    expect(screen.getByRole("button", { name: "활성화" })).toBeDisabled();
    expect(huntApi.transition).not.toHaveBeenCalled();
  });
  it("submits the reviewed rule version through the guarded transition endpoint", async () => {
    prepare();
    const reload = vi.fn().mockResolvedValue(undefined);
    vi.mocked(huntApi.transition).mockResolvedValue({
      ...rule,
      version: 4,
      body: { ...rule.body, state: "ACTIVE" },
    });
    render(<HuntOperations role="ADMIN" rules={[rule]} reload={reload} />);
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "활성화" })).toBeEnabled(),
    );
    fireEvent.click(screen.getByRole("button", { name: "활성화" }));
    await waitFor(() =>
      expect(huntApi.transition).toHaveBeenCalledWith(rule, "ACTIVE"),
    );
    expect(
      await screen.findByText("규칙을 활성화했습니다."),
    ).toBeInTheDocument();
  });
  it("saves a validated lookup as an organization resource and removes duplicate lines", async () => {
    prepare();
    vi.mocked(huntApi.save).mockResolvedValue({ ...rule, kind: "lookup" });
    render(
      <HuntOperations
        role="ADMIN"
        rules={[]}
        reload={vi.fn().mockResolvedValue(undefined)}
      />,
    );
    await screen.findByText("등록 IOC · 최근 0개");
    fireEvent.change(screen.getByLabelText("룩업 값"), {
      target: { value: "10.0.0.1\n10.0.0.1\n10.0.0.2" },
    });
    expect(screen.getByLabelText("룩업 값")).toHaveValue(
      "10.0.0.1\n10.0.0.1\n10.0.0.2",
    );
    fireEvent.click(screen.getByRole("button", { name: "룩업 저장" }));
    await waitFor(() =>
      expect(huntApi.save).toHaveBeenCalledWith(
        "lookup",
        "trusted_scanners",
        { values: ["10.0.0.1", "10.0.0.2"] },
        true,
        undefined,
      ),
    );
  });
  it("keeps operational configuration read only for viewers", async () => {
    prepare();
    render(<HuntOperations role="VIEWER" rules={[rule]} reload={vi.fn()} />);
    await screen.findByText("정상 완료 10회");
    expect(
      screen.queryByRole("button", { name: "활성화" }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "룩업 저장" }),
    ).not.toBeInTheDocument();
    expect(screen.getByLabelText("IOC 값")).toBeDisabled();
  });
});
