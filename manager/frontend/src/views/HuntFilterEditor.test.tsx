import { useState } from "react";
import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { HuntFilterEditor } from "./HuntFilterEditor";
import type { HuntFilter } from "../lib/huntApi";
const fields = [
  { name: "src_ip", label: "주소", type: "ip" },
  { name: "actor", label: "계정", type: "string" },
  { name: "dst_port", label: "포트", type: "number" },
];
function Harness({ initial = null }: { initial?: HuntFilter | null }) {
  const [filter, setFilter] = useState(initial);
  return (
    <>
      <HuntFilterEditor value={filter} fields={fields} onChange={setFilter} />
      <output aria-label="serialized filter">{JSON.stringify(filter)}</output>
    </>
  );
}
describe("boolean filter editor", () => {
  it("clears a prior size warning when another query is restored and does not resurrect it", () => {
    const leaf: HuntFilter = { op: "eq", field: "actor", values: ["alice"] };
    const hundred: HuntFilter = {
      op: "and",
      children: [
        ...Array.from({ length: 3 }, () => ({
          op: "or",
          children: Array.from({ length: 30 }, () => leaf),
        })),
        ...Array.from({ length: 6 }, () => leaf),
      ],
    };
    const onChange = vi.fn();
    const view = render(
      <HuntFilterEditor value={hundred} fields={fields} onChange={onChange} />,
    );
    fireEvent.click(screen.getByRole("button", { name: "최상위 조건 추가" }));
    expect(screen.getByRole("alert")).toHaveTextContent(/100개 노드/);
    expect(onChange).not.toHaveBeenCalled();
    view.rerender(
      <HuntFilterEditor value={leaf} fields={fields} onChange={onChange} />,
    );
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    view.rerender(
      <HuntFilterEditor value={hundred} fields={fields} onChange={onChange} />,
    );
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });
  it("edits an OR group, negates one term and preserves the other operand", () => {
    render(
      <Harness
        initial={{
          op: "and",
          children: [
            { op: "eq", field: "actor", values: ["alice"] },
            { op: "eq", field: "actor", values: ["bob"] },
          ],
        }}
      />,
    );
    fireEvent.change(screen.getByLabelText("그룹 루트 결합"), {
      target: { value: "or" },
    });
    fireEvent.click(screen.getByRole("button", { name: "조건 2 부정" }));
    fireEvent.change(screen.getByLabelText("조건 2.1 값"), {
      target: { value: "service-account" },
    });
    expect(
      JSON.parse(screen.getByLabelText("serialized filter").textContent!),
    ).toEqual({
      op: "or",
      children: [
        { op: "eq", field: "actor", values: ["alice"] },
        {
          op: "not",
          children: [{ op: "eq", field: "actor", values: ["service-account"] }],
        },
      ],
    });
  });
  it("adds a nested multi-value list and can remove its whole group", () => {
    render(
      <Harness
        initial={{
          op: "and",
          children: [{ op: "eq", field: "actor", values: ["alice"] }],
        }}
      />,
    );
    fireEvent.click(
      screen.getByRole("button", { name: "그룹 루트에 하위 그룹 추가" }),
    );
    fireEvent.change(screen.getByLabelText("조건 2.1 연산자"), {
      target: { value: "in" },
    });
    fireEvent.change(screen.getByLabelText("조건 2.1 값"), {
      target: { value: "10.0.0.1\n10.0.0.2" },
    });
    expect(screen.getByLabelText("그룹 2 결합")).toHaveValue("or");
    expect(screen.getByLabelText("serialized filter")).toHaveTextContent(
      '"values":["10.0.0.1","10.0.0.2"]',
    );
    fireEvent.click(screen.getByRole("button", { name: "그룹 2 삭제" }));
    expect(screen.queryByLabelText("조건 2.1 값")).not.toBeInTheDocument();
    expect(screen.getByLabelText("serialized filter")).not.toHaveTextContent(
      "10.0.0.1",
    );
  });
  it("validates typed numbers and lets the analyst express missing values explicitly", () => {
    render(
      <Harness
        initial={{
          op: "and",
          children: [{ op: "gte", field: "dst_port", values: ["NaN"] }],
        }}
      />,
    );
    expect(screen.getByRole("alert")).toHaveTextContent(/유한한 숫자/);
    fireEvent.change(screen.getByLabelText("조건 1 연산자"), {
      target: { value: "exists" },
    });
    fireEvent.click(screen.getByRole("button", { name: "조건 1 부정" }));
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(screen.getByLabelText("현재 조건식")).toHaveTextContent(
      "NOT (포트 값 있음)",
    );
  });
});
