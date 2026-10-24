import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { HuntAggregationPanel } from "./HuntAggregationPanel";
import type { HuntFilter } from "../lib/huntApi";
const fields = [
  { name: "actor", label: "계정", type: "string" },
  { name: "dst_port", label: "포트", type: "number" },
  { name: "source_id", label: "수집원", type: "string" },
];
const spec = {
  from: "2026-10-21T00:00:00Z",
  to: "2026-10-22T00:00:00Z",
  filter: null,
  text: "",
  groupBy: "actor",
  groupFields: ["actor", "dst_port"],
  metric: "count",
  pageSize: 1,
};
describe("compound field analysis", () => {
  it("selects up to three unique dimensions and waits for explicit execution", () => {
    const change = vi.fn(),
      run = vi.fn();
    render(
      <HuntAggregationPanel
        fields={fields}
        dimensions={["actor", "dst_port"]}
        metric="count"
        onDimensions={change}
        onMetric={vi.fn()}
        onRun={run}
        busy={false}
        onFilter={vi.fn()}
      />,
    );
    expect(
      screen
        .getByLabelText("집계 필드 3")
        .querySelector('option[value="actor"]'),
    ).toBeDisabled();
    fireEvent.change(screen.getByLabelText("집계 필드 3"), {
      target: { value: "source_id" },
    });
    expect(change).toHaveBeenCalledWith(["actor", "dst_port", "source_id"]);
    expect(run).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "집계 실행" }));
    expect(run).toHaveBeenCalledOnce();
  });
  it("shows all metrics and a partial-group warning while excluding a whole tuple", () => {
    const filter = vi.fn();
    render(
      <HuntAggregationPanel
        fields={fields}
        dimensions={["actor", "dst_port"]}
        metric="count"
        onDimensions={vi.fn()}
        onMetric={vi.fn()}
        onRun={vi.fn()}
        busy={false}
        onFilter={filter}
        result={{
          query: spec,
          groupHasMore: true,
          groups: [
            {
              value: "alice",
              dimensions: { actor: "alice", dst_port: null },
              count: 12,
              metric: 12,
              hostCount: 2,
              bytesSent: 1500,
              bytesReceived: 300,
            },
          ],
        }}
      />,
    );
    expect(
      screen.getByRole("table", { name: "전체 기간 복합 집계" }),
    ).toHaveTextContent("1,500");
    expect(screen.getByText("(값 없음)")).toBeInTheDocument();
    expect(screen.getByText(/다른 조합이 더 있습니다/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "집계 1 조합 제외" }));
    expect(filter).toHaveBeenCalledOnce();
    const condition = filter.mock.calls[0][0] as HuntFilter;
    function matches(node: HuntFilter, row: Record<string, string>): boolean {
      if (node.op === "not") return !matches(node.children![0], row);
      if (node.op === "and")
        return node.children!.every((child) => matches(child, row));
      if (node.op === "exists")
        return row[node.field!] !== undefined && row[node.field!] !== "";
      return row[node.field!] === node.values![0];
    }
    expect(matches(condition, { actor: "alice" })).toBe(false);
    expect(matches(condition, { actor: "alice", dst_port: "443" })).toBe(true);
    expect(matches(condition, { actor: "bob" })).toBe(true);
  });
});
