import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import type { HuntRelationNode, HuntRelationsV2 } from "../lib/huntApi";
import { HuntRelationsPanel } from "./HuntRelationsPanel";

function node(
  id: string,
  label: string,
  kind: HuntRelationNode["kind"],
): HuntRelationNode {
  return {
    id,
    label,
    kind,
    attributes: {
      source_id: "sensor-a",
      source_type: "AGENT",
      observed_asset_uid: "observer",
      identityBasis: "GUID",
    },
    referenceOnly: false,
    ambiguous: false,
    firstSeen: "2026-10-22T00:00:00Z",
    lastSeen: "2026-10-22T00:00:10Z",
    startedAt: null,
    endedAt: null,
    eventIds: ["a".repeat(64)],
    evidenceCount: 1,
  };
}
function fixture(): HuntRelationsV2 {
  return {
    version: "relations-1.0",
    nodes: [
      node("p", "parent.exe", "PROCESS"),
      node("c", "child.exe", "PROCESS"),
      node("f", "10.0.0.1 → 198.51.100.1", "CONNECTION"),
      node("o", "10.0.0.1:50000", "ENDPOINT"),
      node("d", "198.51.100.1:443", "ENDPOINT"),
    ],
    edges: [
      {
        id: "pc",
        from: "p",
        to: "c",
        kind: "PARENT_OF",
        eventIds: ["a".repeat(64)],
        evidenceCount: 1,
      },
      {
        id: "cf",
        from: "c",
        to: "f",
        kind: "PROCESS_NETWORK",
        eventIds: ["b".repeat(64)],
        evidenceCount: 1,
      },
      {
        id: "of",
        from: "o",
        to: "f",
        kind: "OBSERVED_FROM",
        eventIds: ["b".repeat(64)],
        evidenceCount: 1,
      },
      {
        id: "fd",
        from: "f",
        to: "d",
        kind: "OBSERVED_TO",
        eventIds: ["b".repeat(64)],
        evidenceCount: 1,
      },
    ],
    issues: {},
    issueSamples: [],
    coverage: {
      matchedEvents: 4,
      evaluatedEvents: 4,
      unexaminedEvents: 0,
      inputLimit: 10000,
      nodeLimit: 1000,
      edgeLimit: 2000,
      omittedNodes: 0,
      omittedEdges: 0,
      truncated: false,
      complete: true,
    },
  };
}
describe("evidence relationship workspace", () => {
  it("selects the first node when a queued job delivers graph data after mounting", () => {
    const props = { onRun: vi.fn(), onEvent: vi.fn() };
    const view = render(<HuntRelationsPanel {...props} busy={true} />);
    view.rerender(
      <HuntRelationsPanel {...props} busy={false} graph={fixture()} />,
    );
    expect(
      screen.getByRole("heading", { name: "parent.exe" }),
    ).toBeInTheDocument();
    expect(screen.getByLabelText("선택한 노드의 전체 관계")).toHaveTextContent(
      "부모 → 자식",
    );
  });
  it("keeps the hierarchy and keyboard-selected diagram synchronized with the inspector", () => {
    render(
      <HuntRelationsPanel
        graph={fixture()}
        busy={false}
        onRun={vi.fn()}
        onEvent={vi.fn()}
      />,
    );
    const outline = screen.getByRole("list", { name: "관계 노드 목록" });
    expect(
      within(outline)
        .getAllByRole("button")
        .map((button) => button.textContent),
    ).toEqual([
      expect.stringContaining("parent.exe"),
      expect.stringContaining("child.exe"),
      expect.stringContaining("10.0.0.1 →"),
      expect.stringContaining("10.0.0.1:50000"),
      expect.stringContaining("198.51.100.1:443"),
    ]);
    fireEvent.click(screen.getByText(/선택한 노드의 관계도/));
    fireEvent.keyDown(
      screen.getByRole("button", { name: "child.exe 관계 선택" }),
      { key: "Enter" },
    );
    expect(
      screen.getByRole("heading", { name: "child.exe" }),
    ).toBeInTheDocument();
    expect(screen.getByLabelText("선택한 노드의 전체 관계")).toHaveTextContent(
      "프로세스의 연결 근거",
    );
  });
  it("searches across source attributes and filters kinds without inventing relationships", () => {
    render(
      <HuntRelationsPanel
        graph={fixture()}
        busy={false}
        onRun={vi.fn()}
        onEvent={vi.fn()}
      />,
    );
    fireEvent.change(screen.getByLabelText("관계 노드 유형"), {
      target: { value: "CONNECTION" },
    });
    expect(
      within(screen.getByRole("list", { name: "관계 노드 목록" })).getAllByRole(
        "button",
      ),
    ).toHaveLength(1);
    fireEvent.change(screen.getByLabelText("관계 노드 검색"), {
      target: { value: "sensor-b" },
    });
    expect(
      screen.getByText("검색에 일치하는 노드가 없습니다."),
    ).toBeInTheDocument();
  });
  it("loads the exact source evidence with pending feedback", async () => {
    let finish!: () => void;
    const request = vi.fn(
      () =>
        new Promise<void>((resolve) => {
          finish = resolve;
        }),
    );
    render(
      <HuntRelationsPanel
        graph={fixture()}
        busy={false}
        onRun={vi.fn()}
        onEvent={request}
      />,
    );
    fireEvent.click(screen.getByText(/노드 근거 1건/));
    fireEvent.click(screen.getByRole("button", { name: "노드 근거 1 보기" }));
    expect(request).toHaveBeenCalledWith("a".repeat(64));
    expect(screen.getByRole("status")).toHaveTextContent(
      "근거를 불러오고 있습니다",
    );
    await act(async () => finish());
    await waitFor(() =>
      expect(
        screen.queryByText("근거를 불러오고 있습니다."),
      ).not.toBeInTheDocument(),
    );
  });
  it("exposes ambiguous or truncated analysis and does not equate an empty graph with safety", () => {
    const graph = fixture();
    graph.nodes = [];
    graph.edges = [];
    graph.coverage = {
      ...graph.coverage,
      complete: false,
      truncated: true,
      omittedNodes: 5,
    };
    graph.issues = { PROCESS_IDENTITY_MISSING: 4 };
    render(
      <HuntRelationsPanel
        graph={graph}
        busy={false}
        onRun={vi.fn()}
        onEvent={vi.fn()}
      />,
    );
    expect(screen.getByRole("status")).toHaveTextContent(
      "프로세스 식별 문맥 부족: 4건",
    );
    expect(
      screen.getByText(/빈 그래프만으로 안전을 판정할 수 없습니다/),
    ).toBeInTheDocument();
  });
});
