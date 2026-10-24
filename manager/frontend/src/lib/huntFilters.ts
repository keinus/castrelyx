import type { HuntFieldV2, HuntFilter, HuntQueryV2 } from "./huntApi";

export const scopeFields = new Set([
  "asset_uid",
  "source_id",
  "source_type",
  "src_ip",
  "dst_ip",
  "realm",
  "actor",
]);
export const operatorLabels: Record<string, string> = {
  eq: "같음",
  neq: "같지 않음",
  contains: "문자열 포함",
  prefix: "문자열로 시작",
  in: "목록 중 하나",
  not_in: "목록에서 제외",
  exists: "값 있음",
  cidr: "CIDR 대역",
  gt: "초과",
  gte: "이상",
  lt: "미만",
  lte: "이하",
};
export function operators(type: string) {
  return [
    "eq",
    "neq",
    "in",
    "not_in",
    "exists",
    ...(type === "number"
      ? ["gt", "gte", "lt", "lte"]
      : ["contains", "prefix", ...(type === "ip" ? ["cidr"] : [])]),
  ];
}
export function filterSize(filter: HuntFilter | null): {
  nodes: number;
  depth: number;
} {
  if (!filter) return { nodes: 0, depth: 0 };
  const children = (filter.children ?? []).map(filterSize);
  return {
    nodes: 1 + children.reduce((sum, item) => sum + item.nodes, 0),
    depth: children.length
      ? 1 + Math.max(...children.map((item) => item.depth))
      : 0,
  };
}
export function appendFilter(
  root: HuntFilter | null,
  leaf: HuntFilter,
): HuntFilter {
  return root?.op === "and"
    ? { ...root, children: [...(root.children ?? []), leaf] }
    : { op: "and", children: [...(root ? [root] : []), leaf] };
}
/** Removal prunes empty groups, including an empty NOT. Other branches keep their meaning. */
export function replaceFilter(
  root: HuntFilter | null,
  path: number[],
  next: HuntFilter | null,
): HuntFilter | null {
  if (!path.length) return next;
  if (!root?.children) return root;
  const [index, ...tail] = path;
  const children = root.children.flatMap((child, i) => {
    if (i !== index) return [child];
    const replaced = replaceFilter(child, tail, next);
    return replaced ? [replaced] : [];
  });
  return children.length ? { ...root, children } : null;
}
export function validateFilter(
  filter: HuntFilter | null,
  fields: HuntFieldV2[],
): string[] {
  const errors: string[] = [];
  let nodes = 0;
  function visit(node: HuntFilter, depth: number) {
    if (++nodes > 100 || depth > 8) {
      errors.push("조건은 100개 노드·9단계 이내로 작성하세요.");
      return;
    }
    if (!node || typeof node !== "object" || Array.isArray(node)) {
      errors.push("조건 형식을 확인하세요.");
      return;
    }
    if (["and", "or", "not"].includes(node.op)) {
      if (
        !Array.isArray(node.children) ||
        !node.children.length ||
        node.children.length > 30 ||
        (node.op === "not" && node.children.length !== 1)
      ) {
        errors.push(
          "AND/OR 그룹은 1~30개, NOT은 하나의 조건 또는 그룹이 필요합니다.",
        );
        return;
      }
      node.children.forEach((child) => visit(child, depth + 1));
      return;
    }
    const field = fields.find((item) => item.name === node.field);
    if (!field) {
      errors.push(`지원하지 않는 조건 필드: ${node.field ?? "미지정"}`);
      return;
    }
    if (!operators(field.type).includes(node.op)) {
      errors.push(`${field.label}에 사용할 수 없는 연산자입니다.`);
      return;
    }
    if (node.op === "exists") return;
    if (
      !Array.isArray(node.values) ||
      !node.values.length ||
      node.values.length > 100 ||
      node.values.some(
        (value) => typeof value !== "string" || value.length > 1024,
      )
    ) {
      errors.push(
        `${field.label} 값은 1~100개, 각각 1,024자 이내로 입력하세요.`,
      );
      return;
    }
    if (!["in", "not_in"].includes(node.op) && node.values.length !== 1)
      errors.push(`${field.label}의 단일 조건에는 값 하나가 필요합니다.`);
    if (
      field.type === "number" &&
      node.values.some(
        (value) => !value.trim() || !Number.isFinite(Number(value)),
      )
    )
      errors.push(`${field.label}에는 유한한 숫자를 입력하세요.`);
  }
  if (filter) visit(filter, 0);
  return [...new Set(errors)];
}
export function requireScenarioScope(filter: HuntFilter | null, text: string) {
  if (text.trim())
    throw new Error(
      "시나리오에는 자유 검색어 대신 자산·주소 범위를 사용하세요.",
    );
  function visit(node: HuntFilter) {
    if (["and", "or", "not"].includes(node.op)) {
      node.children?.forEach(visit);
      return;
    }
    if (!scopeFields.has(node.field ?? ""))
      throw new Error(
        "시나리오 범위에는 자산·주소·계정·원천 조건만 사용할 수 있습니다. 조건을 수정한 뒤 다시 실행하세요.",
      );
  }
  if (filter) visit(filter);
}
export function filterDescription(
  filter: HuntFilter | null,
  fields: HuntFieldV2[],
): string {
  if (!filter) return "추가 조건 없음";
  if (["and", "or", "not"].includes(filter.op)) {
    const terms =
      filter.children?.map((child) => filterDescription(child, fields)) ?? [];
    return filter.op === "not"
      ? `NOT (${terms[0] ?? ""})`
      : `(${terms.join(` ${filter.op.toUpperCase()} `)})`;
  }
  return `${fields.find((item) => item.name === filter.field)?.label ?? filter.field} ${operatorLabels[filter.op] ?? filter.op}${filter.op === "exists" ? "" : ` ${JSON.stringify(filter.values ?? [])}`}`;
}

/** Compare only executable query content; server-added null record fields are not edits. */
export function querySignature(query: HuntQueryV2): string {
  function canonical(node: HuntFilter | null | undefined): unknown {
    if (!node) return null;
    if (["and", "or", "not"].includes(node.op))
      return [node.op, (node.children ?? []).map(canonical)];
    return [
      node.op ?? "eq",
      node.field,
      node.op === "exists" ? [] : (node.values ?? []),
    ];
  }
  const instant = (value: string) =>
    Number.isFinite(Date.parse(value)) ? Date.parse(value) : value;
  return JSON.stringify([
    instant(query.from),
    instant(query.to),
    canonical(query.filter),
    query.text ?? "",
    query.groupFields?.length
      ? query.groupFields
      : [query.groupBy ?? "event_type"],
    query.metric ?? "count",
    query.pageSize || 200,
    query.includeRelations ?? false,
  ]);
}

/** Excluding a compound value means NOT (A AND B), rather than A != x AND B != y. */
export function groupFilter(
  dimensions: Record<string, string | null>,
  exclude = false,
): HuntFilter {
  const children: HuntFilter[] = Object.entries(dimensions).map(
    ([field, value]) =>
      value === null || value === ""
        ? { op: "not", children: [{ op: "exists", field }] }
        : {
            // A missing numeric value compares as SQL NULL. Guard the equality so
            // negating this tuple retains events belonging to a missing-value group.
            op: "and",
            children: [
              { op: "exists", field },
              { op: "eq", field, values: [value] },
            ],
          },
  );
  const included: HuntFilter = { op: "and", children };
  return exclude ? { op: "not", children: [included] } : included;
}
