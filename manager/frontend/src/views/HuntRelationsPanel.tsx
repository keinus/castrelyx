import { useId, useMemo, useState } from "react";
import { Alert, AlertDescription, AlertTitle } from "../components/ui/alert";
import { Badge } from "../components/ui/badge";
import { Button } from "../components/ui/button";
import { Field, FieldGroup, FieldLabel } from "../components/ui/field";
import { Input } from "../components/ui/input";
import { NativeSelect } from "../components/ui/native-select";
import type {
  HuntRelationEdge,
  HuntRelationNode,
  HuntRelationsV2,
} from "../lib/huntApi";

const kinds: Record<string, string> = {
  PROCESS: "프로세스",
  CONNECTION: "관측 연결",
  ENDPOINT: "관측 끝점",
};
const edgeNames: Record<string, string> = {
  PARENT_OF: "부모 → 자식",
  PROCESS_NETWORK: "프로세스의 연결 근거",
  OBSERVED_FROM: "관측 출발지",
  OBSERVED_TO: "관측 목적지",
};
const issueNames: Record<string, string> = {
  INVALID_EVENT_ID_OR_TIME: "이벤트 ID·시각 오류",
  PROCESS_IDENTITY_MISSING: "프로세스 식별 문맥 부족",
  PROCESS_IDENTITY_CONFLICT: "프로세스 식별 정보 충돌",
  CONNECTION_IDENTITY_MISSING: "연결 식별 문맥 부족",
  PARENT_CYCLE: "순환 부모 참조",
  CONFLICTING_PARENT_REFERENCE: "부모 참조 충돌",
  PROCESS_LIFETIME_CONFLICT: "프로세스 수명 충돌",
  PARENT_NOT_OBSERVED: "부모 원천 이벤트 미관측",
  CONNECTION_TIME_CONFLICT: "연결 시각 충돌",
  CONNECTION_TUPLE_MISSING: "연결 주소·포트 문맥 부족",
  SELECTION_CHANGED_OR_EXPIRED: "조회 중 원천 만료·선택 건수 변경",
};
const attributeNames: Record<string, string> = {
  observed_asset_uid: "원천 관측 자산",
  asset_uid: "매핑된 자산",
  source_type: "수집 경로",
  source_id: "수집원",
  boot_id: "부팅 ID",
  network_namespace: "네트워크 관측 영역",
  process_guid: "프로세스 GUID",
  process_pid: "PID",
  connection_uid: "원천 연결 ID",
  community_id: "Community ID",
  src_ip: "출발지",
  dst_ip: "목적지",
  src_port: "출발지 포트",
  dst_port: "목적지 포트",
  protocol: "프로토콜",
  identityBasis: "식별 근거",
};
const basisNames: Record<string, string> = {
  GUID: "관측 범위의 GUID",
  BOOT_PID_START: "부팅 ID·PID·시작 시각",
  BOOT_START_ID_TUPLE: "부팅·시작 시각·연결 ID·5-tuple",
  EVENT_5_TUPLE: "해당 이벤트의 5-tuple",
  EVENT_CONNECTION_ID: "해당 이벤트의 연결 ID",
};
const number = (value: number) => Number(value).toLocaleString("ko-KR");

function orderNodes(graph: HuntRelationsV2) {
  const byId = new Map(graph.nodes.map((n) => [n.id, n]));
  const children = new Map<string, string[]>();
  const parentIds = new Set<string>();
  for (const edge of graph.edges.filter((e) => e.kind === "PARENT_OF")) {
    children.set(edge.from, [...(children.get(edge.from) ?? []), edge.to]);
    parentIds.add(edge.to);
  }
  const ordered: { node: HuntRelationNode; depth: number }[] = [];
  const visited = new Set<string>();
  function visit(id: string, depth: number) {
    const node = byId.get(id);
    if (!node || visited.has(id)) return;
    visited.add(id);
    ordered.push({ node, depth });
    for (const child of children.get(id) ?? []) visit(child, depth + 1);
  }
  graph.nodes
    .filter((n) => n.kind === "PROCESS" && !parentIds.has(n.id))
    .forEach((n) => visit(n.id, 0));
  graph.nodes.forEach((n) => visit(n.id, 0));
  return ordered;
}

