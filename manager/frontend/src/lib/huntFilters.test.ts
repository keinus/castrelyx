import { describe, expect, it } from "vitest";
import type { HuntFieldV2, HuntFilter } from "./huntApi";
import {
  appendFilter,
  filterDescription,
  replaceFilter,
  requireScenarioScope,
  validateFilter,
  querySignature,
  groupFilter,
} from "./huntFilters";
const fields: HuntFieldV2[] = [
  { name: "actor", label: "계정", type: "string" },
  { name: "dst_port", label: "포트", type: "number" },
  { name: "src_ip", label: "주소", type: "ip" },
  { name: "event_type", label: "유형", type: "string" },
];
const alice: HuntFilter = { op: "eq", field: "actor", values: ["alice"] };
const network: HuntFilter = {
  op: "cidr",
  field: "src_ip",
  values: ["10.0.0.0/8"],
};
describe("hunting filter contracts", () => {
  it("retains missing numeric dimensions when a different tuple is excluded under SQL null logic", () => {
    type Truth = boolean | null;
    function sql(node: HuntFilter, row: Record<string, string | null>): Truth {
      if (node.op === "exists")
        return row[node.field!] != null && row[node.field!] !== "";
      if (node.op === "not") {
        const child = sql(node.children![0], row);
        return child === null ? null : !child;
      }
      if (node.op === "and") {
        const terms = node.children!.map((child) => sql(child, row));
        return terms.includes(false)
          ? false
          : terms.includes(null)
            ? null
            : true;
      }
      const numeric =
        fields.find((field) => field.name === node.field)?.type === "number";
      if (numeric && row[node.field!] == null) return null;
      return numeric
        ? Number(row[node.field!]) === Number(node.values![0])
        : row[node.field!] === node.values![0];
    }
    const single = groupFilter({ dst_port: "443" }, true);
    expect(sql(single, {})).toBe(true);
    expect(sql(single, { dst_port: "443" })).toBe(false);
    expect(sql(single, { dst_port: "22" })).toBe(true);
    const compound = groupFilter({ actor: "alice", dst_port: "443" }, true);
    expect(sql(compound, { actor: "alice" })).toBe(true);
    expect(sql(compound, { actor: "bob", dst_port: "443" })).toBe(true);
    expect(sql(compound, { actor: "alice", dst_port: "443" })).toBe(false);
    expect(
      sql(groupFilter({ actor: "alice", dst_port: "443" }), { actor: "alice" }),
    ).not.toBe(true);
  });
  it("excludes only the selected compound value and treats missing dimensions explicitly", () => {
    const filter = groupFilter({ actor: "alice", dst_port: "443" }, true);
    function evaluate(node: HuntFilter, row: Record<string, string>): boolean {
      if (node.op === "not") return !evaluate(node.children![0], row);
      if (node.op === "and")
        return node.children!.every((child) => evaluate(child, row));
      if (node.op === "exists")
        return row[node.field!] !== undefined && row[node.field!] !== "";
      return row[node.field!] === node.values![0];
    }
    expect(evaluate(filter, { actor: "alice", dst_port: "443" })).toBe(false);
    for (const row of [
      { actor: "alice", dst_port: "22" },
      { actor: "bob", dst_port: "443" },
      { actor: "bob", dst_port: "22" },
    ])
      expect(evaluate(filter, row)).toBe(true);
    const missing = groupFilter({ actor: "alice", dst_port: null });
    expect(evaluate(missing, { actor: "alice" })).toBe(true);
    expect(evaluate(missing, { actor: "alice", dst_port: "0" })).toBe(false);
  });
  it("distinguishes changed query content from harmless server null fields and ISO formatting", () => {
    const spec = {
      from: "2026-10-22T00:00:00.000Z",
      to: "2026-10-22T01:00:00.000Z",
      filter: alice,
      text: "",
      groupBy: "actor",
      metric: "count",
      pageSize: 200,
    };
    const server = {
      ...spec,
      from: "2026-10-22T00:00:00Z",
      filter: { ...alice, children: null } as unknown as HuntFilter,
    };
    expect(querySignature(spec)).toBe(querySignature(server));
    expect(
      querySignature({ ...spec, filter: { ...alice, values: ["bob"] } }),
    ).not.toBe(querySignature(spec));
    expect(querySignature({ ...spec, pageSize: 500 })).not.toBe(
      querySignature(spec),
    );
  });
  it("preserves boolean precedence when pivots are appended to OR", () => {
    const root: HuntFilter = { op: "or", children: [alice, network] };
    expect(appendFilter(root, { op: "exists", field: "dst_port" })).toEqual({
      op: "and",
      children: [root, { op: "exists", field: "dst_port" }],
    });
    expect(root.children).toHaveLength(2);
  });
  it("prunes a removed negation branch without dropping a sibling", () => {
    const root: HuntFilter = {
      op: "or",
      children: [alice, { op: "not", children: [network] }],
    };
    expect(replaceFilter(root, [1, 0], null)).toEqual({
      op: "or",
      children: [alice],
    });
  });
  it("keeps pure scope OR/NOT intact and rejects non-scope terms instead of widening it", () => {
    const scope: HuntFilter = {
      op: "or",
      children: [alice, { op: "not", children: [network] }],
    };
    expect(() => requireScenarioScope(scope, "")).not.toThrow();
    expect(() =>
      requireScenarioScope(
        {
          op: "and",
          children: [
            scope,
            { op: "eq", field: "event_type", values: ["process.start"] },
          ],
        },
        "",
      ),
    ).toThrow(/자산·주소/);
    expect(() => requireScenarioScope(scope, "needle")).toThrow(/자유 검색어/);
  });
  it("rejects invalid numbers, multi-valued scalar conditions, empty groups and oversized trees", () => {
    for (const filter of [
      { op: "gt", field: "dst_port", values: ["NaN"] },
      { op: "cidr", field: "src_ip", values: ["10.0.0.0/8", "192.168.0.0/16"] },
      { op: "contains", field: "dst_port", values: ["4"] },
      { op: "not", children: [] },
      { op: "and", children: [...Array.from({ length: 100 }, () => alice)] },
      { op: "and", children: [null as unknown as HuntFilter] },
    ])
      expect(validateFilter(filter, fields).length).toBeGreaterThan(0);
    let deep = alice;
    for (let i = 0; i < 9; i++) deep = { op: "not", children: [deep] };
    expect(validateFilter(deep, fields)).not.toEqual([]);
  });
  it("shows nested operands and quoted values rather than an opaque complex-condition chip", () => {
    expect(
      filterDescription(
        {
          op: "and",
          children: [
            { op: "or", children: [alice, network] },
            { op: "not", children: [{ op: "exists", field: "dst_port" }] },
          ],
        },
        fields,
      ),
    ).toBe(
      '((계정 같음 ["alice"] OR 주소 CIDR 대역 ["10.0.0.0/8"]) AND NOT (포트 값 있음))',
    );
  });
});
