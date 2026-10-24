import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { huntApi, type HuntStorageStatus } from "../lib/huntApi";
import { HuntStoragePanel } from "./HuntStoragePanel";

vi.mock("../lib/huntApi", () => ({
  huntApi: {
    storage: vi.fn(),
    installReadModel: vi.fn(),
    backfillReadModel: vi.fn(),
  },
}));
const ready: HuntStorageStatus = {
  pendingMutations: 0,
  queryMode: "PHYSICAL",
  schema: {
    ready: true,
    missing: [],
    incompatible: [],
    checkedAt: "2026-10-22T10:00:00Z",
  },
  partitions: [{ partition: "20261022", rows: 5000, bytes: 1048576, parts: 3 }],
  mutations: [],
};
afterEach(() => vi.clearAllMocks());
describe("storage rollout", () => {
  it("keeps backfill disabled until the schema is compatible", async () => {
    vi.mocked(huntApi.storage).mockResolvedValue({
      ...ready,
      schema: { ...ready.schema, ready: false, incompatible: ["hunt2_actor"] },
    });
    render(<HuntStoragePanel />);
    await screen.findByText(/정의가 다른 열 1개/);
    expect(
      screen.getByRole("button", { name: "정규화 열 설치" }),
    ).toBeDisabled();
    expect(
      screen.getByRole("button", { name: "선택 일자 백필" }),
    ).toBeDisabled();
  });
  it("submits exactly one selected date and shows pending work rather than completion", async () => {
    vi.mocked(huntApi.storage).mockResolvedValue(ready);
    vi.mocked(huntApi.backfillReadModel).mockResolvedValue({
      ...ready,
      pendingMutations: 1,
      mutations: [
        {
          id: "mutation_1.txt",
          command: "MATERIALIZE COLUMN hunt2_actor",
          create_time: "2026-10-22 10:00:01",
          is_done: 0,
          parts_to_do: 3,
          latest_fail_reason: "",
        },
      ],
    });
    render(<HuntStoragePanel />);
    await screen.findByText(/정규화 열 사용 가능/);
    fireEvent.click(screen.getByRole("button", { name: "선택 일자 백필" }));
    await waitFor(() =>
      expect(huntApi.backfillReadModel).toHaveBeenCalledWith("20261022"),
    );
    expect(
      await screen.findByText(/저장소 변경 1개 진행 중/),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "선택 일자 백필" }),
    ).toBeDisabled();
    expect(screen.queryByText("완료")).not.toBeInTheDocument();
  });
  it("keeps a failed request visible and allows a refreshed status to be inspected", async () => {
    vi.mocked(huntApi.storage).mockResolvedValue(ready);
    vi.mocked(huntApi.backfillReadModel).mockRejectedValue(
      new Error("저장소 연결 실패"),
    );
    render(<HuntStoragePanel />);
    await screen.findByText(/정규화 열 사용 가능/);
    fireEvent.click(screen.getByRole("button", { name: "선택 일자 백필" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "저장소 연결 실패",
    );
    expect(
      screen.getByRole("button", { name: "선택 일자 백필" }),
    ).toBeEnabled();
  });
});