export function HuntRelationsPanel({
  graph,
  busy,
  onRun,
  onEvent,
}: {
  graph?: HuntRelationsV2;
  busy: boolean;
  onRun: () => void;
  onEvent: (id: string) => Promise<void>;
}) {
  const [selection, setSelection] = useState(graph?.nodes[0]?.id ?? "");
  const [search, setSearch] = useState("");
  const [mode, setMode] = useState("ALL");
  const [limit, setLimit] = useState(50);
  const [pendingEvent, setPendingEvent] = useState("");
  const [error, setError] = useState("");
  const arrow = useId().replaceAll(":", "");
  const nodes = useMemo(
    () => new Map((graph?.nodes ?? []).map((node) => [node.id, node])),
    [graph?.nodes],
  );
  const ordered = useMemo(() => (graph ? orderNodes(graph) : []), [graph]);
  const filtered = ordered.filter(
    ({ node }) =>
      (mode === "ALL" || node.kind === mode) &&
      `${node.label} ${Object.values(node.attributes).join(" ")}`
        .toLowerCase()
        .includes(search.toLowerCase()),
  );
  const selectedId = nodes.has(selection)
    ? selection
    : (graph?.nodes[0]?.id ?? "");
  const selected = nodes.get(selectedId);
  const related =
    graph?.edges.filter(
      (edge) => edge.from === selectedId || edge.to === selectedId,
    ) ?? [];
  const left = related.filter((e) => e.to === selectedId).slice(0, 12);
  const right = related.filter((e) => e.from === selectedId).slice(0, 12);
  const height = Math.max(180, Math.max(left.length, right.length) * 64 + 40);
  const centerY = height / 2 - 22;
  async function evidence(id: string) {
    setPendingEvent(id);
    setError("");
    try {
      await onEvent(id);
    } catch (cause) {
      if (!(cause instanceof DOMException && cause.name === "AbortError"))
        setError(
          cause instanceof Error
            ? cause.message
            : "근거를 불러오지 못했습니다.",
        );
    } finally {
      setPendingEvent("");
    }
  }
  function nodeMark(node: HuntRelationNode, x: number, y: number) {
    return (
      <g
        key={`${node.id}-${x}-${y}`}
        transform={`translate(${x},${y})`}
        className="hw-relation-node"
        role="button"
        tabIndex={0}
        aria-label={`${node.label} 관계 선택`}
        aria-pressed={node.id === selectedId}
        onClick={() => setSelection(node.id)}
        onKeyDown={(e) => {
          if (e.key === "Enter" || e.key === " ") {
            e.preventDefault();
            setSelection(node.id);
          }
        }}
      >
        <rect width={260} height={44} rx={5} />
        <text x={10} y={17}>
          {node.label.length > 32 ? node.label.slice(0, 32) + "…" : node.label}
        </text>
        <text x={10} y={34} className="hw-relation-kind">
          {kinds[node.kind]}
          {node.referenceOnly ? " · 참조만 존재" : ""}
        </text>
      </g>
    );
  }
  function edgeEvidence(edge: HuntRelationEdge) {
    return (
      <details key={edge.id}>
        <summary>
          {edgeNames[edge.kind] ?? edge.kind} · {nodes.get(edge.from)?.label} →{" "}
          {nodes.get(edge.to)?.label} · 근거 {number(edge.evidenceCount)}건
        </summary>
        <div className="hw-relation-evidence">
          {edge.eventIds.map((id, index) => (
            <Button
              key={id}
              type="button"
              variant="outline"
              size="sm"
              disabled={busy || !!pendingEvent}
              onClick={() => {
                void evidence(id);
              }}
              aria-label={`${edgeNames[edge.kind]} 근거 ${index + 1} 보기`}
            >
              근거 {index + 1} 보기
            </Button>
          ))}
        </div>
        {edge.evidenceCount > edge.eventIds.length && (
          <p className="hw-help">
            대표 근거 {edge.eventIds.length}개를 표시합니다. 전체 기간 이벤트
            조회로 나머지 기록을 확인하세요.
          </p>
        )}
      </details>
    );
  }
  return (
    <section
      className="data-panel hw-relations"
      aria-label="프로세스 트리와 연결 그래프"
    >
      <div className="panel-heading">
        <h3>프로세스 트리·연결 그래프</h3>
        <Button type="button" variant="outline" disabled={busy} onClick={onRun}>
          관계 분석 실행
        </Button>
      </div>
      {!graph ? (
        <p className="hw-help">
          현재 조회 조건과 기간의 원천 근거로 관계를 분석합니다. 프로세스 GUID
          또는 부팅·PID·시작 시각, 연결 ID나 5-tuple이 필요합니다.
        </p>
      ) : (
        <>
          <p className="hw-help">
            일치 {number(graph.coverage.matchedEvents)}건 중{" "}
            {number(graph.coverage.evaluatedEvents)}건 검사 · 노드{" "}
            {number(graph.nodes.length)}개 · 관계 {number(graph.edges.length)}
            개. 주소는 관측 끝점이며 장비의 소유를 뜻하지 않습니다. 센서 간
            연결이나 NAT 변환은 추측하지 않습니다.
          </p>
          {!graph.coverage.complete && (
            <Alert role="status">
              <AlertTitle>관계 분석에 한계가 있습니다</AlertTitle>
              <AlertDescription>
                {graph.coverage.truncated && (
                  <p>
                    최대 {number(graph.coverage.inputLimit)}개 이벤트·
                    {number(graph.coverage.nodeLimit)}개 노드·
                    {number(graph.coverage.edgeLimit)}개 관계입니다. 기간이나
                    조건을 좁혀 다시 실행하세요. 생략 노드{" "}
                    {number(graph.coverage.omittedNodes)}개·관계{" "}
                    {number(graph.coverage.omittedEdges)}개.
                  </p>
                )}
                {Object.entries(graph.issues).map(([reason, count]) => (
                  <p key={reason}>
                    {issueNames[reason] ?? reason}: {number(count)}건
                  </p>
                ))}
              </AlertDescription>
            </Alert>
          )}
          {error && (
            <Alert variant="destructive">
              <AlertTitle>근거 조회 실패</AlertTitle>
              <AlertDescription>{error}</AlertDescription>
            </Alert>
          )}
          {pendingEvent && <p role="status">근거를 불러오고 있습니다.</p>}
          <FieldGroup className="hw-relation-controls">
            <Field>
              <FieldLabel htmlFor={`${arrow}-search`}>
                관계 노드 검색
              </FieldLabel>
              <Input
                id={`${arrow}-search`}
                value={search}
                onChange={(e) => {
                  setSearch(e.target.value);
                  setLimit(50);
                }}
                placeholder="실행 파일·주소·GUID·수집원"
              />
            </Field>
            <Field>
              <FieldLabel htmlFor={`${arrow}-kind`}>관계 노드 유형</FieldLabel>
              <NativeSelect
                id={`${arrow}-kind`}
                value={mode}
                onChange={(e) => {
                  setMode(e.target.value);
                  setLimit(50);
                }}
              >
                <option value="ALL">모든 유형</option>
                <option value="PROCESS">프로세스</option>
                <option value="CONNECTION">관측 연결</option>
                <option value="ENDPOINT">관측 끝점</option>
              </NativeSelect>
            </Field>
          </FieldGroup>
          {graph.nodes.length === 0 ? (
            <p className="hw-help">
              관계를 구성할 식별 필드가 없습니다. 빈 그래프만으로 안전을 판정할
              수 없습니다.
            </p>
          ) : (
            <div className="hw-relation-workspace">
              <div className="hw-relation-outline">
                <p className="hw-help">
                  일치 노드 {number(filtered.length)}개 · 프로세스는 부모 아래에
                  표시됩니다.
                </p>
                <ol aria-label="관계 노드 목록">
                  {filtered.slice(0, limit).map(({ node, depth }) => (
                    <li
                      key={node.id}
                      style={{
                        paddingInlineStart: search
                          ? 0
                          : Math.min(depth, 8) * 12,
                      }}
                    >
                      <Button
                        type="button"
                        variant={node.id === selectedId ? "secondary" : "ghost"}
                        aria-pressed={node.id === selectedId}
                        onClick={() => setSelection(node.id)}
                        className="hw-relation-select"
                      >
                        <span>{node.label}</span>
                        <small>
                          {kinds[node.kind]} · {node.attributes.source_id}
                        </small>
                      </Button>
                    </li>
                  ))}
                </ol>
                {filtered.length > limit && (
                  <Button
                    type="button"
                    variant="outline"
                    onClick={() => setLimit((n) => n + 50)}
                  >
                    노드 더 보기
                  </Button>
                )}
                {!filtered.length && (
                  <p className="hw-help">검색에 일치하는 노드가 없습니다.</p>
                )}
              </div>
              {selected && (
                <div className="hw-relation-inspector">
                  <h4>{selected.label}</h4>
                  <div className="flex flex-wrap gap-2">
                    <Badge variant="secondary">{kinds[selected.kind]}</Badge>
                    {selected.referenceOnly && (
                      <Badge variant="outline">원천 참조만 존재</Badge>
                    )}
                    {selected.ambiguous && (
                      <Badge variant="destructive">식별 충돌</Badge>
                    )}
                  </div>
                  <dl>
                    {Object.entries(selected.attributes)
                      .filter(([, value]) => value !== "")
                      .map(([name, value]) => (
                        <div key={name}>
                          <dt>{attributeNames[name] ?? name}</dt>
                          <dd>
                            {name === "identityBasis"
                              ? (basisNames[value] ?? value)
                              : value}
                          </dd>
                        </div>
                      ))}
                    {selected.startedAt && (
                      <div>
                        <dt>시작 근거 시각</dt>
                        <dd>{selected.startedAt}</dd>
                      </div>
                    )}
                    {selected.endedAt && (
                      <div>
                        <dt>종료 근거 시각</dt>
                        <dd>{selected.endedAt}</dd>
                      </div>
                    )}
                  </dl>
                  <details>
                    <summary>
                      노드 근거 {number(selected.evidenceCount)}건 · 대표{" "}
                      {selected.eventIds.length}개
                    </summary>
                    <div className="hw-relation-evidence">
                      {selected.eventIds.map((id, index) => (
                        <Button
                          key={id}
                          type="button"
                          variant="outline"
                          size="sm"
                          disabled={busy || !!pendingEvent}
                          onClick={() => {
                            void evidence(id);
                          }}
                          aria-label={`노드 근거 ${index + 1} 보기`}
                        >
                          근거 {index + 1} 보기
                        </Button>
                      ))}
                    </div>
                  </details>
                  <div
                    className="hw-relation-links"
                    aria-label="선택한 노드의 전체 관계"
                  >
                    {related.length ? (
                      related.map(edgeEvidence)
                    ) : (
                      <p className="hw-help">근거로 확인한 관계가 없습니다.</p>
                    )}
                  </div>
                  <details className="hw-relation-diagram">
                    <summary>
                      선택한 노드의 관계도 · {Math.min(24, related.length)}개
                      관계 표시
                    </summary>
                    <div className="hw-relation-canvas">
                      <svg
                        width={960}
                        height={height}
                        role="group"
                        aria-label="선택한 노드의 방향 관계도"
                      >
                        <defs>
                          <marker
                            id={arrow}
                            viewBox="0 0 10 10"
                            refX={9}
                            refY={5}
                            markerWidth={6}
                            markerHeight={6}
                            orient="auto-start-reverse"
                          >
                            <path d="M 0 0 L 10 5 L 0 10 z" />
                          </marker>
                        </defs>
                        {left.map((edge, index) => (
                          <path
                            key={edge.id}
                            className="hw-relation-line"
                            d={`M 280 ${40 + index * 64} C 320 ${40 + index * 64},320 ${centerY + 22},350 ${centerY + 22}`}
                            markerEnd={`url(#${arrow})`}
                          />
                        ))}
                        {right.map((edge, index) => (
                          <path
                            key={edge.id}
                            className="hw-relation-line"
                            d={`M 610 ${centerY + 22} C 640 ${centerY + 22},640 ${40 + index * 64},680 ${40 + index * 64}`}
                            markerEnd={`url(#${arrow})`}
                          />
                        ))}
                        {left.map((edge, index) =>
                          nodeMark(nodes.get(edge.from)!, 20, 18 + index * 64),
                        )}
                        {nodeMark(selected, 350, centerY)}
                        {right.map((edge, index) =>
                          nodeMark(nodes.get(edge.to)!, 680, 18 + index * 64),
                        )}
                      </svg>
                    </div>
                    <p className="hw-help">
                      왼쪽에서 오른쪽으로 읽습니다. 앞·뒤 최대 12개씩 표시하며
                      위 목록에서 전체 관계의 근거를 확인할 수 있습니다. 노드를
                      선택하거나 Enter로 이동하세요.
                    </p>
                  </details>
                </div>
              )}
            </div>
          )}
        </>
      )}
    </section>
  );
}
