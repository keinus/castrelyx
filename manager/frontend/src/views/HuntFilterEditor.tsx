import { useState } from "react";
import type { HuntFieldV2, HuntFilter } from "../lib/huntApi";
import {
  appendFilter,
  filterDescription,
  filterSize,
  operatorLabels,
  operators,
  replaceFilter,
  validateFilter,
} from "../lib/huntFilters";

export function HuntFilterEditor({
  value,
  fields,
  onChange,
}: {
  value: HuntFilter | null;
  fields: HuntFieldV2[];
  onChange: (filter: HuntFilter | null) => void;
}) {
  const [feedback, setFeedback] = useState({ value, message: "" });
  const limitError = feedback.value === value ? feedback.message : "";
  // A warning belongs to the attempted edit, not a different restored query.
  // Reset it before committing the new view, including when an older object is revisited.
  if (feedback.value !== value) setFeedback({ value, message: "" });
  const size = filterSize(value);
  const errors = validateFilter(value, fields);
  const leaf = (): HuntFilter => ({
    op: "eq",
    field: fields.some((f) => f.name === "src_ip")
      ? "src_ip"
      : (fields[0]?.name ?? "src_ip"),
    values: [],
  });
  function commit(next: HuntFilter | null) {
    const stats = filterSize(next);
    if (stats.nodes > 100 || stats.depth > 8) {
      setFeedback({
        value,
        message: "조건은 100개 노드·9단계 이내로 작성하세요.",
      });
      return;
    }
    setFeedback({ value: next, message: "" });
    onChange(next);
  }
  function update(path: number[], next: HuntFilter | null) {
    commit(replaceFilter(value, path, next));
  }
  function nodeEditor(node: HuntFilter, path: number[]): React.ReactNode {
    const name = path.length
      ? path.map((index) => index + 1).join(".")
      : "루트";
    const group = ["and", "or", "not"].includes(node.op);
    return (
      <fieldset
        key={name}
        className="hw-filter-node"
        aria-label={`${group ? "그룹" : "조건"} ${name}`}
      >
        <legend>
          {node.op === "not"
            ? "NOT · 일치하지 않음"
            : group
              ? `그룹 ${name}`
              : `조건 ${name}`}
        </legend>
        <div className="hw-filter-node-controls">
          {group && node.op !== "not" && (
            <label>
              결합
              <select
                aria-label={`그룹 ${name} 결합`}
                value={node.op}
                onChange={(event) =>
                  update(path, { ...node, op: event.target.value })
                }
              >
                <option value="and">AND · 모두 일치</option>
                <option value="or">OR · 하나 이상 일치</option>
              </select>
            </label>
          )}
          <button
            type="button"
            onClick={() =>
              update(
                path,
                node.op === "not"
                  ? (node.children?.[0] ?? null)
                  : { op: "not", children: [node] },
              )
            }
          >
            {node.op === "not"
              ? `그룹 ${name} 부정 해제`
              : `${group ? "그룹" : "조건"} ${name} 부정`}
          </button>
          <button type="button" onClick={() => update(path, null)}>
            {group ? "그룹" : "조건"} {name} 삭제
          </button>
        </div>
        {group ? (
          <>
            {(node.children ?? []).map((child, index) =>
              nodeEditor(child, [...path, index]),
            )}
            {node.op !== "not" && (
              <div className="hw-filter-node-controls">
                <button
                  type="button"
                  disabled={(node.children?.length ?? 0) >= 30}
                  onClick={() =>
                    update(path, {
                      ...node,
                      children: [...(node.children ?? []), leaf()],
                    })
                  }
                >
                  그룹 {name}에 조건 추가
                </button>
                <button
                  type="button"
                  disabled={(node.children?.length ?? 0) >= 30}
                  onClick={() =>
                    update(path, {
                      ...node,
                      children: [
                        ...(node.children ?? []),
                        { op: "or", children: [leaf()] },
                      ],
                    })
                  }
                >
                  그룹 {name}에 하위 그룹 추가
                </button>
              </div>
            )}
          </>
        ) : (
          <div className="hw-filter-row">
            <label>
              필드
              <select
                aria-label={`조건 ${name} 필드`}
                value={node.field ?? ""}
                onChange={(event) => {
                  const field = fields.find(
                    (item) => item.name === event.target.value,
                  );
                  update(path, {
                    ...node,
                    field: event.target.value,
                    op: operators(field?.type ?? "string").includes(node.op)
                      ? node.op
                      : "eq",
                  });
                }}
              >
                {!fields.some((field) => field.name === node.field) && (
                  <option value={node.field ?? ""}>
                    {node.field ?? "필드 선택"}
                  </option>
                )}
                {fields.map((field) => (
                  <option value={field.name} key={field.name}>
                    {field.label}
                  </option>
                ))}
              </select>
            </label>
            <label>
              연산자
              <select
                aria-label={`조건 ${name} 연산자`}
                value={node.op}
                onChange={(event) =>
                  update(path, {
                    ...node,
                    op: event.target.value,
                    values:
                      event.target.value === "exists"
                        ? []
                        : (node.values ?? []),
                  })
                }
              >
                {!operators(
                  fields.find((field) => field.name === node.field)?.type ??
                    "string",
                ).includes(node.op) && (
                  <option value={node.op}>{node.op}</option>
                )}
                {operators(
                  fields.find((field) => field.name === node.field)?.type ??
                    "string",
                ).map((op) => (
                  <option value={op} key={op}>
                    {operatorLabels[op]}
                  </option>
                ))}
              </select>
            </label>
            {node.op !== "exists" && (
              <label className="hw-filter-value">
                {["in", "not_in"].includes(node.op)
                  ? "값 · 한 줄에 하나"
                  : "값"}
                {["in", "not_in"].includes(node.op) ||
                (node.values?.length ?? 0) > 1 ? (
                  <textarea
                    rows={3}
                    aria-label={`조건 ${name} 값`}
                    value={(node.values ?? []).join("\n")}
                    onChange={(event) =>
                      update(path, {
                        ...node,
                        values: event.target.value.split("\n"),
                      })
                    }
                  />
                ) : (
                  <input
                    aria-label={`조건 ${name} 값`}
                    value={node.values?.[0] ?? ""}
                    maxLength={1024}
                    onChange={(event) =>
                      update(path, { ...node, values: [event.target.value] })
                    }
                  />
                )}
              </label>
            )}
          </div>
        )}
      </fieldset>
    );
  }
  return (
    <section className="hw-filter-editor" aria-label="복합 조건 편집">
      <div className="hw-filter-heading">
        <strong>조건 편집 · {size.nodes}/100</strong>
        <button
          type="button"
          onClick={() => commit(appendFilter(value, leaf()))}
        >
          최상위 조건 추가
        </button>
        {value && (
          <button type="button" onClick={() => commit(null)}>
            모두 지우기
          </button>
        )}
      </div>
      {value ? (
        nodeEditor(value, [])
      ) : (
        <p className="hw-help">
          추가 조건이 없습니다. 그룹을 만들고 AND·OR·NOT으로 가설을 조합하세요.
        </p>
      )}
      <p className="hw-filter-expression" aria-label="현재 조건식">
        {filterDescription(value, fields)}
      </p>
      <p className="hw-help">
        숫자가 누락된 이벤트는 수치·목록 비교에서 제외됩니다. 누락값은 '값 있음'
        조건을 부정해 조회하세요.
      </p>
      {[...new Set([limitError, ...errors].filter(Boolean))].map((error) => (
        <p className="hw-error" role="alert" key={error}>
          {error}
        </p>
      ))}
    </section>
  );
}
